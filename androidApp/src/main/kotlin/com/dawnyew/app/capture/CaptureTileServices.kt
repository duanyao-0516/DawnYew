package com.dawnyew.app.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.dawnyew.app.MainActivity
import com.dawnyew.app.DawnYewApplication
import kotlinx.coroutines.*

/** Tile launch is an explicit user gesture; Android performs device unlock when needed. */
abstract class CaptureTileService : TileService() {
    protected abstract val automatic: Boolean
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        observing?.cancel()
        observing = scope.launch { CaptureRuntime.settings.collect { updateTile() } }
        updateTile()
    }
    override fun onStopListening() { observing?.cancel(); observing = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTileAdded() { super.onTileAdded(); rememberAdded(true); updateTile() }
    override fun onTileRemoved() { rememberAdded(false); super.onTileRemoved() }
    private fun rememberAdded(added: Boolean) {
        getSharedPreferences(AndroidCaptureController.TILE_PREFERENCES, 0).edit()
            .putBoolean(if (automatic) AndroidCaptureController.AUTOMATIC_TILE else AndroidCaptureController.SCREEN_TILE, added).apply()
    }
    private fun updateTile() {
        qsTile?.let {
            it.label = if (automatic) "自动记账" else "截屏记账"
            it.state = if (!automatic || CaptureRuntime.settings.value.automaticRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }
    final override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { activate() } else activate()
    }
    private fun activate() {
        if (!automatic) launchAndCollapse(Intent(this, ScreenCaptureActivity::class.java))
        else if (CaptureRuntime.settings.value.automaticRunning) {
            startService(Intent(this, ScreenshotWatchService::class.java).setAction(ScreenshotWatchService.ACTION_STOP))
        } else if (CapturePermissions.canObserve(this) && (application as DawnYewApplication).session.authorized) {
            try { ContextCompat.startForegroundService(this, Intent(this, ScreenshotWatchService::class.java).setAction(ScreenshotWatchService.ACTION_START)) }
            catch (_: RuntimeException) { openSettings() }
        } else openSettings()
    }
    private fun openSettings() = launchAndCollapse(Intent(this, MainActivity::class.java).setAction(AndroidCaptureController.ACTION_SETTINGS)
        .putExtra(AndroidCaptureController.EXTRA_ACTION_TOKEN, CaptureActionRuntime.issue(CaptureAction.ENABLE_AUTOMATIC)))
    // PendingIntent overload exists only on API 34+; the Intent overload is required below 34.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchAndCollapse(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, if (automatic) 21 else 20, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
class ScreenCaptureTileService : CaptureTileService() { override val automatic = false }
class AutomaticCaptureTileService : CaptureTileService() { override val automatic = true }
