package com.dawnyew.app.capture

import java.util.Locale

internal data class ScreenshotMedia(
    val volume: String, val id: Long, val generationAdded: Long, val addedAtMillis: Long,
    val takenAtMillis: Long, val mime: String, val displayName: String, val relativePath: String,
    val pending: Boolean,
) { val identity: String get() = "$volume:$id:$generationAdded" }

internal data class ScreenshotQueryWindow(val fromMillis: Long, val throughMillis: Long, val expiresAtElapsed: Long)

/** Bounded process-only invalidation and deduplication. It never retains a URI or image contents. */
internal class ScreenshotDetectionPolicy(
    private val enabledAtMillis: Long,
    private val baselines: Map<String, Long>,
    private val lifetimeMillis: Long = 120_000,
    private val capacity: Int = 128,
) {
    private var pendingDeadline: Long? = null
    private val claimed = linkedMapOf<String, Long>()

    init { require(lifetimeMillis > 0 && capacity > 0) }

    fun invalidate(elapsed: Long) { pendingDeadline = elapsed + lifetimeMillis }

    /** Authorization is checked before consuming the signal; denied callers cannot trigger a query. */
    fun takeWindow(authorized: Boolean, interactive: Boolean, locked: Boolean, elapsed: Long, wallTime: Long): ScreenshotQueryWindow? {
        val deadline = pendingDeadline ?: return null
        if (elapsed >= deadline) { pendingDeadline = null; return null }
        if (!authorized || !interactive || locked || wallTime < enabledAtMillis) return null
        pendingDeadline = null
        return ScreenshotQueryWindow(maxOf(enabledAtMillis, wallTime - lifetimeMillis), wallTime, deadline)
    }

    /** MediaStore DATE_ADDED is second precision; generation rejects pre-enable rows in that same second. */
    fun claim(item: ScreenshotMedia, window: ScreenshotQueryWindow, elapsed: Long): Boolean {
        claimed.entries.removeAll { elapsed >= it.value }
        if (elapsed >= window.expiresAtElapsed || item.pending || item.generationAdded <= (baselines[item.volume] ?: return false)) return false
        if (item.addedAtMillis < window.fromMillis / 1000 * 1000 || item.addedAtMillis > window.throughMillis) return false
        if (item.takenAtMillis > 0 && (item.takenAtMillis < window.fromMillis / 1000 * 1000 || item.takenAtMillis > window.throughMillis + 1000)) return false
        if (item.mime.lowercase(Locale.ROOT) !in setOf("image/png", "image/jpeg") || !isScreenshot(item.displayName, item.relativePath)) return false
        if (item.identity in claimed) return false
        claimed[item.identity] = elapsed + lifetimeMillis
        while (claimed.size > capacity) claimed.remove(claimed.keys.first())
        return true
    }

    fun clear() { pendingDeadline = null; claimed.clear() }

    companion object {
        private val namePrefix = Regex("^(screenshot|screen[_ -]?shot|screen[_ -]?capture|截屏|截图)(?:[^a-z]|$)")
        private val screenshotFolders = setOf("screenshots", "screenshot", "screen shots", "screen captures", "截屏", "截图")
        fun isScreenshot(name: String, path: String): Boolean = namePrefix.containsMatchIn(name.lowercase(Locale.ROOT)) ||
            path.replace('\\', '/').lowercase(Locale.ROOT).split('/').any { it in screenshotFolders }
    }
}
