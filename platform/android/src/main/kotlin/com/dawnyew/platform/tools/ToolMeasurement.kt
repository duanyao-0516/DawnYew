package com.dawnyew.platform.tools

import kotlin.math.log10

internal data class PcmLevel(val dbfs: Double?, val clipped: Boolean)

/** PCM stays in memory. Partial reads are accumulated into complete, equal-duration RMS windows. */
internal class PcmLevelAccumulator(private val windowSamples: Int) {
    private var count = 0
    private var sumSquares = 0.0
    private var clipped = false

    init { require(windowSamples > 0) }

    fun append(samples: ShortArray, readCount: Int): List<PcmLevel> {
        require(readCount in 0..samples.size)
        val levels = mutableListOf<PcmLevel>()
        for (index in 0 until readCount) {
            val value = samples[index].toInt()
            sumSquares += value.toDouble() * value
            clipped = clipped || value == Short.MIN_VALUE.toInt() || value == Short.MAX_VALUE.toInt()
            count++
            if (count == windowSamples) {
                val meanSquare = sumSquares / windowSamples
                val dbfs = if (meanSquare == 0.0) null else 10.0 * log10(meanSquare / (32768.0 * 32768.0))
                levels += PcmLevel(dbfs, clipped)
                count = 0
                sumSquares = 0.0
                clipped = false
            }
        }
        return levels
    }
}

internal object RulerCalibration {
    fun isValid(pixelsPerCm: Float): Boolean = pixelsPerCm.isFinite() && pixelsPerCm in 10f..1000f

    fun defaultPixelsPerCm(verticalDpi: Float, fallbackDensityDpi: Int): Float {
        val physical = verticalDpi / 2.54f
        if (isValid(physical)) return physical
        val fallback = fallbackDensityDpi / 2.54f
        return if (isValid(fallback)) fallback else 160f / 2.54f
    }
}
