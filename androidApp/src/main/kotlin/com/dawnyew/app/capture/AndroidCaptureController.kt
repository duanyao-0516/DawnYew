package com.dawnyew.app.capture

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.dawnyew.app.R
import com.dawnyew.presentation.CapturePlatform
import kotlinx.coroutines.flow.update

/** Registers launchers with the Activity, while services own their independent lifetimes. */
internal class AndroidCaptureController(
    private val activity: FragmentActivity,
    private val canUse: () -> Boolean,
) : CapturePlatform {
    override val state = CaptureRuntime.settings
    private var enableAfterPermission = false
    private val permissionRequest = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (enableAfterPermission && !CapturePermissions.canObserve(activity)) {
            enableAfterPermission = false
            state.update { it.copy(message = "自动识别需要全部照片权限，并开启「截图待核对」和「截图记账运行状态」通知；也可以使用截屏按钮或分享截图。") }
        }
        refresh()
    }

    fun refresh() {
        val prefs = activity.getSharedPreferences(TILE_PREFERENCES, 0)
        state.update { it.copy(
            mediaPermission = CapturePermissions.hasMedia(activity),
            notificationPermission = CapturePermissions.hasNotifications(activity),
            screenTileAdded = prefs.getBoolean(SCREEN_TILE, false),
            automaticTileAdded = prefs.getBoolean(AUTOMATIC_TILE, false),
        ) }
        if (canUse()) {
            CaptureRuntime.authorizedForeground.tryEmit(Unit)
            if (enableAfterPermission && CapturePermissions.canObserve(activity)) {
                enableAfterPermission = false
                startAutomatic()
            }
        }
    }

    override fun setAutomaticCapture(enabled: Boolean) {
        if (!canUse()) return
        if (!enabled) {
            enableAfterPermission = false
            activity.startService(Intent(activity, ScreenshotWatchService::class.java).setAction(ScreenshotWatchService.ACTION_STOP))
            return
        }
        refresh()
        if (CapturePermissions.canObserve(activity)) startAutomatic()
        else {
            enableAfterPermission = true
            val requested = buildList {
                if (!CapturePermissions.hasMedia(activity)) add(CapturePermissions.mediaPermission)
                if (Build.VERSION.SDK_INT >= 34 && !CapturePermissions.hasMedia(activity)) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                if (Build.VERSION.SDK_INT >= 33 && !CapturePermissions.hasNotificationRuntimePermission(activity)) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (requested.isEmpty()) {
                enableAfterPermission = false
                state.update { it.copy(message = "通知或截图通知渠道已关闭，请在应用设置中开启后重试。") }
            } else permissionRequest.launch(requested.toTypedArray())
        }
    }
    private fun startAutomatic() {
        try {
            ContextCompat.startForegroundService(activity, Intent(activity, ScreenshotWatchService::class.java).setAction(ScreenshotWatchService.ACTION_START))
        } catch (_: RuntimeException) {
            state.update { it.copy(automaticRunning = false, message = "系统未允许启动截图监听，请保持应用在前台后重试。") }
        }
    }

    override fun requestTile(automatic: Boolean) {
        if (!canUse()) return
        if (Build.VERSION.SDK_INT < 33) {
            state.update { it.copy(message = "请下拉通知栏，进入快捷按钮编辑，将「${if (automatic) "自动记账" else "截屏记账"}」拖入面板。") }
            return
        }
        val component = ComponentName(activity, if (automatic) AutomaticCaptureTileService::class.java else ScreenCaptureTileService::class.java)
        try {
            activity.getSystemService(StatusBarManager::class.java).requestAddTileService(component,
                if (automatic) "自动记账" else "截屏记账", Icon.createWithResource(activity, R.drawable.ic_capture_shortcut), activity.mainExecutor) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                if (added) activity.getSharedPreferences(TILE_PREFERENCES, 0).edit().putBoolean(if (automatic) AUTOMATIC_TILE else SCREEN_TILE, true).apply()
                refresh()
                state.update { it.copy(message = if (added) "已添加，可从通知栏快捷面板使用" else "尚未添加，也可下拉通知栏手动编辑快捷按钮。") }
            }
        } catch (_: RuntimeException) {
            state.update { it.copy(message = "请下拉通知栏，在快捷按钮编辑中手动添加。") }
        }
    }
    override fun openAppSettings() {
        if (canUse()) activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    }
    companion object {
        const val ACTION_SETTINGS = "com.dawnyew.action.CAPTURE_SETTINGS"
        const val EXTRA_ACTION_TOKEN = "capture_action_token"
        const val TILE_PREFERENCES = "capture_tiles"
        const val SCREEN_TILE = "screen"
        const val AUTOMATIC_TILE = "automatic"
    }
}
