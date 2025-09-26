package com.example.preciselocationapp

// Simple data class that holds location information.
// It's used to pass location data between components.

data class LocationData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val lastFixTime: Long? = null,
    val speed: Float? = null
)