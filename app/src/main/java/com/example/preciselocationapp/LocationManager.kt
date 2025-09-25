package com.example.preciselocationapp

// This class handles all location-related operations

import android.content.Context
import android.os.Looper
//import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.annotation.SuppressLint
import com.google.android.gms.tasks.Task

class LocationManager(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val settingsClient = LocationServices.getSettingsClient(context)

    private var locationRequest: LocationRequest = newLocationRequest(1000)
    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData

    private var locationCallback: LocationCallback? = null
    private var isUpdating = false

    init {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _locationData.value = LocationData(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    lastFixTime = loc.time
                )
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
        fused.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
        isUpdating = true
    }

    fun stopLocationUpdates() {
        fused.removeLocationUpdates(locationCallback!!)
        isUpdating = false
    }

    fun checkLocationSettings(): Task<LocationSettingsResponse> {
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

        return settingsClient.checkLocationSettings(settingsRequest)
    }

    private fun newLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs)
            .build()
    }
}