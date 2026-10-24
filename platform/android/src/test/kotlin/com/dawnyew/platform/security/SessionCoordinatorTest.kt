package com.dawnyew.platform.security

import org.junit.Test
import kotlin.test.*

class SessionCoordinatorTest {
    private var time = 1_000L
    private fun unlocked(): SessionCoordinator = SessionCoordinator({ time }, "process-a").also {
        it.configureLock(true); assertTrue(it.finishAuthentication(it.beginAuthentication(), true, false))
    }
    @Test fun pickerReturnRestoresAuthorizationButKeepsItSuspendedUntilReturn() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(true); assertTrue(s.authorized); assertTrue(s.externalIsCurrent(lease))
        s.background(false); assertFalse(s.authorized); assertTrue(s.externalIsCurrent(lease))
        s.foreground(false)
        assertTrue(s.finishExternal(lease)); assertTrue(s.authorized)
    }
    @Test fun pickerResultCannotResumeUntilForegroundAndDoesNotExemptNextDeparture() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(false)
        assertFalse(s.resumeExternal(lease)); assertFalse(s.finishExternal(lease)); assertFalse(s.authorized)
        s.foreground(false)
        assertFalse(s.authorized)
        assertTrue(s.resumeExternal(lease)); assertTrue(s.finishExternal(lease))
        assertFalse(s.finishExternal(lease))
        s.background(false); s.foreground(false)
        assertFalse(s.authorized)
    }
    @Test fun cancelledPickerRestoresOnlyItsOwnSessionAndAllowsAnotherLaunch() {
        val s = unlocked(); val cancelled = s.beginExternal()
        s.background(false); s.foreground(false)
        // Both RESULT_OK and RESULT_CANCELED are matching launcher results.
        assertTrue(s.finishExternal(cancelled)); assertTrue(s.authorized)
        val next = s.beginExternal()
        assertFalse(s.resumeExternal(cancelled)); assertTrue(s.externalIsCurrent(next))
        assertFailsWith<IllegalStateException> { s.beginExternal() }
    }
    @Test fun failedLaunchCleanupDoesNotLeaveAnExemptionForHome() {
        val s = unlocked(); val failed = s.beginExternal()
        s.cancelExternal(failed)
        assertTrue(s.authorized)
        s.background(false); s.foreground(false)
        assertFalse(s.authorized); assertFalse(s.resumeExternal(failed))
    }
    @Test fun additionalDepartureWithoutResultRevokesPickerAuthorization() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(false); s.foreground(false); s.background(false); s.foreground(false)
        assertFalse(s.authorized); assertFalse(s.resumeExternal(lease))
    }
    @Test fun rotationRetainsOnlyProcessOwnedOperationAndCannotUnlockInBackground() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(false); s.background(true)
        assertTrue(s.externalIsCurrent(lease)); assertFalse(s.resumeExternal(lease))
        s.foreground(false)
        assertTrue(s.finishExternal(lease)); assertTrue(s.authorized)
    }
    @Test fun screenLockAndExpiryCannotRestorePickerSession() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(false); s.foreground(true)
        assertFalse(s.finishExternal(lease)); assertFalse(s.authorized)
        val expired = unlocked(); val old = expired.beginExternal()
        expired.background(false); time += 120_001; expired.foreground(false)
        assertFalse(expired.finishExternal(old)); assertFalse(expired.authorized)
    }
    @Test fun sensitiveOperationStillNeedsItsOwnAuthenticationAfterPickerReturn() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(false); s.foreground(false); assertTrue(s.resumeExternal(lease))
        val attempt = s.beginAuthentication()
        assertFalse(s.authorized); assertFalse(s.resumeExternal(lease))
        s.background(false); s.foreground(false)
        assertTrue(s.finishAuthentication(attempt, true, false))
        assertTrue(s.finishExternal(lease))
    }
    @Test fun screenOffOrNewEntryInvalidatesCallbacksEvenAfterDeviceUnlock() {
        val s = unlocked(); val lease = s.beginExternal(); val attempt = s.beginAuthentication()
        s.revoke()
        s.foreground(false)
        assertFalse(s.finishExternal(lease)); assertFalse(s.finishAuthentication(attempt, true, false)); assertFalse(s.authorized)
    }
    @Test fun lateAuthenticationCannotUnlockBackgroundOrLockedActivity() {
        val s = unlocked(); val attempt = s.beginAuthentication()
        assertFalse(s.finishAuthentication(attempt, false, false))
        assertFalse(s.finishAuthentication(attempt, true, true))
        s.cancelAuthentication(attempt); assertFalse(s.finishAuthentication(attempt, true, false))
    }
    @Test fun deadlineAndProcessIdentityCannotBeReplayed() {
        val s = unlocked(); val old = s.beginExternal(); time += 120_001
        assertFalse(s.finishExternal(old)); s.foreground(false)
        val other = SessionCoordinator({ time }, "process-b"); other.configureLock(false)
        other.beginExternal(); assertFalse(other.externalIsCurrent(old)); assertFalse(other.finishExternal(old))
    }
    @Test fun clockRegressionAndDuplicateCallbacksAreRejected() {
        val s = unlocked(); val old = s.beginExternal(); time--
        assertFalse(s.externalIsCurrent(old)); time++
        assertTrue(s.finishExternal(old)); assertFalse(s.finishExternal(old))
        val attempt = s.beginAuthentication(); assertTrue(s.finishAuthentication(attempt, true, false)); assertFalse(s.finishAuthentication(attempt, true, false))
    }
    @Test fun revocationNotifiesDraftOwnerForLockedAndUnlockedUsersButNotValidContinuations() {
        listOf(false,true).forEach { lock ->
            var resets=0
            val s=SessionCoordinator({time},"draft-owner",onRevoked={resets++})
            s.configureLock(lock)
            if(lock) assertTrue(s.finishAuthentication(s.beginAuthentication(),true,false))
            s.foreground(false)
            s.background(true);s.foreground(false);assertEquals(0,resets)
            val picker=s.beginExternal();s.background(false);s.foreground(false)
            assertTrue(s.finishExternal(picker));assertEquals(0,resets)
            s.background(false);assertEquals(1,resets)
            s.foreground(false);s.revoke();assertEquals(2,resets)
            assertEquals(!lock,s.authorized)
        }
    }

}
