package com.dawnyew.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.dawnyew.core.*
import com.dawnyew.protocol.BackupProtocol
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DashboardQueryTest {
    private fun id()=UUID.randomUUID().toString()
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    @Test fun sqlPanelsAndHistoryMatchReferenceWithoutDecodingOutsideScope()=runBlocking {
        val dir=Files.createTempDirectory("dashboard-query-").toFile();val path=dir.resolve("ledger.db").absolutePath
        val repo=RoomLedgerRepository(createDatabase(path),env)
        try {
            repo.initialize();val base=repo.snapshot();val ledger=base.ledgers.first();val category=base.categories.first {it.direction==Direction.EXPENSE}
            val account=Account(id(),ledger.id,"现金",openingBalanceMinor=10000,openingAt="2026-10-01T00:00:00")
            fun entry(date:String,amount:Long)=Entry(id(),ledger.id,EntryKind.EXPENSE,amount,category.id,date+"T12:00:00",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=id())
            val a=entry("2026-10-05",101);val b=entry("2026-10-04",299);val before=entry("2026-09-04",600);val outside=entry("2027-01-01",900)
            val state=base.copy(accounts=listOf(account),entries=listOf(a,b,before,outside),entryDetails=listOf(EntryDetails(b.id,account.id)),budgets=listOf(MonthlyBudget(ledger.id,"2026-10",2000)),budgetAllocations=listOf(BudgetAllocation(id(),ledger.id,"2026-10",category.id,1000)))
            repo.replace(state)
            val month=ActivityScope(startDate="2026-10-01",endDate="2026-10-31")
            val ids=PanelCatalog.definitions(PanelSurface.STATISTICS).map {it.id}+listOf("budget_tracking","category_change","category_radar","income_expense","monthly_expense")
            for(panel in ids) {
                val request=PanelRequest(panel,if(panel=="category_monthly") month.copy(categoryIds=setOf(category.id)) else month)
                assertEquals(PanelQueries.calculate(state,request),repo.panelStatistics(request),panel)
            }
            for(scope in listOf(month.copy(weekdays=setOf(1)),month.copy(accountUnassigned=true),month.copy(projectUnassigned=true),month.copy(merchantUnassigned=true),month.copy(tagUnassigned=true))) {
                assertEquals(activityPageSnapshot(state,scope),repo.queryActivityPage(scope),scope.toString())
            }
            val history=AccountHistoryRequest(startDate="2026-10-01",endDate="2026-10-31")
            assertEquals(AccountHistoryQueries.calculate(state,history),repo.accountHistory(history))
            val layout=PanelLayout(PanelCatalog.defaultLayout(PanelSurface.HOME).order.reversed(),emptySet())
            repo.execute(Command.SetPreference("homePanels",PanelCatalog.encode(PanelSurface.HOME,layout)),id())
            assertEquals(layout,PanelCatalog.resolve(repo.snapshot().preferences,PanelSurface.HOME))
            BundledSQLiteDriver().open(path).use { connection ->
                connection.execSQL("PRAGMA busy_timeout=5000")
                connection.prepare("UPDATE entries SET payload='{}' WHERE id=?").use {it.bindText(1,outside.id);it.step()}
            }
            assertEquals(400,repo.panelStatistics(PanelRequest("category",month)).rows.sumOf {it.value})
            assertEquals(9701,repo.accountHistory(history).points.last().balanceMinor)
        } finally {repo.close();dir.deleteRecursively()}
    }
    @Test fun panelPreferencesSurviveRealReopenAndStreamingBackupRestore()=runBlocking {
        val dir=Files.createTempDirectory("dashboard-preferences-").toFile();val path=dir.resolve("ledger.db").absolutePath
        var repo=RoomLedgerRepository(createDatabase(path),env)
        try {
            repo.initialize()
            val old=repo.snapshot()
            assertTrue(old.preferences.none {it.key in setOf("homePanels","statisticsPanels")})
            PanelCatalog.resolve(old.preferences,PanelSurface.HOME)
            assertEquals(old,repo.snapshot())
            for(surface in PanelSurface.entries) {
                val layout=PanelLayout(PanelCatalog.defaultLayout(surface).order.reversed(),emptySet())
                repo.execute(Command.SetPreference(PanelCatalog.preferenceKey(surface),PanelCatalog.encode(surface,layout)),id())
            }
            val expected=repo.snapshot();val backup=BackupProtocol.create(expected,env.utcNow())
            repo.close();repo=RoomLedgerRepository(createDatabase(path),env,createIfEmpty=false);repo.initialize()
            assertEquals(expected.preferences,repo.snapshot().preferences)
            val target=createDatabase(dir.resolve("restore.db").absolutePath)
            try {
                val summary=writeCandidate(target,expected.datasetId) {
                    for(type in BackupProtocol.entityTypes.reversed()) stage(BackupProtocol.decodeRecords(type,backup.records.getValue(type)))
                }
                assertEquals(summary,target.verifyCandidate(summary))
                val restored=RoomLedgerRepository(target,env,createIfEmpty=false);restored.initialize()
                assertEquals(expected.preferences,restored.snapshot().preferences)
                assertEquals(backup.records,BackupProtocol.create(restored.snapshot(),env.utcNow()).records)
            } finally {target.close()}
        } finally {repo.close();dir.deleteRecursively()}
    }

}
