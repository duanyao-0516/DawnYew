package com.dawnyew.app.capture

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dawnyew.app.DawnYewApplication
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
    private val floatingPreferences by lazy { FloatingCapturePreferences(activity) }
    private var resumeFloatingPermissions = false
    private var floatingSettingsDestination: FloatingSettingsDestination? = null
    private var floatingDisclosure: AlertDialog? = null
    private val floatingNotificationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        resumeFloatingPermissions = granted && floatingPreferences.enabled
        if (!granted) FloatingCaptureRuntime.message.value = "尚未允许通知。开启后才能通过结果通知查看、修改或撤销记账。"
        refresh()
    }
    private val floatingSettings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val destination = floatingSettingsDestination
        floatingSettingsDestination = null
        if (destination == FloatingSettingsDestination.NOTIFICATIONS && floatingPreferences.enabled) {
            resumeFloatingPermissions = FloatingCaptureNotifications.canNotify(activity)
            if (!resumeFloatingPermissions) FloatingCaptureRuntime.message.value = "记账结果通知仍未开启，请在系统设置中允许通知和「悬浮记账结果」渠道。"
        } else if (destination == FloatingSettingsDestination.ACCESSIBILITY) {
            FloatingCaptureRuntime.message.value = when {
                !floatingPreferences.enabled -> null
                !accessibilityEnabled() -> "尚未启用无障碍服务，请在系统设置中打开「DawnYew 悬浮记账」。"
                !FloatingCaptureRuntime.connected.value -> "授权已开启，正在等待系统连接悬浮记账服务。"
                else -> null
            }
        }
        refresh()
    }
    private val permissionRequest = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (enableAfterPermission && !CapturePermissions.canObserve(activity)) {
            enableAfterPermission = false
            state.update { it.copy(message = "自动识别需要全部照片权限，并开启「截图待核对」和「截图记账运行状态」通知；也可以使用截屏按钮或分享截图。") }
        }
        refresh()
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) { floatingDisclosure?.dismiss(); floatingDisclosure = null }
        })
    }

    fun refresh() {
        FloatingCaptureNotifications.ensureChannels(activity)
        if (FloatingCaptureRuntime.connected.value && FloatingCaptureNotifications.canNotify(activity) &&
            FloatingCaptureRuntime.message.value == "授权已开启，正在等待系统连接悬浮记账服务。") FloatingCaptureRuntime.message.value = null
        FloatingCaptureRuntime.refresh(activity)
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
            if (resumeFloatingPermissions) {
                resumeFloatingPermissions = false
                if (floatingPreferences.enabled) advanceFloatingPermissions()
            }
        }
    }

    override fun setFloatingCapture(enabled: Boolean) {
        if (!canUse()) return
        floatingPreferences.enabled = enabled
        FloatingCaptureRuntime.message.value = null
        if (!enabled) {
            resumeFloatingPermissions = false
            floatingSettingsDestination = null
            floatingDisclosure?.dismiss(); floatingDisclosure = null
            (activity.application as DawnYewApplication).floatingCapture.cancelPending()
            FloatingCaptureAccessibilityService.dismissPending()
            refresh()
        } else {
            requestFloatingPermissions()
        }
    }

    override fun setFloatingConfirmation(enabled: Boolean) {
        if (!canUse()) return
        floatingPreferences.confirmBeforeSave = enabled
        refresh()
    }

    override fun requestFloatingPermissions() {
        if (!canUse()) return
        FloatingCaptureNotifications.ensureChannels(activity)
        FloatingCaptureRuntime.message.value = null
        advanceFloatingPermissions()
        FloatingCaptureRuntime.refresh(activity)
    }

    private fun advanceFloatingPermissions() {
        if (!canUse() || floatingSettingsDestination != null || floatingDisclosure != null) return
        when {
            !CapturePermissions.hasNotificationRuntimePermission(activity) -> {
                val askedBefore = floatingPreferences.storage.getBoolean("notification_prompt_seen", false)
                if (askedBefore && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    openFloatingSettings(FloatingSettingsDestination.NOTIFICATIONS,
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                } else {
                    floatingPreferences.storage.edit().putBoolean("notification_prompt_seen", true).apply()
                    floatingNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            !FloatingCaptureNotifications.canNotify(activity) -> {
                FloatingCaptureRuntime.message.value = "请允许通知及「悬浮记账结果」渠道；记账成功后可从通知查看、修改和撤销。"
                openFloatingSettings(FloatingSettingsDestination.NOTIFICATIONS,
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            !accessibilityEnabled() -> showFloatingDisclosure()
            else -> {
                FloatingCaptureRuntime.message.value = if (FloatingCaptureRuntime.connected.value) null else "授权已开启，正在等待系统连接悬浮记账服务。"
                FloatingCaptureRuntime.refresh(activity)
            }
        }
    }

    private fun accessibilityEnabled(): Boolean {
        val component = ComponentName(activity, FloatingCaptureAccessibilityService::class.java)
        return activity.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.let { ComponentName(it.packageName, it.name) == component } }
    }

    private fun showFloatingDisclosure() {
        if (floatingDisclosure != null) return
        floatingDisclosure = AlertDialog.Builder(activity)
            .setTitle(R.string.floating_capture_permission_title)
            .setMessage(R.string.floating_capture_permission_explanation)
            .setNegativeButton("暂不开启", null)
            .setPositiveButton("前往系统授权") { _, _ ->
                if (canUse()) openFloatingSettings(FloatingSettingsDestination.ACCESSIBILITY, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .create().also { dialog ->
                dialog.setOnDismissListener { if (floatingDisclosure === dialog) floatingDisclosure = null }
                dialog.show()
            }
    }

    private fun openFloatingSettings(destination: FloatingSettingsDestination, intent: Intent) {
        floatingSettingsDestination = destination
        try { floatingSettings.launch(intent) }
        catch (_: RuntimeException) {
            floatingSettingsDestination = null
            FloatingCaptureRuntime.message.value = "系统设置暂时无法打开，请从手机设置进入 DawnYew 的通知或无障碍权限。"
            FloatingCaptureRuntime.refresh(activity)
        }
    }

    private enum class FloatingSettingsDestination { NOTIFICATIONS, ACCESSIBILITY }

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
