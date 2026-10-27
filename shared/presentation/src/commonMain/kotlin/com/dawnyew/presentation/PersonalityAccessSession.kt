package com.dawnyew.presentation

data class PersonalityDataScope(val datasetId: String, val epoch: Long)

interface PersonalityTestAccess {
    fun updateAccess(ready: Boolean, sessionEpoch: Long)
    fun suspendAccess()
    fun synchronizeDataScope()
}

/** Only access is shared. Each test owns its answers, navigation and callback generation. */
class PersonalityTestsAccess(private val tests: List<PersonalityTestAccess>) : PersonalityTestAccess {
    override fun updateAccess(ready: Boolean, sessionEpoch: Long) = tests.forEach { it.updateAccess(ready, sessionEpoch) }
    override fun suspendAccess() = tests.forEach { it.suspendAccess() }
    override fun synchronizeDataScope() = tests.forEach { it.synchronizeDataScope() }
}

/** Main-thread, process-only gate. Constructor never invokes owner callbacks. */
internal class PersonalityAccessSession(
    private val currentDataScope: () -> PersonalityDataScope?,
    private val onScopeInvalidated: () -> Unit,
    private val onAccessChanged: () -> Unit,
    private val onPublish: () -> Unit,
) : PersonalityTestAccess {
    private var dataScope: PersonalityDataScope? = null
    private var sessionEpoch: Long? = null
    var visible: Boolean = false
        private set
    var generation: Long = 0
        private set

    override fun updateAccess(ready: Boolean, sessionEpoch: Long) {
        val scopeChanged = refreshScope()
        val next = ready && dataScope != null
        if (visible != next || this.sessionEpoch != sessionEpoch) {
            generation++
            onAccessChanged()
        } else if (scopeChanged) onAccessChanged()
        this.sessionEpoch = sessionEpoch
        visible = next
        onPublish()
    }

    override fun suspendAccess() {
        if (visible) generation++
        visible = false
        onAccessChanged()
        onPublish()
    }

    override fun synchronizeDataScope() {
        if (refreshScope()) onAccessChanged()
        onPublish()
    }

    fun accept(token: Long): Boolean {
        synchronizeDataScope()
        return visible && token == generation
    }

    /** The caller publishes after its navigation or visible question set is fully updated. */
    fun invalidateCallbacks() { generation++ }

    private fun refreshScope(): Boolean {
        val current = currentDataScope()
        val changed = current != dataScope
        if (changed) {
            dataScope = current
            onScopeInvalidated()
            generation++
        }
        if (current == null) visible = false
        return changed
    }
}
