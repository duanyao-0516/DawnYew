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
)

/** Platform capabilities only. Recognition candidates still require EditorDraft confirmation. */
interface CapturePlatform {
    val state: StateFlow<CaptureSettingsUi>
    fun setAutomaticCapture(enabled: Boolean)
    fun requestTile(automatic: Boolean)
    fun openAppSettings()
}
