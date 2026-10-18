package com.dawnyew.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.dawnyew.platform.tools.AndroidNoiseMeter
import com.dawnyew.platform.tools.AndroidRulerMetrics
import com.dawnyew.presentation.NoiseUi
import com.dawnyew.presentation.RulerScale
import com.dawnyew.presentation.UtilityPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity-local hardware access; calibration is deliberately separate from financial preferences. */
internal class AndroidUtilityPlatform(
    private val activity: FragmentActivity,
    scope: CoroutineScope,
    private val canUseTools: () -> Boolean,
    private val currentPage: () -> String,
    private val requestMicrophone: () -> Unit,
) : UtilityPlatform {
    private val ruler = AndroidRulerMetrics(activity)
    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private var observingDisplay = false
    private var rulerRevision = SystemClock.elapsedRealtimeNanos()
    private var rulerConfiguration: String? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { refreshRuler() }
        override fun onDisplayChanged(displayId: Int) {
            if (activity.display?.displayId == displayId) refreshRuler()
        }
    }
    private val preferences = activity.getSharedPreferences("utility_noise_calibration", Context.MODE_PRIVATE)
    private val mutableRuler = MutableStateFlow(ruler.snapshot().let { rulerConfiguration = it.configurationId; RulerScale(it.pixelsPerCm, it.calibrated, rulerRevision) })
    override val rulerScale = mutableRuler.asStateFlow()
    private val mutableNoise = MutableStateFlow(NoiseUi())
    override val noise = mutableNoise.asStateFlow()
    private val originalBarsBehavior = WindowCompat.getInsetsController(activity.window, activity.window.decorView).systemBarsBehavior
    private var fullscreenRequested = false
    private var noiseSourceKey: String? = null
    private val meter = AndroidNoiseMeter(activity, scope, onSample = { sample ->
        if (canUseTools() && currentPage() == "噪音测量" && mutableNoise.value.running) {
            noiseSourceKey = sample.sourceKey
            val offset = if (preferences.getString("source", null) == sample.sourceKey)
                preferences.getString("offset", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -200.0..200.0 }
                else null
            mutableNoise.value = NoiseUi(true, sample.dbfs, sample.clipped, sample.sourceLabel, offset)
        }
    }, onError = { error ->
        mutableNoise.value = mutableNoise.value.copy(running = false, dbfs = null, clipped = false, error = error)
    })

    override fun setRulerFullscreen(enabled: Boolean) {
        fullscreenRequested = enabled
        updateWindow()
    }

    private fun updateWindow() {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreenRequested && currentPage() == "尺子" && canUseTools()) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.systemBarsBehavior = originalBarsBehavior
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun foreground() {
        if (!observingDisplay) {
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            observingDisplay = true
        }
        refreshRuler()
        updateWindow()
    }

    fun background() {
        if (observingDisplay) {
            displayManager?.unregisterDisplayListener(displayListener)
            observingDisplay = false
        }
        stopNoise()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = originalBarsBehavior
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun refreshRuler() {
        val value = ruler.snapshot()
        if (rulerConfiguration != value.configurationId) { rulerRevision++; rulerConfiguration = value.configurationId }
        mutableRuler.value = RulerScale(value.pixelsPerCm, value.calibrated, rulerRevision)
    }

    override fun saveRulerScale(pixelsPerCm: Float) { ruler.save(pixelsPerCm); refreshRuler() }
    override fun resetRulerScale() { ruler.reset(); refreshRuler() }

    override fun startNoise() {
        if (!canUseTools() || currentPage() != "噪音测量") return
        if (mutableNoise.value.running) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophone()
            return
        }
        noiseSourceKey = null
        mutableNoise.value = NoiseUi(running = true)
        meter.start()
    }

    /** A permission callback never starts capture or extends a ledger authorization session. */
    fun microphonePermissionResult(granted: Boolean) {
        mutableNoise.value = NoiseUi(error = if (granted) null else "未获得麦克风权限。可再次点击开始；若系统不再询问，请在系统应用权限中允许麦克风。")
    }

    override fun stopNoise() {
        meter.stop()
        mutableNoise.value = mutableNoise.value.copy(running = false, dbfs = null, clipped = false)
    }

    override fun calibrateNoise(referenceDb: Double) {
        val reading = mutableNoise.value
        val source = requireNotNull(noiseSourceKey) { "尚未取得麦克风读数" }
        val dbfs = requireNotNull(reading.dbfs) { "尚未取得有效读数" }
        require(canUseTools() && currentPage() == "噪音测量" && reading.running && !reading.clipped) { "请保持测量开启" }
        require(referenceDb.isFinite() && referenceDb in 20.0..120.0) { "参考读数超出范围" }
        val offset = referenceDb - dbfs
        require(offset.isFinite() && offset in -200.0..200.0) { "校准偏移超出范围" }
        preferences.edit { putString("source", source); putString("offset", offset.toString()) }
        mutableNoise.value = reading.copy(calibrationOffsetDb = offset)
    }

    override fun resetNoiseCalibration() {
        preferences.edit { clear() }
        mutableNoise.value = mutableNoise.value.copy(calibrationOffsetDb = null)
    }
}
