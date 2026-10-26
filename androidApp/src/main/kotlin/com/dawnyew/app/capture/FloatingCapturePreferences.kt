package com.dawnyew.app.capture

import android.content.Context
import android.content.SharedPreferences

/** User choices only; screenshots, authorization tokens and ledger records are never stored here. */
internal class FloatingCapturePreferences(context: Context) {
    val storage: SharedPreferences = context.getSharedPreferences("floating_capture", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = storage.getBoolean("enabled", false)
        set(value) { storage.edit().putBoolean("enabled", value).apply() }
    var confirmBeforeSave: Boolean
        get() = storage.getBoolean("confirm_before_save", false)
        set(value) { storage.edit().putBoolean("confirm_before_save", value).apply() }
    var onRight: Boolean
        get() = storage.getBoolean("on_right", true)
        set(value) { storage.edit().putBoolean("on_right", value).apply() }
    var verticalFraction: Float
        get() = storage.getFloat("vertical_fraction", 0.5f).coerceIn(0f, 1f)
        set(value) { storage.edit().putFloat("vertical_fraction", value.coerceIn(0f, 1f)).apply() }
}
