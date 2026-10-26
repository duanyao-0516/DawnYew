package com.dawnyew.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.dawnyew.app.DawnYewApplication
import com.dawnyew.core.Money
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.max

/** The system binds this service. It never reads accessibility nodes or triggers captures from events. */
class FloatingCaptureAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = FloatingCaptureRequestGate()
    private val app get() = application as DawnYewApplication
    private lateinit var preferences: FloatingCapturePreferences
    private lateinit var overlay: FloatingCaptureOverlay
    @Volatile private var attached = false
    private var observedEnabled = false
    private var observedConfirm = false
    private var task: Job? = null
    private var screenshotTimeout: Runnable? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val enabled = preferences.enabled; val confirm = preferences.confirmBeforeSave
        // Drag coordinates share these preferences but must never reconstruct the overlay mid-gesture.
        if (observedEnabled != enabled || observedConfirm != confirm) {
            observedEnabled = enabled; observedConfirm = confirm
            cancelPending()
            FloatingCaptureRuntime.message.value = null
            updateAvailability()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                cancelPending()
                FloatingCaptureRuntime.message.value = "屏幕已锁定，解锁后恢复悬浮球。"
            } else if (screenAvailable()) FloatingCaptureRuntime.message.value = null
            updateAvailability()
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = FloatingCapturePreferences(this)
        observedEnabled = preferences.enabled; observedConfirm = preferences.confirmBeforeSave
        overlay = FloatingCaptureOverlay(this, preferences, ::capture)
        preferences.storage.registerOnSharedPreferenceChangeListener(preferencesChanged)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            var datasetEpoch = app.repository.epoch
            app.repository.state.collect {
                if (datasetEpoch != app.repository.epoch) {
                    datasetEpoch = app.repository.epoch
                    cancelPending(); updateAvailability()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        attached = true; current = this
        FloatingCaptureRuntime.connected.value = true
        FloatingCaptureRuntime.message.value = null
        updateAvailability()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { cancelPending(); updateAvailability() }

    private fun screenAvailable() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isDeviceLocked
    private fun available() = attached && preferences.enabled && screenAvailable()

    private fun updateAvailability() {
        if (!available()) overlay.hideAll()
        else if (task?.isActive != true) runCatching { overlay.showBall() }.onFailure {
            FloatingCaptureRuntime.message.value = "悬浮球暂时无法显示，请在系统无障碍设置中重新启用。"
        }
        FloatingCaptureRuntime.refresh(this)
    }

    private fun capture() {
        if (!available()) { updateAvailability(); return }
        if (!FloatingCaptureNotifications.canNotify(this)) {
            failMessage("通知未开启，请先回到 DawnYew 的截屏记账设置开启通知，以便查看、修改或撤销记账结果。")
            return
        }
        val id = requests.begin() ?: return
        val datasetEpoch = app.repository.epoch
        val requestId = UUID.randomUUID().toString()
        val capturedAt = app.environment.localNow()
        val confirmBeforeSave = preferences.confirmBeforeSave
        FloatingCaptureRuntime.message.value = "正在识别当前页面…"
        FloatingCaptureRuntime.refresh(this)
        overlay.hideAll()
        // Removal is committed before the next composed frame. Two callbacks leave a full frame
        // without our ball; a delayed request still belongs to the same cancellable request id.
        val firstFrame = Choreographer.FrameCallback {
            if (requests.isCurrent(id) && available()) {
                val secondFrame = Choreographer.FrameCallback {
                    frameCallback = null
                    if (requests.isCurrent(id) && available()) requestScreenshot(id, datasetEpoch, requestId, capturedAt, confirmBeforeSave)
                    else finish(id)
                }
                frameCallback = secondFrame; Choreographer.getInstance().postFrameCallback(secondFrame)
            } else { frameCallback = null; finish(id) }
        }
        frameCallback = firstFrame; Choreographer.getInstance().postFrameCallback(firstFrame)
        screenshotTimeout = Runnable {
            if (requests.isCurrent(id)) { failMessage("截屏超时，请回到目标页面再点一次悬浮球。"); cancelPending(); updateAvailability() }
        }.also { handler.postDelayed(it, 10_000) }
    }

    private fun requestScreenshot(id: Long, datasetEpoch: Long, requestId: String, capturedAt: String, confirmBeforeSave: Boolean) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, pixels, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    try {
                        screenshot.hardwareBuffer.use { buffer ->
                            if (!requests.claimScreenshot(id)) return
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?: throw IllegalStateException("无法读取截屏像素")
                            try {
                                val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: throw IllegalStateException("无法复制截屏像素")
                                bitmap = software
                                val scale = (1920.0 / max(software.width, software.height)).coerceAtMost(1.0)
                                if (scale < 1.0) {
                                    val smaller = Bitmap.createScaledBitmap(software, (software.width * scale).toInt().coerceAtLeast(1),
                                        (software.height * scale).toInt().coerceAtLeast(1), true)
                                    bitmap = smaller
                                    if (smaller !== software) software.recycle()
                                }
                            } finally { hardware.recycle() }
                        }
                        val owned = bitmap ?: throw IllegalStateException("截屏为空")
                        if (!handler.post { receivePixels(id, datasetEpoch, requestId, capturedAt, confirmBeforeSave, owned) }) owned.recycle()
                        bitmap = null
                    } catch (failure: Throwable) {
                        bitmap?.recycle()
                        handler.post {
                            if (requests.isCurrent(id)) {
                                failMessage("无法读取截屏，请确认目标页面允许截图。"); finish(id)
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    handler.post {
                        if (requests.claimScreenshot(id)) { failMessage(screenshotError(errorCode)); finish(id) }
                    }
                }
            })
        } catch (_: RuntimeException) {
            failMessage("截屏服务暂不可用，请在系统无障碍设置中重新启用。"); finish(id)
        }
    }

    private fun receivePixels(id: Long, datasetEpoch: Long, requestId: String, capturedAt: String, confirmBeforeSave: Boolean, bitmap: Bitmap) {
        if (!requests.isCurrent(id) || !available() || app.repository.epoch != datasetEpoch) {
            bitmap.recycle(); finish(id); return
        }
        screenshotTimeout?.let(handler::removeCallbacks); screenshotTimeout = null
        // UNDISPATCHED enters recognize before another main-thread cancellation can strand ownership.
        task = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val review = withTimeout(60_000) { app.floatingCapture.recognize(bitmap, requestId, capturedAt) }
                if (!requests.isCurrent(id) || !available() || app.repository.epoch != datasetEpoch) return@launch
                if (review == null) {
                    FloatingCaptureRuntime.message.value = "请通过通知解锁 DawnYew，继续处理这笔记账。"
                    finish(id); return@launch
                }
                val complete = review.draft.direction != null && runCatching { Money.parse(review.draft.amount) }.isSuccess
                if (confirmBeforeSave || !complete) showReview(id, review)
                else save(id, review)
            } catch (cancelled: CancellationException) {
                if (requests.isCurrent(id) && available()) failMessage("识别已取消或超时，请重试。")
                finish(id)
            } catch (failure: Exception) {
                if (requests.isCurrent(id)) failMessage(failure.message ?: "本机识别未完成，请重试。")
                finish(id)
            }
        }
    }

    private fun showReview(id: Long, review: FloatingCaptureReview) {
        if (!requests.isCurrent(id) || !available()) { finish(id); return }
        FloatingCaptureRuntime.message.value = "请核对全局确认面板中的账单。"
        FloatingCaptureRuntime.refresh(this)
        try {
            overlay.showConfirmation(review, cancel = {
                FloatingCaptureRuntime.message.value = "已取消此次记账。"
                finish(id)
            }, save = { edited ->
                if (requests.isCurrent(id) && available()) {
                    overlay.dismissConfirmation()
                    task = scope.launch { save(id, edited) }
                } else finish(id)
            })
        } catch (_: RuntimeException) { failMessage("无法显示确认面板，请重新启用悬浮球后重试。"); finish(id) }
    }

    private suspend fun save(id: Long, review: FloatingCaptureReview) {
        try {
            if (!requests.isCurrent(id) || !available()) return
            val outcome = app.floatingCapture.save(review)
            if (requests.isCurrent(id)) {
                FloatingCaptureRuntime.message.value = when {
                    outcome == null -> "请通过通知解锁 DawnYew，继续处理这笔记账。"
                    !outcome.active -> "这张截图的账单已在回收站，没有重复记账。"
                    !outcome.created -> "这张截图已记账，可通过通知查看或修改。"
                    else -> "已记账，可通过通知查看、修改或撤销。"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (requests.isCurrent(id)) failMessage(failure.message ?: "记账未完成，请重试。")
        } finally { finish(id) }
    }

    private fun finish(id: Long) {
        if (!requests.finish(id)) return
        screenshotTimeout?.let(handler::removeCallbacks); screenshotTimeout = null
        overlay.dismissConfirmation()
        if (available()) runCatching { overlay.showBall() }.onFailure {
            FloatingCaptureRuntime.message.value = "悬浮球暂时无法恢复，请重新启用。"
        }
        FloatingCaptureRuntime.refresh(this)
    }

    private fun failMessage(message: String) {
        FloatingCaptureRuntime.message.value = message
        app.floatingCapture.notifyFailure(message)
        FloatingCaptureRuntime.refresh(this)
    }

    private fun cancelPending() {
        requests.cancel(); task?.cancel(); task = null
        screenshotTimeout?.let(handler::removeCallbacks); screenshotTimeout = null
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }; frameCallback = null
        overlay.hideAll()
    }

    private fun offerReview(review: FloatingCaptureReview): Boolean {
        if (!available()) return false
        val id = requests.begin() ?: return false
        showReview(id, review)
        return requests.isCurrent(id)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (attached) runCatching { overlay.configurationChanged() }.onFailure { cancelPending(); updateAvailability() }
    }

    private fun detach() {
        app.floatingCapture.cancelPending()
        attached = false
        if (current === this) { current = null; FloatingCaptureRuntime.connected.value = false }
        cancelPending(); FloatingCaptureRuntime.refresh(this)
    }
    override fun onUnbind(intent: Intent?): Boolean { detach(); return super.onUnbind(intent) }
    override fun onDestroy() {
        detach()
        preferences.storage.unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        unregisterReceiver(screenReceiver)
        scope.cancel(); super.onDestroy()
    }

    companion object {
        @Volatile private var current: FloatingCaptureAccessibilityService? = null
        // A process-scoped executor accepts late callbacks after service teardown so buffers close.
        private val pixels = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "floating-capture-pixels").apply { isDaemon = true } }
        internal fun showConfirmation(review: FloatingCaptureReview): Boolean {
            val service = current ?: return false
            if (Looper.myLooper() == Looper.getMainLooper()) return service.offerReview(review)
            if (!service.attached) return false
            return service.handler.post {
                if (!service.offerReview(review)) service.failMessage("悬浮确认暂不可用，请返回 DawnYew 继续处理。")
            }
        }
        fun dismissPending() {
            val service = current ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) { service.cancelPending(); service.updateAvailability() }
            else service.handler.post { service.cancelPending(); service.updateAvailability() }
        }
        internal fun screenshotError(code: Int): String = when (code) {
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "点击太快，请稍等片刻后重试。"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍截屏授权已失效，请在系统设置中重新启用。"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY, ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "目标画面已变化，请回到目标页面重试。"
            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "该页面包含受保护内容，系统不允许截图。"
            else -> "系统暂时无法截图，请确认目标页面允许截图后重试。"
        }
    }
}
