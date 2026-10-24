package com.dawnyew.app.capture

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dawnyew.app.DawnYewApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** User-started, non-sticky observer. It never reads or writes a ledger or retains image permissions. */
internal class ScreenshotWatchService : Service() {
    private data class Access(val session: Long, val generation: Long, val deliveryEpoch: Long)
    private data class Volume(val name: String, val generation: Long, val version: String)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as DawnYewApplication
    private val power get() = getSystemService(PowerManager::class.java)
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    @Volatile private var running = false
    private var observing = false
    private var initializeJob: Job? = null
    private var readJob: Job? = null
    @Volatile private var queryCancellation: CancellationSignal? = null
    private var policy: ScreenshotDetectionPolicy? = null
    private var volumes: List<Volume> = emptyList()
    private val offered = linkedMapOf<String, Long>()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = invalidate()
        override fun onChange(selfChange: Boolean, uri: Uri?) = invalidate()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { cancelRead(); discardOffered() }
            updatePaused()
            processPending()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        work.launch {
            app.sessionRevocations.drop(1).collect {
                // Opening a result normally revokes the prior Activity session. Do not erase that token.
                cancelRead()
                if (running && policy == null) stopWatching("开启过程已取消，请解锁后重新开启。")
                else updatePaused()
            }
        }
        work.launch { CaptureRuntime.authorizedForeground.collect { updatePaused(); processPending() } }
        work.launch {
            while (true) {
                delay(5_000)
                checkPermissions()
                if (running) updatePaused()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWatching("已停止系统截图监听。")
            ACTION_START -> if (!running) startWatching()
            else -> stopWatching(null)
        }
        return START_NOT_STICKY
    }

