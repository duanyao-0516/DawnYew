package com.dawnyew.app.capture

import android.os.SystemClock
import java.util.UUID

internal enum class CaptureAction { ENABLE_AUTOMATIC }

/** Internal UI actions are single-use capabilities, independent from image/database generations. */
internal class CaptureActionTokens(
    now: () -> Long,
    newToken: () -> String,
    capacity: Int = 3,
    lifetimeMillis: Long = 120_000,
) {
    private val actions = CaptureInbox<Unit>(now, newToken, {}, capacity, lifetimeMillis)
    fun issue(action: CaptureAction): String = actions.put(Unit, action.name, 0)
    fun consume(token: String, action: CaptureAction): Boolean = actions.take(token, action.name, 0) != null
    fun clear() = actions.clear()
}

/** Never written to preferences, a Bundle or disk; process death revokes every pending action. */
internal object CaptureActionRuntime {
    private val tokens = CaptureActionTokens(SystemClock::elapsedRealtime, { UUID.randomUUID().toString() })
    fun issue(action: CaptureAction): String = tokens.issue(action)
    fun consume(token: String, action: CaptureAction): Boolean = tokens.consume(token, action)
    fun clear() = tokens.clear()
}
