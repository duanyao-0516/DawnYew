package com.dawnyew.app.capture

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import com.dawnyew.app.DawnYewApplication
import com.dawnyew.core.*
import com.dawnyew.presentation.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only user-triggered requests. Pixels and unlock continuations never reach disk. */
internal class FloatingCaptureWorkflow(private val app: DawnYewApplication) {
    private data class Lease(val generation: Long, val databaseEpoch: Long, val deadline: Long)
    private sealed interface Pending {
        data class Pixels(val bitmap: Bitmap, val requestId: String, val capturedAt: String) : Pending
        data class Review(val review: FloatingCaptureReview) : Pending
        data class Undo(val target: FloatingCaptureUndo) : Pending
    }
    private val preferences = FloatingCapturePreferences(app)
    private val commitGate = Mutex()
    private val leases = mutableMapOf<String, Lease>()
    private var generation = 0L
    private val pending = CaptureInbox<Pending>(SystemClock::elapsedRealtime, { UUID.randomUUID().toString() }, {
        if (it is Pending.Pixels && !it.bitmap.isRecycled) it.bitmap.recycle()
    })

    private fun screenAvailable() = app.getSystemService(PowerManager::class.java).isInteractive &&
        !app.getSystemService(KeyguardManager::class.java).isDeviceLocked
    private fun available() = preferences.enabled && FloatingCaptureRuntime.connected.value && screenAvailable()
    private fun requireCurrent(requestId: String): Lease {
        val lease = leases[requestId] ?: error("这次截图已过期，请重新截屏")
        check(available() && lease.generation == generation && lease.databaseEpoch == app.repository.epoch &&
            SystemClock.elapsedRealtime() < lease.deadline) { "截图已过期或账本数据已切换，请重新截屏" }
        return lease
    }
    private fun queue(value: Pending) {
        pending.clear()
        FloatingCaptureNotifications.clearPending(app)
        val token = pending.put(value, "unlock", generation)
        if (!FloatingCaptureNotifications.unlock(app, token)) {
            pending.discard(token)
            error("通知权限已关闭，请在 DawnYew 的悬浮记账设置中开启通知后重试")
        }
        app.applicationScope.launch { delay(120_001); pending.prune(); FloatingCaptureNotifications.dismissPending(app, token) }
    }

