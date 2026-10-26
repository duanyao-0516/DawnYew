package com.dawnyew.presentation

import kotlinx.coroutines.flow.StateFlow

data class CaptureSettingsUi(
    val automaticRunning: Boolean = false,
    val automaticPaused: Boolean = false,
    val mediaPermission: Boolean = false,
    val notificationPermission: Boolean = false,
    val screenTileAdded: Boolean = false,
    val automaticTileAdded: Boolean = false,
    val message: String? = null,
    val floatingEnabled: Boolean = false,
    val floatingConnected: Boolean = false,
    val floatingConfirmBeforeSave: Boolean = false,
    val floatingMessage: String? = null,
)

/** Platform capabilities only. The floating capture flow applies its own save/confirmation setting. */
interface CapturePlatform {
    val state: StateFlow<CaptureSettingsUi>
    fun setAutomaticCapture(enabled: Boolean)
    fun requestTile(automatic: Boolean)
    fun openAppSettings()
    fun setFloatingCapture(enabled: Boolean)
    fun setFloatingConfirmation(enabled: Boolean)
    fun requestFloatingPermissions()
}
