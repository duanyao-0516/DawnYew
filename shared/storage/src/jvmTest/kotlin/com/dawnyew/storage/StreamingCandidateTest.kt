package com.dawnyew.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.dawnyew.core.*
import com.dawnyew.protocol.BackupProtocol
import com.dawnyew.protocol.CanonicalJson
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class StreamingCandidateTest {
    private fun id()=UUID.randomUUID().toString()
    private val now="2026-10-18T04:00:00Z"
    private fun entry(ledger:Ledger,category:Category)=Entry(id(),ledger.id,EntryKind.EXPENSE,100,category.id,"2026-10-17T00:00:00",createdAt=now,updatedAt=now,creationOperationId=id())
    private suspend fun fixture(block:suspend(String,DawnYewDatabase)->Unit) {
        val directory=Files.createTempDirectory("dawnyew-stream-").toFile();val path=directory.resolve("ledger.db").absolutePath;val db=createDatabase(path)
        try { block(path,db) } finally { db.close();directory.deleteRecursively() }
    }
    @Test fun stagesChildrenBeforeParentsAndExportsSortedBoundedChunks()=runBlocking {
        fixture { _,db ->
            val ledger=Ledger(id(),"Stream");val category=Category(id(),ledger.id,Direction.EXPENSE,"Stream")
            val expense=entry(ledger,category);val refund=expense.copy(id=id(),kind=EntryKind.REFUND,amountMinor=20,parentExpenseId=expense.id,creationOperationId=id())
            val summary=writeCandidate(db,id()) {
                stage(LedgerEntityBatch(entries=listOf(refund)))
                stage(LedgerEntityBatch(entries=listOf(expense)))
                stage(LedgerEntityBatch(categories=listOf(category)));stage(LedgerEntityBatch(ledgers=listOf(ledger)))
            }
            assertEquals(2L,summary.counts.getValue("entries"))
            db.exportSnapshot {
                assertEquals(summary.datasetId,datasetId);assertEquals(summary.counts,counts)
                val ids=mutableListOf<String>();visit("entries") { assertTrue(it.entries.size<=128);ids+=it.entries.map { e->e.id } }
                assertEquals(listOf(expense.id,refund.id).sorted(),ids)
            }
        }
    }
    @Test fun lateCrossRecordFailureOrCancellationLeavesNoCandidateRows()=runBlocking {
        fixture { path,db ->
            val l=Ledger(id(),"Stream");val c=Category(id(),l.id,Direction.EXPENSE,"Stream");val e=entry(l,c);val g=DeletionGroup(id(),now,id())
            assertFailsWith<DomainException> { writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(l),categories=listOf(c),entries=listOf(e.copy(deletionGroupId=g.id)),deletionGroups=listOf(g))) } }
            assertFailsWith<CancellationException> { writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(l)));throw CancellationException("injected") } }
            BundledSQLiteDriver().open(path).use { connection -> for(table in listOf("ledgers","categories","entries","deletion_groups","metadata")) connection.prepare("SELECT COUNT(*) FROM $table").use { assertTrue(it.step());assertEquals(0L,it.getLong(0)) } }
        }
    }
    @Test fun duplicateRowsAndRefundOverflowAbortWholeCandidate()=runBlocking {
        fixture { _,db ->
            val l=Ledger(id(),"Stream");val c=Category(id(),l.id,Direction.EXPENSE,"Stream");val e=entry(l,c)
            assertFails { writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(l),categories=listOf(c),entries=listOf(e)));stage(LedgerEntityBatch(entries=listOf(e))) } }
            val r=e.copy(id=id(),kind=EntryKind.REFUND,parentExpenseId=e.id,amountMinor=101,creationOperationId=id())
            assertFailsWith<DomainException> { writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(l),categories=listOf(c),entries=listOf(e,r))) } }
        }
    }
    @Test fun candidateIsReverifiedBeforePublicationAndBindsItsExactPreparedContents()=runBlocking {
        fixture { path,db ->
            val l=Ledger(id(),"Verified");val c=Category(id(),l.id,Direction.EXPENSE,"Verified");val e=entry(l,c)
            val summary=writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(l),categories=listOf(c),entries=listOf(e))) }
            assertEquals(summary,db.verifyCandidate(summary));assertEquals(64,summary.contentDigest.length)
            BundledSQLiteDriver().open(path).use { connection -> connection.execSQL("PRAGMA busy_timeout=5000");connection.execSQL("UPDATE entries SET amountMinor=101") }
            assertFailsWith<DomainException> { db.verifyCandidate(summary) }
            BundledSQLiteDriver().open(path).use { connection -> connection.execSQL("PRAGMA busy_timeout=5000");connection.execSQL("UPDATE entries SET amountMinor=100") }
            assertEquals(summary,db.verifyCandidate(summary))
            // This is semantically valid but is no longer the content the user previewed.
            BundledSQLiteDriver().open(path).use { connection -> connection.execSQL("PRAGMA busy_timeout=5000");connection.execSQL("UPDATE entries SET amountMinor=101,payload=replace(payload,'\"amountMinor\":100','\"amountMinor\":101')") }
            assertFailsWith<DomainException> { db.verifyCandidate(summary) }
            assertNotEquals(summary.contentDigest,db.verifyCandidate().contentDigest)
        }
    }
    @Test fun candidateReverificationRejectsLateBrokenRelationshipsAndInvalidPayloadFields()=runBlocking {
        fixture { path,db ->
            val l=Ledger(id(),"Verified");val c=Category(id(),l.id,Direction.EXPENSE,"Verified");val e=entry(l,c)
            val r=e.copy(id=id(),kind=EntryKind.REFUND,amountMinor=50,parentExpenseId=e.id,creationOperationId=id())
            writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(l),categories=listOf(c),entries=listOf(e,r))) }
            BundledSQLiteDriver().open(path).use { connection -> connection.execSQL("PRAGMA busy_timeout=5000");connection.execSQL("UPDATE entries SET amountMinor=101,payload=replace(payload,'\"amountMinor\":50','\"amountMinor\":101') WHERE kind='REFUND'") }
            assertFailsWith<DomainException> { db.verifyCandidate() }
            BundledSQLiteDriver().open(path).use { connection -> connection.execSQL("PRAGMA busy_timeout=5000");connection.execSQL("UPDATE entries SET amountMinor=50,payload=replace(payload,'\"amountMinor\":101','\"amountMinor\":50') WHERE kind='REFUND'");connection.execSQL("UPDATE entries SET payload=replace(payload,'\"revision\":1','\"revision\":0')") }
            assertFailsWith<DomainException> { db.verifyCandidate() }
        }
    }
    @Test fun candidateReverificationRejectsTruncatedDatabaseWithoutReinitializing()=runBlocking {
        val directory=Files.createTempDirectory("dawnyew-stream-corrupt-").toFile();val path=directory.resolve("ledger.db").absolutePath
        var db=createDatabase(path)
        try {
            val summary=writeCandidate(db,id()) { stage(LedgerEntityBatch(ledgers=listOf(Ledger(id(),"Verified")))) }
            db.close();java.io.RandomAccessFile(path,"rw").use { it.setLength(100) }
            db=createDatabase(path)
            assertFails { db.verifyCandidate(summary) }
            assertEquals(100L,directory.resolve("ledger.db").length())
        } finally { db.close();directory.deleteRecursively() }
    }
    @Test fun moreThanTwentyMiBAndTenThousandRowsRoundtripThroughBoundedSqlChunks()=runBlocking {
        fixture { path,source ->
            val dataset=id();val ledger=Ledger(id(),"Large");val category=Category(id(),ledger.id,Direction.EXPENSE,"Large")
            val count=15_001;val start=System.nanoTime()
            val original=writeCandidate(source,dataset) {
                stage(LedgerEntityBatch(ledgers=listOf(ledger),categories=listOf(category)))
                var left=count
                while(left>0) { val size=minOf(left,128);stage(LedgerEntityBatch(entries=List(size) { entry(ledger,category).copy(note="账".repeat(500)) }));left-=size }
            }
            val target=createDatabase(path+".copy")
            var wireBytes=0L;var maxBatch=0;var records=0L
            try {
                val restored=writeCandidate(target,dataset) {
                    val sink=this
                    source.exportSnapshot {
                        assertEquals(original.counts,counts)
                        for(type in ledgerEntityTypes) visit(type) { batch ->
                            val wire=BackupProtocol.encodeRecords(type,batch);maxBatch=maxOf(maxBatch,wire.size)
                            wire.forEach { wireBytes+=CanonicalJson.write(it).encodeToByteArray().size;records++ }
                            sink.stage(BackupProtocol.decodeRecords(type,wire))
                        }
                    }
                }
                assertEquals(original,restored);assertTrue(wireBytes>20L*1024*1024);assertTrue(records>10_000);assertTrue(maxBatch<=128)
                assertEquals(restored,target.verifyCandidate(restored))
                target.exportSnapshot { assertEquals(count.toLong(),counts.getValue("entries")) }
                println("DawnYew bounded SQLite candidate roundtrip: entries=$count wireBytes=$wireBytes maxChunk=$maxBatch elapsedMs=${(System.nanoTime()-start)/1_000_000}")
            } finally { target.close() }
        }
    }
}
