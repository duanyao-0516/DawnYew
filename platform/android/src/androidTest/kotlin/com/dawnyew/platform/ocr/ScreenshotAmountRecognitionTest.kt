package com.dawnyew.platform.ocr

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.OcrResult
import com.dawnyew.platform.files.ScreenshotImageDecoder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

/** Real production inference only: no Activity, repository, account, permission flow or save command. */
@RunWith(AndroidJUnit4::class)
class ScreenshotAmountRecognitionTest {
    @Test fun recognizePrivateNumberedScreenshots() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val args = InstrumentationRegistry.getArguments()
        val requestedRun = args.getString("ocrRunId")
        org.junit.Assume.assumeTrue("Private corpus is opt-in; use scripts/test-ocr-amounts.py", requestedRun != null)
        val runId = requireNotNull(requestedRun)
        require(Regex("[a-f0-9]{32}").matches(runId))
        val root = File(context.filesDir, "ocr-amount-tests/$runId")
        val inputs = JSONObject(File(root, "inputs.json").readText())
        val samples = inputs.getJSONArray("samples")
        val repeats = inputs.getInt("repeats")
        require(repeats in 1..5 && samples.length() in 1..32)
        val results = JSONArray()
        val report = JSONObject().put("schemaVersion", 2)
            .put("scope", "real production image decode and offline OCR; no bookkeeping workflow")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("api", Build.VERSION.SDK_INT)
            .put("engineVersion", AndroidOfflineOcrEngine.ENGINE_VERSION)
            .put("parserVersion", PaymentScreenshotParser.VERSION).put("observations", results)
        val engine = AndroidOfflineOcrEngine.getInstance(context)
        try {
            repeat(repeats) { pass ->
                val order = (0 until samples.length()).let { if (pass % 2 == 0) it.toList() else it.reversed() }
                for (index in order) {
                    val sample = samples.getJSONObject(index)
                    val id = sample.getString("id")
                    require(Regex("case-[0-9]{3}").matches(id))
                    val file = File(root, "$id.image")
                    val observation = JSONObject().put("id", id).put("pass", pass + 1).put("coldRun", pass == 0 && index == 0)
                    results.put(observation)
                    try {
                        require(file.length() in 1..(20L * 1024 * 1024))
                        val bytes = file.readBytes()
                        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(digest == sample.getString("sha256")) { "Sample transport integrity mismatch" }
                        observation.put("sha256", digest)
                        val original = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, original)
                        observation.put("originalWidth", original.outWidth).put("originalHeight", original.outHeight)
                        val bitmap = ScreenshotImageDecoder.decode(file)
                        try {
                            observation.put("inputWidth", bitmap.width).put("inputHeight", bitmap.height)
                            val start = SystemClock.elapsedRealtime()
                            val raw = engine.recognize(bitmap)
                            observation.put("elapsedMs", SystemClock.elapsedRealtime() - start)
                            observation.put("blocks", blocks(raw))
                            // Candidate enumeration has no access to expected.json, filenames or oracle values.
                            observation.put("numericTokens", tokens(raw))
                            val candidate = PaymentScreenshotParser.parse(raw)
                            observation.put("businessParser", JSONObject()
                                .put("disposition", candidate.disposition.name)
                                .put("amountMinor", candidate.amountMinor ?: JSONObject.NULL)
                                .put("direction", candidate.direction?.name ?: JSONObject.NULL)
                                .put("amountCandidates", JSONArray(candidate.amountCandidates))
                                .put("messages", JSONArray(candidate.messages)))
                        } finally { bitmap.recycle() }
                    } catch (failure: Exception) {
                        observation.put("error", if (failure is OfflineOcrException) failure.failure.name else failure.javaClass.simpleName)
                    }
                    File(root, "report.json").writeText(report.toString(2))
                }
            }
        } finally { engine.release() }

        // Ground truth is read only after all inference and candidate extraction has completed.
        val expected = JSONObject(File(root, "expected.json").readText())
        var exact = 0; var magnitude = 0; var parserResolved = 0; var directionResolved = 0
        for (index in 0 until results.length()) {
            val observation = results.getJSONObject(index)
            val label = expected.getJSONObject(observation.getString("id"))
            val signed = label.getLong("signedMinor")
            // +12.34 and 12.34 have the same numeric value but different direction labels.
            val direction = label.getString("direction")
            require(direction in listOf("EXPENSE", "INCOME"))
            val candidates = observation.optJSONArray("numericTokens") ?: JSONArray()
            val tokens = (0 until candidates.length()).map { candidates.getJSONObject(it).getLong("signedMinor") }
            val exactMatch = signed in tokens
            val magnitudeMatch = tokens.any { abs(it) == abs(signed) }
            val parsed = observation.optJSONObject("businessParser")
            val parserMatch = parsed != null && !parsed.isNull("amountMinor") && parsed.getLong("amountMinor") == abs(signed)
            val directionMatch = parserMatch && parsed?.optString("direction") == direction
            if (exactMatch) exact++
            if (magnitudeMatch) magnitude++
            if (parserMatch) parserResolved++
            if (directionMatch) directionResolved++
            observation.put("evaluation", JSONObject().put("expectedSignedMinor", signed).put("expectedDirection", direction)
                .put("exactSignedToken", exactMatch).put("magnitudeToken", magnitudeMatch).put("businessParserResolved", parserMatch)
                .put("businessDirectionResolved", directionMatch))
        }
        report.put("summary", JSONObject().put("sampleCount", samples.length()).put("runs", results.length())
            .put("exactSignedTokenMatches", exact).put("magnitudeTokenMatches", magnitude).put("businessParserResolved", parserResolved)
            .put("businessDirectionResolved", directionResolved)
            .put("meaning", "Token recall is not final business amount selection or general accuracy"))
        File(root, "report.json").writeText(report.toString(2))
        assertEquals(samples.length() * repeats, exact, "Exact signed amount token mismatch; inspect the private report, not application logs")
    }

    private fun blocks(raw: OcrResult) = JSONArray().apply {
        raw.blocks.forEachIndexed { index, block -> put(JSONObject().put("index", index).put("text", block.text)
            .put("confidence", block.confidence.toDouble()).put("polygon", JSONArray().apply {
                block.polygon.forEach { point -> put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) }
            })) }
    }

    /** Diagnostic lexical candidates only. Never correct a decimal point, digit, sign or confidence. */
    private fun tokens(raw: OcrResult) = JSONArray().apply {
        val pattern = Regex("(?<![0-9A-Za-z.,])([+\\-−]?)[￥¥]?\\s*((?:[1-9]\\d{0,2}(?:,\\d{3})+|0|[1-9]\\d*)\\.\\d{2})(?![0-9.,])")
        raw.blocks.forEachIndexed { index, block ->
            pattern.findAll(block.text).forEach { match ->
                val parts = match.groupValues[2].replace(",", "").split('.')
                val whole = parts[0].toLongOrNull() ?: return@forEach
                if (whole > (Long.MAX_VALUE - 99) / 100) return@forEach
                val unsigned = whole * 100 + parts[1].toLong()
                val signed = if (match.groupValues[1] in listOf("-", "−")) -unsigned else unsigned
                put(JSONObject().put("raw", match.value).put("signedMinor", signed).put("blockIndex", index)
                    .put("confidence", block.confidence.toDouble()))
            }
        }
    }
}
