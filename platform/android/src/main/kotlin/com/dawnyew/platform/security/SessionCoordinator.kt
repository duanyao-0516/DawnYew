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

    fun configureLock(enabled: Boolean) { lockEnabled = enabled; if (!enabled) authorized = true }
    fun beginAuthentication(): Lease {
        authorized = !lockEnabled
        return lease().also { authentication = it }
    }
    fun authenticationIsCurrent(attempt: Lease): Boolean = authentication == attempt && valid(attempt)
    fun finishAuthentication(attempt: Lease, foreground: Boolean, deviceLocked: Boolean): Boolean {
        if (!foreground || deviceLocked || !authenticationIsCurrent(attempt)) return false
        authentication = null; authorized = true; return true
    }
    fun cancelAuthentication(attempt: Lease) { if (authentication == attempt) authentication = null }
    fun beginExternal(): Lease {
        check(authorized) { "应用尚未授权" }; check(external == null) { "已有文件操作" }
        return lease().also { external = it }
    }
    fun externalIsCurrent(value: Lease): Boolean = external == value && valid(value)
    fun cancelExternal(value: Lease) { if (external == value) external = null }
    fun finishExternal(value: Lease): Boolean {
        if (!externalIsCurrent(value)) return false
        external = null; return true
    }
    /** Leaving for a picker suspends authorization; its result still needs fresh device authentication. */
    fun background(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        authorized = !lockEnabled
        if (external == null && authentication == null) revoke()
    }
    fun foreground(deviceLocked: Boolean) {
        if (deviceLocked) revoke()
        if (external?.let { !valid(it) } == true) { external = null; epoch++ }
        if (authentication?.let { !valid(it) } == true) authentication = null
    }
    fun revoke() { epoch++; external = null; authentication = null; authorized = !lockEnabled }
    private fun lease(): Lease { val time = now(); return Lease(++sequence, epoch, time, time + 120_000, processId) }
    private fun valid(value: Lease): Boolean = value.process == processId && value.epoch == epoch && now() in value.issued..value.deadline
}
