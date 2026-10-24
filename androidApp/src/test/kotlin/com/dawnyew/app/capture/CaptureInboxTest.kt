package com.dawnyew.app.capture

import org.junit.Test
import kotlin.test.*

class CaptureInboxTest {
    @Test fun expiredWrongGenerationAndConsumedTokensCannotDeliverAgain() {
        var time = 0L; var sequence = 0
        val released = mutableListOf<String>()
        val box = CaptureInbox({ time }, { "token-${sequence++}" }, released::add, lifetimeMillis = 100)
        val first = box.put("pixels-a", "capture", 1)
        assertNull(box.take(first, "different-purpose", 1))
        assertEquals("pixels-a", box.take(first, "capture", 1))
        assertNull(box.take(first, "capture", 1))
        assertTrue(released.isEmpty())
        val second = box.put("pixels-b", "capture", 1)
        assertNull(box.take(second, "capture", 2)); assertEquals(listOf("pixels-b"), released)
        val third = box.put("pixels-c", "capture", 2)
        time = 100
        assertNull(box.take(third, "capture", 2)); assertEquals(listOf("pixels-b", "pixels-c"), released)
    }

    @Test fun boundedQueueAndLockCleanupReleaseOnlyStillOwnedPayloads() {
        var sequence = 0
        val released = mutableListOf<String>()
        val box = CaptureInbox({ 0L }, { "token-${sequence++}" }, released::add, capacity = 2)
        val first = box.put("a", "capture", 1)
        val second = box.put("b", "capture", 1)
        box.put("c", "capture", 1)
        assertNull(box.take(first, "capture", 1)); assertEquals(listOf("a"), released)
        assertEquals("b", box.take(second, "capture", 1))
        box.clear(); assertEquals(listOf("a", "c"), released)
        box.clear(); assertEquals(listOf("a", "c"), released)
    }
}
