package com.example.preciselocationapp

import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.annotation.SuppressLint
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.asStateFlow

class LocationManager private constructor(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val settingsClient = LocationServices.getSettingsClient(context)

    private var locationRequest: LocationRequest = newLocationRequest(1000)
    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData

    private val _trackedLocations = MutableStateFlow<List<List<LocationData>>>(emptyList())
    val trackedLocations: StateFlow<List<List<LocationData>>> = _trackedLocations.asStateFlow()

    private val _isGpsWarm = MutableStateFlow(false)
    val isGpsWarm: StateFlow<Boolean> = _isGpsWarm.asStateFlow()

    private var lastLocationTime: Long = 0
    private val speedReadings = mutableListOf<Float>()

    private var locationCallback: LocationCallback? = null
    private var isUpdating = false

    init {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                if (loc.hasSpeed() && loc.speedAccuracyMetersPerSecond > 1) {
                    speedReadings.add(loc.speed)
                    if (speedReadings.size > 10) {
                        speedReadings.removeAt(0)
                    }
                }

                val averageSpeed = if (speedReadings.isEmpty()) 0f else speedReadings.average().toFloat()

                val newLocation = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    lastFixTime = loc.time,
                    speed = averageSpeed
                )
                _locationData.value = newLocation

                val currentSessions = _trackedLocations.value
                if (currentSessions.isNotEmpty()) {
                    val lastSession = currentSessions.last()
                    val lastTrackedLocation = lastSession.lastOrNull()
                    if (lastTrackedLocation == null ||
                        (newLocation.latitude != lastTrackedLocation.latitude || newLocation.longitude != lastTrackedLocation.longitude)) {
                        val updatedLastSession = lastSession + newLocation
                        _trackedLocations.value = currentSessions.dropLast(1) + listOf(updatedLastSession)
                    }
                }

                if (lastLocationTime > 0) {
                    val timeDifference = loc.time - lastLocationTime
                    _isGpsWarm.value = timeDifference < 5000 // GPS is warm if updates are frequent
                }
                lastLocationTime = loc.time
            }
        }
    }

    fun setInterval(intervalMs: Long) {
        locationRequest = newLocationRequest(intervalMs)
        if (isUpdating) {
            stopLocationUpdates()
            startLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isUpdating) return
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                _locationData.value = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    lastFixTime = loc.time
                )
            }
        }
        fused.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
        isUpdating = true
    }

    fun stopLocationUpdates() {
        if (!isUpdating) return
        fused.removeLocationUpdates(locationCallback!!)
        isUpdating = false
        lastLocationTime = 0
        _isGpsWarm.value = false
        speedReadings.clear()
        _locationData.value = _locationData.value?.copy(speed = 0f)
    }

    fun startNewSession() {
        speedReadings.clear()
        if (_trackedLocations.value.lastOrNull()?.isNotEmpty() != false) {
            _trackedLocations.value = _trackedLocations.value + listOf(emptyList())
        }
    }

    fun clearSession(index: Int) {
        if (index >= 0 && index < _trackedLocations.value.size) {
            val mutableSessions = _trackedLocations.value.toMutableList()
            mutableSessions.removeAt(index)
            _trackedLocations.value = mutableSessions
        }
    }

    fun checkLocationSettings(): Task<LocationSettingsResponse> {
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        return settingsClient.checkLocationSettings(settingsRequest)
    }

    // Location Request
    private fun newLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
    }

    companion object {
        @Volatile
        private var INSTANCE: LocationManager? = null

        fun getInstance(context: Context): LocationManager {
            return INSTANCE ?: synchronized(this) {
                val instance = LocationManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}