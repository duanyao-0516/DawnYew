package com.dawnyew.app

import com.dawnyew.presentation.CapturePlatform
import com.dawnyew.presentation.CaptureSettingsUi
import kotlinx.coroutines.flow.MutableStateFlow

/** Rendering fixture: system capture permission flows have their own device tests. */
internal object PreviewCapturePlatform : CapturePlatform {
    override val state = MutableStateFlow(CaptureSettingsUi())
    override fun setAutomaticCapture(enabled: Boolean): Unit = error("Not part of rendering fixture")
    override fun requestTile(automatic: Boolean): Unit = error("Not part of rendering fixture")
    override fun openAppSettings(): Unit = error("Not part of rendering fixture")
}
