@file:OptIn(kotlin.ExperimentalStdlibApi::class)

package com.dawnyew.storage

import com.dawnyew.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.text.Normalizer
import java.util.UUID
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlin.test.*

class RoomLedgerRepositoryTest {
    private fun id(label: String) = UUID.nameUUIDFromBytes(label.encodeToByteArray()).toString()
    private fun hash(label: String) = Sha256.hex(label.encodeToByteArray())
    private val env = object : DomainEnvironment {
        override fun id() = UUID.randomUUID().toString()
        override fun localNow() = "2026-10-18T12:00:00"
        override fun utcNow() = "2026-10-18T04:00:00Z"
        override fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
    }
    private suspend fun fixture(block: suspend (String, RoomLedgerRepository) -> Unit) {
        val dir = Files.createTempDirectory("dawnyew-storage-").toFile()
        val path = dir.resolve("ledger.db").absolutePath
        val repository = RoomLedgerRepository(createDatabase(path), env)
        try { repository.initialize(); block(path, repository) }
        finally { repository.close(); dir.deleteRecursively() }
    }
    private suspend fun expense(repository: RoomLedgerRepository, amount: Long = 10000): String {
        val state = repository.snapshot()
        val category = state.categories.first { it.direction == Direction.EXPENSE }
        return repository.execute(Command.SaveEntry(ledgerId = category.ledgerId, kind = EntryKind.EXPENSE,
            amountMinor = amount, categoryId = category.id, occurrence = "2026-10-17T12:00:00", note = "午餐"), env.id()).single()
    }
    private fun debugCommand(ledgerId: String, count: Int = 100): Command = Json.decodeFromString<Command>(buildJsonObject {
        put("type", "com.dawnyew.core.Command.GenerateDebugEntries")
        put("ledgerId", ledgerId); put("startDate", "2026-09-19"); put("endDate", "2026-10-18"); put("count", count)
    }.toString())

    @Test fun debugGenerationPersistsExactBatchAndUndoPreservesExistingEntriesAcrossReopen() = runBlocking {
        fixture { path, repository ->
            expense(repository)
            val before = repository.snapshot()
            val command = debugCommand(before.ledgers.single().id)
            val operation = env.id()
            val concurrent = List(2) { async { repository.execute(command, operation, before.contentVersion) } }.awaitAll()
            assertEquals(concurrent.first(), concurrent.last())
            val generated = repository.snapshot()
            assertEquals(101, generated.entries.size)
            assertEquals(100, generated.importBatches.single().count)
            assertEquals(100, generated.importMembers.size)
            assertEquals(before.entries, generated.entries.take(1))
            assertEquals(before.contentVersion + 1, generated.contentVersion)
            assertEquals(before.receipts.size + 1, generated.receipts.size)
            repository.close()
            val reopened = RoomLedgerRepository(createDatabase(path), env, createIfEmpty = false)
            try {
                reopened.initialize()
                assertEquals(generated, reopened.snapshot())
                reopened.execute(Command.UndoImport(concurrent.first().single()), env.id())
                val undone = reopened.snapshot()
                assertEquals(before.entries, undone.entries)
                assertEquals(before.ledgers, undone.ledgers)
                assertEquals(before.categories, undone.categories)
                assertEquals("UNDONE", undone.importBatches.single().status)
                BundledSQLiteDriver().open(path).use { connection ->
                    connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                }
            } finally { reopened.close() }
        }
    }

    @Test fun debugGenerationFailureRollsBackEntriesBatchMembersReceiptAndVersion() = runBlocking {
        fixture { path, repository ->
            expense(repository)
            val before = repository.snapshot(); val operation = env.id()
            val command = debugCommand(before.ledgers.single().id)
            BundledSQLiteDriver().open(path).use {
                it.execSQL("CREATE TRIGGER fail_debug_receipt BEFORE INSERT ON operation_receipts BEGIN SELECT RAISE(ABORT, 'test batch failure'); END")
            }
            assertFails { repository.execute(command, operation) }
            assertEquals(before, repository.snapshot())
            assertEquals(before, repository.state.value)
            BundledSQLiteDriver().open(path).use { it.execSQL("DROP TRIGGER fail_debug_receipt") }
            repository.execute(command, operation)
            assertEquals(101, repository.snapshot().entries.size)
            assertEquals(before.contentVersion + 1, repository.snapshot().contentVersion)
        }
    }

