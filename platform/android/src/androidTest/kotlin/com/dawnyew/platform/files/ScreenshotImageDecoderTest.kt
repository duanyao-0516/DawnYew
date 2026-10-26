package com.dawnyew.platform.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.DomainException
import com.dawnyew.core.ErrorCode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated images only; no personal screenshot is embedded in the test APK. */
@RunWith(AndroidJUnit4::class)
class ScreenshotImageDecoderTest {
    private fun withDirectory(test: (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "screenshot-decoder-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    private fun image(directory: File, format: Bitmap.CompressFormat, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(28, 78, 128))
        // The signature must determine the format, not the extension or the caller's MIME claim.
        val file = File(directory, "input-${UUID.randomUUID()}.bin")
        try { file.outputStream().use { check(bitmap.compress(format, 90, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    @Test fun jpegAndPngUseSoftwarePixelsAndDoNotUpscaleSmallImages() = withDirectory { directory ->
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            val file = image(directory, format, 320, 480)
            val bytes = file.readBytes()
            val bitmap = ScreenshotImageDecoder.decode(file)
            try {
                assertEquals(320, bitmap.width); assertEquals(480, bitmap.height)
                assertNotEquals(Bitmap.Config.HARDWARE, bitmap.config)
                // Pixel access fails for a hardware Bitmap and exercises the actual decoder output.
                assertTrue(android.graphics.Color.blue(bitmap.getPixel(10, 10)) > 100)
                assertArrayEquals(bytes, file.readBytes())
            } finally { bitmap.recycle() }
        }
    }

    @Test fun largePortraitAndLandscapeHaveTheSameProductionLongEdgeLimit() = withDirectory { directory ->
        for ((width, height) in listOf(1080 to 2400, 2400 to 1080)) {
            val file = image(directory, Bitmap.CompressFormat.PNG, width, height)
            val bitmap = ScreenshotImageDecoder.decode(file)
            try {
                assertEquals(if (width > height) 1920 else 864, bitmap.width)
                assertEquals(if (height > width) 1920 else 864, bitmap.height)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun formatAndInvalidImageBoundsAreRejected() = withDirectory { directory ->
        for (bytes in listOf(byteArrayOf(), "GIF89a".toByteArray(), byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))) {
            val file = File(directory, "invalid-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
            val failure = expectValidation { ScreenshotImageDecoder.decode(file).recycle() }
            assertEquals(ErrorCode.VALIDATION, failure.code)
            assertTrue("The caller owns the input file", file.exists())
        }
    }

    @Test fun excessiveSideOrPixelCountIsRejectedBeforeFullDecode() = withDirectory { directory ->
        for ((width, height) in listOf(12001 to 1, 6000 to 4001)) {
            val file = File(directory, "oversize-$width-$height.png")
            writeSolidPng(file, width, height)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            assertEquals("Fixture must expose real dimensions", width, bounds.outWidth)
            assertEquals(height, bounds.outHeight)
            var cancellationChecks = 0
            val failure = expectValidation {
                ScreenshotImageDecoder.decode(file) { cancellationChecks++ }.recycle()
            }
            assertTrue(failure.message.contains("图片尺寸"))
            assertEquals("Size rejection precedes allocation and the next checkpoint", 1, cancellationChecks)
        }
    }

    @Test fun cancellationPropagatesAtEachDecodeBoundaryWithoutTakingFileOwnership() = withDirectory { directory ->
        val file = image(directory, Bitmap.CompressFormat.PNG, 320, 480)
        val bytes = file.readBytes()
        // Checkpoints are before dimensions, before pixel decoding, and after decoding before transfer.
        for (cancelAt in 1..3) {
            var checks = 0
            val cancelled = CancellationException("decoder-test-$cancelAt")
            try {
                ScreenshotImageDecoder.decode(file) { if (++checks == cancelAt) throw cancelled }.recycle()
                fail("Cancellation must be propagated")
            } catch (failure: CancellationException) { assertSame(cancelled, failure) }
            assertEquals(cancelAt, checks)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    private fun expectValidation(block: () -> Unit): DomainException {
        try { block() } catch (failure: DomainException) { return failure }
        throw AssertionError("Expected image validation failure")
    }

    /** Stream a tiny compressed solid image without allocating an oversized Bitmap in the test. */
    private fun writeSolidPng(file: File, width: Int, height: Int) {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(width * 3 + 1) // PNG filter 0 + RGB pixels.
            repeat(height) { deflater.write(row) }
        }
        DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            val header = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            for ((type, payload) in listOf("IHDR" to header, "IDAT" to compressed.toByteArray(), "IEND" to byteArrayOf())) {
                val name = type.toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply { update(name); update(payload) }
                output.writeInt(payload.size); output.write(name); output.write(payload); output.writeInt(crc.value.toInt())
            }
        }
    }
}
