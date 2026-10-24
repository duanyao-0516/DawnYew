package com.dawnyew.app.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureActionTokensTest {
    @Test fun forgedAndReplayedActionsNeverAuthorize() {
        var sequence = 0
        val tokens = CaptureActionTokens({ 0L }, { "token-${sequence++}" })
        assertFalse(tokens.consume("externally-forged", CaptureAction.ENABLE_AUTOMATIC))
        val issued = tokens.issue(CaptureAction.ENABLE_AUTOMATIC)
        assertTrue(tokens.consume(issued, CaptureAction.ENABLE_AUTOMATIC))
        assertFalse(tokens.consume(issued, CaptureAction.ENABLE_AUTOMATIC))
    }

    @Test fun waitingForUnlockDoesNotExtendDeadline() {
        var time = 100L
        val tokens = CaptureActionTokens({ time }, { "token" }, lifetimeMillis = 100)
        val issued = tokens.issue(CaptureAction.ENABLE_AUTOMATIC)
        time = 200
        assertFalse(tokens.consume(issued, CaptureAction.ENABLE_AUTOMATIC))
    }

    @Test fun clearCapacityAndNewProcessRevokePendingActions() {
        var sequence = 0
        val tokens = CaptureActionTokens({ 0L }, { "token-${sequence++}" }, capacity = 1)
        val first = tokens.issue(CaptureAction.ENABLE_AUTOMATIC)
        val second = tokens.issue(CaptureAction.ENABLE_AUTOMATIC)
        assertFalse(tokens.consume(first, CaptureAction.ENABLE_AUTOMATIC))
        val newProcess = CaptureActionTokens({ 0L }, { "another-token" })
        assertFalse(newProcess.consume(second, CaptureAction.ENABLE_AUTOMATIC))
        tokens.clear()
        assertFalse(tokens.consume(second, CaptureAction.ENABLE_AUTOMATIC))
    }

    @Test fun actionPurposeCannotConsumeAnImageReviewToken() {
        val imageInbox = CaptureInbox({ 0L }, { "image-token" }, { _: String -> })
        val image = imageInbox.put("pixels", "offline-screenshot-review", 0)
        val actions = CaptureActionTokens({ 0L }, { "action-token" })
        assertFalse(actions.consume(image, CaptureAction.ENABLE_AUTOMATIC))
        assertEquals("pixels", imageInbox.take(image, "offline-screenshot-review", 0))
    }
}
