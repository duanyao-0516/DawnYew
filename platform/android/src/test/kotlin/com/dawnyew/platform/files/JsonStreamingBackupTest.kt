package com.dawnyew.platform.files

import com.dawnyew.core.*
import com.dawnyew.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class JsonStreamingBackupTest {
    private val env = object : DomainEnvironment {
        override fun id() = UUID.randomUUID().toString()
        override fun localNow() = "2026-10-18T12:00:00"
        override fun utcNow() = "2026-10-18T04:00:00Z"
        override fun normalize(value: String) = value
    }
    private fun prototype(): BackupDocument {
        val initial = DomainEngine(env).initialState()
        val entry = Entry(env.id(), initial.ledgers.first().id, EntryKind.EXPENSE, 100,
            initial.categories.first { it.direction == Direction.EXPENSE }.id, env.localNow(), "测".repeat(500),
            createdAt = env.utcNow(), updatedAt = env.utcNow(), creationOperationId = env.id())
        return BackupProtocol.create(initial.copy(entries = listOf(entry)), env.utcNow())
    }
    private suspend fun generate(document: BackupDocument, count: Int, file: File) {
        val counts = document.counts + ("entries" to count.toLong())
        file.outputStream().use { output ->
            JsonStreamCodec.writeStreaming(document.datasetId, document.createdAt, counts, output) { type, emit ->
                if (type == "entries") repeat(count) { index ->
                    val row = document.records.getValue(type).single().toMutableMap()
                    row["id"] = JsonPrimitive("00000000-0000-4000-8000-${(100000 + index).toString().padStart(12, '0')}")
                    row["creationOperationId"] = JsonPrimitive("00000000-0000-4000-8000-${(200000 + index).toString().padStart(12, '0')}")
                    emit(JsonObject(row))
                } else document.records.getValue(type).forEach { emit(it) }
            }
        }
    }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        file.inputStream().use { input -> while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Test fun moreThanTwentyMiBAndTenThousandRecordsRoundtripWithBoundedChunks() = runBlocking {
        val root = Files.createTempDirectory("dawnyew-json-large-").toFile()
        try {
            val input = File(root, "input.json"); val output = File(root, "output.json"); val staging = File(root, "staging")
            generate(prototype(), 12_001, input)
            assertTrue(input.length() > 20L * 1024 * 1024, "fixture must exceed old limit")
            val prepared = input.inputStream().use { JsonStreamCodec.prepare(it, staging) }
            prepared.use {
                var entries = 0L
                output.outputStream().use { out -> JsonStreamCodec.writeStreaming(prepared.metadata.datasetId, prepared.metadata.createdAt, prepared.metadata.counts, out) { type, emit ->
                    prepared.visit(type) { chunk ->
                        assertTrue(chunk.size in 1..128)
                        BackupProtocol.decodeRecords(type, chunk) // Real per-record schema and domain validation.
                        if (type == "entries") entries += chunk.size
                        chunk.forEach { emit(it) }
                    }
                } }
                assertEquals(12_001L, entries); assertEquals(digest(input), digest(output))
            }
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun arbitraryEntityAndRecordOrderStillUsesCanonicalDigest() = runBlocking {
        val root = Files.createTempDirectory("dawnyew-json-order-").toFile()
        try {
            val document = prototype(); val original = ByteArrayOutputStream().also { JsonStreamCodec.write(document, it) }.toString("UTF-8")
            val tree = BackupProtocol.json.parseToJsonElement(original).jsonObject
            val entities = tree.getValue("entities").jsonObject
            val shuffled = JsonObject(tree.toMutableMap().apply {
                put("entities", JsonObject(entities.entries.reversed().associate { (type, rows) -> type to JsonArray(rows.jsonArray.reversed()) }))
            })
            JsonStreamCodec.prepare(shuffled.toString().byteInputStream(), root).use { assertEquals(document.sha256, it.metadata.sha256) }
        } finally { root.deleteRecursively() }
    }
    @Test fun wrongDigestDuplicateIdAndInsufficientSpaceCleanAllScratch() {
        val root = Files.createTempDirectory("dawnyew-json-invalid-").toFile()
        try {
            val document = prototype(); val output = ByteArrayOutputStream(); JsonStreamCodec.write(document, output)
            val raw = output.toString("UTF-8")
            assertFails { JsonStreamCodec.prepare(raw.replace(document.sha256, "0".repeat(64)).byteInputStream(), root) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
            val tree = BackupProtocol.json.parseToJsonElement(raw).jsonObject
            val entities = tree.getValue("entities").jsonObject
            val duplicated = JsonObject(tree.toMutableMap().apply { put("entities", JsonObject(entities.toMutableMap().apply {
                put("entries", JsonArray(entities.getValue("entries").jsonArray + entities.getValue("entries").jsonArray))
            })) }).toString().replace("\"entries\":\"1\"", "\"entries\":\"2\"")
            assertFails { JsonStreamCodec.prepare(duplicated.byteInputStream(), root) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertFailsWith<IOException> { JsonStreamCodec.prepare(raw.byteInputStream(), root, ensureSpace = { throw IOException("synthetic disk full") }) }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationDuringTokensAndMergeLeavesTargetAndScratchUntouched() = runBlocking {
        val root = Files.createTempDirectory("dawnyew-json-cancel-").toFile()
        try {
            val source = File(root, "source.json"); generate(prototype(), 300, source)
            val target = File(root, "active-marker").apply { writeText("existing-dataset") }
            val staging = File(root, "staging")
            var checks = 0
            assertFailsWith<CancellationException> { source.inputStream().use { JsonStreamCodec.prepare(it, staging, checkCancelled = { if (++checks == 500) throw CancellationException("test token cancellation") }) } }
            assertTrue(staging.listFiles().orEmpty().isEmpty()); assertEquals("existing-dataset", target.readText())
            // Cancellation after all input bytes have been read interrupts the independent disk sorting/hash pass.
            var eof = false
            val input = object : FilterInputStream(source.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it < 0) eof = true }
            }
            assertFailsWith<CancellationException> { input.use { JsonStreamCodec.prepare(it, staging, checkCancelled = { if (eof) throw CancellationException("test merge cancellation") }) } }
            assertTrue(staging.listFiles().orEmpty().isEmpty()); assertEquals("existing-dataset", target.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun unknownAndLongPropertyNamesAreRejectedBeforeTheirValue() {
        val root = Files.createTempDirectory("dawnyew-json-properties-").toFile()
        try {
            listOf("unknown", "x".repeat(129)).forEach { name ->
                val bytes = ("{\"entities\":{\"entries\":[{\"" + name + "\":\"" + "x".repeat(200000) + "\"}]}}").toByteArray()
                var consumed = 0
                val input = object : FilterInputStream(bytes.inputStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) consumed += it }
                }
                assertFails { JsonStreamCodec.prepare(input, root) }
                assertTrue(consumed < bytes.size / 2, "must reject property before reading its large value")
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
