package com.example.preciselocationapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

/**
 * MainActivity handles location updates and displays them using a Compose UI.
 * It requests location permissions, manages location update intervals via a slider,
 * and shows the current latitude, longitude, accuracy, and update interval on screen.
 */
class MainActivity : ComponentActivity() {

    // Fused location client for getting GPS/location updates.
    private lateinit var fused: FusedLocationProviderClient

    // Client to check device location settings (e.g., if GPS is enabled).
    private lateinit var settingsClient: SettingsClient

    // Callback to receive location updates from the FusedLocationProviderClient.
    private lateinit var locationCallback: LocationCallback

    // State variables to store the latest location details for the UI.
    private var _latitude by mutableStateOf<Double?>(null)   // Latest latitude
    private var _longitude by mutableStateOf<Double?>(null)  // Latest longitude
    private var _accuracy by mutableStateOf<Float?>(null)    // Latest accuracy in meters
    private var _lastFixTime by mutableStateOf<Long?>(null)  // Timestamp of last location fix

    // The desired interval for location updates (in milliseconds). Default is 1000ms = 1 second.
    private var _intervalMs by mutableLongStateOf(1000L) // update interval in ms

    // LocationRequest instance used to configure the update interval and accuracy.
    // This will be recreated when the interval changes.
    private var locationRequest: LocationRequest = newLocationRequest(_intervalMs)

    // Launcher to request location permissions from the user.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        // Callback after permission dialog: check if fine/coarse permission was granted.
        val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            // If either permission is granted, start location updates.
            startLocationUpdatesIfPermitted()
        }
    }

    // Launcher to prompt the user to change location settings if needed (e.g., enable GPS).
    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // If the user agrees to change settings, attempt to start updates again.
        if (it.resultCode == RESULT_OK) {
            startLocationUpdatesIfPermitted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // Initialize the activity.

        // Initialize the fused location provider and settings client.
        fused = LocationServices.getFusedLocationProviderClient(this)
        settingsClient = LocationServices.getSettingsClient(this)

        // Define the callback that processes incoming location updates.
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // This is called when a new location is available.
                val loc = result.lastLocation ?: return
                _latitude = loc.latitude
                _longitude = loc.longitude
                _accuracy = loc.accuracy
                _lastFixTime = loc.time
            }
        }

        // Set up the Compose UI content.
        setContent {
            // Read the latest state values for use in the Compose UI.
            val lat = _latitude
            val lng = _longitude
            val acc = _accuracy
            val time = _lastFixTime

            // Slider state to adjust the update interval in the UI.
            var sliderValue by remember { mutableFloatStateOf(_intervalMs.toFloat()) }

            // React to changes in the slider (update interval changes).
            LaunchedEffect(sliderValue) {
                // Convert the slider (Float) to Long, with a minimum of 200 ms.
                val newMs = sliderValue.toLong().coerceAtLeast(200L)
                if (newMs != _intervalMs) {
                    _intervalMs = newMs
                    updateLocationRequest(newMs)
                    if (hasLocationPermission()) {
                        // Restart location updates with the new interval.
                        restartLocationUpdates()
                    }
                }
            }

            // Build the UI layout.
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title text.
                        Text(
                            text = "Precise Location (Compose)",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        // Display latitude and longitude, or "--" if not yet available.
                        Text(
                            text = "Lat: ${lat?.toString() ?: "--"}, Lng: ${lng?.toString() ?: "--"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        // Display accuracy, formatted to one decimal, or "--" if not available.
                        Text(
                            text = "Accuracy: ${acc?.let { "%.1f m".format(it) } ?: "--"}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        // Display the current update interval in milliseconds.
                        Text(
                            text = "Interval: $_intervalMs ms",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // Slider to adjust the location update interval (200 ms to 5000 ms).
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 200f..5000f,
                            steps = 9,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        // Tip/instruction text.
                        Text(
                            text = "Tip: move the slider to choose update frequency (200 ms -> 5 s)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        // After setting up the UI, check permissions and start location updates if allowed.
        checkPermissionsAndStart()
    }

    /**
     * Checks if the app has fine or coarse location permission.
     * @return true if ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION is granted.
     */
    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Requests location permissions if not already granted, otherwise starts location updates.
     */
    private fun checkPermissionsAndStart() {
        if (hasLocationPermission()) {
            // Permissions already granted, start receiving updates.
            startLocationUpdatesIfPermitted()
        } else {
            // Ask the user for fine and coarse location permissions.
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * Creates a new LocationRequest with the given interval in milliseconds.
     * Uses high accuracy priority and sets the min and max update intervals.
     */
    private fun newLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)  // at least half interval for updates
            .setMaxUpdateDelayMillis(intervalMs)         // allow delay up to the full interval
            .build()
    }

    /**
     * Updates the existing LocationRequest to use a new update interval.
     * @param intervalMs The new update interval in milliseconds.
     */
    private fun updateLocationRequest(intervalMs: Long) {
        locationRequest = newLocationRequest(intervalMs)
    }

    /**
     * Starts receiving location updates if permissions are granted and settings allow it.
     */
    @SuppressLint("MissingPermission") // We only call this if we have permission.
    private fun startLocationUpdatesIfPermitted() {
        if (!hasLocationPermission()) return

        // Build a request to check if the device's location settings are satisfied.
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        settingsClient.checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                // All location settings are satisfied. Request location updates.
                fused.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    // Location settings are not satisfied, but this can be fixed by showing the user a dialog.
                    try {
                        // In a full application, you could launch an intent to prompt the user:
                        // ex.startResolutionForResult(this, REQUEST_CHECK_SETTINGS)
                        // Here we would use `locationSettingsLauncher` to start the resolution.
                    } catch (sendEx: IntentSender.SendIntentException) {
                        // Failed to show the settings dialog or send the intent.
                    }
                }
            }
    }

    /**
     * Stops and then restarts location updates.
     * Useful when the update interval has been changed.
     */
    private fun restartLocationUpdates() {
        if (!hasLocationPermission()) return
        // Remove existing updates and start fresh.
        fused.removeLocationUpdates(locationCallback)
        startLocationUpdatesIfPermitted()
    }

    /**
     * Called when the activity comes to the foreground.
     * Restarts location updates if permissions are still granted.
     */
    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startLocationUpdatesIfPermitted()
        }
    }

    /**
     * Called when the activity goes to the background.
     * Stops location updates to save battery.
     */
    override fun onPause() {
        super.onPause()
        fused.removeLocationUpdates(locationCallback)
    }
}
