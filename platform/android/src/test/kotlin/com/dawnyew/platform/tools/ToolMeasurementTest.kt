package com.dawnyew.platform.tools

import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class ToolMeasurementTest {
    @Test fun digitalSilenceHasNoDecibelValue() {
        val reading = PcmLevelAccumulator(4).append(shortArrayOf(0, 0, 0, 0), 4).single()
        assertNull(reading.dbfs)
        assertFalse(reading.clipped)
    }

    @Test fun halfAmplitudeAlternatingSignalMeasuresMinusSixDecibels() {
        val reading = PcmLevelAccumulator(4).append(shortArrayOf(16384, -16384, 16384, -16384), 4).single()
        assertEquals(-6.020599913, reading.dbfs!!, 0.000001)
        assertFalse(reading.clipped)
    }

    @Test fun fullScaleSineMeasuresItsRmsRatherThanItsPeak() {
        val samples = ShortArray(4000) { (32767.0 * sin(2.0 * PI * 1000.0 * it / 16000.0)).toInt().toShort() }
        val reading = PcmLevelAccumulator(4000).append(samples, samples.size).single()
        assertEquals(-3.0103, reading.dbfs!!, 0.002)
        assertTrue(reading.clipped)
    }

    @Test fun chunkedReadsOnlyEmitCompleteWindowsAndIgnoreUnusedBufferTail() {
        val accumulator = PcmLevelAccumulator(4)
        assertTrue(accumulator.append(shortArrayOf(16384, -16384, Short.MAX_VALUE), 2).isEmpty())
        val windows = accumulator.append(shortArrayOf(16384, -16384, 0, 0, 0, 0), 6)
        assertEquals(2, windows.size)
        assertEquals(-6.020599913, windows[0].dbfs!!, 0.000001)
        assertFalse(windows[0].clipped)
        assertNull(windows[1].dbfs)
        assertFalse(windows[1].clipped)
    }

    @Test fun negativeFullScaleDoesNotOverflowAndClippingDoesNotLeakIntoNextWindow() {
        val windows = PcmLevelAccumulator(2).append(shortArrayOf(Short.MIN_VALUE, Short.MIN_VALUE, 0, 0), 4)
        assertEquals(0.0, windows[0].dbfs)
        assertTrue(windows[0].clipped)
        assertFalse(windows[1].clipped)
    }

    @Test fun invalidSignalWindowAndReadCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PcmLevelAccumulator(0) }
        assertFailsWith<IllegalArgumentException> { PcmLevelAccumulator(4).append(shortArrayOf(1), 2) }
        assertFailsWith<IllegalArgumentException> { PcmLevelAccumulator(4).append(shortArrayOf(1), -1) }
    }

    @Test fun physicalVerticalDpiConvertsToCentimetersWithoutLogicalDensity() {
        assertEquals(200f, RulerCalibration.defaultPixelsPerCm(508f, 160), 0.0001f)
        assertEquals(160f / 2.54f, RulerCalibration.defaultPixelsPerCm(Float.NaN, 160), 0.0001f)
    }

    @Test fun calibrationRejectsNonFiniteAndUnphysicalValues() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f, 9f, 1001f).forEach {
            assertFalse(RulerCalibration.isValid(it), "Unexpectedly accepted $it")
        }
        listOf(10f, 100f, 1000f).forEach { assertTrue(RulerCalibration.isValid(it)) }
    }

    @Test fun invalidDeviceDpiHasFiniteUsableFallback() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -100f).forEach {
            assertEquals(160f / 2.54f, RulerCalibration.defaultPixelsPerCm(it, 0), 0.0001f)
        }
    }
}
