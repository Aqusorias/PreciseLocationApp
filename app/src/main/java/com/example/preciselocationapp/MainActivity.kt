package com.example.preciselocationapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationCallback: LocationCallback

    // Shared mutable state
    private var _latitude by mutableStateOf<Double?>(null)
    private var _longitude by mutableStateOf<Double?>(null)
    private var _accuracy by mutableStateOf<Float?>(null)
    private var _lastFixTime by mutableStateOf<Long?>(null)

    // Interval state
    private var _intervalMs by mutableLongStateOf(1000L) // default 1s

    // LocationRequest instance (recreated on interval change)
    private var locationRequest: LocationRequest = newLocationRequest(_intervalMs)

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            startLocationUpdatesIfPermitted()
        }
    }

    // Launcher for location settings resolution
    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startLocationUpdatesIfPermitted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fused = LocationServices.getFusedLocationProviderClient(this)
        settingsClient = LocationServices.getSettingsClient(this)

        // Create callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _latitude = loc.latitude
                _longitude = loc.longitude
                _accuracy = loc.accuracy
                _lastFixTime = loc.time
            }
        }

        setContent {
            val lat = _latitude
            val lng = _longitude
            val acc = _accuracy
            val time = _lastFixTime
            var sliderValue by remember { mutableFloatStateOf(_intervalMs.toFloat()) }

            LaunchedEffect(sliderValue) {
                val newMs = sliderValue.toLong().coerceAtLeast(200L)
                if (newMs != _intervalMs) {
                    _intervalMs = newMs
                    updateLocationRequest(newMs)
                    if (hasLocationPermission()) {
                        restartLocationUpdates()
                    }
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Precise Location (Compose)",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Lat: ${lat?.toString() ?: "--"}, Lng: ${lng?.toString() ?: "--"}",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Text(
                            text = "Accuracy: ${acc?.let { "%.1f m".format(it) } ?: "--"}",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Text(
                            text = "Interval: $_intervalMs ms",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 200f..5000f,
                            steps = 9,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Text(
                            text = "Tip: move the slider to choose update frequency (200 ms -> 5 s)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        checkPermissionsAndStart()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun checkPermissionsAndStart() {
        if (hasLocationPermission()) {
            startLocationUpdatesIfPermitted()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // Build a fresh LocationRequest using Builder (modern API)
    private fun newLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs)
            .build()
    }

    private fun updateLocationRequest(intervalMs: Long) {
        locationRequest = newLocationRequest(intervalMs)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesIfPermitted() {
        if (!hasLocationPermission()) return

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        settingsClient.checkLocationSettings(builder.build())
            .addOnSuccessListener {
                fused.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    try {
                        print("hi")
                    } catch (sendEx: IntentSender.SendIntentException) {
                        // Handle failure
                    }
                }
            }
    }

    private fun restartLocationUpdates() {
        if (!hasLocationPermission()) return
        fused.removeLocationUpdates(locationCallback)
        startLocationUpdatesIfPermitted()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startLocationUpdatesIfPermitted()
        }
    }

    override fun onPause() {
        super.onPause()
        fused.removeLocationUpdates(locationCallback)
    }
}

