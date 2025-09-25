package com.example.preciselocationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.location.LocationSettingsStatusCodes
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
private fun applyMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    // supports: **bold**, *italic*, _underline_
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
    private var isTrackingEnabled by mutableStateOf(true)

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
        locationManager = LocationManager(this)

        setContent {
            AppContent(
                uiState.value,
                intervalMs = _intervalMs,
                onIntervalChanged = ::updateInterval,
                onRetry = { checkPermissionsAndStart() }, // retry callback
                onOpenSettings = { openAppSettings() },
                isTrackingEnabled = isTrackingEnabled,
                onTrackingToggle = { toggleTracking() }
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

    private fun checkPermissionsAndStart() {
        if (hasLocationPermission()) {
            checkLocationSettings()
        } else {
            uiState.value = UiState.RequestingPermissions
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun openAppSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    private fun checkLocationSettings() {
        uiState.value = UiState.RequestingLocationSettings

        val task = locationManager.checkLocationSettings()
        task.addOnSuccessListener { locationSettingsResponse: LocationSettingsResponse ->
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

        if (hasLocationPermission()) {
            if (uiState.value !is UiState.Ready) {
                checkLocationSettings()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        locationManager.stopLocationUpdates()
    }

    private fun toggleTracking() {
        isTrackingEnabled = !isTrackingEnabled
        if (isTrackingEnabled) {
            locationManager.startLocationUpdates()
        } else {
            locationManager.stopLocationUpdates()
        }
    }
}

// ---------- 💞 UI COMPOSABLE 💞 ---------- //
@Composable
fun AppContent(state: UiState, intervalMs: Long, onIntervalChanged: (Long) -> Unit, onRetry: () -> Unit, onOpenSettings: (() -> Unit)? = null, isTrackingEnabled: Boolean, onTrackingToggle: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is UiState.Loading -> LoadingScreen()
                is UiState.RequestingPermissions -> LoadingScreen(title = "Requesting Permissions...", subtitle = "Please grant location access to continue.")
                is UiState.RequestingLocationSettings -> LoadingScreen(title = "Checking Location Settings...", subtitle = "Ensuring GPS and location services are enabled.")
                is UiState.Ready -> MainScreen(locationData = state.locationData, intervalMs = intervalMs, onIntervalChanged = onIntervalChanged, isTrackingEnabled = isTrackingEnabled, onTrackingToggle = onTrackingToggle)
                is UiState.Error -> ErrorScreen(message = state.message, onRetry = onRetry)
                is UiState.ErrorNeedsSettings -> ErrorScreen(message = state.message, onRetry = onRetry, onOpenSettings = onOpenSettings)
            }
        }
    }
}

// Loading Screen with animated Spinner
@Composable
fun LoadingScreen(
    title: String = "Loading...",
    subtitle: String = "Please wait..."
) {
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
            // circular progress indicator
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


// Main screen
@Composable
fun MainScreen(locationData: LocationData?, intervalMs: Long, onIntervalChanged: (Long) -> Unit, isTrackingEnabled: Boolean, onTrackingToggle: () -> Unit) {
    MaterialTheme {
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
                value = intervalMs.toFloat(),
                onValueChange = {
                    val newMs = it.toLong().coerceAtLeast(200L)
                    onIntervalChanged(newMs)
                },
                valueRange = 200f..5000f,
                steps = 9,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Text(
                text = "Tip: move the slider to choose update frequency (200 ms → 5 s)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )

            Button(
                onClick = onTrackingToggle,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(if (isTrackingEnabled) "Stop Tracking" else "Start Tracking")
            }
        }
    }
}

// Error screen
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