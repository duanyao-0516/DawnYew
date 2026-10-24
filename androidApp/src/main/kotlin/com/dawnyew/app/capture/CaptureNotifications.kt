package com.dawnyew.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dawnyew.app.MainActivity
import com.dawnyew.app.R

internal object CaptureNotifications {
    const val WATCHER_ID = 1901
    const val PROJECTION_ID = 1902
    private const val RESULT_ID = 1903
    internal const val SERVICE_CHANNEL = "screenshot-services"
    internal const val RESULT_CHANNEL = "screenshot-review"

    private fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "截图记账运行状态", NotificationManager.IMPORTANCE_LOW))
        it.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "截图待核对", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_capture_shortcut).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)

    fun watcherForeground(context: Context): Notification {
        manager(context)
        val stop = PendingIntent.getService(context, WATCHER_ID,
            Intent(context, ScreenshotWatchService::class.java).setAction(ScreenshotWatchService.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, WATCHER_ID, Intent(context, MainActivity::class.java)
            .setAction(AndroidCaptureController.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return base(context, SERVICE_CHANNEL).setContentTitle("自动记账已开启")
            .setContentText("仅识别新系统截图；点此管理，或停止监听")
            .setContentIntent(open).setOngoing(true).addAction(0, "停止", stop).build()
    }
    fun projectionForeground(context: Context): Notification {
        manager(context)
        val stop = PendingIntent.getService(context, PROJECTION_ID,
            Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return base(context, SERVICE_CHANNEL).setContentTitle("正在截屏记账")
            .setContentText("仅截取一次，完成后立即停止").setOngoing(true).addAction(0, "取消", stop).build()
    }
    fun showResult(context: Context, token: String) {
        val notifications = manager(context)
        if (!CapturePermissions.hasNotifications(context)) return
        val intent = CaptureRuntime.reviewIntent(context, token).setData(android.net.Uri.parse("dawnyew-capture://review/$token"))
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(token, RESULT_ID, base(context, RESULT_CHANNEL)
            .setContentTitle("截图已准备好")
            .setContentText("点击在本机识别、核对账单，确认后保存")
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build())
    }
    fun dismissResult(context: Context, token: String) { context.getSystemService(NotificationManager::class.java).cancel(token, RESULT_ID) }
}
