package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceControllerTest {
    private class Repo : LedgerRepository {
        override val state = MutableStateFlow<LedgerState?>(LedgerState("dataset", ledgers = listOf(Ledger("ledger", "日常"))))
        var requested: ActivityScope? = null
        var delayMillis = 0L
        override suspend fun initialize() = Unit
        override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> = emptyList()
        override suspend fun snapshot() = state.value!!
        override suspend fun replace(state: LedgerState) { this.state.value = state }
        override suspend fun close() = Unit
        override suspend fun queryActivityPage(scope: ActivityScope, cursor: ActivityCursor?, limit: Int): ActivityPage {
            requested = scope
            val source = state.value!!
            if (delayMillis > 0) withContext(NonCancellable) { delay(delayMillis) }
            return ActivityPage(listOf(FinancialActivity(ActivityFamily.TRANSFER, scope.keyword.ifEmpty { "transfer" }, "ledger", ActivityKind.TRANSFER, 100, "2026-10-24T12:00:00", "", true, false, false)), null, source.datasetId, source.contentVersion)
        }
        override suspend fun financeSnapshot(ledgerIds: Set<String>) = FinanceSnapshot(emptyList(), emptyList(), emptyList(), 0, 0, 0)
        override suspend fun activitySummary(scope: ActivityScope) = ActivitySummary(100, 20, 0, 50, 2, listOf(ActivityDaySummary("2026-10-24", 2, 100, 20, 0, 50)))
    }
    private val environment = object : DomainEnvironment {
        override fun id() = "operation"
        override fun localNow() = "2026-10-24T12:00:00"
        override fun utcNow() = "2026-10-24T04:00:00Z"
        override fun normalize(value: String) = value
    }

    @Test fun appDashboardIsClearedOnRevocationAndDatasetReplacement() = runTest {
        val repo = Repo()
        val app = AppController(repo, environment, backgroundScope)
        runCurrent()
        val request = PanelRequest("daily", ActivityScope(startDate="2026-10-01", endDate="2026-10-31"))
        app.dashboard.loadPanel(request)
        app.dashboard.loadHistory(AccountHistoryRequest(startDate="2026-10-01", endDate="2026-10-31"))
        runCurrent()
        assertNotNull(app.dashboard.panels.value["daily"]?.snapshot)
        assertNotNull(app.dashboard.history.value.snapshot)
        app.resetTransientState(); runCurrent()
        assertTrue(app.dashboard.panels.value.isEmpty())
        assertNull(app.dashboard.history.value.snapshot)
        app.dashboard.loadPanel(request); runCurrent()
        repo.state.value = repo.state.value!!.copy(datasetId="replacement")
        runCurrent()
        assertTrue(app.dashboard.panels.value.isEmpty())
    }

    @Test fun customHomeRangeMonthlyBusinessAndCalendarHaveSeparateQueryScopes()=runTest {
        val app=AppController(Repo(),environment,backgroundScope);runCurrent()
        val home=QueryScope(setOf("ledger"),"2026-09-15","2026-10-10",keyword="ignored",includeArchived=false)
        val selected=homeStatisticsScope(home)
        val monthly=homeMonthlyScope(home)
        val calendar=homeCalendarScope(home,"2026-08")
        assertEquals("2026-09-15",selected.startDate);assertEquals("2026-10-10",selected.endDate)
        assertEquals("2026-10-01",monthly.startDate);assertEquals("2026-10-31",monthly.endDate)
        assertEquals("2026-08-01",calendar.startDate);assertEquals("2026-08-31",calendar.endDate)
        assertEquals(home.ledgerIds,calendar.ledgerIds);assertFalse(calendar.includeArchived)
        app.finance.loadHome(selected.toActivityScope(),calendar.toActivityScope());runCurrent()
        assertEquals(selected.toActivityScope(),app.finance.homeActivities.value.scope)
        assertEquals(selected.toActivityScope().copy(recorded=false),app.finance.pending.value.scope)
        assertEquals(calendar.toActivityScope(),app.finance.calendar.value.scope)
        assertEquals(monthly,homeMonthlyScope(home))
        val drill=homePeriodDrillScope(selected,"2026-10")
        assertEquals("2026-10-01",drill.startDate);assertEquals("2026-10-10",drill.endDate)
        assertEquals(setOf(ActivityKind.EXPENSE,ActivityKind.INCOME,ActivityKind.REFUND),drill.kinds)
        assertEquals(selected.ledgerIds,drill.ledgerIds)
        assertFalse(drill.includeArchived)

    }

    @Test fun homePanelDetailKeepsItsSourcePeriodWithoutChangingTheStatisticsTab()=runTest {
        val app=AppController(Repo(),environment,backgroundScope);runCurrent()
        val old=app.navigation.value.statisticsScope.copy(ledgerIds=setOf("statistics-ledger"),startDate="2025-01-01",endDate="2025-01-31")
        app.navigate {it.copy(statisticsScope=old)}
        val source=app.navigation.value.homeScope
        app.finance.openStatistics("分类统计",source)
        assertEquals(source,app.finance.navigation.value.sourceStatisticsScope)
        assertEquals(source.ledgerIds,app.finance.navigation.value.sourceLedgerIds)
        assertEquals(old,app.navigation.value.statisticsScope)
        app.finance.showActivities(source.toActivityScope());assertTrue(app.finance.back())
        assertEquals(source,app.finance.navigation.value.sourceStatisticsScope)
        assertTrue(app.finance.back());assertEquals("首页",app.navigation.value.page)
        assertEquals(old,app.navigation.value.statisticsScope)
    }

    @Test fun activityDrillReturnsToExactObjectAndOriginalTab() = runTest {
        val app = AppController(Repo(), environment, backgroundScope)
        runCurrent()
        app.finance.selectTab("更多")
        app.finance.open("存钱详情", "plan")
        val filter = ActivityScope(accountIds = setOf("bank"), keyword = "saving")
        app.finance.showActivities(filter)
        runCurrent()
        assertEquals("流水", app.navigation.value.page)
        assertEquals(filter, app.finance.activities.value.scope)
        assertTrue(app.finance.back())
        assertEquals("存钱详情", app.navigation.value.page)
        assertEquals("plan", app.finance.navigation.value.id)
        assertEquals("更多", app.navigation.value.tab)
        assertTrue(app.finance.back())
        assertEquals("更多", app.navigation.value.page)
    }

    @Test fun resetInvalidatesRoutesAndInFlightFinancialReads() = runTest {
        val repo = Repo().apply { delayMillis = 100 }
        val app = AppController(repo, environment, backgroundScope)
        runCurrent()
        app.finance.open("账户详情", "account")
        app.finance.showActivities(ActivityScope(keyword = "old"))
        runCurrent()
        val generation = app.transientGeneration.value
        app.resetTransientState()
        advanceTimeBy(200); runCurrent()
        assertTrue(app.transientGeneration.value > generation)
        assertTrue(app.finance.activities.value.activities.isEmpty())
        assertNull(app.finance.navigation.value.id)
        assertFalse(app.finance.back())
    }

    @Test fun laterScopeSupersedesAnUncooperativeOldRead() = runTest {
        val repo = Repo().apply { delayMillis = 100 }
        val app = AppController(repo, environment, backgroundScope)
        runCurrent()
        app.finance.loadActivities(ActivityScope(keyword = "old")); runCurrent()
        app.finance.loadActivities(ActivityScope(keyword = "new")); runCurrent()
        advanceTimeBy(200); runCurrent()
        assertEquals("new", app.finance.activities.value.activities.single().id)
        assertEquals("new", app.finance.activities.value.contentScope?.keyword)
    }

    @Test fun overviewAndCalendarUseDatabaseProjections() = runTest {
        val app = AppController(Repo(), environment, backgroundScope)
        runCurrent()
        val query = ActivityScope(ledgerIds = setOf("ledger"), startDate = "2026-10-01", endDate = "2026-10-31")
        app.finance.loadSnapshot(query.ledgerIds)
        app.finance.loadHome(query)
        runCurrent()
        assertNotNull(app.finance.snapshot.value.snapshot)
        assertEquals(2, app.finance.calendar.value.summary?.activityCount)
        assertEquals(false, app.finance.pending.value.scope?.recorded)
        assertEquals(query, app.finance.homeActivities.value.contentScope)
    }

    @Test fun detailsChangesInvalidateStatisticsCacheEvenWhenEntriesAreIdentical() {
        val old = LedgerState("dataset")
        assertFalse(old.hasSameQueryContent(old.copy(contentVersion = 2, entryDetails = listOf(EntryDetails("entry", excludedFromBudget = true)))))
        assertTrue(old.hasSameQueryContent(old.copy(contentVersion = 2, preferences = listOf(UserPreference("theme", "dark")))))
    }
    @Test fun annualAndBudgetQueriesCannotOverwriteMonthlyStatistics()=runTest {
        val app=AppController(Repo(),environment,backgroundScope)
        runCurrent()
        val month=QueryScope(ledgerIds=setOf("ledger"),startDate="2026-10-01",endDate="2026-10-31")
        app.loadStatistics(month)
        app.loadYearStatistics(month)
        app.loadBudgetStatistics(month.copy(startDate="2026-09-01",endDate="2026-09-30"))
        runCurrent()
        assertEquals(month,app.statistics.value.snapshot?.scope)
        assertEquals("2026-01-01",app.yearStatistics.value.snapshot?.scope?.startDate)
        assertEquals("2026-12-31",app.yearStatistics.value.snapshot?.scope?.endDate)
        assertEquals("2026-09-01",app.budgetStatistics.value.snapshot?.scope?.startDate)
        app.resetTransientState();runCurrent()
        assertNull(app.yearStatistics.value.snapshot);assertNull(app.budgetStatistics.value.snapshot)
    }

    @Test fun enteringAccountTabFromHomeDoesNotPinTheOldLedgerOrCreateAPhantomBackFrame()=runTest {
        val repo=Repo();repo.state.value=repo.state.value!!.copy(ledgers=listOf(Ledger("home","日常"),Ledger("next","旅行")))
        val app=AppController(repo,environment,backgroundScope);runCurrent()
        app.navigate {it.copy(homeScope=it.homeScope.copy(ledgerIds=setOf("home")))}
        app.finance.open("账户")
        assertEquals("账户",app.navigation.value.page)
        assertEquals("账户",app.navigation.value.tab)
        assertNull(app.finance.navigation.value.sourceLedgerIds)
        assertFalse(app.finance.back())
        app.selectScope(app.navigation.value.scope.copy(ledgerIds=setOf("next")));runCurrent()
        assertEquals(setOf("next"),app.navigation.value.scope.ledgerIds)
        assertNull(app.finance.navigation.value.sourceLedgerIds)
        app.finance.open("账户","account-in-next")
        assertEquals(setOf("next"),app.finance.navigation.value.sourceLedgerIds)
        assertTrue(app.finance.back())
        assertNull(app.finance.navigation.value.id)
        assertNull(app.finance.navigation.value.sourceLedgerIds)
        assertEquals(setOf("next"),app.navigation.value.scope.ledgerIds)
        assertFalse(isPrimaryFinanceDestination("账户","account-in-next"))
    }

    @Test fun topLevelStatisticsUsesItsOwnScopeWhileCategoryDetailKeepsTheHomeSource()=runTest {
        val app=AppController(Repo(),environment,backgroundScope);runCurrent()
        val home=app.navigation.value.homeScope.copy(startDate="2026-09-15",endDate="2026-10-10")
        val statistics=app.navigation.value.statisticsScope.copy(startDate="2025-02-01",endDate="2025-02-28")
        app.navigate {it.copy(homeScope=home,statisticsScope=statistics)}
        app.finance.openStatistics("分类统计",home)
        assertEquals(home,app.finance.navigation.value.sourceStatisticsScope)
        app.finance.openStatistics("统计",home)
        assertEquals("统计",app.navigation.value.tab)
        assertNull(app.finance.navigation.value.sourceStatisticsScope)
        assertNull(app.finance.navigation.value.sourceLedgerIds)
        assertEquals(statistics,app.navigation.value.scope)
        assertFalse(app.finance.back())
    }

    @Test fun activityCreationRetainsSourceLedgerAcrossAmountAndTransferRoutes()=runTest {
        val repo=Repo();repo.state.value=repo.state.value!!.copy(ledgers=listOf(Ledger("home","主页"),Ledger("statistics","统计")))
        val app=AppController(repo,environment,backgroundScope);runCurrent()
        app.navigate {it.copy(homeScope=it.homeScope.copy(ledgerIds=setOf("home")),statisticsScope=it.statisticsScope.copy(ledgerIds=setOf("statistics")))}
        app.finance.selectTab("统计")
        app.finance.showActivities(ActivityScope(ledgerIds=setOf("statistics")))
        app.finance.open("金额")
        assertEquals(setOf("statistics"),app.finance.navigation.value.sourceLedgerIds)
        app.finance.back();app.finance.open("新增转账")
        assertEquals(setOf("statistics"),app.finance.navigation.value.sourceLedgerIds)
        app.beginEditor(app.finance.navigation.value.sourceLedgerIds?.singleOrNull())
        assertEquals("statistics",app.editor.value?.ledgerId)
        app.finance.selectTab("首页")
        assertNull(app.finance.navigation.value.sourceLedgerIds)
        assertEquals(setOf("home"),app.navigation.value.scope.ledgerIds)
    }
    @Test fun activitySummaryIsAnIndependentFullScopeProjection()=runTest {
        val app=AppController(Repo(),environment,backgroundScope);runCurrent()
        val query=ActivityScope(keyword="all rows")
        app.finance.loadActivities(query);runCurrent()
        assertEquals(query,app.finance.activitySummary.value.scope)
        assertEquals(2L,app.finance.activitySummary.value.summary?.activityCount)
        assertEquals(1,app.finance.activities.value.activities.size)
        app.finance.loadHome(ActivityScope(keyword="home"));runCurrent()
        assertEquals(query,app.finance.activitySummary.value.scope)
        app.resetTransientState();runCurrent();assertNull(app.finance.activitySummary.value.summary)
    }

}
