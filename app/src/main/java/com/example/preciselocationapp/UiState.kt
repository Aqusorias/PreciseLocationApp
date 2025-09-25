package com.example.preciselocationapp


sealed class UiState {
    object Loading: UiState() // Initial state
    object RequestingPermissions: UiState() // Waiting for user to grand permissions
    object RequestingLocationSettings: UiState() // Waiting for user to enable GPS

    data class Ready(val locationData: LocationData?): UiState() // Main UI ready
    data class Error(val message: String): UiState() // Any failure
    data class ErrorNeedsSettings(val message: String): UiState()
}