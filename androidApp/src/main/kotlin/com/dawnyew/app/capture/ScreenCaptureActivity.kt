package com.dawnyew.app.capture

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import kotlinx.coroutines.launch

/** Transparent consent host: never renders a ledger, screenshot or cached authorization. */
class ScreenCaptureActivity : ComponentActivity() {
    private var requestId = ""
    private var captureStarted = false
    private var leavingForReview = false
    private var foreground = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) { finish(); return@registerForActivityResult }
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) { finish(); return@registerForActivityResult }
        captureStarted = true
        try {
            ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureService.EXTRA_REQUEST, requestId)
                .putExtra(ScreenCaptureService.EXTRA_GRANT, result.data))
        } catch (_: RuntimeException) { showFailure("系统未允许开始截屏，请返回原页面重试。") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString("capture-request") ?: UUID.randomUUID().toString()
        captureStarted = savedInstanceState?.getBoolean("capture-started") ?: false
        lifecycleScope.launch { ScreenCaptureSession.state.collect { consumeCompletion() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { stopCapture(); finish() }
        })
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
            permission.launch(intent)
        } else if (captureStarted && requestId !in ScreenCaptureSession.state.value) {
            showFailure("截屏已中断，请重新点击截屏记账。")
        }
    }
    override fun onResume() { super.onResume(); foreground = true; consumeCompletion() }
    override fun onPause() { foreground = false; super.onPause() }
    override fun onStop() {
        if (captureStarted && !isChangingConfigurations && !leavingForReview) stopCapture()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("capture-request", requestId)
        outState.putBoolean("capture-started", captureStarted)
        super.onSaveInstanceState(outState)
    }
    private fun stopCapture() {
        if (captureStarted) startService(Intent(this, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_STOP).putExtra(ScreenCaptureService.EXTRA_REQUEST, requestId))
    }
    private fun consumeCompletion() {
        val result = ScreenCaptureSession.state.value[requestId] ?: return
        if (!foreground || leavingForReview || result.requestId != requestId || !result.complete) return
        leavingForReview = true
        if (result.token != null) {
            startActivity(CaptureRuntime.reviewIntent(this, result.token))
            finish()
        } else showFailure(result.error ?: "截屏已取消")
    }
    private fun showFailure(message: String) {
        captureStarted = false
        AlertDialog.Builder(this).setTitle("截屏记账").setMessage(message)
            .setPositiveButton("知道了") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}
