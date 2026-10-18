package com.dawnyew.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.dawnyew.core.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.text.Normalizer
import java.util.UUID
import kotlin.test.*

class ImportTransactionTest {
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=Normalizer.normalize(value,Normalizer.Form.NFC)
    }
    private suspend fun fixture(block:suspend (String,RoomLedgerRepository)->Unit) {
        val directory=Files.createTempDirectory("dawnyew-import-").toFile();val path=directory.resolve("ledger.db").absolutePath
        val repository=RoomLedgerRepository(createDatabase(path),env)
        try { repository.initialize();block(path,repository) } finally { repository.close();directory.deleteRecursively() }
    }
    private fun plan(s:LedgerState):ImportPlan {
        val l=Ledger(env.id(),"Imported");val c=Category(env.id(),l.id,Direction.EXPENSE,"Imported")
        val e=Entry(env.id(),l.id,EntryKind.EXPENSE,100,c.id,"2026-10-17T00:00:00",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id())
        val r=e.copy(id=env.id(),kind=EntryKind.REFUND,amountMinor=20,parentExpenseId=e.id,creationOperationId=env.id())
        val source=SourceIdentity(env.id(),e.id,"fixture","stable-account","payment","001",canonicalSource="canonical",sourceDigest=Sha256.hex("canonical".encodeToByteArray()))
        return ImportPlan(env.id(),env.id(),s.datasetId,s.contentVersion,0,"fixture","1",Sha256.hex("file".encodeToByteArray()),Sha256.hex("mapping".encodeToByteArray()),
            listOf(l),listOf(c),listOf(ImportRow(1,e,source),ImportRow(2,r))).sealed()
    }
    @Test fun commitIsDurableAndConcurrentSubmissionRetriesDoNotDuplicate()=runBlocking {
        fixture { path,repo ->
            val before=repo.snapshot();val p=plan(before)
            val results=List(2) { async { repo.execute(Command.CommitImport(p),env.id(),before.contentVersion) } }.awaitAll()
            assertEquals(results[0],results[1]);val state=repo.snapshot()
            assertEquals(before.contentVersion+1,state.contentVersion);assertEquals(1,state.importBatches.size);assertEquals(2,state.importMembers.size);assertEquals(1,state.receipts.size)
            repo.close();val reopened=RoomLedgerRepository(createDatabase(path),env)
            try { reopened.initialize();assertEquals(state,reopened.snapshot());assertEquals(listOf(p.batchId),reopened.execute(Command.CommitImport(p),env.id())) } finally { reopened.close() }
        }
    }
    @Test fun failedReceiptRollsBackContainersSourcesBatchMembersAndVersion()=runBlocking {
        fixture { path,repo ->
            val before=repo.snapshot();val p=plan(before)
            BundledSQLiteDriver().open(path).use { it.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON operation_receipts BEGIN SELECT RAISE(ABORT, 'injected'); END") }
            assertFails { repo.execute(Command.CommitImport(p),env.id()) }
            assertEquals(before,repo.snapshot());assertEquals(before,repo.state.value)
            BundledSQLiteDriver().open(path).use { it.execSQL("DROP TRIGGER fail_import") }
            repo.execute(Command.CommitImport(p),env.id());assertEquals(2,repo.snapshot().entries.size)
        }
    }
    @Test fun undoRollsBackIfReceiptFailsThenCommitsHistoryWithoutDanglingForeignKey()=runBlocking {
        fixture { path,repo ->
            val p=plan(repo.snapshot());repo.execute(Command.CommitImport(p),env.id());val before=repo.snapshot()
            BundledSQLiteDriver().open(path).use { it.execSQL("CREATE TRIGGER fail_undo BEFORE INSERT ON operation_receipts BEGIN SELECT RAISE(ABORT, 'injected'); END") }
            assertFails { repo.execute(Command.UndoImport(p.batchId),env.id()) };assertEquals(before,repo.snapshot())
            BundledSQLiteDriver().open(path).use { it.execSQL("DROP TRIGGER fail_undo") }
            repo.execute(Command.UndoImport(p.batchId),env.id());val after=repo.snapshot()
            assertTrue(after.entries.isEmpty());assertTrue(after.sourceIdentities.isEmpty());assertEquals(2,after.ledgers.size)
            assertTrue(after.importMembers.all { it.state=="UNDONE" });assertEquals("UNDONE",after.importBatches.single().status)
            repo.execute(Command.UndoImport(p.batchId),env.id());assertEquals(after,repo.snapshot())
            DurableSQLiteDriver().open(path).use { connection ->
                connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
                connection.prepare("PRAGMA secure_delete").use { assertTrue(it.step());assertEquals(1L,it.getLong(0)) }
            }
        }
    }
    @Test fun invalidRefundDependencyOrPurgeMakesEntireOperationFail()=runBlocking {
        fixture { _,repo ->
            val before=repo.snapshot();val p=plan(before)
            val broken=p.copy(rows=p.rows.drop(1)).sealed()
            assertFailsWith<DomainException> { repo.execute(Command.CommitImport(broken),env.id()) };assertEquals(before,repo.snapshot())
            repo.execute(Command.CommitImport(p),env.id());val expense=repo.snapshot().entries.first { it.kind==EntryKind.EXPENSE }
            repo.execute(Command.SoftDelete(expense.id),env.id());repo.execute(Command.Purge(expense.id),env.id())
            val purged=repo.snapshot();assertFailsWith<DomainException> { repo.execute(Command.UndoImport(p.batchId),env.id()) };assertEquals(purged,repo.snapshot())
            assertTrue(purged.importMembers.all { it.state=="REMOVED" })
        }
    }
}
