package com.dawnyew.platform.files

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class CanonicalRecordStoreTest {
    @Test fun partialReaderOpenFailureClosesEarlierReadersAndPreservesPrimaryError() {
        val root = Files.createTempDirectory("dawnyew-merge-open-").toFile()
        try {
            var opened = 0; var closed = 0
            val openFailure = IOException("synthetic second open failure")
            val closeFailure = IOException("synthetic close failure")
            val store = CanonicalRecordStore(File(root, "runs"), {}, {}, { file ->
                if (++opened == 2) throw openFailure
                object : FilterInputStream(file.inputStream()) {
                    override fun close() { closed++; super.close(); throw closeFailure }
                }
            })
            repeat(256) { store.add(it.toString().padStart(4, '0'), "{}".toByteArray()) }
            val error = assertFailsWith<IOException> { store.finish() }
            assertSame(openFailure, error); assertEquals(1, closed)
            assertEquals(listOf(closeFailure), error.suppressed.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun writeFailureClosesEveryReaderEvenWhenClosingAlsoFails() {
        val root = Files.createTempDirectory("dawnyew-merge-close-").toFile()
        try {
            var merging = false; var closed = 0
            val writeFailure = IOException("synthetic disk full")
            val store = CanonicalRecordStore(File(root, "runs"), {}, { if (merging) throw writeFailure }, { file ->
                object : FilterInputStream(file.inputStream()) {
                    override fun close() { closed++; super.close(); throw IOException("synthetic close $closed") }
                }
            })
            repeat(256) { store.add(it.toString().padStart(4, '0'), "{}".toByteArray()) }
            merging = true
            val error = assertFailsWith<IOException> { store.finish() }
            assertSame(writeFailure, error); assertEquals(2, closed); assertEquals(2, error.suppressed.size)
        } finally { root.deleteRecursively() }
    }
}
