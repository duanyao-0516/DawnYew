package com.dawnyew.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.dawnyew.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class MigrationTest {
    private val json=Json { encodeDefaults=true }
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private fun state():LedgerState {
        val engine=DomainEngine(env);var s=engine.initialState()
        repeat(2) { s=engine.apply(s,Command.SaveEntry(ledgerId=s.ledgers.first().id,kind=EntryKind.EXPENSE,amountMinor=100L+it,
            categoryId=s.categories.first().id,occurrence="2026-10-17T00:00:00",note="v1 retained"),env.id()).state }
        return s
    }
    private fun insert(connection:SQLiteConnection,sql:String,vararg values:Any?) {
        connection.prepare(sql).use { statement ->
            values.forEachIndexed { i,value -> when(value) { null -> statement.bindNull(i+1); is Long -> statement.bindLong(i+1,value); is Int -> statement.bindLong(i+1,value.toLong()); else -> statement.bindText(i+1,value.toString()) } }
            statement.step()
        }
    }
    private fun v1(path:String,state:LedgerState,version:Int=1) {
        val schema=json.parseToJsonElement(File("schemas/com.dawnyew.storage.DawnYewDatabase/$version.json").readText()).jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(path).use { connection ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity=element.jsonObject;val table=entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                entity["indices"]?.jsonArray?.forEach { index -> connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            installConstraints(connection,setOf("metadata","ledgers","categories","entries","deletion_groups","budgets","source_identities","import_batches","import_members","preferences","operation_receipts"))
            insert(connection,"INSERT INTO metadata VALUES (?,?,?)",1,state.datasetId,state.contentVersion)
            state.ledgers.forEachIndexed { i,l -> insert(connection,"INSERT INTO ledgers(id,normalizedName,currency,position,payload) VALUES (?,?,?,?,?)",l.id,l.name,l.currency,i,json.encodeToString(l)) }
            state.categories.forEachIndexed { i,c -> insert(connection,"INSERT INTO categories VALUES (?,?,?,?,?,?)",c.id,c.ledgerId,c.direction.name,c.name,i,json.encodeToString(c)) }
            state.entries.forEachIndexed { i,e -> insert(connection,"INSERT INTO entries(id,ledgerId,kind,direction,amountMinor,categoryId,parentExpenseId,deletionGroupId,occurrence,position,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?)",e.id,e.ledgerId,e.kind.name,"EXPENSE",e.amountMinor,e.categoryId,e.parentExpenseId,e.deletionGroupId,e.occurrence,i,json.encodeToString(e))
                if(version>=2) insert(connection,"UPDATE entries SET creationOperationId=? WHERE id=?",e.creationOperationId,e.id)
                if(version>=3) insert(connection,"UPDATE entries SET noteSearch=? WHERE id=?",literalSearchKey(e.note),e.id) }
            state.receipts.forEachIndexed { i,r -> insert(connection,"INSERT INTO operation_receipts VALUES (?,?,?)",r.operationId,i,json.encodeToString(r)) }
            connection.execSQL("PRAGMA user_version=$version")
        }
    }
    @Test fun realExportedV1MigratesWithoutLosingRowsAndEnforcesUniqueCommandId()=runBlocking {
        val directory=Files.createTempDirectory("dawnyew-v1-").toFile();val path=directory.resolve("ledger.db").absolutePath;val expected=state()
        try {
            v1(path,expected)
            val repo=RoomLedgerRepository(createDatabase(path),env)
            try { repo.initialize();assertEquals(expected,repo.snapshot()) } finally { repo.close() }
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("PRAGMA user_version").use { assertTrue(it.step());assertEquals(4L,it.getLong(0)) }
                assertFails { insert(connection,"UPDATE entries SET creationOperationId=? WHERE id=?",expected.entries.first().creationOperationId,expected.entries.last().id) }
                connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun duplicateV1CommandIdentityAbortsMigrationAndPreservesV1ForRecovery()=runBlocking {
        val directory=Files.createTempDirectory("dawnyew-v1-conflict-").toFile();val path=directory.resolve("ledger.db").absolutePath
        val s=state();val duplicate=s.copy(entries=s.entries.map { it.copy(creationOperationId=s.entries.first().creationOperationId) })
        try {
            v1(path,duplicate)
            val repo=RoomLedgerRepository(createDatabase(path),env)
            try { assertFails { repo.initialize() } } finally { repo.close() }
            BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("PRAGMA user_version").use { assertTrue(it.step());assertEquals(1L,it.getLong(0)) }
                connection.prepare("SELECT COUNT(*) FROM entries").use { assertTrue(it.step());assertEquals(2L,it.getLong(0)) }
                connection.prepare("PRAGMA table_info(entries)").use { statement -> while(statement.step()) assertNotEquals("creationOperationId",statement.getText(1)) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun checkpointedCopyMigratesOnlyCandidateAndRetainsSourceSchemaAndReceipts()=runBlocking {
        val directory=Files.createTempDirectory("dawnyew-copy-upgrade-").toFile()
        val source=directory.resolve("old.db");val candidate=directory.resolve("candidate.db");val expected=state()
        try {
            v1(source.absolutePath,expected);val original=source.readBytes()
            assertEquals(1,probeDatabaseVersion(source.absolutePath))
            checkpointDatabaseForCopy(source.absolutePath,1)
            source.copyTo(candidate)
            val repo=RoomLedgerRepository(createDatabase(candidate.absolutePath),env,createIfEmpty=false)
            try {
                repo.initialize();assertEquals(expected,repo.snapshot())
                val receipt=expected.receipts.last()
                assertEquals(receipt.resultIds,repo.lookupOperation(receipt.operationId)?.resultIds)
            } finally {repo.close()}
            checkpointDatabaseForCopy(candidate.absolutePath,CURRENT_DATABASE_VERSION)
            assertEquals(CURRENT_DATABASE_VERSION,probeDatabaseVersion(candidate.absolutePath))
            assertEquals(1,probeDatabaseVersion(source.absolutePath));assertContentEquals(original,source.readBytes())
        } finally {directory.deleteRecursively()}
    }

    @Test fun candidateMigrationFailureLeavesOriginalFileByteIdenticalAndOldSchemaUsable()=runBlocking {
        val directory=Files.createTempDirectory("dawnyew-copy-upgrade-fail-").toFile()
        val source=directory.resolve("old.db");val candidate=directory.resolve("candidate.db")
        val valid=state();val duplicate=valid.copy(entries=valid.entries.map {it.copy(creationOperationId=valid.entries.first().creationOperationId)})
        try {
            v1(source.absolutePath,duplicate);val original=source.readBytes()
            checkpointDatabaseForCopy(source.absolutePath,1);source.copyTo(candidate)
            val repo=RoomLedgerRepository(createDatabase(candidate.absolutePath),env,createIfEmpty=false)
            try {assertFails {repo.initialize()}} finally {repo.close()}
            assertContentEquals(original,source.readBytes());assertEquals(1,probeDatabaseVersion(source.absolutePath))
            assertEquals(1,probeDatabaseVersion(candidate.absolutePath))
            BundledSQLiteDriver().open(source.absolutePath).use { connection ->
                connection.prepare("SELECT COUNT(*) FROM entries").use {assertTrue(it.step());assertEquals(2L,it.getLong(0))}
                connection.prepare("SELECT COUNT(*) FROM operation_receipts").use {assertTrue(it.step());assertEquals(valid.receipts.size.toLong(),it.getLong(0))}
            }
        } finally {directory.deleteRecursively()}
    }


    @Test fun oldVersionTwoAndThreeCopyMigrationsKeepPayloadBytesAndProtectedSource()=runBlocking {
        for(version in 2..3) {
            val directory=Files.createTempDirectory("dawnyew-v$version-copy-").toFile()
            val source=directory.resolve("source.db");val candidate=directory.resolve("candidate.db");val expected=state()
            try {
                v1(source.absolutePath,expected,version);checkpointDatabaseForCopy(source.absolutePath,version)
                val original=source.readBytes();source.copyTo(candidate)
                val repo=RoomLedgerRepository(createDatabase(candidate.absolutePath),env,createIfEmpty=false)
                try { repo.initialize();assertEquals(expected,repo.snapshot()) } finally { repo.close() }
                assertEquals(4,probeDatabaseVersion(candidate.absolutePath))
                assertContentEquals(original,source.readBytes());assertEquals(version,probeDatabaseVersion(source.absolutePath))
                BundledSQLiteDriver().open(candidate.absolutePath).use { connection ->
                    connection.prepare("SELECT id,payload FROM entries ORDER BY id").use { rows ->
                        val payloads=buildMap { while(rows.step()) put(rows.getText(0),rows.getText(1)) }
                        assertEquals(expected.entries.associate { it.id to json.encodeToString(it) },payloads)
                    }
                    for(table in listOf("accounts","entry_details","entry_tags","transfers","dimensions","contacts","obligations","recurring_plans","recurring_occurrences","savings_plans","savings_movements","savings_wishes","budget_allocations","budget_reallocations")) {
                        connection.prepare("SELECT COUNT(*) FROM $table").use { assertTrue(it.step());assertEquals(0L,it.getLong(0)) }
                    }
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun rawProbeNeverCreatesMissingFilesAndCheckpointRejectsVersionDrift() {
        val directory=Files.createTempDirectory("dawnyew-schema-probe-").toFile();val missing=directory.resolve("missing.db")
        try {
            assertFails {probeDatabaseVersion(missing.absolutePath)};assertFalse(missing.exists())
            assertFails {checkpointDatabaseForCopy(missing.absolutePath,1)};assertFalse(missing.exists())
            val future=directory.resolve("future.db")
            BundledSQLiteDriver().open(future.absolutePath).use {it.execSQL("PRAGMA user_version=99")}
            val original=future.readBytes()
            assertEquals(99,probeDatabaseVersion(future.absolutePath));assertContentEquals(original,future.readBytes())
            assertFails {checkpointDatabaseForCopy(future.absolutePath,1)}
            assertFails {checkpointDatabaseForCopy(future.absolutePath,99)}
            assertContentEquals(original,future.readBytes())
        } finally {directory.deleteRecursively()}
    }

    @Test fun incompleteWalCheckpointRejectsCopyUntilReaderReleasesSnapshot() {
        val directory=Files.createTempDirectory("dawnyew-copy-wal-").toFile();val source=directory.resolve("old.db")
        try {
            v1(source.absolutePath,state())
            BundledSQLiteDriver().open(source.absolutePath).use {writer ->
                writer.execSQL("PRAGMA journal_mode=WAL");writer.execSQL("PRAGMA wal_autocheckpoint=0")
                BundledSQLiteDriver().open(source.absolutePath).use {reader ->
                    reader.execSQL("BEGIN")
                    reader.prepare("SELECT contentVersion FROM metadata").use {assertTrue(it.step())}
                    writer.execSQL("UPDATE metadata SET contentVersion=contentVersion+1")
                    assertFails {checkpointDatabaseForCopy(source.absolutePath,1)}
                    reader.execSQL("COMMIT")
                }
            }
            checkpointDatabaseForCopy(source.absolutePath,1)
            assertEquals(1,probeDatabaseVersion(source.absolutePath))
            assertTrue(!File(source.path+"-wal").exists() || File(source.path+"-wal").length()==0L)
        } finally {directory.deleteRecursively()}
    }
}
