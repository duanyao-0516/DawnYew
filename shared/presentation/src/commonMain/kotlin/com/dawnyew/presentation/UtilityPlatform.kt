package com.dawnyew.presentation

import kotlinx.coroutines.flow.StateFlow

/** Physical screen scale is device-specific and must never enter a ledger backup. */
data class RulerScale(val pixelsPerCm: Float, val calibrated: Boolean = false, val revision: Long = 0)

data class NoiseUi(
    val running: Boolean = false,
    val dbfs: Double? = null,
    val clipped: Boolean = false,
    val sourceLabel: String = "",
    val calibrationOffsetDb: Double? = null,
    val error: String? = null,
)

/** Optional local hardware tools. Common UI has no microphone, window or Android types. */
interface UtilityPlatform {
    val rulerScale: StateFlow<RulerScale>
    val noise: StateFlow<NoiseUi>
    fun setRulerFullscreen(enabled: Boolean)
    fun saveRulerScale(pixelsPerCm: Float)
    fun resetRulerScale()
    fun startNoise()
    fun stopNoise()
    fun calibrateNoise(referenceDb: Double)
    fun resetNoiseCalibration()
}
