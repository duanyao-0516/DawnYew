package com.dawnyew.app.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Connection status is process-local; an enabled preference is not proof of a live service. */
internal object FloatingCaptureRuntime {
    val connected = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun refresh(context: Context) {
        val preferences = FloatingCapturePreferences(context)
        val status = if (!preferences.enabled) null else message.value ?: when {
            !connected.value -> "请在系统无障碍设置中启用「DawnYew 悬浮记账」。"
            !FloatingCaptureNotifications.canNotify(context) -> "通知未开启，请在记账设置中开启通知，以便查看、修改或撤销记账结果。"
            else -> null
        }
        CaptureRuntime.settings.update { it.copy(
            floatingEnabled = preferences.enabled,
            floatingConnected = connected.value,
            floatingConfirmBeforeSave = preferences.confirmBeforeSave,
            floatingMessage = status,
        ) }
    }
}
