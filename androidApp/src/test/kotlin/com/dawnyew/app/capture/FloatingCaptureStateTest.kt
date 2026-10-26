package com.dawnyew.app.capture

import org.junit.Test
import kotlin.test.*

class FloatingCaptureStateTest {
    @Test fun aScreenshotCallbackIsConsumedOnceAndCancellationRejectsLateDelivery() {
        val gate = FloatingCaptureRequestGate()
        val first = assertNotNull(gate.begin())
        assertNull(gate.begin(), "Rapid taps must not create concurrent captures")
        assertTrue(gate.claimScreenshot(first))
        assertFalse(gate.claimScreenshot(first), "A duplicated platform callback must not run OCR twice")
        gate.cancel()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.finish(first))
        val second = assertNotNull(gate.begin())
        assertNotEquals(first, second)
        assertFalse(gate.claimScreenshot(first))
        assertTrue(gate.claimScreenshot(second))
        assertTrue(gate.finish(second))
        assertFalse(gate.finish(second))
    }

    @Test fun anExpiredRequestCannotEndItsReplacement() {
        val gate = FloatingCaptureRequestGate()
        val expired = assertNotNull(gate.begin())
        gate.cancel()
        val replacement = assertNotNull(gate.begin())
        assertFalse(gate.finish(expired))
        assertTrue(gate.isCurrent(replacement))
    }

    @Test fun floatingPositionClampsMalformedSavedCoordinatesAndResizesWithinInsets() {
        assertEquals(FloatingBallPosition(260, 330), FloatingBallGeometry.position(320, 800, 60, 20, 100, true, .5f))
        assertEquals(FloatingBallPosition(0, 20), FloatingBallGeometry.position(320, 800, 60, 20, 100, false, -4f))
        assertEquals(FloatingBallPosition(260, 740), FloatingBallGeometry.position(320, 800, 60, 20, 0, true, 7f))
        assertEquals(FloatingBallPosition(260, 380), FloatingBallGeometry.position(320, 800, 60, 20, 0, true, Float.NaN))
        assertEquals(FloatingBallPosition(0, 0), FloatingBallGeometry.position(30, 30, 60, 0, 0, true, .9f))
    }

    @Test fun dragPersistenceUsesAvailableHeightRatherThanAbsoluteScreenPixels() {
        assertEquals(.5f, FloatingBallGeometry.verticalFraction(380, 800, 60, 20, 0))
        assertEquals(0f, FloatingBallGeometry.verticalFraction(-10, 800, 60, 20, 0))
        assertEquals(1f, FloatingBallGeometry.verticalFraction(900, 800, 60, 20, 0))
        assertEquals(.5f, FloatingBallGeometry.verticalFraction(10, 30, 60, 0, 0))
    }
}
