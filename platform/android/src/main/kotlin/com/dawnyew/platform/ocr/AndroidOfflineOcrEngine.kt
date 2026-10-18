package com.dawnyew.platform.ocr

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import com.dawnyew.core.OcrPoint
import com.dawnyew.core.OcrResult
import com.dawnyew.core.OcrTextBlock
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class OcrFailure { MODEL_INTEGRITY, INITIALIZATION, INVALID_IMAGE, INFERENCE }
class OfflineOcrException(val failure: OcrFailure) : IllegalStateException(when (failure) {
    OcrFailure.MODEL_INTEGRITY -> "离线识别资源校验失败，请重新安装完整安装包"
    OcrFailure.INITIALIZATION -> "离线识别引擎初始化失败"
    OcrFailure.INVALID_IMAGE -> "截图尺寸或文字布局不支持，请使用单笔消费截图"
    OcrFailure.INFERENCE -> "截图识别失败，请换一张清晰截图"
})

/**
 * The application singleton owns native sessions. Call only after the application lock has opened.
 * The caller owns the Bitmap and must retain it until recognize returns (including cancellation).
 * No image, recognized text, merchant or amount is logged or written to disk by this class.
 */
class AndroidOfflineOcrEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val gate = SingleFlightGate()
    private var engine: PaddleOCR? = null

    suspend fun recognize(bitmap: Bitmap): OcrResult = gate.recognize {
        if (bitmap.isRecycled) throw OfflineOcrException(OcrFailure.INVALID_IMAGE)
        try {
            OcrResourceLimits.checkBitmap(bitmap.width, bitmap.height)
        } catch (_: IllegalArgumentException) {
            throw OfflineOcrException(OcrFailure.INVALID_IMAGE)
        }
        // Session initialization cannot be interrupted. Retain the completed sessions even if
        // cancellation arrives during load, then check cancellation before starting inference.
        val activeEngine = engine ?: withContext(NonCancellable) { createEngine() }.also { engine = it }
        currentCoroutineContext().ensureActive()
        try {
            val result = activeEngine.recognize(bitmap)
            OcrResult(
                blocks = result.results.map { block ->
                    OcrTextBlock(block.text, block.confidence, block.box.points.map { OcrPoint(it.x, it.y) })
                },
                engineVersion = ENGINE_VERSION,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            throw OfflineOcrException(OcrFailure.INVALID_IMAGE)
        } catch (_: com.paddle.ocr.model.OCRError.InvalidImage) {
            throw OfflineOcrException(OcrFailure.INVALID_IMAGE)
        } catch (_: Exception) {
            throw OfflineOcrException(OcrFailure.INFERENCE)
        }
    }

    /** Waits for any native call before closing; the next explicit recognition can reload. */
    suspend fun release() = gate.release {
        val previous = engine
        engine = null
        previous?.release()
    }

    private suspend fun createEngine(): PaddleOCR {
        verifyBundledAssets()
        return try {
            // Defense in depth; the shipped ORT is also compiled with --no_telemetry.
            Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
            System.loadLibrary("opencv_java4")
            PaddleOCR.create(
                context = appContext,
                config = PaddleOCRConfig(
                    detImgMode = "BGR", detLimitSideLen = 960, detLimitType = "max",
                    detMaxSideLimit = 1920, detMaxCandidates = OcrResourceLimits.MAX_CONTOURS,
                    recScoreThresh = 0.0f, recBatchSize = 1,
                ),
                engineConfig = EngineConfig(numThreads = 2),
                detModelAssetPath = "ocr/det/inference.onnx",
                recModelAssetPath = "ocr/rec/inference.onnx",
                recConfigAssetPath = "ocr/rec/inference.yml",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw OfflineOcrException(OcrFailure.INITIALIZATION)
        } catch (_: LinkageError) {
            throw OfflineOcrException(OcrFailure.INITIALIZATION)
        }
    }

    private fun verifyBundledAssets() {
        for ((path, expected) in ASSET_DIGESTS) {
            val actual = try {
                val sha = MessageDigest.getInstance("SHA-256")
                appContext.assets.open(path).use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        sha.update(buffer, 0, size)
                    }
                }
                sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            } catch (_: Exception) {
                throw OfflineOcrException(OcrFailure.MODEL_INTEGRITY)
            }
            if (actual != expected) throw OfflineOcrException(OcrFailure.MODEL_INTEGRITY)
        }
    }

    companion object {
        const val ENGINE_VERSION = "ppocr-v5-mobile/ort-1.31.0-cpu-no-telemetry/bgr-1"
        @Volatile private var instance: AndroidOfflineOcrEngine? = null
        fun getInstance(context: Context): AndroidOfflineOcrEngine = instance ?: synchronized(this) {
            instance ?: AndroidOfflineOcrEngine(context).also { instance = it }
        }

        private val ASSET_DIGESTS = mapOf(
            "ocr/det/inference.onnx" to "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
            "ocr/det/inference.yml" to "98069072e1b6b37d727fd9d9f11725faa46d6ea0de012f2ed26caea011c37699",
            "ocr/rec/inference.onnx" to "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092",
            "ocr/rec/inference.yml" to "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e",
        )
    }
}
