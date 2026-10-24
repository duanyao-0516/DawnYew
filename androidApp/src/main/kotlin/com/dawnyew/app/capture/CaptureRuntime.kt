package com.dawnyew.app.capture

import android.app.KeyguardManager
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dawnyew.app.MainActivity
import com.dawnyew.presentation.CaptureSettingsUi
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal sealed interface CapturePayload {
    data class Pixels(val bitmap: Bitmap) : CapturePayload
    data class Recognized(val result: CaptureRecognition) : CapturePayload
}

/** No Activities, authorization grants, images or candidates are persisted. */
internal object CaptureRuntime {
    const val ACTION_REVIEW = "com.dawnyew.action.REVIEW_CAPTURE"
    const val EXTRA_TOKEN = "capture_token"
    private const val PURPOSE = "offline-screenshot-review"
    val settings = MutableStateFlow(CaptureSettingsUi())
    val authorizedForeground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val handler = Handler(Looper.getMainLooper())
    private val inbox = CaptureInbox<CapturePayload>(SystemClock::elapsedRealtime, { UUID.randomUUID().toString() }, {
        if (it is CapturePayload.Pixels && !it.bitmap.isRecycled) it.bitmap.recycle()
    })

    private var invalidation = 0L
    private val tokens = linkedSetOf<String>()
    @Synchronized fun deliveryEpoch(): Long = invalidation

    /** Takes ownership even on rejection. Lock invalidation and publication share this monitor. */
    @Synchronized fun offer(context: Context, payload: CapturePayload, generation: Long, expectedEpoch: Long): String? {
        if (expectedEpoch != invalidation || !context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            if (payload is CapturePayload.Pixels && !payload.bitmap.isRecycled) payload.bitmap.recycle()
            return null
        }
        while (tokens.size >= 3) discard(context, tokens.first())
        val token = inbox.put(payload, PURPOSE, generation)
        tokens += token
        try { CaptureNotifications.showResult(context, token) }
        catch (_: RuntimeException) { discard(context, token); return null }
        handler.postDelayed({ discard(context.applicationContext, token) }, 120_000)
        return token
    }
    @Synchronized fun take(context: Context, token: String, generation: Long): CapturePayload? {
        tokens.remove(token)
        CaptureNotifications.dismissResult(context, token)
        return inbox.take(token, PURPOSE, generation)
    }
    @Synchronized fun discard(context: Context, token: String) {
        tokens.remove(token); inbox.discard(token); CaptureNotifications.dismissResult(context, token)
    }
    @Synchronized fun clear(context: Context) {
        invalidation++
        tokens.toList().forEach { discard(context, it) }
        inbox.clear()
    }
    fun reviewIntent(context: Context, token: String) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_REVIEW).putExtra(EXTRA_TOKEN, token)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
