package com.dawnyew.app.capture

/** Process-local, bounded, single-use handoff. Does not authorize access to a ledger. */
internal class CaptureInbox<T>(
    private val now: () -> Long,
    private val newToken: () -> String,
    private val release: (T) -> Unit,
    private val capacity: Int = 3,
    private val lifetimeMillis: Long = 120_000,
) {
    private data class Pending<T>(val purpose: String, val generation: Long, val deadline: Long, val value: T)
    private val pending = linkedMapOf<String, Pending<T>>()

    @Synchronized fun put(value: T, purpose: String, generation: Long): String {
        prune()
        while (pending.size >= capacity) discard(pending.keys.first())
        val token = newToken()
        check(token !in pending)
        pending[token] = Pending(purpose, generation, now() + lifetimeMillis, value)
        return token
    }

    @Synchronized fun take(token: String, purpose: String, generation: Long): T? {
        prune()
        val entry = pending[token] ?: return null
        if (entry.generation != generation) { discard(token); return null }
        if (entry.purpose != purpose) return null
        pending.remove(token)
        return entry.value
    }

    @Synchronized fun discard(token: String) { pending.remove(token)?.let { release(it.value) } }
    @Synchronized fun clear() { pending.keys.toList().forEach(::discard) }
    @Synchronized fun prune() { pending.filterValues { now() >= it.deadline }.keys.toList().forEach(::discard) }
}