    @Test fun invalidDebugInputAndModifiedBatchUndoAreAtomic() = runBlocking {
        fixture { _, repository ->
            expense(repository)
            val before = repository.snapshot()
            assertFailsWith<DomainException> { repository.execute(debugCommand(before.ledgers.single().id, 0), env.id()) }
            assertEquals(before, repository.snapshot())
            val batchId = repository.execute(debugCommand(before.ledgers.single().id), env.id()).single()
            val generated = repository.snapshot(); val entry = generated.entries.last()
            repository.execute(Command.SaveEntry(entry.id, entry.ledgerId, entry.kind, entry.amountMinor, entry.categoryId,
                entry.occurrence, "保留用户修改", entry.revision), env.id())
            val modified = repository.snapshot()
            assertEquals(ErrorCode.CONFLICT, assertFailsWith<DomainException> { repository.execute(Command.UndoImport(batchId), env.id()) }.code)
            assertEquals(modified, repository.snapshot())
            assertEquals(modified, repository.state.value)
        }
    }

    @Test fun debugSubmissionRecoversAfterReceiptLossAndDoesNotMisidentifyOrdinaryImports() = runBlocking {
        fixture { _, repository ->
            val before = repository.snapshot(); val operation = env.id()
            val command = debugCommand(before.ledgers.single().id)
            val ids = repository.execute(command, operation)
            val expected = OperationOutcome(operation, "GenerateDebugEntries", ids)
            assertEquals(expected, repository.lookupOperation(operation))
            val withoutReceipt = repository.snapshot().copy(receipts = emptyList())
            repository.replace(withoutReceipt)
            assertEquals(expected, repository.lookupOperation(operation))
            assertEquals(ids, repository.execute(command, operation, before.contentVersion))
            assertEquals(withoutReceipt, repository.snapshot())
            assertFailsWith<DomainException> { repository.execute(debugCommand(before.ledgers.single().id, 101), operation) }
            repository.execute(Command.UndoImport(ids.single()), env.id())
            repository.replace(repository.snapshot().copy(receipts = emptyList()))
            assertEquals(expected, repository.lookupOperation(operation))
            repository.replace(repository.snapshot().let { state ->
                state.copy(importBatches = state.importBatches.map { it.copy(adapterId = "ordinary-file-import") })
            })
            assertNull(repository.lookupOperation(operation))
            assertEquals(ErrorCode.CONFLICT, assertFailsWith<DomainException> { repository.execute(command, operation) }.code)
        }
    }

    @Test fun persistsInitializationAndCommandsAcrossReopen() = runBlocking {
        fixture { path, repository ->
            val id = expense(repository)
            val expected = repository.snapshot()
            repository.close()
            val reopened = RoomLedgerRepository(createDatabase(path), env)
            try {
                reopened.initialize()
                assertEquals(expected, reopened.snapshot())
                assertEquals(id, reopened.state.value!!.entries.single().id)
            } finally { reopened.close() }
        }
    }

    @Test fun retriesSameOperationWithoutDuplicatingReceiptOrVersion() = runBlocking {
        fixture { _, repository ->
            val command = Command.CreateLedger("旅行")
            val first = repository.execute(command, id("same-operation"))
            val version = repository.snapshot().contentVersion
            assertEquals(first, repository.execute(command, id("same-operation")))
            assertEquals(version, repository.snapshot().contentVersion)
            assertEquals(1, repository.snapshot().receipts.size)
            assertFailsWith<DomainException> { repository.execute(Command.CreateLedger("不同内容"), id("same-operation")) }
            assertEquals(version, repository.snapshot().contentVersion)
        }
    }

    @Test fun concurrentRefundsCannotExceedParentAndFailedWriteChangesNothing() = runBlocking {
        fixture { _, repository ->
            val id = expense(repository, 100)
            val before = repository.snapshot()
            val results = List(2) { index -> async {
                runCatching { repository.execute(Command.AddRefund(id, 75, "2026-10-18T10:00:00", ""), id("refund-$index")) }
            } }.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            val after = repository.snapshot()
            assertEquals(before.contentVersion + 1, after.contentVersion)
            assertEquals(75, after.entries.filter { it.kind == EntryKind.REFUND }.sumOf { it.amountMinor })
            assertEquals(before.receipts.size + 1, after.receipts.size)
        }
    }

    @Test fun sqliteRejectsInvalidAmountAndWrongLedgerCategory() = runBlocking {
        fixture { path, repository ->
            val id = expense(repository)
            val other = repository.execute(Command.CreateLedger("其他"), id("other-ledger")).single()
            BundledSQLiteDriver().open(path).use { connection ->
                connection.execSQL("PRAGMA foreign_keys=ON")
                assertFails { connection.execSQL("UPDATE entries SET amountMinor=0 WHERE id='$id'") }
                assertFails { connection.execSQL("UPDATE entries SET ledgerId='$other' WHERE id='$id'") }
            }
            assertEquals(10000, repository.snapshot().entries.single().amountMinor)
        }
    }

