package com.example.preciselocationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.location.LocationSettingsStatusCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private fun applyMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var lastEnd = 0
    val patterns = listOf(
        Regex("\\*\\*\\*(.*?)\\*\\*\\*") to SpanStyle(fontWeight = FontWeight.Bold, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        Regex("\\*\\*(.*?)\\*\\*") to SpanStyle(fontWeight = FontWeight.Bold),
        Regex("\\*(.*?)\\*") to SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        Regex("_(.*?)_") to SpanStyle(textDecoration = TextDecoration.Underline)
    )

    val matches = patterns.flatMap { (regex, style) ->
        regex.findAll(text).map { it to style }
    }.sortedBy { (match, _) -> match.range.first }

    for ((match, style) in matches) {
        val start = match.range.first
        val end = match.range.last
        if (start >= lastEnd) {
            if (start > lastEnd) append(text.substring(lastEnd, start))
            withStyle(style) { append(match.groupValues[1]) }
            lastEnd = end + 1
        }
    }
    if (lastEnd < text.length) append(text.substring(lastEnd))
}

class MainActivity : ComponentActivity() {
    private lateinit var locationManager: LocationManager
    private var _intervalMs by mutableLongStateOf(1000L)
    private var isTrackingEnabled by mutableStateOf(false)

    private val uiState = mutableStateOf<UiState>(UiState.Loading)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            checkLocationSettings()
        } else {
            val fineRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)

            val permanentlyDenied = !fineRationale && !coarseRationale

            if (permanentlyDenied) {
                uiState.value = UiState.ErrorNeedsSettings("Location permission *permanently* denied. Please open app settings and allow **precise** location.")
            } else {
                uiState.value = UiState.Error("Location permission denied. App needs location to work.")
            }
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startLocationService()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            locationManager.startLocationUpdates()
            uiState.value = UiState.Ready(locationManager.locationData.value)
        } else {
            uiState.value = UiState.Error("Location settings could not be enabled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = LocationManager.getInstance(this)

        lifecycleScope.launch {
            locationManager.locationData.collect {
                val currentState = uiState.value
                if (currentState is UiState.Ready) {
                    uiState.value = currentState.copy(locationData = it)
                }
            }
        }

        setContent {
            val isGpsWarm by locationManager.isGpsWarm.collectAsState()
            val trackedLocations by locationManager.trackedLocations.collectAsState()
            var showTrackedLocations by remember { mutableStateOf(false) }
            var timeSinceLastUpdate by remember { mutableStateOf("") }

            LaunchedEffect(trackedLocations) {
                while (true) {
                    val lastLocation = trackedLocations.lastOrNull()?.lastOrNull()
                    if (lastLocation != null && lastLocation.lastFixTime != null) {
                        val diff = System.currentTimeMillis() - lastLocation.lastFixTime
                        timeSinceLastUpdate = "Last update: ${diff / 1000}s ago"
                    } else {
                        timeSinceLastUpdate = ""
                    }
                    delay(1000)
                }
            }

            AppContent(
                uiState.value,
                intervalMs = _intervalMs,
                onIntervalChanged = ::updateInterval,
                onRetry = { checkPermissionsAndStart() },
                onOpenSettings = { openAppSettings() },
                isTrackingEnabled = isTrackingEnabled,
                onTrackingToggle = { toggleTracking() },
                isGpsWarm = isGpsWarm,
                trackedLocations = trackedLocations,
                showTrackedLocations = showTrackedLocations,
                onShowTrackedLocationsChange = { showTrackedLocations = it },
                timeSinceLastUpdate = timeSinceLastUpdate,
                onClearSession = { locationManager.clearSession(it) }
            )
        }

        checkPermissionsAndStart()
    }

    private fun updateInterval(newInterval: Long) {
        _intervalMs = newInterval
        locationManager.setInterval(newInterval)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (hasLocationPermission()) {
            checkLocationSettings()
        } else {
            uiState.value = UiState.RequestingPermissions
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    private fun checkLocationSettings() {
        uiState.value = UiState.RequestingLocationSettings

        val task = locationManager.checkLocationSettings()
        task.addOnSuccessListener { _: LocationSettingsResponse ->
            locationManager.startLocationUpdates()
            uiState.value = UiState.Ready(locationManager.locationData.value)
        }.addOnFailureListener { ex ->
            when (ex) {
                is ResolvableApiException -> {
                    val status = ex.status
                    when (status.statusCode) {
                        LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                            try {
                                val intentSenderRequest = IntentSenderRequest.Builder(ex.resolution).build()
                                locationSettingsLauncher.launch(intentSenderRequest)
                            } catch (_: IntentSender.SendIntentException) {
                                uiState.value = UiState.Error("Failed to open location settings.")
                            }
                        }
                        LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                            uiState.value = UiState.Error("Location services cannot be enabled on this device.")
                        }
                        else -> {
                            uiState.value = UiState.Error("Unknown location error: ${status.statusCode}")
                        }
                    }
                }
                else -> {
                    uiState.value = UiState.Error("Could not check location settings: ${ex.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission() && uiState.value !is UiState.Ready) {
            checkLocationSettings()
        }
    }

    private fun startLocationService() {
        Intent(this, LocationService::class.java).also {
            startService(it)
        }
    }

    private fun stopLocationService() {
        Intent(this, LocationService::class.java).also {
            stopService(it)
        }
    }

    private fun toggleTracking() {
        isTrackingEnabled = !isTrackingEnabled
        if (isTrackingEnabled) {
            locationManager.startNewSession()
            if (hasBackgroundLocationPermission()) {
                startLocationService()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        } else {
            stopLocationService()
            locationManager.stopLocationUpdates()
        }
    }
}

// ---------- 💞 UI COMPOSABLE 💞 ---------- //
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    state: UiState,
    intervalMs: Long,
    onIntervalChanged: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    isTrackingEnabled: Boolean,
    onTrackingToggle: () -> Unit,
    isGpsWarm: Boolean,
    trackedLocations: List<List<LocationData>>,
    showTrackedLocations: Boolean,
    onShowTrackedLocationsChange: (Boolean) -> Unit,
    timeSinceLastUpdate: String,
    onClearSession: (Int) -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is UiState.Loading -> LoadingScreen()
                is UiState.RequestingPermissions -> LoadingScreen(title = "Requesting Permissions...", subtitle = "Please grant location access to continue.")
                is UiState.RequestingLocationSettings -> LoadingScreen(title = "Checking Location Settings...", subtitle = "Ensuring GPS and location services are enabled.")
                is UiState.Ready -> {
                    if (showTrackedLocations) {
                        TrackedLocationsScreen(
                            trackedLocations = trackedLocations,
                            onBack = { onShowTrackedLocationsChange(false) },
                            timeSinceLastUpdate = timeSinceLastUpdate,
                            onClearSession = onClearSession
                        )
                    } else {
                        MainScreen(
                            locationData = state.locationData,
                            intervalMs = intervalMs,
                            onIntervalChanged = onIntervalChanged,
                            isTrackingEnabled = isTrackingEnabled,
                            onTrackingToggle = onTrackingToggle,
                            isGpsWarm = isGpsWarm,
                            onShowTrackedLocations = { onShowTrackedLocationsChange(true) }
                        )
                    }
                }
                is UiState.Error -> ErrorScreen(message = state.message, onRetry = onRetry)
                is UiState.ErrorNeedsSettings -> ErrorScreen(message = state.message, onRetry = onRetry, onOpenSettings = onOpenSettings)
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackedLocationsScreen(
    trackedLocations: List<List<LocationData>>,
    onBack: () -> Unit,
    timeSinceLastUpdate: String,
    onClearSession: (Int) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tracked Locations") }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            })
        }
    ) {
        LazyColumn(modifier = Modifier.padding(it)) {
            itemsIndexed(trackedLocations) { index, session ->
                val startTime = session.firstOrNull()?.lastFixTime
                val endTime = session.lastOrNull()?.lastFixTime

                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Session ${index + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        IconButton(onClick = { onClearSession(index) }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear Session")
                        }
                    }

                    if (startTime != null && endTime != null) {
                        val formattedStart = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startTime))
                        val formattedEnd = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(endTime))
                        Text(text = "$formattedStart - $formattedEnd", modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    session.forEach { loc ->
                        val formattedTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(loc.lastFixTime ?: 0))
                        Text(
                            text = "Lat: ${loc.latitude}, Lng: ${loc.longitude}, Time: $formattedTime",
                            modifier = Modifier.padding(16.dp)
                        )
                        Divider()
                    }
                }
            }
            item {
                Text(text = timeSinceLastUpdate, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
fun LoadingScreen(title: String = "Loading...", subtitle: String = "Please wait...") {
    Box(
        modifier = Modifier
            .fillMaxHeight(1f / 3)
            .padding(top = 96.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun MainScreen(
    locationData: LocationData?,
    intervalMs: Long,
    onIntervalChanged: (Long) -> Unit,
    isTrackingEnabled: Boolean,
    onTrackingToggle: () -> Unit,
    isGpsWarm: Boolean,
    onShowTrackedLocations: () -> Unit
) {
    val sliderValues = listOf(200f, 500f, 1000f, 1500f, 2000f, 3000f, 4000f, 5000f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp, top = 128.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Precise Location (Compose)",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Text(
            text = "${String.format("%.1f", locationData?.speed?.let { it * 3.6 } ?: 0f)} km/h",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold
        )

        if (isGpsWarm) {
            Text("GPS is warm", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            Text(applyMarkdown("GPS is *cold*"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Text(
            text = "Lat: ${locationData?.latitude?.toString() ?: "--"}, Lng: ${locationData?.longitude?.toString() ?: "--"}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Accuracy: ${locationData?.accuracy?.let { "%.1f m".format(it) } ?: "--"}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Interval: $intervalMs ms",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = sliderValues.indexOf(intervalMs.toFloat()).toFloat(),
            onValueChange = {
                val newMs = sliderValues[it.roundToInt()]
                onIntervalChanged(newMs.toLong())
            },
            valueRange = 0f..(sliderValues.size - 1).toFloat(),
            steps = sliderValues.size - 2,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text(
            text = "Tip: move the slider to choose update frequency",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        Button(
            onClick = onTrackingToggle,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(if (isTrackingEnabled) "Stop Tracking" else "Start Tracking")
        }

        Button(
            onClick = onShowTrackedLocations,
            modifier = Modifier.padding(top = 8.dp),
            enabled = isTrackingEnabled
        ) {
            Text("Show Tracked Locations")
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, onOpenSettings: (() -> Unit)? = null) {
    val formattedMessage = applyMarkdown(message)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = formattedMessage,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (onOpenSettings == null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Retry Permission Request")
            }
        } else {
            Button(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        }
    }
}