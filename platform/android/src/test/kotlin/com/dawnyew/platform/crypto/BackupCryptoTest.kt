package com.dawnyew.platform.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    private val directory = Files.createTempDirectory("dawnyew-crypto-test").toFile()
    private val crypto = BackupCrypto(directory)
    private fun password() = "DawnYew-test-口令🗝".toCharArray()
    private fun fixture(size: Int) = javaClass.getResourceAsStream("/crypto-v1/pattern-$size.dwybak")!!.use { it.readBytes() }
    private fun plaintext(size: Int) = ByteArray(size) { (it % 251).toByte() }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun decryptsIndependentLibsodiumGoldenVectorsAtAllFrameBoundaries() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 131072)) {
            val output = ByteArrayOutputStream()
            val pass = password()
            crypto.decrypt(fixture(size).inputStream(), output, pass)
            assertArrayEquals(plaintext(size), output.toByteArray())
            assertTrue(pass.all { it == '\u0000' })
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun randomEncryptionRoundTripsAndUsesFreshRandomMaterial() {
        val plain = plaintext(65537)
        val encrypted = (0..1).map {
            ByteArrayOutputStream().also { crypto.encrypt(plain.inputStream(), it, password()) }.toByteArray()
        }
        assertFalse(encrypted[0].contentEquals(encrypted[1]))
        for (bytes in encrypted) {
            val output = ByteArrayOutputStream()
            crypto.decrypt(bytes.inputStream(), output, password())
            assertArrayEquals(plain, output.toByteArray())
        }
    }

    private fun rejected(bytes: ByteArray, pass: CharArray = password()): Exception {
        val output = ByteArrayOutputStream()
        val error = try {
            crypto.decrypt(bytes.inputStream(), output, pass)
            fail("Invalid container accepted")
            error("unreachable")
        } catch (e: Exception) { e }
        assertEquals("No plaintext may escape before complete authentication", 0, output.size())
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(pass.all { it == '\u0000' })
        return error
    }

    @Test fun rejectsTamperingTruncationMissingFinalAndTrailingBytes() {
        val original = fixture(65536)
        val tampered = original.clone().also { it[90] = (it[90].toInt() xor 1).toByte() }
        rejected(tampered)
        rejected(original.copyOf(original.size - 1))
        rejected(original.copyOf(80 + 4 + 65553))
        rejected(original + byteArrayOf(0))
        rejected(original.copyOf(79))
    }

    @Test fun rejectsNonCanonicalFrameLengthsBeforeAllocation() {
        for (length in listOf(0L, 16L, 65554L, 0xffffffffL)) {
            val bytes = fixture(0).clone()
            for (i in 0..3) bytes[80 + i] = (length ushr (24 - 8 * i)).toByte()
            rejected(bytes)
        }
    }

    @Test fun rejectsAllUnsupportedHeaderProfiles() {
        for (offset in listOf(0, 8, 10, 12, 14, 16, 20, 28, 32, 34, 36)) {
            rejected(fixture(0).clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() })
        }
    }

    @Test fun wrongPasswordAndAuthenticationFailureShareError() {
        val wrong = rejected(fixture(1), "different-passphrase".toCharArray())
        val corrupt = rejected(fixture(1).clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertEquals(wrong.javaClass, corrupt.javaClass)
        assertEquals(wrong.message, corrupt.message)
    }

    @Test fun passwordUnicodeRulesRejectNulUnpairedSurrogatesAndScalarBounds() {
        for (pass in listOf("short", "a".repeat(129), "a".repeat(12) + '\u0000', "a".repeat(12) + '\ud800', "a".repeat(12) + '\udc00')) {
            val chars = pass.toCharArray()
            try { crypto.encrypt(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), chars); fail("Invalid password accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(chars.all { it == '\u0000' })
        }
        val unicode = "🗝".repeat(12).toCharArray()
        val bytes = ByteArrayOutputStream().also { crypto.encrypt(byteArrayOf(1).inputStream(), it, unicode) }.toByteArray()
        val out = ByteArrayOutputStream()
        crypto.decrypt(bytes.inputStream(), out, "🗝".repeat(12).toCharArray())
        assertArrayEquals(byteArrayOf(1), out.toByteArray())
    }

    @Test fun cancellationCleansStagingAndNeverPublishesPartialPlaintext() {
        var checks = 0
        val cancelled = BackupCrypto(directory, checkCancelled = { if (++checks >= 4) throw java.io.InterruptedIOException("cancelled") })
        val output = ByteArrayOutputStream()
        try { cancelled.decrypt(fixture(131072).inputStream(), output, password()); fail("Expected cancellation") }
        catch (_: java.io.InterruptedIOException) { }
        assertEquals(0, output.size())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun rejectsFrameReorderingDuplicationDeletionAndHeaderAuthenticationChanges() {
        val bytes = fixture(131072)
        val header = bytes.copyOfRange(0, 80)
        val first = bytes.copyOfRange(80, 80 + 4 + 65553)
        val second = bytes.copyOfRange(80 + 4 + 65553, 80 + 2 * (4 + 65553))
        val final = bytes.copyOfRange(80 + 2 * (4 + 65553), bytes.size)
        rejected(header + second + first + final)
        rejected(header + first + first + final)
        rejected(header + first + final)
        for (offset in listOf(40, 56, 79)) rejected(bytes.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() })
    }

    @Test fun rejectsAuthenticatedFramesWithNonCanonicalTagAndLengthCombination() {
        for ((length, final) in listOf(65536 to true, 1 to false, 0 to false)) {
            val header = fixture(0).copyOfRange(0, 80)
            val encodedPassword = "DawnYew-test-口令🗝".toByteArray(Charsets.UTF_8)
            val key = Sodium.deriveKey(encodedPassword, header.copyOfRange(40, 56))
            encodedPassword.fill(0)
            val streamHeader = ByteArray(24)
            val state = Sodium.initPush(key, streamHeader)
            key.fill(0)
            try {
                streamHeader.copyInto(header, 56)
                val aad = java.nio.ByteBuffer.allocate(92).put(header).putLong(0).putInt(length + 17).array()
                val cipher = Sodium.push(state, ByteArray(length), length, aad, final)
                rejected(header + java.nio.ByteBuffer.allocate(4).putInt(cipher.size).array() + cipher)
            } finally { Sodium.destroy(state) }
        }
    }

    @Test fun streamsOverTwentyMiBWithBoundedReadAndWriteBuffers() {
        val length = 21L * 1024 * 1024 + 17
        val expected = java.security.MessageDigest.getInstance("SHA-256")
        val input = object : java.io.InputStream() {
            var offset = 0L
            override fun read(): Int = if (offset == length) -1 else ((offset++ % 251).toInt()).also { expected.update(it.toByte()) }
            override fun read(bytes: ByteArray, start: Int, count: Int): Int {
                assertTrue(count <= 65536)
                if (offset == length) return -1
                val size = minOf(count.toLong(), length - offset).toInt()
                repeat(size) { bytes[start + it] = ((offset + it) % 251).toByte() }
                expected.update(bytes, start, size)
                offset += size
                return size
            }
        }
        val encrypted = File(directory, "large.dwybak")
        encrypted.outputStream().use { crypto.encrypt(input, it, password()) }
        val actual = java.security.MessageDigest.getInstance("SHA-256")
        var count = 0L
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { actual.update(value.toByte()); count++ }
            override fun write(bytes: ByteArray, start: Int, size: Int) {
                assertTrue(size <= 65536)
                actual.update(bytes, start, size)
                count += size
            }
        }
        encrypted.inputStream().use { crypto.decrypt(it, output, password()) }
        assertEquals(length, count)
        assertArrayEquals(expected.digest(), actual.digest())
        assertEquals(listOf("large.dwybak"), directory.listFiles()!!.map { it.name })
    }

    @Test fun nativeOpaqueStateRejectsStaleAndUnknownHandlesSafely() {
        val key = ByteArray(32)
        val state = Sodium.initPush(key, ByteArray(24))
        Sodium.destroy(state)
        Sodium.destroy(state)
        for (handle in listOf(state, Long.MAX_VALUE, -1L, 0L)) {
            try { Sodium.push(handle, ByteArray(0), 0, ByteArray(92), true); fail("Invalid handle accepted") }
            catch (_: IllegalStateException) { }
        }
    }

}
