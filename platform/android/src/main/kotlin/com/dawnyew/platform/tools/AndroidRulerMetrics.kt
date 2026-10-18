package com.dawnyew.platform.tools

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.security.MessageDigest

data class RulerMetricSnapshot(val pixelsPerCm: Float, val calibrated: Boolean, val configurationId: String)

/** Device display calibration is intentionally separate from ledger data and ledger backups. */
class AndroidRulerMetrics(private val context: Context) {
    private val preferences = context.getSharedPreferences("tool_ruler_calibration", Context.MODE_PRIVATE)

    fun snapshot(): RulerMetricSnapshot {
        val metrics = context.resources.displayMetrics
        val key = calibrationKey()
        val saved = runCatching { preferences.getFloat(key, Float.NaN) }.getOrDefault(Float.NaN)
        return if (RulerCalibration.isValid(saved)) RulerMetricSnapshot(saved, true, key)
        else RulerMetricSnapshot(RulerCalibration.defaultPixelsPerCm(metrics.ydpi, metrics.densityDpi), false, key)
    }

    fun save(pixelsPerCm: Float) {
        require(RulerCalibration.isValid(pixelsPerCm)) { "校准后的刻度需在 10–1000 像素/厘米之间" }
        preferences.edit().putFloat(calibrationKey(), pixelsPerCm).apply()
    }

    fun reset() { preferences.edit().remove(calibrationKey()).apply() }

    private fun calibrationKey(): String {
        val display = runCatching { context.display }.getOrNull()
            ?: context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = context.resources.displayMetrics
        val mode = display?.mode
        // uniqueId/address are hidden Android APIs; bind only to public device/display/mode data.
        // Physical resolution, rotation and density bind calibration without invalidating it on refresh-rate or system-bar changes.
        val identity = listOf(
            "v1", Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.DISPLAY,
            display?.displayId, display?.name, mode?.physicalWidth, mode?.physicalHeight,
            display?.rotation, context.resources.configuration.orientation,
            metrics.densityDpi,
            metrics.ydpi.toBits(), metrics.xdpi.toBits(),
        ).joinToString("|") { value -> value.toString().let { "${it.length}:$it" } }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "px_per_cm_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
