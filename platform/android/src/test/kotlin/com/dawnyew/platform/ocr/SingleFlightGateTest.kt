package com.dawnyew.platform.ocr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SingleFlightGateTest {
    @Test fun cancelledNativeWorkRetainsExclusivityUntilItReallyStops() = runBlocking {
        val gate = SingleFlightGate()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var delivered = false
        val operation = launch(Dispatchers.Default) {
            gate.recognize {
                started.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                "native result"
            }
            delivered = true
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        operation.cancel()
        try {
            gate.recognize { "must not start" }
            fail("Concurrent request must be rejected")
        } catch (_: OcrBusyException) {
            // Expected while the previous native call is still running.
        } finally {
            finish.countDown()
        }
        operation.join()
        assertFalse(delivered)
        assertEquals("next", gate.recognize { "next" })
    }

    @Test fun resourceReleaseWaitsForRecognition() = runBlocking {
        val gate = SingleFlightGate()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var released = false
        val operation = launch(Dispatchers.Default) {
            gate.recognize { started.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val release = launch { gate.release { released = true } }
        yield()
        assertFalse(released)
        finish.countDown()
        joinAll(operation, release)
        assertTrue(released)
    }

    @Test fun inferenceFailureDoesNotPoisonTheGate() = runBlocking {
        val gate = SingleFlightGate()
        try { gate.recognize { throw IllegalStateException("fixture") } } catch (_: IllegalStateException) { }
        assertEquals(1, gate.recognize { 1 })
    }
}
