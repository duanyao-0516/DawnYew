package com.dawnyew.app.capture

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal object CapturePermissions {
    val mediaPermission: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasMedia(context: Context) = ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
    fun hasNotificationRuntimePermission(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun hasNotifications(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return notificationsAvailable(
            NotificationManagerCompat.from(context).areNotificationsEnabled(), hasNotificationRuntimePermission(context),
            manager.getNotificationChannel(CaptureNotifications.RESULT_CHANNEL)?.importance,
            manager.getNotificationChannel(CaptureNotifications.SERVICE_CHANNEL)?.importance,
        )
    }
    /** Missing channels will be created with visible defaults; user-blocked channels stay blocked. */
    internal fun notificationsAvailable(appEnabled: Boolean, runtimeGranted: Boolean, resultImportance: Int?, serviceImportance: Int?): Boolean =
        appEnabled && runtimeGranted && resultImportance != NotificationManager.IMPORTANCE_NONE && serviceImportance != NotificationManager.IMPORTANCE_NONE
    fun canObserve(context: Context) = hasMedia(context) && hasNotifications(context)
}
