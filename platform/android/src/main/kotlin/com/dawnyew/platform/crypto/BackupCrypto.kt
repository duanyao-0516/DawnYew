package com.dawnyew.platform.crypto

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files

/**
 * DWYBAK v1. Call on an I/O worker, with stagingDirectory under the app's private
 * credential-encrypted noBackupFilesDir. Streams remain owned by the caller.
 * Password arrays are consumed and cleared, including on failure.
 *
 * Decryption authenticates the entire container in a private temporary file before
 * copying any plaintext to output. Output must still be an isolated JSON parser or
 * candidate file: business validation and atomic generation commit belong to the caller.
 * A failing output/cancellation during the final copy can leave a partial candidate.
 */
class BackupCrypto(
    private val stagingDirectory: File,
    private val checkCancelled: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("备份操作已取消")
    },
    private val ensureSpace: (Long) -> Unit = { nextBytes ->
        if (stagingDirectory.usableSpace < SPACE_RESERVE + nextBytes) throw IOException("备份暂存空间不足")
    },
) {
    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        var pass: ByteArray? = null
        var key: ByteArray? = null
        var state = 0L
        val plain = ByteArray(CHUNK)
        try {
            pass = encodePassword(password)
            checkCancelled()
            val salt = Sodium.randomBytes(16)
            key = Sodium.deriveKey(pass, salt)
            pass.fill(0)
            checkCancelled()
            val streamHeader = ByteArray(24)
            state = Sodium.initPush(key, streamHeader)
            key.fill(0)
            val header = newHeader(salt, streamHeader)
            output.write(header)
            var index = 0uL
            var total = 0L
            while (true) {
                checkCancelled()
                val length = fill(input, plain, CHUNK)
                total = Math.addExact(total, length.toLong())
                val final = length < CHUNK
                val cipherLength = length + OVERHEAD
                checkCancelled()
                val cipher = Sodium.push(state, plain, length, aad(header, index, cipherLength), final)
                try {
                    output.write(unsigned(cipherLength.toULong(), 4))
                    output.write(cipher)
                } finally { cipher.fill(0); plain.fill(0) }
                if (final) break
                if (index == ULong.MAX_VALUE) throw IOException("备份帧计数溢出")
                index++
            }
        } finally {
            plain.fill(0)
            pass?.fill(0)
            key?.fill(0)
            password.fill('\u0000')
            if (state != 0L) Sodium.destroy(state)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        var pass: ByteArray? = null
        var key: ByteArray? = null
        var state = 0L
        var staged: File? = null
        var failure: Throwable? = null
        val transfer = ByteArray(CHUNK)
        try {
            checkCancelled()
            val header = ByteArray(80)
            readExact(input, header)
            // No file-provided KDF parameter reaches native code.
            validateHeader(header)
            pass = encodePassword(password)
            key = Sodium.deriveKey(pass, header.copyOfRange(40, 56))
            pass.fill(0)
            checkCancelled()
            state = Sodium.initPull(key, header.copyOfRange(56, 80))
            key.fill(0)
            if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) throw IOException("无法创建私有备份暂存目录")
            ensureSpace(CHUNK.toLong())
            staged = Files.createTempFile(stagingDirectory.toPath(), "backup-", ".staging").toFile()
            staged.outputStream().use { sink ->
                var index = 0uL
                var total = 0L
                val prefix = ByteArray(4)
                while (true) {
                    checkCancelled()
                    readExact(input, prefix)
                    val cipherLength = number(prefix, 0, 4)
                    if (cipherLength !in OVERHEAD.toULong()..(CHUNK + OVERHEAD).toULong()) corrupt()
                    val length = cipherLength.toInt()
                    val cipher = ByteArray(length)
                    var decoded: ByteArray? = null
                    try {
                        readExact(input, cipher)
                        decoded = Sodium.pull(state, cipher, aad(header, index, length))
                        val tag = decoded[0].toInt() and 255
                        val plainLength = decoded.size - 1
                        if (plainLength != length - OVERHEAD) corrupt()
                        val final = tag == TAG_FINAL
                        if (final) {
                            if (plainLength >= CHUNK) corrupt()
                        } else if (tag != TAG_MESSAGE || plainLength != CHUNK) corrupt()
                        total = Math.addExact(total, plainLength.toLong())
                        ensureSpace(plainLength.toLong())
                        sink.write(decoded, 1, plainLength)
                        if (final) {
                            if (input.read() != -1) corrupt()
                            break
                        }
                        if (index == ULong.MAX_VALUE) corrupt()
                        index++
                    } finally { cipher.fill(0); decoded?.fill(0) }
                }
            }
            checkCancelled()
            staged.inputStream().use { source ->
                while (true) {
                    checkCancelled()
                    val length = source.read(transfer)
                    if (length < 0) break
                    output.write(transfer, 0, length)
                    transfer.fill(0)
                }
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            transfer.fill(0)
            pass?.fill(0)
            key?.fill(0)
            password.fill('\u0000')
            if (state != 0L) Sodium.destroy(state)
            staged?.let {
                try { Files.deleteIfExists(it.toPath()) }
                catch (cleanup: IOException) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    private fun fill(input: InputStream, bytes: ByteArray, count: Int): Int {
        var offset = 0
        while (offset < count) {
            checkCancelled()
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) break
            if (read == 0) {
                val one = input.read()
                if (one < 0) break
                bytes[offset++] = one.toByte()
            } else offset += read
        }
        return offset
    }

    private fun readExact(input: InputStream, bytes: ByteArray) {
        if (fill(input, bytes, bytes.size) != bytes.size) corrupt()
    }

    companion object {
        private const val CHUNK = 65536
        private const val OVERHEAD = 17
        private const val TAG_MESSAGE = 0
        private const val TAG_FINAL = 3
        private const val SPACE_RESERVE = 128L * 1024 * 1024
        internal const val CORRUPT = "口令不正确或备份已损坏"
        private fun corrupt(): Nothing = throw IOException(CORRUPT)
        private val MAGIC = byteArrayOf(0x44, 0x57, 0x59, 0x42, 0x41, 0x4b, 0x0d, 0x0a)
        private fun unsigned(value: ULong, size: Int) = ByteArray(size) { i -> (value shr ((size - 1 - i) * 8)).toByte() }
        private fun number(bytes: ByteArray, start: Int, size: Int): ULong {
            var result = 0uL
            repeat(size) { result = (result shl 8) or (bytes[start + it].toInt() and 255).toULong() }
            return result
        }
        private fun newHeader(salt: ByteArray, stream: ByteArray): ByteArray = ByteArray(80).also { h ->
            MAGIC.copyInto(h)
            for ((offset, size, value) in PROFILE) unsigned(value.toULong(), size).copyInto(h, offset)
            salt.copyInto(h, 40)
            stream.copyInto(h, 56)
        }
        private val PROFILE = listOf(
            Triple(8, 2, 1L), Triple(10, 2, 80L), Triple(12, 2, 1L), Triple(14, 2, 1L),
            Triple(16, 4, 3L), Triple(20, 8, 67108864L), Triple(28, 4, 65536L),
            Triple(32, 2, 16L), Triple(34, 2, 24L), Triple(36, 4, 0L),
        )
        private fun validateHeader(header: ByteArray) {
            if (!header.copyOfRange(0, 8).contentEquals(MAGIC)) corrupt()
            for ((offset, size, value) in PROFILE) if (number(header, offset, size) != value.toULong()) corrupt()
        }
        private fun aad(header: ByteArray, index: ULong, length: Int) = ByteArray(92).also {
            header.copyInto(it)
            unsigned(index, 8).copyInto(it, 80)
            unsigned(length.toULong(), 4).copyInto(it, 88)
        }
        // Encode scalars directly: no immutable String copy, trimming, or normalization.
        private fun encodePassword(password: CharArray): ByteArray {
            val bytes = ByteArray(512)
            var n = 0
            var scalars = 0
            var i = 0
            try {
                while (i < password.size) {
                    val first = password[i++].code
                    val scalar = when (first) {
                        in 0xd800..0xdbff -> {
                            require(i < password.size && password[i].code in 0xdc00..0xdfff) { "口令包含无效Unicode" }
                            0x10000 + ((first - 0xd800) shl 10) + (password[i++].code - 0xdc00)
                        }
                        in 0xdc00..0xdfff -> throw IllegalArgumentException("口令包含无效Unicode")
                        else -> first
                    }
                    require(scalar != 0) { "口令不能包含NUL" }
                    require(++scalars <= 128) { "口令须为12至128个Unicode字符" }
                    when {
                        scalar < 0x80 -> bytes[n++] = scalar.toByte()
                        scalar < 0x800 -> { bytes[n++] = (0xc0 or (scalar shr 6)).toByte(); bytes[n++] = (0x80 or (scalar and 63)).toByte() }
                        scalar < 0x10000 -> { bytes[n++] = (0xe0 or (scalar shr 12)).toByte(); bytes[n++] = (0x80 or ((scalar shr 6) and 63)).toByte(); bytes[n++] = (0x80 or (scalar and 63)).toByte() }
                        else -> { bytes[n++] = (0xf0 or (scalar shr 18)).toByte(); bytes[n++] = (0x80 or ((scalar shr 12) and 63)).toByte(); bytes[n++] = (0x80 or ((scalar shr 6) and 63)).toByte(); bytes[n++] = (0x80 or (scalar and 63)).toByte() }
                    }
                }
                require(scalars >= 12 && n <= 512) { "口令须为12至128个Unicode字符且不超过512字节" }
                return bytes.copyOf(n)
            } finally { bytes.fill(0) }
        }
    }
}

internal object Sodium {
    init { System.loadLibrary("dawnyew_crypto"); initialize() }
    private external fun initialize()
    external fun randomBytes(size: Int): ByteArray
    external fun deriveKey(password: ByteArray, salt: ByteArray): ByteArray
    external fun initPush(key: ByteArray, header: ByteArray): Long
    external fun initPull(key: ByteArray, header: ByteArray): Long
    external fun push(state: Long, plaintext: ByteArray, length: Int, aad: ByteArray, final: Boolean): ByteArray
    /** Returned array starts with the authenticated tag byte. */
    external fun pull(state: Long, ciphertext: ByteArray, aad: ByteArray): ByteArray
    external fun destroy(state: Long)
}
