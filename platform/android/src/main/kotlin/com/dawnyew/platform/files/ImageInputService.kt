package com.dawnyew.platform.files

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.dawnyew.core.Sha256
import com.dawnyew.core.invalid
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class DecodedImage(val bitmap: Bitmap, val fingerprint: String)

class ImageInputService(
    private val context: Context,
    private val access: LocalDocumentAccess,
    private val checkCancelled: () -> Unit = {},
) {
    fun decode(uri: Uri): DecodedImage {
        val directory = File(context.noBackupFilesDir, "staging").apply { mkdirs() }
        val file = File(directory, "image-${UUID.randomUUID()}")
        try {
            var total = 0L
            val digest = Sha256()
            access.input(uri).use { source ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > 20L * 1024 * 1024) invalid("截图不能超过 20 MiB")
                        if (directory.usableSpace < 128L * 1024 * 1024 + count) invalid("暂存空间不足")
                        output.write(buffer, 0, count)
                        digest.update(buffer.copyOf(count))
                    }
                }
            }
            val bitmap = ScreenshotImageDecoder.decode(file, checkCancelled)
            try {
                return DecodedImage(bitmap, digest.finishHex())
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            }
        } finally {
            file.delete()
        }
    }
}