    private fun startWatching() {
        if (!CapturePermissions.canObserve(this)) { stopWatching("需要完整照片读取与通知权限，才能开启截图监听。"); return }
        if (!app.session.authorized || keyguard.isDeviceLocked || !power.isInteractive) { stopWatching("请先解锁设备与应用，再开启截图监听。"); return }
        try {
            ServiceCompat.startForeground(this, CaptureNotifications.WATCHER_ID, CaptureNotifications.watcherForeground(this),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: RuntimeException) { stopWatching("系统未允许启动截图监听，请在应用前台重试。"); return }
        running = true
        val enabledAt = System.currentTimeMillis()
        val access = access() ?: run { stopWatching("请解锁后重试。"); return }
        CaptureRuntime.settings.update { it.copy(automaticRunning = true, automaticPaused = false, message = "正在开启系统截图监听…") }
        initializeJob = work.launch {
            try {
                // Generation counters establish the baseline without enumerating historical image rows.
                val baselines = withContext(Dispatchers.IO) {
                    requireAccess(access)
                    MediaStore.getExternalVolumeNames(this@ScreenshotWatchService).sorted().take(MAX_VOLUMES).map { volume ->
                        currentCoroutineContext().ensureActive(); requireAccess(access)
                        Volume(volume, MediaStore.getGeneration(this@ScreenshotWatchService, volume), MediaStore.getVersion(this@ScreenshotWatchService, volume))
                    }
                }
                requireAccess(access)
                check(baselines.isNotEmpty())
                volumes = baselines
                policy = ScreenshotDetectionPolicy(enabledAt, baselines.associate { it.name to it.generation })
                observing = true
                baselines.forEach { contentResolver.registerContentObserver(MediaStore.Images.Media.getContentUri(it.name), true, observer) }
                CaptureRuntime.settings.update { it.copy(automaticRunning = true, automaticPaused = false, message = "仅监听开启后的新系统截图；识别后会通知你核对。") }
            } catch (_: CancellationException) {
                if (running && policy == null) stopWatching("开启过程已取消，请重试。")
            } catch (_: Exception) { stopWatching("无法监听系统图片库，请检查照片权限后重试。") }
        }
    }

    /** Content notifications are invalidations only: no URI or metadata is retained while inaccessible. */
    private fun invalidate() {
        if (!running || !checkPermissions()) return
        policy?.invalidate(SystemClock.elapsedRealtime())
        updatePaused()
        processPending()
    }

    private fun processPending() {
        if (!running || readJob != null || !checkPermissions()) return
        val rules = policy ?: return
        val access = access() ?: return
        val window = rules.takeWindow(app.session.authorized, power.isInteractive, keyguard.isDeviceLocked,
            SystemClock.elapsedRealtime(), System.currentTimeMillis()) ?: return
        readJob = work.launch {
            try {
                var recognizedCount = 0
                // A provider may notify before commit. Short bounded retries never accept IS_PENDING rows.
                for (pause in listOf(180L, 400L, 900L)) {
                    delay(pause); requireAccess(access)
                    if (SystemClock.elapsedRealtime() >= window.expiresAtElapsed) break
                    val attemptWindow = window.copy(throughMillis = System.currentTimeMillis())
                    val candidates = withContext(Dispatchers.IO) { queryCandidates(access, attemptWindow) }
                    for (candidate in candidates) {
                        requireAccess(access)
                        if (recognizedCount >= MAX_RECOGNITIONS) break
                        if (!rules.claim(candidate, attemptWindow, SystemClock.elapsedRealtime())) continue
                        recognizedCount++
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(candidate.volume), candidate.id)
                        val result = try { withTimeout(20_000) { CaptureRecognizer.fromUri(this@ScreenshotWatchService, uri) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        requireAccess(access)
                        if (result?.draft != null && SystemClock.elapsedRealtime() < window.expiresAtElapsed) {
                            val token = CaptureRuntime.offer(this@ScreenshotWatchService, CapturePayload.Recognized(result), access.generation, access.deliveryEpoch) ?: continue
                            offered.entries.removeAll { SystemClock.elapsedRealtime() >= it.value }
                            offered[token] = SystemClock.elapsedRealtime() + 120_000
                            while (offered.size > MAX_OFFERED) {
                                val oldest = offered.keys.first(); offered.remove(oldest)
                                CaptureRuntime.discard(this@ScreenshotWatchService, oldest)
                            }
                        }
                    }
                    if (recognizedCount >= MAX_RECOGNITIONS) break
                }
            } catch (_: CancellationException) {
                // Epoch changes, explicit stop and timeout never publish an old candidate.
            } catch (_: android.os.OperationCanceledException) {
                // A revoked session or screen-off cancels the provider before any further rows are read.
            } catch (_: SecurityException) { stopWatching("照片读取权限已失效，请重新授权后开启。"); }
            catch (_: Exception) { stopWatching("系统图片库已变化，请重新开启截图监听。"); }
            finally {
                readJob = null
                updatePaused()
                processPending()
            }
        }
    }

    private suspend fun queryCandidates(access: Access, window: ScreenshotQueryWindow): List<ScreenshotMedia> {
        val results = mutableListOf<ScreenshotMedia>()
        for (volume in volumes) {
            currentCoroutineContext().ensureActive(); requireAccess(access)
            check(MediaStore.getVersion(this, volume.name) == volume.version && MediaStore.getGeneration(this, volume.name) >= volume.generation)
            requireAccess(access)
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.GENERATION_ADDED,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING)
            val arguments = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.GENERATION_ADDED}>? AND ${MediaStore.MediaColumns.DATE_ADDED}>=? AND ${MediaStore.MediaColumns.DATE_ADDED}<=? AND ${MediaStore.MediaColumns.IS_PENDING}=0 AND ${MediaStore.MediaColumns.MIME_TYPE} IN (?,?) AND (LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ? OR LOWER(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE ? OR LOWER(${MediaStore.MediaColumns.RELATIVE_PATH}) LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(volume.generation.toString(),
                    (window.fromMillis / 1000).toString(), (window.throughMillis / 1000).toString(), "image/png", "image/jpeg",
                    "%screen%shot%", "%screen%capture%", "%screen%", "%截图%", "%截屏%", "%截图%", "%截屏%"))
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.Images.Media._ID))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ROWS)
            }
            val signal = CancellationSignal()
            queryCancellation = signal
            try {
                requireAccess(access)
                contentResolver.query(MediaStore.Images.Media.getContentUri(volume.name), projection, arguments, signal)?.use { cursor ->
                    var rows = 0
                    while (rows++ < MAX_ROWS && cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive(); requireAccess(access)
                        results += ScreenshotMedia(volume.name, cursor.getLong(0), cursor.getLong(1), cursor.getLong(2) * 1000,
                            cursor.getLong(3), cursor.getString(4).orEmpty(), cursor.getString(5).orEmpty(), cursor.getString(6).orEmpty(), cursor.getInt(7) != 0)
                    }
                }
            } finally { if (queryCancellation === signal) queryCancellation = null }
        }
        return results.sortedWith(compareByDescending<ScreenshotMedia> { it.addedAtMillis }.thenByDescending { it.id })
    }

    private fun access(): Access? = if (running && app.session.authorized && power.isInteractive && !keyguard.isDeviceLocked && CapturePermissions.canObserve(this))
        Access(app.session.epoch, app.repository.epoch, CaptureRuntime.deliveryEpoch()) else null

    private fun requireAccess(expected: Access) {
        if (access() != expected) throw CancellationException("Screenshot access changed")
    }

    private fun checkPermissions(): Boolean {
        if (running && !CapturePermissions.canObserve(this)) { stopWatching("照片或通知权限已关闭，截图监听已停止。"); return false }
        return true
    }

    private fun updatePaused() {
        if (running) CaptureRuntime.settings.update { it.copy(automaticPaused = access() == null) }
    }

    private fun cancelRead() { queryCancellation?.cancel(); readJob?.cancel() }
    private fun discardOffered() { offered.keys.toList().forEach { CaptureRuntime.discard(this, it) }; offered.clear() }

    private fun stopWatching(message: String?) {
        running = false
        initializeJob?.cancel(); initializeJob = null
        cancelRead()
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
        policy?.clear(); policy = null; volumes = emptyList()
        discardOffered()
        CaptureRuntime.settings.update { it.copy(automaticRunning = false, automaticPaused = false, message = message) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopWatching(CaptureRuntime.settings.value.message)
        unregisterReceiver(screenReceiver)
        work.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dawnyew.action.START_SCREENSHOT_WATCH"
        const val ACTION_STOP = "com.dawnyew.action.STOP_SCREENSHOT_WATCH"
        private const val MAX_ROWS = 32
        private const val MAX_VOLUMES = 4
        private const val MAX_RECOGNITIONS = 4
        private const val MAX_OFFERED = 16
    }
}
