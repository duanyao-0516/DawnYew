package com.dawnyew.platform.security

import java.util.UUID

/** Process-memory authorization. No token is serialized into an Intent, Bundle, or saved state. */
class SessionCoordinator(private val now: () -> Long, private val processId: String = UUID.randomUUID().toString()) {
    class Lease internal constructor(val id: Long, val epoch: Long, val issued: Long, val deadline: Long, val process: String)
    @Volatile var epoch: Long = 0; private set
    @Volatile var authorized: Boolean = false; private set
    var lockEnabled: Boolean = true; private set
    private var sequence = 0L
    private var external: Lease? = null
    private var authentication: Lease? = null
    private var inForeground = false
    private var externalDeparted = false

    fun configureLock(enabled: Boolean) { lockEnabled = enabled; if (!enabled) authorized = true }
    fun beginAuthentication(): Lease {
        authorized = !lockEnabled
        return lease().also { authentication = it }
    }
    fun authenticationIsCurrent(attempt: Lease): Boolean = authentication == attempt && valid(attempt)
    fun finishAuthentication(attempt: Lease, foreground: Boolean, deviceLocked: Boolean): Boolean {
        if (!foreground || deviceLocked || !authenticationIsCurrent(attempt)) return false
        authentication = null; inForeground = true; authorized = true; return true
    }
    fun cancelAuthentication(attempt: Lease) { if (authentication == attempt) authentication = null }
    fun beginExternal(): Lease {
        check(authorized) { "应用尚未授权" }; check(external == null) { "已有文件操作" }
        externalDeparted = false
        return lease().also { external = it }
    }
    fun externalIsCurrent(value: Lease): Boolean = external == value && valid(value)
    fun cancelExternal(value: Lease) { if (external == value) { external = null; externalDeparted = false } }
    /** Call only for the matching ActivityResult, after the activity is resumed. */
    fun resumeExternal(value: Lease): Boolean {
        if (!inForeground || authentication != null || !externalIsCurrent(value)) return false
        authorized = true; return true
    }
    fun finishExternal(value: Lease): Boolean {
        if (!resumeExternal(value)) return false
        cancelExternal(value); return true
    }
    /** Only the registered picker's first departure can suspend rather than revoke the session. */
    fun background(changingConfigurations: Boolean) {
        inForeground = false
        if (changingConfigurations) return
        authorized = !lockEnabled
        if (authentication?.let(::valid) == true) return
        if (external?.let(::valid) == true && !externalDeparted) { externalDeparted = true; return }
        revoke()
    }
    fun foreground(deviceLocked: Boolean) {
        inForeground = !deviceLocked
        if (deviceLocked || external?.let { !valid(it) } == true) revoke()
        if (authentication?.let { !valid(it) } == true) authentication = null
    }
    fun revoke() { epoch++; external = null; externalDeparted = false; authentication = null; authorized = !lockEnabled }
    private fun lease(): Lease { val time = now(); return Lease(++sequence, epoch, time, time + 120_000, processId) }
    private fun valid(value: Lease): Boolean = value.process == processId && value.epoch == epoch && now() in value.issued..value.deadline
}
