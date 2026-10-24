package com.dawnyew.app.capture

import android.app.Activity
import android.app.KeyguardManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import com.dawnyew.app.DawnYewApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

internal data class ScreenCaptureProgress(val requestId: String? = null, val complete: Boolean = false, val token: String? = null, val error: String? = null)
internal object ScreenCaptureSession {
    val state = MutableStateFlow<Map<String, ScreenCaptureProgress>>(emptyMap())
    fun publish(progress: ScreenCaptureProgress) {
        val id = progress.requestId ?: return
        state.update { (it + (id to progress)).entries.toList().takeLast(8).associate { entry -> entry.toPair() } }
    }
    fun forget(id: String) { state.update { it - id } }
}

/** A consent grant creates exactly one projection and one VirtualDisplay, then stops. */
class ScreenCaptureService : Service() {
    private val worker = HandlerThread("one-shot-screen-capture").apply { start() }
    private val handler = Handler(worker.looper)
    private data class Request(val id: String, val generation: Long, val deliveryEpoch: Long, val gate: CaptureRequestGate = CaptureRequestGate())
    @Volatile private var request: Request? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var cleaned = false
    private val app get() = application as DawnYewApplication
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val expected = intent.getStringExtra(EXTRA_REQUEST)
            val current = request
            if (current != null && (expected == null || expected == current.id)) {
                // Cancel immediately on the main thread; the worker may currently be copying pixels.
                current.gate.cancel({ CaptureRuntime.discard(this, it) }) {
                    ScreenCaptureSession.publish(ScreenCaptureProgress(current.id, complete = true, error = "截屏已取消"))
                }
                handler.post { finishService() }
            } else if (current == null) {
                expected?.let { id ->
                    ScreenCaptureSession.state.value[id]?.token?.let { CaptureRuntime.discard(this, it) }
                    ScreenCaptureSession.publish(ScreenCaptureProgress(id, complete = true, error = "截屏已取消"))
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_REQUEST)
        val grant = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_GRANT, Intent::class.java)
            else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_GRANT) as? Intent)
        if (id == null || grant == null) { if (request == null) stopSelf(); return START_NOT_STICKY }
        if (request != null) {
            if (request?.id != id) ScreenCaptureSession.publish(ScreenCaptureProgress(id, complete = true, error = "上一次截屏正在结束，请稍后重试"))
            return START_NOT_STICKY
        }
        val current = Request(id, app.repository.epoch, CaptureRuntime.deliveryEpoch())
        request = current
        ScreenCaptureSession.publish(ScreenCaptureProgress(id))
        try {
            ServiceCompat.startForeground(this, CaptureNotifications.PROJECTION_ID, CaptureNotifications.projectionForeground(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (_: RuntimeException) {
            handler.post { fail(current, "系统未允许截屏服务，请重试") }
            return START_NOT_STICKY
        }
        handler.post {
            if (current.gate.isFinished()) { finishService(); return@post }
            if (!screenAvailable()) { fail(current, "屏幕已锁定，请解锁后重试"); return@post }
            try {
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant)
                projection!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { fail(current, "系统已停止截屏") }
                }, handler)
                // The consent window and notification shade must leave before creating the surface.
                handler.postDelayed({ if (!current.gate.isFinished()) createDisplay(current) }, 500)
                handler.postDelayed({ if (!current.gate.isFinished()) fail(current, "截屏超时，请返回目标页面重试") }, 10_000)
            } catch (_: RuntimeException) { fail(current, "截屏授权已失效，请重新授权") }
        }
        return START_NOT_STICKY
    }

    private fun screenAvailable() = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isDeviceLocked
    private fun createDisplay(current: Request) {
        if (!screenAvailable()) { fail(current, "屏幕已锁定，请解锁后重试"); return }
        try {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val scale = (1920.0 / max(bounds.width(), bounds.height())).coerceAtMost(1.0)
            val width = (bounds.width() * scale).toInt().coerceAtLeast(1)
            val height = (bounds.height() * scale).toInt().coerceAtLeast(1)
            val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = images
            images.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    image.use {
                        if (!current.gate.isFinished()) {
                            val bitmap = copyPixels(image)
                            val published = current.gate.finish {
                                val token = if (screenAvailable() && app.repository.epoch == current.generation)
                                    CaptureRuntime.offer(this, CapturePayload.Pixels(bitmap), current.generation, current.deliveryEpoch)
                                else { bitmap.recycle(); null }
                                ScreenCaptureSession.publish(ScreenCaptureProgress(current.id, complete = true, token = token,
                                    error = if (token == null) "截屏上下文已改变，请重试" else null))
                                token
                            }
                            if (!published) bitmap.recycle()
                        }
                    }
                    // Close the acquired Image before its reader and display.
                    finishService()
                } catch (_: RuntimeException) { fail(current, "无法读取截屏，请确认目标页面允许截图") }
            }, handler)
            display = requireNotNull(projection).createVirtualDisplay("DawnYew single screenshot", width, height,
                (resources.displayMetrics.densityDpi * scale).toInt().coerceAtLeast(1), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null, handler)
        } catch (_: RuntimeException) { fail(current, "无法开始截屏，请重新授权") }
    }

    private fun copyPixels(image: Image): Bitmap {
        val plane = image.planes.first()
        require(plane.pixelStride == 4)
        val source = plane.buffer
        val row = IntArray(image.width)
        val result = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            repeat(image.height) { y ->
                source.position(y * plane.rowStride)
                repeat(image.width) { x ->
                    val r = source.get().toInt() and 255; val g = source.get().toInt() and 255
                    val b = source.get().toInt() and 255; val a = source.get().toInt() and 255
                    row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                result.setPixels(row, 0, image.width, 0, y, image.width, 1)
            }
            return result
        } catch (failure: Throwable) { result.recycle(); throw failure }
    }

    private fun fail(current: Request, message: String) {
        current.gate.finish {
            ScreenCaptureSession.publish(ScreenCaptureProgress(current.id, complete = true, error = message)); null
        }
        finishService()
    }
    /** Resource teardown is worker-confined; the request gate handles main-thread cancellation. */
    private fun finishService() {
        if (cleaned) return
        cleaned = true
        releaseResources()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun releaseResources() {
        val oldDisplay = display; display = null; runCatching { oldDisplay?.release() }
        val oldReader = reader; reader = null; runCatching { oldReader?.close() }
        val oldProjection = projection; projection = null; runCatching { oldProjection?.stop() }
    }
    override fun onDestroy() {
        request?.let { current ->
            current.gate.finish {
                ScreenCaptureSession.publish(ScreenCaptureProgress(current.id, complete = true, error = "截屏已停止")); null
            }
        }
        handler.post { releaseResources(); handler.removeCallbacksAndMessages(null); worker.quitSafely() }
        super.onDestroy()
    }
    companion object {
        const val EXTRA_REQUEST = "capture_request"
        const val EXTRA_GRANT = "projection_grant"
        const val ACTION_STOP = "com.dawnyew.action.STOP_SCREEN_CAPTURE"
    }
}
