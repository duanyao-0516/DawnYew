package com.dawnyew.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.dawnyew.core.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class FinancialActivityQueryTest {
    private fun id()=UUID.randomUUID().toString()
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private suspend fun fixture(count:Int=20,block:suspend(RoomLedgerRepository,LedgerState)->Unit) {
        val dir=Files.createTempDirectory("financial-query-").toFile();val repo=RoomLedgerRepository(createDatabase(dir.resolve("ledger.db").absolutePath),env)
        try {
            repo.initialize();val base=repo.snapshot();val l=base.ledgers.first();val c=base.categories.first {it.direction==Direction.EXPENSE}
            val a=Account(id(),l.id,"Cash",openingAt="2026-10-01T00:00:00");val b=Account(id(),l.id,"Bank",openingAt=a.openingAt)
            val d=Dimension(id(),l.id,DimensionKind.PROJECT,"Trip");val tag=Dimension(id(),l.id,DimensionKind.TAG,"Travel")
            val entries=List(count) { i->Entry(id(),l.id,EntryKind.EXPENSE,100L+i,c.id,"2026-10-17T10:00:00",note="note %_$i",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=id()) }
            val transfers=entries.take(count/2).map {e->AccountTransfer(e.id,l.id,a.id,b.id,e.amountMinor,e.occurrence,e.note,createdAt=env.utcNow(),updatedAt=env.utcNow())}
            val s=base.copy(entries=entries,accounts=listOf(a,b),dimensions=listOf(d,tag),transfers=transfers,
                entryDetails=entries.mapIndexed { i,e->EntryDetails(e.id,a.id,recorded=i%2==0,excludedFromBudget=i%3==0,reconciled=i%4==0,projectId=d.id) },
                entryTags=entries.filterIndexed {i,_->i%2==0}.map {EntryTag(it.id,tag.id)})
            repo.replace(s);block(repo,s)
        } finally {repo.close();dir.deleteRecursively()}
    }
    @Test fun sqlScopeAndSummaryMatchMemoryForEveryFilterAndSharedUuidFamily()=runBlocking {
        fixture {repo,s->
            val scopes=listOf(ActivityScope(),ActivityScope(categoryIds=setOf(s.categories.first().id)),ActivityScope(accountIds=setOf(s.accounts.first().id)),
                ActivityScope(projectIds=setOf(s.dimensions.first().id)),ActivityScope(tagIds=setOf(s.dimensions.last().id)),ActivityScope(recorded=false),
                ActivityScope(excludedFromBudget=true),ActivityScope(reconciled=true),ActivityScope(keyword="%_"),ActivityScope(minAmountMinor=105,maxAmountMinor=112),
                ActivityScope(kinds=setOf(ActivityKind.TRANSFER)),ActivityScope(deleted=true),ActivityScope(startDate="2026-10-18",endDate="2026-10-18"))
            for(scope in scopes) {
                val loaded=mutableListOf<FinancialActivity>();var cursor:ActivityCursor?=null
                do {val page=repo.queryActivityPage(scope,cursor,3);loaded+=page.activities;cursor=page.nextCursor} while(cursor!=null)
                assertEquals(activityPageSnapshot(s,scope,null,200).activities,loaded,"scope=$scope")
                assertEquals(activitySummarySnapshot(s,scope),repo.activitySummary(scope),"summary scope=$scope")
            }
            val first=repo.queryActivityPage(ActivityScope(),null,3)
            assertNotNull(first.nextCursor);assertEquals(ActivityFamily.TRANSFER,first.activities.first().family)
            repo.execute(Command.SetPreference("theme","dark"),id())
            assertFails {repo.queryActivityPage(ActivityScope(),first.nextCursor,3)}
            Unit
        }
    }
    @Test fun narrowSqlQueryDoesNotDecodePayloadsOutsideItsScope()=runBlocking {
        val dir=Files.createTempDirectory("financial-bounded-").toFile();val path=dir.resolve("ledger.db").absolutePath
        val repo=RoomLedgerRepository(createDatabase(path),env)
        try {
            repo.initialize();val base=repo.snapshot();val c=base.categories.first()
            val first=Entry(id(),c.ledgerId,EntryKind.EXPENSE,100,c.id,"2026-10-17T10:00:00",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=id())
            val outside=first.copy(id=id(),occurrence="2026-09-17T10:00:00",creationOperationId=id())
            repo.replace(base.copy(entries=listOf(first,outside)))
            // A database query must only fetch the selected rows; full snapshot decoding would touch this sentinel.
            BundledSQLiteDriver().open(path).use { connection ->
                connection.execSQL("PRAGMA busy_timeout=5000")
                connection.prepare("UPDATE entries SET payload='{}' WHERE id=?").use {it.bindText(1,outside.id);it.step()}
            }
            val scope=ActivityScope(startDate="2026-10-01",endDate="2026-10-31")
            assertEquals(listOf(first.id),repo.queryActivityPage(scope).activities.map {it.id})
            assertEquals(1L,repo.activitySummary(scope).activityCount)
        } finally {repo.close();dir.deleteRecursively()}
    }
    @Test fun tenThousandRowsHaveBoundedPagesAndCalendarCountsIncludeTransfers()=runBlocking {
        fixture(10_000) {repo,_->
            val page=repo.queryActivityPage(ActivityScope(),null,50)
            assertEquals(50,page.activities.size);assertNotNull(page.nextCursor)
            val summary=repo.activitySummary(ActivityScope())
            assertEquals(15_000L,summary.activityCount);assertEquals(15_000L,summary.days.single().count)
        }
    }
}
