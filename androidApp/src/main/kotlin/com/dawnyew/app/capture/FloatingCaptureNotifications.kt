package com.dawnyew.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dawnyew.app.DawnYewApplication
import com.dawnyew.app.MainActivity
import com.dawnyew.app.R
import com.dawnyew.core.Direction
import com.dawnyew.core.Money
import com.dawnyew.presentation.CaptureBookingOutcome
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class FloatingCaptureUndo(val datasetId: String, val entryId: String,
    val creationOperationId: String, val revision: Long, val operationId: String)
internal data class FloatingCaptureAction(val kind: String, val datasetId: String = "", val entryId: String = "", val token: String = "")

/** Saved records have durable IDs. Pending pixels use a separate expiring process-only token. */
internal object FloatingCaptureNotifications {
    const val ACTION = "com.dawnyew.action.FLOATING_CAPTURE"
    private const val CHANNEL = "floating-capture-results"
    private const val RESULT_ID = 1910
    private const val PENDING_ID = 1911
    private const val FAILURE_ID = 1912
    private val pendingTags = mutableSetOf<String>()
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    fun ensureChannels(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL, "悬浮记账结果", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun canNotify(context: Context): Boolean {
        ensureChannels(context)
        return CapturePermissions.hasNotificationRuntimePermission(context) && manager(context).areNotificationsEnabled() &&
            manager(context).getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun base(context: Context) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_capture_shortcut).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setOnlyAlertOnce(true).setAutoCancel(true)
    /** Permission may disappear between canNotify() and notify(); committed writes stay successful. */
    private fun publish(context: Context, tag: String?, id: Int, notification: Notification): Boolean = try {
        manager(context).notify(tag, id, notification)
        true
    } catch (_: RuntimeException) { false }

    private fun feedback(context: Context, message: String) {
        val show = Runnable { Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run() else Handler(Looper.getMainLooper()).post(show)
    }
    private fun open(context: Context, kind: String, dataset: String = "", entry: String = "", token: String = ""): PendingIntent {
        val uri = Uri.Builder().scheme("dawnyew-floating").authority(kind).appendPath(dataset).appendPath(entry).appendPath(token).build()
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION).setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("kind", kind).putExtra("dataset", dataset).putExtra("entry", entry).putExtra("token", token)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun action(intent: Intent?): FloatingCaptureAction? {
        if (intent?.action != ACTION) return null
        val kind = intent.getStringExtra("kind") ?: return null
        return when (kind) {
            "resume" -> intent.getStringExtra("token")?.takeIf(::validId)?.let { FloatingCaptureAction(kind, token = it) }
            "view", "edit" -> {
                val dataset = intent.getStringExtra("dataset"); val entry = intent.getStringExtra("entry")
                if (dataset != null && entry != null && validId(dataset) && validId(entry)) FloatingCaptureAction(kind, dataset, entry) else null
            }
            else -> null
        }
    }
    private fun validId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    fun saved(context: Context, outcome: CaptureBookingOutcome) {
        val fallback = when {
            !outcome.active -> "这张截图的账单已在回收站，请在 DawnYew 中查看。"
            !outcome.created -> "这张截图已经记账；结果通知不可用，请在 DawnYew 中查看。"
            else -> "已保存账单；结果通知不可用，请在 DawnYew 中查看、修改或撤销。"
        }
        if (!canNotify(context)) { feedback(context, fallback); return }
        val type = if (outcome.direction == Direction.INCOME) "收入" else "支出"
        val title = when { !outcome.active -> "这张截图的账单已在回收站"; !outcome.created -> "这张截图已记账"; else -> "已记$type ¥${Money.format(outcome.amountMinor)}" }
        val notification = base(context).setContentTitle(title).setContentText("${outcome.ledgerName} · ${outcome.categoryName}")
            .setContentIntent(open(context, "view", outcome.datasetId, outcome.entryId))
        if (outcome.active) notification.addAction(0, "修改", open(context, "edit", outcome.datasetId, outcome.entryId))
        if (outcome.active && outcome.created) {
            val undo = Intent(context, FloatingCaptureUndoReceiver::class.java)
                .setData(Uri.parse("dawnyew-floating://undo/${outcome.datasetId}/${outcome.entryId}/${outcome.revision}"))
                .putExtra("dataset", outcome.datasetId).putExtra("entry", outcome.entryId)
                .putExtra("creation", outcome.creationOperationId).putExtra("revision", outcome.revision)
                .putExtra("operation", UUID.randomUUID().toString())
            notification.addAction(0, "撤销", PendingIntent.getBroadcast(context, 0, undo, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (!publish(context, "${outcome.datasetId}/${outcome.entryId}", RESULT_ID, notification.build())) feedback(context, fallback)
    }
    fun undoTarget(intent: Intent): FloatingCaptureUndo? {
        val dataset = intent.getStringExtra("dataset") ?: return null
        val entry = intent.getStringExtra("entry") ?: return null
        val creation = intent.getStringExtra("creation") ?: return null
        val operation = intent.getStringExtra("operation") ?: return null
        val revision = intent.getLongExtra("revision", 0)
        return if (listOf(dataset, entry, creation, operation).all(::validId) && revision > 0)
            FloatingCaptureUndo(dataset, entry, creation, revision, operation) else null
    }
    fun undone(context: Context, target: FloatingCaptureUndo) {
        if (!canNotify(context) || !publish(context, "${target.datasetId}/${target.entryId}", RESULT_ID,
            base(context).setContentTitle("已撤销截图记账").setContentText("账单已移入回收站").build()))
            feedback(context, "已撤销截图记账；结果通知不可用，可在 DawnYew 回收站中查看。")
    }
    fun unlock(context: Context, token: String): Boolean {
        if (!canNotify(context)) return false
        pendingTags += token
        val published = publish(context, token, PENDING_ID, base(context).setContentTitle("解锁后继续截图记账")
            .setContentText("点击解锁 DawnYew，本次操作保留两分钟").setTimeoutAfter(120_000)
            .setContentIntent(open(context, "resume", token = token)).build())
        if (!published) pendingTags -= token
        return published
    }
    fun dismissPending(context: Context, token: String) { pendingTags -= token; manager(context).cancel(token, PENDING_ID) }
    fun clearPending(context: Context) { pendingTags.toList().forEach { dismissPending(context, it) } }
    fun failure(context: Context, message: String) {
        if (!canNotify(context) || !publish(context, null, FAILURE_ID,
            base(context).setContentTitle("截图记账未完成").setContentText(message).build())) feedback(context, message)
    }
}

class FloatingCaptureUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = FloatingCaptureNotifications.undoTarget(intent) ?: return
        val result = goAsync()
        val app = context.applicationContext as DawnYewApplication
        app.applicationScope.launch {
            try { app.floatingCapture.undo(target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { app.floatingCapture.notifyFailure(failure.message ?: "撤销未完成，请到详情中核对") }
            finally { result.finish() }
        }
    }
}