    /** Takes bitmap ownership synchronously, including cancellation before IO dispatch. */
    suspend fun recognize(bitmap: Bitmap, requestId: String, capturedAt: String): FloatingCaptureReview? {
        var transferred = false
        try {
            check(available()) { "悬浮记账已关闭或屏幕已锁定" }
            leases.entries.removeAll { SystemClock.elapsedRealtime() >= it.value.deadline }
            leases[requestId] = Lease(generation, app.repository.epoch, SystemClock.elapsedRealtime() + 120_000)
            if (!app.session.authorized) {
                queue(Pending.Pixels(bitmap, requestId, capturedAt)); transferred = true; return null
            }
            app.awaitReady()
            requireCurrent(requestId)
            val epoch = app.session.epoch
            val state = app.repository.snapshot()
            transferred = true
            val recognition = CaptureRecognizer.fromBitmap(app, bitmap)
            requireCurrent(requestId)
            check(app.session.authorized && epoch == app.session.epoch) { "账本授权已变化，请重新截屏" }
            val draft = recognition.draft ?: error(recognition.explanation.ifBlank { "未识别出金额，请重新截屏" })
            val ledgers = state.ledgers.filterNot { it.archived }.sortedWith(compareBy<Ledger> { it.sortOrder }.thenBy { it.id })
            val lastLedger = state.preferences.firstOrNull { it.key == "lastLedgerId" }?.value
            val ledger = ledgers.firstOrNull { it.id == lastLedger } ?: ledgers.firstOrNull() ?: error("没有可用账本")
            val categories = state.categories.filter { it.enabled }.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
            val matching = categories.filter { it.ledgerId == ledger.id && it.direction == draft.direction }
            val other = if (draft.direction == Direction.INCOME) "其他收入" else "其他支出"
            val category = matching.firstOrNull { it.name == other } ?: matching.firstOrNull()
            return FloatingCaptureReview(requestId, capturedAt, state.datasetId,
                draft.copy(occurrence = draft.occurrence.ifBlank { capturedAt }), ledgers, categories, ledger.id, category?.id,
                defaultedTime = draft.occurrence.isBlank(), defaultLedgerId = ledger.id, defaultCategoryId = category?.id)
        } finally {
            if (!transferred && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    suspend fun save(review: FloatingCaptureReview): CaptureBookingOutcome? {
        val lease = requireCurrent(review.requestId)
        if (!app.session.authorized) { queue(Pending.Review(review)); return null }
        val sessionEpoch = app.session.epoch
        val outcome = commitGate.withLock {
            requireCurrent(review.requestId)
            check(app.session.authorized && app.session.epoch == sessionEpoch) { "账本授权已变化，请重新截屏" }
            CaptureBookingCoordinator(app.repository.inGeneration(lease.databaseEpoch)).record(CaptureBookingRequest(
            operationId = review.requestId, amountMinor = Money.parse(review.draft.amount),
            direction = requireNotNull(review.draft.direction) { "请选择收入或支出" },
            capturedAt = review.capturedAt, fingerprint = requireNotNull(review.draft.fingerprint) { "截图指纹已失效，请重新截屏" },
            engineVersion = requireNotNull(review.draft.engineVersion) { "识别引擎版本缺失，请重新截屏" }, occurrence = review.draft.occurrence,
            note = noteWithDefaults(review), ledgerId = review.ledgerId, categoryId = review.categoryId,
        ), review.datasetId)
        }
        // A committed operation remains recoverable through the repository receipt even if access
        // changes during IO; never publish sensitive result text into a different session/dataset.
        if (available() && generation == lease.generation && app.repository.epoch == lease.databaseEpoch &&
            app.session.authorized && app.session.epoch == sessionEpoch) FloatingCaptureNotifications.saved(app, outcome)
        leases.remove(review.requestId)
        return outcome
    }

    private fun noteWithDefaults(review: FloatingCaptureReview): String {
        val decisions = buildList {
            if (review.defaultedTime && review.draft.occurrence == review.capturedAt) add("时间采用截图时间")
            if (review.ledgerId == review.defaultLedgerId) review.ledgers.firstOrNull { it.id == review.ledgerId }?.let { add("默认账本：${it.name}") }
            if (review.categoryId != null && review.categoryId == review.defaultCategoryId)
                review.categories.firstOrNull { it.id == review.categoryId }?.let { add("默认分类：${it.name}") }
        }
        if (decisions.isEmpty()) return review.draft.note
        val suffix = "[截图记账：${decisions.joinToString("；")}]"
        // Reserve room for provenance instead of allowing a long merchant string to truncate it.
        val note = review.draft.note.codePoints().limit((499 - suffix.codePointCount(0, suffix.length)).toLong()).toArray()
        val source = String(note, 0, note.size)
        return listOf(source, suffix).filter { it.isNotBlank() }.joinToString("\n")
    }

    suspend fun resume(token: String) {
        val action = pending.take(token, "unlock", generation)
        FloatingCaptureNotifications.dismissPending(app, token)
        check(app.session.authorized && screenAvailable()) { "请解锁账本后重试" }
        when (action) {
            is Pending.Pixels -> {
                // Preserve the original TTL/database binding while renewing only the app authorization.
                try { requireCurrent(action.requestId) } catch (error: Exception) { action.bitmap.recycle(); throw error }
                val originalLease = leases.getValue(action.requestId)
                val review = recognize(action.bitmap, action.requestId, action.capturedAt)
                leases[action.requestId] = originalLease
                if (review != null) continueReview(review)
            }
            is Pending.Review -> save(action.review)
            is Pending.Undo -> undo(action.target)
            null -> error("待处理截图已过期，请回到目标页面重新截屏")
        }
    }

    private suspend fun continueReview(review: FloatingCaptureReview) {
        requireCurrent(review.requestId)
        val complete = review.draft.direction != null && runCatching { Money.parse(review.draft.amount) }.isSuccess
        if (preferences.confirmBeforeSave || !complete) {
            check(FloatingCaptureAccessibilityService.showConfirmation(review)) { "悬浮服务未连接，请重新启用后截屏" }
        } else save(review)
    }

    suspend fun undo(target: FloatingCaptureUndo) {
        check(screenAvailable()) { "请先解锁屏幕，再撤销这笔记账" }
        if (!app.session.authorized) { queue(Pending.Undo(target)); return }
        app.awaitReady()
        val databaseEpoch = app.repository.epoch
        val sessionEpoch = app.session.epoch
        commitGate.withLock {
            check(screenAvailable() && app.session.authorized && app.session.epoch == sessionEpoch) { "授权已变化，请解锁后重新撤销" }
            CaptureBookingCoordinator(app.repository.inGeneration(databaseEpoch))
                .undo(target.datasetId, target.entryId, target.creationOperationId, target.revision, target.operationId)
        }
        if (app.repository.epoch == databaseEpoch && app.session.authorized && app.session.epoch == sessionEpoch)
            FloatingCaptureNotifications.undone(app, target)
    }

    fun cancelPending() {
        generation++; pending.clear(); leases.clear()
        FloatingCaptureNotifications.clearPending(app)
    }
    fun notifyFailure(message: String) {
        FloatingCaptureRuntime.message.value = message
        FloatingCaptureRuntime.refresh(app)
        FloatingCaptureNotifications.failure(app, message)
    }
}