    @Test fun ordinaryEditDoesNotDeleteAndReinsertWholeDatabase() = runBlocking {
        fixture { path, repository ->
            val id = expense(repository)
            BundledSQLiteDriver().open(path).use { connection ->
                connection.execSQL("CREATE TABLE delete_audit (value INTEGER NOT NULL)")
                for (table in listOf("ledgers", "categories", "entries")) {
                    connection.execSQL("CREATE TRIGGER audit_${table} AFTER DELETE ON $table BEGIN INSERT INTO delete_audit VALUES (1); END")
                }
            }
            val old = repository.snapshot().entries.single()
            repository.execute(Command.SaveEntry(id, old.ledgerId, old.kind, 20000, old.categoryId,
                old.occurrence, "晚餐", old.revision), id("edit"))
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("SELECT COUNT(*) FROM delete_audit").use { statement ->
                    assertTrue(statement.step()); assertEquals(0, statement.getLong(0))
                }
            }
            assertEquals(20000, repository.snapshot().entries.single().amountMinor)
        }
    }

    @Test fun replacementValidatesAndCommitsAsOneSnapshot() = runBlocking {
        fixture { _, repository ->
            expense(repository)
            val old = repository.snapshot()
            val invalid = old.copy(entries = old.entries.map { it.copy(amountMinor = 0) })
            assertFailsWith<DomainException> { repository.replace(invalid) }
            assertEquals(old, repository.snapshot())
            val restored = DomainEngine(env).initialState()
            repository.replace(restored)
            assertEquals(restored, repository.snapshot())
            assertEquals(restored, repository.state.value)
        }
    }

    @Test fun eachDriverConnectionUsesForeignKeysAndFullSynchronous() {
        val file = Files.createTempFile("dawnyew-pragmas-", ".db").toFile()
        try {
            repeat(2) {
                DurableSQLiteDriver().open(file.absolutePath).use { connection ->
                    for ((pragma, expected) in listOf("foreign_keys" to 1L, "synchronous" to 2L)) {
                        connection.prepare("PRAGMA $pragma").use { statement ->
                            assertTrue(statement.step()); assertEquals(expected, statement.getLong(0))
                        }
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun roomKeepsWalAndFullOnActualPooledConnections() = runBlocking {
        val directory = Files.createTempDirectory("dawnyew-pool-").toFile()
        val database = createDatabase(directory.resolve("ledger.db").absolutePath)
        val repository = RoomLedgerRepository(database, env)
        try {
            repository.initialize()
            repeat(3) {
                database.useWriterConnection { connection ->
                    connection.usePrepared("PRAGMA synchronous") { statement ->
                        assertTrue(statement.step()); assertEquals(2L, statement.getLong(0))
                    }
                    connection.usePrepared("PRAGMA foreign_keys") { statement ->
                        assertTrue(statement.step()); assertEquals(1L, statement.getLong(0))
                    }
                    connection.usePrepared("PRAGMA journal_mode") { statement ->
                        assertTrue(statement.step()); assertEquals("wal", statement.getText(0))
                    }
                }
                database.useReaderConnection { connection ->
                    connection.usePrepared("PRAGMA synchronous") { statement ->
                        assertTrue(statement.step()); assertEquals(2L, statement.getLong(0))
                    }
                }
            }
        } finally { repository.close(); directory.deleteRecursively() }
    }

    @Test fun receiptInsertFailureRollsBackEntitiesAndVersionTogether() = runBlocking {
        fixture { path, repository ->
            val before = repository.snapshot()
            BundledSQLiteDriver().open(path).use { it.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON operation_receipts BEGIN SELECT RAISE(ABORT, 'test disk failure'); END") }
            assertFails { repository.execute(Command.CreateLedger("不能半成功"), id("failure")) }
            assertEquals(before, repository.snapshot())
            assertEquals(before, repository.state.value)
            BundledSQLiteDriver().open(path).use { it.execSQL("DROP TRIGGER fail_receipt") }
            assertEquals(1, repository.execute(Command.CreateLedger("可以重试"), id("failure")).size)
            assertEquals(before.contentVersion + 1, repository.snapshot().contentVersion)
        }
    }

    @Test fun restoreCanSwapUniqueNamesWithoutDeletingLedgers() = runBlocking {
        fixture { _, repository ->
            repository.execute(Command.CreateLedger("旅行"), id("travel"))
            val before = repository.snapshot()
            val names = before.ledgers.map { it.name }.reversed()
            val replacement = before.copy(ledgers = before.ledgers.mapIndexed { i, ledger -> ledger.copy(name = names[i]) })
            repository.replace(replacement)
            assertEquals(replacement, repository.snapshot())
        }
    }

    @Test fun purgeKeepsRemovedImportHistoryWithoutDanglingLiveForeignKey() = runBlocking {
        fixture { path, repository ->
            val id = expense(repository)
            val before = repository.snapshot()
            val batch = ImportBatch(id("batch"), id("submission"), "reviewed", "1", hash("file"), hash("preview"), hash("mapping"), env.utcNow(), count = 1)
            repository.replace(before.copy(importBatches = listOf(batch),
                importMembers = listOf(ImportMember(batch.id, id, 1, hash("content"))),
                sourceIdentities = listOf(SourceIdentity(id("source"), id, "provider", "scope", "expense", "external", canonicalSource = "original", sourceDigest = hash("original")))))
            repository.execute(Command.SoftDelete(id), id("delete"))
            repository.execute(Command.Purge(id), id("purge"))
            val after = repository.snapshot()
            assertTrue(after.entries.isEmpty()); assertTrue(after.sourceIdentities.isEmpty())
            assertEquals("REMOVED", after.importMembers.single().state)
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
            }
        }
    }

    @Test fun missingIntegrityTriggerRefusesReopenWithoutClearingData() = runBlocking {
        fixture { path, repository ->
            expense(repository)
            repository.close()
            BundledSQLiteDriver().open(path).use { it.execSQL("DROP TRIGGER entries_validate_update") }
            val reopened = RoomLedgerRepository(createDatabase(path), env)
            try { assertFails { reopened.initialize() } } finally { reopened.close() }
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("SELECT COUNT(*) FROM entries").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
            }
        }
    }

    @Test fun recordsBundledEngineVersionAndCompileOptions() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            val version = connection.prepare("SELECT sqlite_version()").use {
                assertTrue(it.step()); it.getText(0)
            }
            val options = mutableListOf<String>()
            connection.prepare("PRAGMA compile_options").use {
                while (it.step()) options += it.getText(0)
            }
            assertTrue(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
            assertTrue(options.isNotEmpty())
            println("bundled_sqlite_version=$version")
            println("bundled_sqlite_compile_options=${options.sorted().joinToString(",")}")
        }
    }
    @Test fun existingEmptyDatabaseCannotBeSilentlyInitialized() = runBlocking {
        val dir=Files.createTempDirectory("dawnyew-existing-empty-").toFile();val path=dir.resolve("ledger.db").absolutePath
        val repository=RoomLedgerRepository(createDatabase(path),env,createIfEmpty=false)
        try { assertFailsWith<DomainException> { repository.initialize() };assertNull(repository.state.value) }
        finally { repository.close();dir.deleteRecursively() }
    }

    @Test fun writerCacheRefreshesWhenAnotherConnectionCommitsANewerMetadataVersion()=runBlocking {
        fixture { path,first ->
            val second=RoomLedgerRepository(createDatabase(path),env,createIfEmpty=false)
            try {
                second.initialize();second.execute(Command.CreateLedger("second writer"),env.id())
                first.execute(Command.CreateLedger("first writer"),env.id())
                val state=first.snapshot();assertEquals(setOf("日常","second writer","first writer"),state.ledgers.map { it.name }.toSet());assertEquals(3L,state.contentVersion)
            } finally { second.close() }
        }
    }

    @Test fun operationLookupUsesReceiptAndDurableCreationIdentityAfterReceiptRemoval()=runBlocking {
        fixture { _,repo ->
            val before=repo.snapshot();val operation=env.id();val c=before.categories.first()
            val ids=repo.execute(Command.SaveEntry(ledgerId=c.ledgerId,kind=EntryKind.EXPENSE,amountMinor=100,categoryId=c.id,occurrence="2026-10-17T00:00:00",note=""),operation)
            assertEquals(OperationOutcome(operation,"SaveEntry",ids),repo.lookupOperation(operation))
            repo.replace(repo.snapshot().copy(receipts=emptyList()))
            assertEquals(OperationOutcome(operation,"SaveEntry",ids),repo.lookupOperation(operation))
            assertNull(repo.lookupOperation(env.id()))
        }
    }

}
