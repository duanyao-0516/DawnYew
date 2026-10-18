package com.dawnyew.platform.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class NoiseSample(val dbfs: Double?, val clipped: Boolean, val sourceKey: String, val sourceLabel: String)

/** Foreground-only, memory-only PCM capture. The Activity owns permission and lifecycle policy. */
class AndroidNoiseMeter(
    context: Context,
    private val scope: CoroutineScope,
    private val onSample: (NoiseSample) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val captureMutex = Mutex()
    private var generation = 0L
    private var active: RecordingRun? = null

    /** Repeated starts are idempotent; a new start waits for the preceding recorder to release. */
    fun start() {
        val run = synchronized(stateLock) {
            if (active != null) return
            RecordingRun(++generation).also { active = it }
        }
        val job = scope.launch(Dispatchers.IO) {
            val failure = try {
                captureMutex.withLock { record(run) }
                null
            } catch (_: CancellationException) {
                null
            } catch (_: SecurityException) {
                "麦克风权限不可用，请授权后重新开始"
            } catch (error: MeasurementFailure) {
                error.message
            } catch (_: Exception) {
                "麦克风测量未能继续，请检查麦克风是否被占用后重试"
            } finally {
                synchronized(stateLock) { if (active === run) active = null }
            }
            if (failure != null) withContext(Dispatchers.Main.immediate) {
                if (synchronized(stateLock) { generation == run.generation && !run.cancelled }) onError(failure)
            }
        }
        synchronized(run.recorderLock) { run.job = job; if (run.cancelled) job.cancel() }
        job.invokeOnCompletion { synchronized(stateLock) { if (active === run) active = null } }
    }

    /** Invalidates queued callbacks immediately and stops a blocking read before cancelling its job. */
    fun stop() {
        val run = synchronized(stateLock) {
            generation++
            active.also { active = null; it?.cancelled = true }
        } ?: return
        stopRecorder(run)
        synchronized(run.recorderLock) { run.job?.cancel() }
    }

    private suspend fun record(run: RecordingRun) {
        currentCoroutineContext().ensureActive()
        if (run.cancelled) throw CancellationException()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
        val manager = audioManager ?: throw MeasurementFailure("此设备没有可用的音频服务")
        val selected = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            .sortedBy { it.id }.firstOrNull() ?: throw MeasurementFailure("没有找到内置麦克风")
        val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw MeasurementFailure("此设备不支持所需的麦克风采样格式")
        val recorder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, WINDOW_SAMPLES * 2))
            .setPrivacySensitive(true)
            .build()
        val effects = mutableListOf<AudioEffect>()
        val audioSessionId = recorder.audioSessionId
        var routingRegistered = false
        var configurationRegistered = false
        val routeListener = AudioRouting.OnRoutingChangedListener {
            val changed = synchronized(run.recorderLock) {
                val expected = run.expectedRoute.get()
                !run.closed && expected != null && deviceIdentity(recorder.routedDevice) != expected
            }
            if (changed) {
                failRun(run, "麦克风输入已变化，测量已停止，请重新开始并核对校准")
            }
        }
        val configurationListener = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (run.cancelled || run.closed) return
                val configuration = configs.firstOrNull { it.clientAudioSessionId == audioSessionId } ?: return
                when {
                    configuration.isClientSilenced -> failRun(run, "系统已暂停麦克风输入，请检查麦克风开关或其他录音应用")
                    run.expectedConfiguration.get()?.let { it != configurationIdentity(configuration) } == true ->
                        failRun(run, "麦克风处理方式已变化，测量已停止，请重新开始并核对校准")
                }
            }
        }
        try {
            synchronized(run.recorderLock) {
                if (run.cancelled) throw CancellationException()
                run.recorder = recorder
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw MeasurementFailure("无法初始化内置麦克风")
            if (!recorder.setPreferredDevice(selected)) throw MeasurementFailure("无法选择内置麦克风")
            val processing = listOf(
                disableEffect("agc", effects) { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioSessionId) else null },
                disableEffect("ns", effects) { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioSessionId) else null },
                disableEffect("aec", effects) { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioSessionId) else null },
            ).joinToString(",")
            recorder.addOnRoutingChangedListener(routeListener, mainHandler)
            routingRegistered = true
            recorder.registerAudioRecordingCallback(context.mainExecutor, configurationListener)
            configurationRegistered = true
            synchronized(run.recorderLock) {
                if (run.cancelled) throw CancellationException()
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MeasurementFailure("麦克风未能开始采样")
            }

            val routeDeadline = SystemClock.elapsedRealtime() + 1_500
            var sourceKey: String? = null
            var microphoneIdentity: String? = null
            val label = if (source == MediaRecorder.AudioSource.UNPROCESSED) "内置麦克风 · 原始输入"
                else "内置麦克风 · 语音识别输入（可能含系统处理）"
            val accumulator = PcmLevelAccumulator(WINDOW_SAMPLES)
            val buffer = ShortArray(READ_SAMPLES)
            try {
                while (true) {
                    checkActive(run)
                    if (manager.isMicrophoneMute) throw MeasurementFailure("系统麦克风已关闭，请打开后重新开始")
                    verifyBoundRoute(run, recorder)
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    checkActive(run)
                    if (read < 0) throw MeasurementFailure("麦克风采样已中断，请重新开始")
                    if (read == 0) { delay(10); continue }
                    val routed = recorder.routedDevice
                    val configuration = recorder.activeRecordingConfiguration
                    if (configuration?.isClientSilenced == true) throw MeasurementFailure("系统已暂停麦克风输入，请检查麦克风开关或其他录音应用")
                    if (sourceKey == null) {
                        // AudioRecord may initially report no route/configuration. Never publish those startup frames.
                        if (routed == null || configuration == null) {
                            if (SystemClock.elapsedRealtime() >= routeDeadline) throw MeasurementFailure("未能确认麦克风输入，请重新开始")
                            continue
                        }
                        if (routed.type != AudioDeviceInfo.TYPE_BUILTIN_MIC || routed.id != selected.id) {
                            throw MeasurementFailure("系统未使用所选内置麦克风，请断开外接输入后重试")
                        }
                        if (configuration.clientAudioSource != source) throw MeasurementFailure("麦克风采样源不匹配，请重新开始")
                        val route = deviceIdentity(routed)
                        val inputConfiguration = configurationIdentity(configuration)
                        microphoneIdentity = activeMicrophonesIdentity(recorder)
                        run.expectedRoute.set(route)
                        run.expectedConfiguration.set(inputConfiguration)
                        sourceKey = listOf("pcm16-16k-v1", Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
                            "source=$source", route, microphoneIdentity, processing, inputConfiguration).joinToString("|")
                        continue
                    }
                    verifyBoundRoute(run, recorder)
                    if (configuration == null || configurationIdentity(configuration) != run.expectedConfiguration.get() ||
                        activeMicrophonesIdentity(recorder) != microphoneIdentity) {
                        throw MeasurementFailure("麦克风输入或处理方式已变化，测量已停止，请重新开始并核对校准")
                    }
                    for (level in accumulator.append(buffer, read)) {
                        val sample = NoiseSample(level.dbfs, level.clipped, sourceKey, label)
                        withContext(Dispatchers.Main.immediate) {
                            if (synchronized(stateLock) { active === run && !run.cancelled } && run.failure.get() == null) onSample(sample)
                        }
                    }
                }
            } finally { buffer.fill(0) }
        } finally {
            run.closed = true
            run.expectedRoute.set(null)
            run.expectedConfiguration.set(null)
            if (routingRegistered) runCatching { recorder.removeOnRoutingChangedListener(routeListener) }
            if (configurationRegistered) runCatching { recorder.unregisterAudioRecordingCallback(configurationListener) }
            stopRecorder(run)
            effects.forEach { runCatching { it.release() } }
            synchronized(run.recorderLock) {
                run.recorder = null
                runCatching { recorder.release() }
            }
        }
    }

    private suspend fun checkActive(run: RecordingRun) {
        currentCoroutineContext().ensureActive()
        if (run.cancelled) throw CancellationException()
        run.failure.get()?.let { throw MeasurementFailure(it) }
    }

    private fun verifyBoundRoute(run: RecordingRun, recorder: AudioRecord) {
        run.expectedRoute.get()?.let { expected ->
            if (deviceIdentity(recorder.routedDevice) != expected) throw MeasurementFailure("麦克风输入已变化，测量已停止，请重新开始并核对校准")
        }
    }

    private fun failRun(run: RecordingRun, message: String) {
        if (!run.cancelled && !run.closed && run.failure.compareAndSet(null, message)) stopRecorder(run)
    }

    private fun stopRecorder(run: RecordingRun) = synchronized(run.recorderLock) {
        run.recorder?.let { recorder -> runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } }
        Unit
    }

    private fun disableEffect(name: String, effects: MutableList<AudioEffect>, create: () -> AudioEffect?): String = try {
        val effect = create()
        if (effect == null) "$name=unavailable" else {
            effects += effect
            if (effect.hasControl()) effect.enabled = false
            "$name=" + if (effect.enabled) "active" else "disabled"
        }
    } catch (_: Exception) { "$name=unknown" }

    private fun deviceIdentity(device: AudioDeviceInfo?): String? = device?.let {
        "device=${it.id},type=${it.type},address=${it.address},product=${it.productName}"
    }

    private fun activeMicrophonesIdentity(recorder: AudioRecord): String = runCatching {
        recorder.activeMicrophones.map { "${it.id}:${it.type}:${it.address}:${it.description}:${it.group}:${it.indexInTheGroup}:${it.channelMapping}" }
            .sorted().joinToString(";")
    }.getOrDefault("microphone-details-unavailable")

    private fun configurationIdentity(configuration: AudioRecordingConfiguration): String {
        val format = configuration.format
        val client = configuration.clientFormat
        val effects = (configuration.effects + configuration.clientEffects).map { "${it.type}:${it.uuid}" }.sorted()
        return "source=${configuration.clientAudioSource};deviceFormat=${format.sampleRate},${format.channelMask},${format.encoding};" +
            "clientFormat=${client.sampleRate},${client.channelMask},${client.encoding};effects=$effects"
    }

    private class RecordingRun(val generation: Long) {
        val recorderLock = Any()
        @Volatile var cancelled = false
        @Volatile var closed = false
        var recorder: AudioRecord? = null
        var job: Job? = null
        val failure = AtomicReference<String?>(null)
        val expectedRoute = AtomicReference<String?>(null)
        val expectedConfiguration = AtomicReference<String?>(null)
    }

    private class MeasurementFailure(message: String) : Exception(message)

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SAMPLES = 4_000
        const val READ_SAMPLES = 1_024
    }
}
