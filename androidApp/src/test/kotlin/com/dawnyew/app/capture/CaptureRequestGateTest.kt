package com.dawnyew.app.capture

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class CaptureRequestGateTest {
    @Test fun cancellationDuringPixelCopyPreventsAnyPublication() {
        val gate = CaptureRequestGate()
        val copying = CountDownLatch(1); val continueCopy = CountDownLatch(1)
        var published = false
        val worker = thread {
            copying.countDown(); assertTrue(continueCopy.await(2, TimeUnit.SECONDS))
            gate.finish { published = true; "image" }
        }
        assertTrue(copying.await(2, TimeUnit.SECONDS))
        gate.cancel({ fail("No result should exist") }, {})
        continueCopy.countDown(); worker.join(2000)
        assertFalse(worker.isAlive); assertFalse(published); assertTrue(gate.isFinished())
    }

    @Test fun failedPublicationCanStillReportATerminalError() {
        val gate = CaptureRequestGate()
        assertFailsWith<IllegalStateException> { gate.finish { error("Notification unavailable") } }
        assertFalse(gate.isFinished())
        var errorReported = false
        assertTrue(gate.finish { errorReported = true; null })
        assertTrue(errorReported); assertTrue(gate.isFinished())
    }

    @Test fun cancellationRetractsAlreadyPublishedResultAndCompletionIsSingleUse() {
        val gate = CaptureRequestGate(); val removed = mutableListOf<String>()
        assertTrue(gate.finish { "image" })
        assertFalse(gate.finish { fail("Must publish once") })
        gate.cancel(removed::add, {})
        gate.cancel(removed::add, {})
        assertEquals(listOf("image"), removed)
    }
}
