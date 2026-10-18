package com.dawnyew.platform.security

import org.junit.Test
import kotlin.test.*

class SessionCoordinatorTest {
    private var time = 1_000L
    private fun unlocked(): SessionCoordinator = SessionCoordinator({ time }, "process-a").also {
        it.configureLock(true); assertTrue(it.finishAuthentication(it.beginAuthentication(), true, false))
    }
    @Test fun pickerDepartureRequiresFreshAuthenticationButRotationKeepsOperation() {
        val s = unlocked(); val lease = s.beginExternal()
        s.background(true); assertTrue(s.authorized); assertTrue(s.externalIsCurrent(lease))
        s.background(false); assertFalse(s.authorized); assertTrue(s.externalIsCurrent(lease))
        assertTrue(s.finishExternal(lease)); assertFalse(s.authorized)
        assertTrue(s.finishAuthentication(s.beginAuthentication(), true, false))
    }
    @Test fun screenLockOrNewEntryInvalidatesEveryPriorCallback() {
        val s = unlocked(); val lease = s.beginExternal(); val attempt = s.beginAuthentication()
        s.revoke()
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
}
