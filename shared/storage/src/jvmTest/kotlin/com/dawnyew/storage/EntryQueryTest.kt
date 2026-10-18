package com.dawnyew.storage

import com.dawnyew.core.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class EntryQueryTest {
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private suspend fun fixture(count:Int,block:suspend(RoomLedgerRepository,LedgerState)->Unit) {
        val directory=Files.createTempDirectory("dawnyew-query-").toFile();val repo=RoomLedgerRepository(createDatabase(directory.resolve("ledger.db").absolutePath),env)
        try {
            repo.initialize();val base=repo.snapshot();val c=base.categories.first()
            val entries=List(count) { i -> Entry(env.id(),c.ledgerId,EntryKind.EXPENSE,100,c.id,"2026-10-17T10:00:00",note=when(i%4){0->"100%_真实\\账单 ÄΣ";1->"100xx真实账单";else->"record $i"},createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id()) }
            val s=base.copy(entries=entries);repo.replace(s);block(repo,s)
        } finally { repo.close();directory.deleteRecursively() }
    }
    @Test fun sameSecondKeysetPagesAreCompleteStableAndRejectStaleCursor()=runBlocking {
        fixture(251) { repo,s ->
            val scope=QueryScope();var cursor:EntryCursor?=null;val loaded=mutableListOf<Entry>()
            do { val page=repo.queryEntryPage(scope,cursor,50);assertTrue(page.entries.size<=50);loaded+=page.entries;cursor=page.nextCursor } while(cursor!=null)
            assertEquals(s.entries.map { it.id }.sortedDescending(),loaded.map { it.id });assertEquals(251,loaded.map { it.id }.distinct().size)
            val first=repo.queryEntryPage(scope,null,50)
            repo.execute(Command.SetPreference("theme","dark"),env.id())
            assertFailsWith<DomainException> { repo.queryEntryPage(scope,first.nextCursor,50) }
        }
    }
    @Test fun literalLikeWildcardsAndUnicodeCaseMatchDomainPredicate()=runBlocking {
        fixture(24) { repo,s ->
            for(keyword in listOf("%_","\\账单","äς","100","missing")) {
                val scope=QueryScope(keyword=keyword)
                assertEquals(Statistics.calculate(s,scope).entries.map { it.id },repo.queryEntryPage(scope,null,50).entries.map { it.id })
                assertEquals(Statistics.calculate(s,scope).expense,repo.statistics(scope).expense)
            }
        }
    }
    @Test fun aggregateMetadataAndTotalsBelongToOneConcurrentReadSnapshot()=runBlocking {
        fixture(100) { repo,_ ->
            val writer=async(Dispatchers.Default) { repeat(20) { repo.execute(Command.CreateLedger("ledger-$it"),env.id()) } }
            val reads=List(20) { async(Dispatchers.Default) { repo.statistics(QueryScope()) } }.awaitAll()
            writer.await()
            reads.forEach { assertEquals(100L,it.entryCount);assertEquals(10000L,it.expense);assertTrue(it.entries.isEmpty());assertTrue(it.contentVersion in 1..21);assertEquals(10000L,it.ledgerBreakdown.sumOf { g->g.expense }) }
        }
    }
    @Test fun tenThousandRowsUseBoundedPageAndSqlAggregation()=runBlocking {
        fixture(10_000) { repo,s ->
            val pageStart=System.nanoTime();val page=repo.queryEntryPage(QueryScope(),null,50);val pageMs=(System.nanoTime()-pageStart)/1_000_000
            val statsStart=System.nanoTime();val stats=repo.statistics(QueryScope());val statsMs=(System.nanoTime()-statsStart)/1_000_000
            val e=s.entries.first();val editStart=System.nanoTime();repo.execute(Command.SaveEntry(e.id,e.ledgerId,e.kind,101,e.categoryId,e.occurrence,e.note,e.revision),env.id());val editMs=(System.nanoTime()-editStart)/1_000_000
            assertEquals(50,page.entries.size);assertNotNull(page.nextCursor);assertEquals(10000L,stats.entryCount);assertEquals(1_000_000L,stats.expense);assertTrue(stats.entries.isEmpty())
            println("DawnYew SQLite 10000 rows: first page 50=${pageMs}ms, aggregates=${statsMs}ms, cached single edit=${editMs}ms")
        }
    }
    @Test fun captureQueriesIncludeRecycleBinWithoutReturningRows()=runBlocking {
        fixture(4) { repo,s ->
            val e=s.entries.first();val fingerprint=Sha256.hex("image".encodeToByteArray())
            repo.replace(s.copy(entries=s.entries.map { if(it.id==e.id) it.copy(captureFingerprint=fingerprint,originKind=OriginKind.OCR) else it }))
            assertEquals(CaptureDuplicateSummary(true,true,false),repo.findCaptureDuplicates(fingerprint,100,"2026-10-17"))
            repo.execute(Command.SoftDelete(e.id),env.id())
            assertEquals(CaptureDuplicateSummary(true,true,true),repo.findCaptureDuplicates(fingerprint,100,"2026-10-17"))
            assertEquals(CaptureDuplicateSummary(false,false,false),repo.findCaptureDuplicates(null,null,null))
            assertEquals(CaptureDuplicateSummary(false,false,false),repo.findCaptureDuplicates(null,999,"2026-10-17"))
        }
    }
    @Test fun sqlRefundOnlyPeriodIncludesCategoryAndLedgerBreakdown()=runBlocking {
        fixture(1) { repo,s ->
            repo.execute(Command.AddRefund(s.entries.single().id,20,"2026-10-18T10:00:00",""),env.id())
            val stats=repo.statistics(QueryScope(startDate="2026-10-18",endDate="2026-10-18"))
            assertEquals(1L,stats.entryCount);assertEquals(0L,stats.expense);assertEquals(20L,stats.refunds)
            assertEquals(-20L,stats.categoryBreakdown.single().netExpense);assertEquals(-20L,stats.ledgerBreakdown.single().netExpense);assertTrue(stats.categories.isEmpty())
        }
    }

}
