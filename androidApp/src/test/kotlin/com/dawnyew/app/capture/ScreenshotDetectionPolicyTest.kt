package com.dawnyew.app.capture

import org.junit.Test
import kotlin.test.*

class ScreenshotDetectionPolicyTest {
    private val start = 1_000_500L
    private fun policy() = ScreenshotDetectionPolicy(start, mapOf("external_primary" to 40L))
    private fun image(id: Long = 1) = ScreenshotMedia("external_primary", id, 41 + id, 1_001_000,
        1_001_050, "image/png", "Screenshot_2026.png", "Pictures/Screenshots/", false)
    private fun window(policy: ScreenshotDetectionPolicy, elapsed: Long = 0, wall: Long = 1_002_000): ScreenshotQueryWindow {
        policy.invalidate(elapsed)
        return assertNotNull(policy.takeWindow(true, true, false, elapsed, wall))
    }

    @Test fun unauthorizedLockedAndScreenOffSignalsDoNotCreateQueryWindows() {
        val policy = policy(); policy.invalidate(10)
        assertNull(policy.takeWindow(false, true, false, 20, 1_002_000))
        assertNull(policy.takeWindow(true, true, true, 30, 1_002_000))
        assertNull(policy.takeWindow(true, false, false, 40, 1_002_000))
        assertEquals(start, assertNotNull(policy.takeWindow(true, true, false, 50, 1_002_000)).fromMillis)
        assertNull(policy.takeWindow(true, true, false, 60, 1_002_000))
    }

    @Test fun expiredInvalidationDoesNotScanOnLaterUnlockAndNewSignalsUseOnlyTwoMinutes() {
        val policy = policy(); policy.invalidate(0)
        assertNull(policy.takeWindow(true, true, false, 120_000, 1_200_000))
        assertNull(policy.takeWindow(true, true, false, 120_001, 1_200_001))
        policy.invalidate(130_000)
        assertEquals(1_180_000L, assertNotNull(policy.takeWindow(true, true, false, 140_000, 1_300_000)).fromMillis)
    }

    @Test fun generationAndWindowRejectHistoricalAndImportedOldPictures() {
        val policy = policy(); val range = window(policy)
        assertFalse(policy.claim(image().copy(generationAdded = 40, addedAtMillis = start), range, 0))
        assertFalse(policy.claim(image().copy(addedAtMillis = 999_000), range, 0))
        assertFalse(policy.claim(image().copy(takenAtMillis = 999_000), range, 0))
        assertFalse(policy.claim(image().copy(volume = "unobserved"), range, 0))
        assertTrue(policy.claim(image().copy(takenAtMillis = 0), range, 0))
    }

    @Test fun pendingImageCanBeAcceptedAfterCommitAndDedupThenPreventsRepeatOcr() {
        val policy = policy(); val range = window(policy)
        assertFalse(policy.claim(image().copy(pending = true), range, 0))
        assertTrue(policy.claim(image(), range, 1))
        assertFalse(policy.claim(image(), range, 2))
        assertFalse(policy.claim(image(2), range, 120_000))
    }

    @Test fun onlyPngJpegWithScreenshotNameOrFolderAreCandidates() {
        val policy = policy(); val range = window(policy)
        assertFalse(policy.claim(image().copy(mime = "image/webp"), range, 0))
        assertFalse(policy.claim(image().copy(displayName = "holiday.jpg", relativePath = "DCIM/Camera/"), range, 0))
        assertFalse(ScreenshotDetectionPolicy.isScreenshot("screenshotting.jpg", "Pictures/"))
        assertTrue(policy.claim(image().copy(mime = "image/jpeg", displayName = "截图_1.jpg", relativePath = "DCIM/"), range, 0))
        assertTrue(ScreenshotDetectionPolicy.isScreenshot("vendor_1.png", "DCIM/ScreenShots/"))
    }

    @Test fun clearingDropsPendingSignalsAndDedupStateIsBounded() {
        val policy = ScreenshotDetectionPolicy(start, mapOf("external_primary" to 40L), capacity = 2)
        val range = window(policy)
        assertTrue(policy.claim(image(1), range, 0)); assertTrue(policy.claim(image(2), range, 0)); assertTrue(policy.claim(image(3), range, 0))
        assertFalse(policy.claim(image(2), range, 1)); assertTrue(policy.claim(image(1), range, 1))
        policy.invalidate(2); policy.clear()
        assertNull(policy.takeWindow(true, true, false, 3, 1_002_000))
    }
}
