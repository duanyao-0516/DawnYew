package com.dawnyew.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.dawnyew.core.*
import com.dawnyew.protocol.BackupProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class FinancialStorageTest {
    private fun id()=UUID.randomUUID().toString()
    private val now="2026-10-18T04:00:00Z"
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()=now
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private fun fixture():LedgerState {
        val l=Ledger(id(),"Finance");val c=Category(id(),l.id,Direction.EXPENSE,"Food")
        val e=Entry(id(),l.id,EntryKind.EXPENSE,100,c.id,"2026-10-17T10:00:00",createdAt=now,updatedAt=now,creationOperationId=id())
        val a=Account(id(),l.id,"Cash",openingBalanceMinor=-123,openingAt="2026-10-01T00:00:00")
        val b=Account(id(),l.id,"Bank",openingAt="2026-10-01T00:00:00")
        val tag=Dimension(id(),l.id,DimensionKind.TAG,"Lunch")
        val project=Dimension(id(),l.id,DimensionKind.PROJECT,"Trip")
        val t=AccountTransfer(e.id,l.id,a.id,b.id,20,e.occurrence,createdAt=now,updatedAt=now)
        return LedgerState(id(),ledgers=listOf(l),categories=listOf(c),entries=listOf(e),accounts=listOf(a,b),
            dimensions=listOf(tag,project),entryDetails=listOf(EntryDetails(e.id,a.id,recorded=false,excludedFromBudget=true,projectId=project.id)),
            entryTags=listOf(EntryTag(e.id,tag.id)),transfers=listOf(t))
    }
    @Test fun realSqlitePersistsNewFactsAndReopensWithoutChangingOldPayload()=runBlocking {
        val directory=Files.createTempDirectory("finance-storage-").toFile();val path=directory.resolve("ledger.db").absolutePath
        var repo=RoomLedgerRepository(createDatabase(path),env)
        try {
            repo.initialize();val expected=fixture();repo.replace(expected);repo.close()
            repo=RoomLedgerRepository(createDatabase(path),env,createIfEmpty=false);repo.initialize()
            assertEquals(expected,repo.snapshot())
            assertEquals(-123L,repo.snapshot().accounts.first().openingBalanceMinor)
        } finally {repo.close();directory.deleteRecursively()}
    }
    @Test fun candidateRestoresFinancialFactsAndRejectsCrossLedgerAccountAndPayloadDrift()=runBlocking {
        val directory=Files.createTempDirectory("finance-candidate-").toFile();val path=directory.resolve("ledger.db").absolutePath;val db=createDatabase(path)
        val state=fixture()
        try {
            val backup=BackupProtocol.create(state,now)
            val summary=writeCandidate(db,state.datasetId) {
                for(type in BackupProtocol.entityTypes.reversed()) stage(BackupProtocol.decodeRecords(type,backup.records.getValue(type)))
            }
            assertEquals(23,summary.counts.size)
            assertEquals(2L,summary.counts.getValue("accounts"))
            assertEquals(summary,db.verifyCandidate(summary))
            BundledSQLiteDriver().open(path).use { it.execSQL("PRAGMA busy_timeout=5000");it.execSQL("UPDATE accounts SET openingBalanceMinor=9") }
            assertFails {db.verifyCandidate(summary)}
            Unit
        } finally {db.close();directory.deleteRecursively()}
    }
    @Test fun candidateRejectsCrossLedgerFinancialReferenceBeforePublication()=runBlocking {
        val directory=Files.createTempDirectory("finance-bad-reference-").toFile();val db=createDatabase(directory.resolve("ledger.db").absolutePath)
        try {
            val s=fixture();val other=Ledger(id(),"Other")
            assertFails {
                writeCandidate(db,s.datasetId) {
                    stage(LedgerEntityBatch(ledgers=s.ledgers+other,categories=s.categories,entries=s.entries))
                    stage(LedgerEntityBatch(accounts=s.accounts.map {it.copy(ledgerId=other.id)},entryDetails=s.entryDetails.map {it.copy(projectId=null)}))
                }
            }
            assertNull(db.ledgerDao().metadata())
        } finally {db.close();directory.deleteRecursively()}
    }
    @Test fun allFourteenFinancialEntitiesRoundtripAcrossCandidateSnapshotAndReopen()=runBlocking {
        val directory=Files.createTempDirectory("finance-complete-").toFile();val path=directory.resolve("ledger.db").absolutePath;val db=createDatabase(path)
        val state=financialGraphFixture();val backup=BackupProtocol.create(state,now)
        try {
            val summary=writeCandidate(db,state.datasetId) {
                for(type in BackupProtocol.entityTypes.reversed()) stage(BackupProtocol.decodeRecords(type,backup.records.getValue(type).reversed()))
            }
            assertEquals(23,summary.counts.size)
            BackupProtocol.entityTypes.drop(9).forEach {assertTrue(summary.counts.getValue(it)>0,"missing $it")}
            val other=createDatabase(directory.resolve("copy.db").absolutePath)
            try {
                val copy=writeCandidate(other,state.datasetId) {
                    val sink=this
                    db.exportSnapshot {for(type in BackupProtocol.entityTypes) visit(type) {batch->sink.stage(BackupProtocol.decodeRecords(type,BackupProtocol.encodeRecords(type,batch)))}}
                }
                assertEquals(summary,copy)
                val repo=RoomLedgerRepository(other,env,createIfEmpty=false)
                repo.initialize();assertEquals(backup.records,BackupProtocol.create(repo.snapshot(),now).records)
            } finally {other.close()}
        } finally {db.close();directory.deleteRecursively()}
    }
    @Test fun candidateRejectsNonzeroVirtualOpeningAndKeepsTransactionEmpty()=runBlocking {
        val directory=Files.createTempDirectory("finance-virtual-").toFile();val db=createDatabase(directory.resolve("ledger.db").absolutePath)
        try {
            val state=financialGraphFixture();val backup=BackupProtocol.create(state,now);val virtual=state.obligations.single().virtualAccountId
            assertFails {
                writeCandidate(db,state.datasetId) {
                    for(type in BackupProtocol.entityTypes) {
                        val records=backup.records.getValue(type).map {r->if(type=="accounts" && r.getValue("id").jsonPrimitive.content==virtual) JsonObject(r+("openingBalanceMinor" to JsonPrimitive("1"))) else r}
                        stage(BackupProtocol.decodeRecords(type,records))
                    }
                }
            }
            assertNull(db.ledgerDao().metadata())
        } finally {db.close();directory.deleteRecursively()}
    }
    @Test fun recurringTemplateUsesNextUnprocessedOccurrenceForLaterAccountOpening()=runBlocking {
        val directory=Files.createTempDirectory("finance-next-account-").toFile();val db=createDatabase(directory.resolve("ledger.db").absolutePath)
        try {
            val original=financialGraphFixture();val newer=Account(id(),original.ledgers.single().id,"New bank",openingAt="2026-10-18T00:00:00")
            val state=original.copy(accounts=original.accounts+newer,recurringPlans=original.recurringPlans.map {it.copy(accountId=newer.id)})
            val backup=BackupProtocol.create(state,now)
            val summary=writeCandidate(db,state.datasetId) {for(type in BackupProtocol.entityTypes) stage(BackupProtocol.decodeRecords(type,backup.records.getValue(type)))}
            assertEquals(summary,db.verifyCandidate(summary))
        } finally {db.close();directory.deleteRecursively()}
    }
    @Test fun budgetSqlExcludesParentAndRefundDynamicallyButKeepsOrdinaryTotals()=runBlocking {
        val directory=Files.createTempDirectory("finance-budget-").toFile();val repo=RoomLedgerRepository(createDatabase(directory.resolve("ledger.db").absolutePath),env)
        try {
            repo.initialize();val s=fixture();val e=s.entries.single()
            val refund=e.copy(id=id(),kind=EntryKind.REFUND,amountMinor=40,parentExpenseId=e.id,occurrence="2026-10-18T10:00:00",creationOperationId=id())
            repo.replace(s.copy(entries=listOf(e,refund)))
            val stats=repo.statistics(QueryScope())
            assertEquals(100L,stats.expense);assertEquals(40L,stats.refunds);assertEquals(0L,stats.budgetUsed)
            repo.replace(s.copy(entries=listOf(e,refund),entryDetails=listOf(s.entryDetails.single().copy(excludedFromBudget=false))))
            assertEquals(60L,repo.statistics(QueryScope()).budgetUsed)
        } finally {repo.close();directory.deleteRecursively()}
    }
}
