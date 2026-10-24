package com.dawnyew.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.dawnyew.core.Money
import com.dawnyew.core.Sha256
import com.dawnyew.platform.files.ImageInputService
import com.dawnyew.platform.files.LocalDocumentAccess
import com.dawnyew.platform.ocr.AndroidOfflineOcrEngine
import com.dawnyew.platform.ocr.CaptureDisposition
import com.dawnyew.platform.ocr.PaymentScreenshotParser
import com.dawnyew.presentation.CaptureDraftUi
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class CaptureRecognition(val draft: CaptureDraftUi?, val explanation: String)

/** All image entry points use the same decoder, native inference and candidate rules. */
internal object CaptureRecognizer {
    suspend fun fromUri(context: Context, uri: Uri): CaptureRecognition = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val image = ImageInputService(context, LocalDocumentAccess(context)) { job.ensureActive() }.decode(uri)
        try { recognize(context, image.bitmap, image.fingerprint) } finally { image.bitmap.recycle() }
    }

    /** Takes ownership of the bitmap, including cancellation and failures. */
    suspend fun fromBitmap(context: Context, bitmap: Bitmap): CaptureRecognition = try {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            job.ensureActive()
            val digest = Sha256()
            val sink = object : OutputStream() {
                override fun write(value: Int) { digest.update(byteArrayOf(value.toByte())) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    job.ensureActive(); digest.update(bytes.copyOfRange(offset, offset + length))
                }
            }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, sink)) { "截屏编码失败，请重试" }
            recognize(context, bitmap, digest.finishHex())
        }
    } finally { bitmap.recycle() }

    private suspend fun recognize(context: Context, bitmap: Bitmap, fingerprint: String): CaptureRecognition {
        currentCoroutineContext().ensureActive()
        val result = AndroidOfflineOcrEngine.getInstance(context).recognize(bitmap)
        currentCoroutineContext().ensureActive()
        val value = PaymentScreenshotParser.parse(result)
        if (value.disposition == CaptureDisposition.UNSUPPORTED) return CaptureRecognition(null, value.messages.joinToString("\n"))
        val occurrence = if (value.localDate != null && value.localTime != null) "${value.localDate}T${value.localTime}" else ""
        return CaptureRecognition(CaptureDraftUi(
            amount = value.amountMinor?.let(Money::format).orEmpty(), occurrence = occurrence,
            note = value.merchant.orEmpty(), warnings = value.messages + listOfNotNull(
                if (occurrence.isEmpty()) "未识别出完整时间，暂填当前时间，请核对后修改。" else null,
                if (value.amountCandidates.size > 1) "候选金额：" + value.amountCandidates.joinToString { Money.format(it) } else null,
            ), engineVersion = result.engineVersion, fingerprint = fingerprint,
        ), "")
    }
}
