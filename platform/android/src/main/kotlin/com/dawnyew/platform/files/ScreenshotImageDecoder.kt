package com.dawnyew.platform.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.dawnyew.core.invalid
import java.io.File
import kotlin.math.max

/**
 * Decodes an already bounded, caller-owned local file using the screenshot input policy.
 * URI/provider checks, byte limits, fingerprints and temporary-file cleanup stay with the caller.
 * A successful return transfers Bitmap ownership; cancellation retains and recycles it here.
 */
internal object ScreenshotImageDecoder {
    fun decode(file: File, checkCancelled: () -> Unit = {}): Bitmap {
        val magic = ByteArray(8)
        val count = file.inputStream().use { it.read(magic) }
        val png = count == 8 && magic.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val jpeg = count >= 3 && magic[0] == 0xff.toByte() && magic[1] == 0xd8.toByte() && magic[2] == 0xff.toByte()
        if (!png && !jpeg) invalid("仅支持 PNG 或 JPEG 截图")
        checkCancelled()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 24_000_000 ||
            max(bounds.outWidth, bounds.outHeight) > 12000) {
            invalid("图片尺寸无效或超过 2400 万像素 / 12000 像素长边")
        }
        checkCancelled()

        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val ratio = (1920.0 / max(info.size.width, info.size.height)).coerceAtMost(1.0)
            decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }
        try {
            checkCancelled()
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}
