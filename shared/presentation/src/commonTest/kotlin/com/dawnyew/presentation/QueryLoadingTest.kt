package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueryLoadingTest {
    private val october=QueryScope(startDate="2026-10-01",endDate="2026-10-31")
    private val november=QueryScope(startDate="2026-11-01",endDate="2026-11-30")
    private val entry=Entry("entry","ledger",EntryKind.EXPENSE,1234,"category","2026-10-18T12:00:00",createdAt="now",updatedAt="now",creationOperationId="op")
    private val data=LedgerState("dataset",ledgers=listOf(Ledger("ledger","日常")),categories=listOf(Category("category","ledger",Direction.EXPENSE,"餐饮")),entries=listOf(entry))
    private class Environment:DomainEnvironment {
        private var id=0
        override fun id()="operation-${id++}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=value
    }
    private class Repository(data:LedgerState):LedgerRepository {
        override val state=MutableStateFlow<LedgerState?>(data)
        val pageCalls=mutableListOf<Pair<QueryScope,EntryCursor?>>()
        val statisticsCalls=mutableListOf<QueryScope>()
        var pageGate:CompletableDeferred<Unit>?=null
        var statisticsGate:CompletableDeferred<Unit>?=null
        var pageFailure=false
        var statisticsFailure=false
        var ignoreCancellation=false
        var pageLocked=false
        var statisticsLocked=false
        override suspend fun initialize()=Unit
        override suspend fun execute(command:Command,operationId:String,expectedVersion:Long?):List<String> {
            if(command is Command.SetPreference) {
                val old=state.value!!
                state.value=old.copy(contentVersion=old.contentVersion+1,preferences=old.preferences.filterNot {it.key==command.key}+UserPreference(command.key,command.value))
            }
            return emptyList()
        }
        override suspend fun queryEntryPage(scope:QueryScope,cursor:EntryCursor?,limit:Int):EntryPage {
            val snapshot=state.value!!
            val gate=pageGate
            val locked=pageLocked
            pageCalls+=scope to cursor
            awaitQuery(gate,locked)
            if(pageFailure) error("fixture page failure")
            return pageSnapshot(snapshot,scope,cursor,limit)
        }
        override suspend fun statistics(scope:QueryScope):StatisticsSnapshot {
            val snapshot=state.value!!
            val gate=statisticsGate
            val locked=statisticsLocked
            statisticsCalls+=scope
            awaitQuery(gate,locked)
            if(statisticsFailure) error("fixture statistics failure")
            return Statistics.calculate(snapshot,scope)
        }
        private suspend fun awaitQuery(gate:CompletableDeferred<Unit>?,locked:Boolean) {
            suspend fun read() {
                gate?.await()
                if(locked) throw DomainException(ErrorCode.LOCKED,"fixture authorization expired")
            }
            if(ignoreCancellation) withContext(NonCancellable) {read()} else read()
        }
        override suspend fun snapshot()=state.value!!
        override suspend fun replace(state:LedgerState) {this.state.value=state}
        override suspend fun close()=Unit
    }

    @Test fun homeKeepsPeriodTrendAndTodaysGrossAmountsIndependentOfSearchAndStatistics()=runTest {
        val income=entry.copy(id="income",kind=EntryKind.INCOME,amountMinor=8000)
        val refund=entry.copy(id="refund",kind=EntryKind.REFUND,amountMinor=200,parentExpenseId=entry.id)
        val historic=entry.copy(id="historic",amountMinor=4567,occurrence="2026-09-15T12:00:00")
        val repo=Repository(data.copy(entries=listOf(entry,income,refund,historic)))
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.loadStatistics(october.copy(kinds=setOf(EntryKind.INCOME)));runCurrent()
        val selected=QueryScope(setOf("ledger"),"2026-09-01","2026-09-30",setOf(EntryKind.REFUND),setOf("missing"),"hidden",false)
        controller.loadHome(selected,"2026-10-18");runCurrent()
        val period=controller.homeStatistics.value.snapshot!!
        val today=controller.todayStatistics.value.snapshot!!
        assertEquals(homeStatisticsScope(selected),period.scope)
        assertEquals(4567L,period.netExpense)
        assertEquals("2026-09-15",period.trend.single {it.netExpense!=0L}.period)
        assertEquals(8000L,today.income)
        assertEquals(1234L,today.expense)
        assertEquals(200L,today.refunds)
        assertEquals(1034L,today.netExpense)
        assertEquals(todayStatisticsScope(selected,"2026-10-18"),today.scope)
        assertEquals(setOf(EntryKind.INCOME),controller.statistics.value.snapshot!!.scope.kinds)
        assertEquals(8000L,controller.statistics.value.snapshot!!.income)
    }

    @Test fun changingHomePeriodReusesTodayUntilTheLedgerOrLocalDateChanges()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.loadHome(october,"2026-10-18");runCurrent()
        controller.loadHome(november,"2026-10-18");runCurrent()
        assertEquals(3,repo.statisticsCalls.size)
        assertEquals(november,controller.homeStatistics.value.snapshot!!.scope)
        assertEquals("2026-10-18",controller.todayStatistics.value.snapshot!!.scope.startDate)
        controller.loadHome(november,"2026-10-19");runCurrent()
        assertEquals(4,repo.statisticsCalls.size)
        assertEquals("2026-10-19",controller.todayStatistics.value.snapshot!!.scope.startDate)
        controller.loadHome(november.copy(ledgerIds=setOf("ledger")),"2026-10-19");runCurrent()
        assertEquals(6,repo.statisticsCalls.size)
        assertEquals(setOf("ledger"),controller.todayStatistics.value.snapshot!!.scope.ledgerIds)
    }

    @Test fun externalReturnRetriesBothHomeQueriesAfterAuthorizationFailure()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.navigate {it.copy(homeScope=november)}
        repo.statisticsLocked=true
        controller.loadHome(november,"2026-10-18");runCurrent()
        assertNotNull(controller.homeStatistics.value.error)
        assertNotNull(controller.todayStatistics.value.error)
        repo.statisticsLocked=false
        controller.resumeVisibleQueries();runCurrent()
        assertEquals(4,repo.statisticsCalls.size)
        assertNull(controller.homeStatistics.value.error)
        assertNull(controller.todayStatistics.value.error)
        assertEquals(november,controller.homeStatistics.value.snapshot!!.scope)
        assertEquals("2026-10-18",controller.todayStatistics.value.snapshot!!.scope.startDate)
        assertNull(controller.statistics.value.snapshot)
    }

    @Test fun externalReturnRetriesOnlyFailedHomePeriodAndKeepsSuccessfulTodayCached()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.loadHome(october,"2026-10-18");runCurrent()
        controller.navigate {it.copy(homeScope=november)}
        repo.statisticsFailure=true
        controller.loadHome(november,"2026-10-18");runCurrent()
        assertNotNull(controller.homeStatistics.value.error)
        assertNull(controller.todayStatistics.value.error)
        repo.statisticsFailure=false
        controller.resumeVisibleQueries();runCurrent()
        assertEquals(4,repo.statisticsCalls.size)
        assertNull(controller.homeStatistics.value.error)
        assertEquals(november,controller.homeStatistics.value.snapshot!!.scope)
        assertEquals(1234L,controller.todayStatistics.value.snapshot!!.expense)
        controller.resumeVisibleQueries();runCurrent()
        assertEquals(4,repo.statisticsCalls.size)
    }

    @Test fun externalReturnReplacesBothPendingHomeReadsAndIgnoresLateAuthorizationErrors()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.navigate {it.copy(homeScope=november)}
        repo.ignoreCancellation=true;repo.statisticsLocked=true;repo.statisticsGate=CompletableDeferred()
        val oldGate=repo.statisticsGate!!
        controller.loadHome(november,"2026-10-18");runCurrent()
        assertTrue(controller.homeStatistics.value.loading)
        assertTrue(controller.todayStatistics.value.loading)
        repo.statisticsLocked=false;repo.statisticsGate=null
        controller.resumeVisibleQueries();runCurrent()
        assertFalse(controller.homeStatistics.value.loading)
        assertFalse(controller.todayStatistics.value.loading)
        oldGate.complete(Unit);runCurrent()
        assertNull(controller.homeStatistics.value.error)
        assertNull(controller.todayStatistics.value.error)
        assertEquals(november,controller.homeStatistics.value.snapshot!!.scope)
        assertEquals(1234L,controller.todayStatistics.value.snapshot!!.expense)
        assertEquals(4,repo.statisticsCalls.size)
    }

    @Test fun replacingResettingOrClosingTheDatasetClearsBothHomeResultsAndCaches()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.loadHome(october,"2026-10-18");runCurrent()
        repo.state.value=data.copy(datasetId="replacement",entries=emptyList());runCurrent()
        assertNull(controller.homeStatistics.value.snapshot)
        assertNull(controller.todayStatistics.value.snapshot)
        controller.loadHome(october,"2026-10-18");runCurrent()
        assertEquals("replacement",controller.homeStatistics.value.snapshot!!.datasetId)
        assertEquals(0L,controller.todayStatistics.value.snapshot!!.expense)
        controller.resetTransientState()
        assertNull(controller.homeStatistics.value.snapshot)
        assertNull(controller.todayStatistics.value.snapshot)
        assertEquals("首页",controller.navigation.value.page)
        controller.loadHome(october,"2026-10-18");runCurrent()
        assertEquals(6,repo.statisticsCalls.size)
        repo.state.value=null;runCurrent()
        assertNull(controller.homeStatistics.value.snapshot)
        assertNull(controller.todayStatistics.value.snapshot)
    }

    @Test fun changingPeriodKeepsCompletedRowsUntilReplacementIsReady()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.pageGate=CompletableDeferred()
        controller.loadEntries(november);runCurrent()
        assertEquals(listOf(entry),controller.entries.value.entries)
        assertTrue(controller.entries.value.loading)
        assertTrue(controller.entries.value.loaded)
        repo.pageGate!!.complete(Unit);runCurrent()
        assertTrue(controller.entries.value.entries.isEmpty())
        assertFalse(controller.entries.value.loading)
        assertEquals(november,controller.entries.value.scope)
    }

    @Test fun refreshFailurePreservesCompletedRowsAndReportsTheFailure()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.pageFailure=true
        controller.loadEntries(october,force=true);runCurrent()
        assertEquals(listOf(entry),controller.entries.value.entries)
        assertTrue(controller.entries.value.loaded)
        assertNotNull(controller.entries.value.error)
        assertFalse(controller.entries.value.loading)
    }

    @Test fun preferenceOnlyUpdatesReuseCachedPagesWithoutAnotherQuery()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.state.value=data.copy(contentVersion=2,preferences=listOf(UserPreference("lastPeriod","2026-11")))
        runCurrent()
        controller.loadEntries(october);runCurrent()
        assertEquals(1,repo.pageCalls.size)
        assertEquals(2L,controller.entries.value.contentVersion)
        assertEquals(listOf(entry),controller.entries.value.entries)
    }

    @Test fun olderPeriodCannotReplaceTheLatestCompletedPage()=runTest {
        val repo=Repository(data)
        repo.ignoreCancellation=true
        repo.pageGate=CompletableDeferred()
        val oldGate=repo.pageGate!!
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.pageGate=null
        controller.loadEntries(november);runCurrent()
        oldGate.complete(Unit);runCurrent()
        assertEquals(november,controller.entries.value.scope)
        assertTrue(controller.entries.value.entries.isEmpty())
    }

    @Test fun firstPageFailureRemainsAnErrorWithoutClaimingAnEmptyLoadedPage()=runTest {
        val repo=Repository(data)
        repo.pageFailure=true
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        assertFalse(controller.entries.value.loaded)
        assertNotNull(controller.entries.value.error)
        assertFalse(controller.entries.value.loading)
    }
    @Test fun statisticsKeepTheDisplayedPeriodUntilTheRequestedPeriodCompletes()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadStatistics(october);runCurrent()
        repo.statisticsGate=CompletableDeferred()
        controller.loadStatistics(november);runCurrent()
        val updating=controller.statistics.value
        assertEquals(november,updating.scope)
        assertEquals(october,updating.displayedScope)
        assertEquals(1234L,updating.snapshot!!.netExpense)
        assertTrue(updating.refreshing)
        repo.statisticsGate!!.complete(Unit);runCurrent()
        assertEquals(november,controller.statistics.value.displayedScope)
        assertEquals(0L,controller.statistics.value.snapshot!!.netExpense)
        assertFalse(controller.statistics.value.loading)
    }

    @Test fun returningToCachedStatisticsAndPreferenceUpdatesDoNotStartLoading()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadStatistics(october);runCurrent()
        controller.loadStatistics(november);runCurrent()
        repo.state.value=data.copy(contentVersion=2,preferences=listOf(UserPreference("lastPeriod","2026-11")))
        runCurrent()
        controller.loadStatistics(october)
        assertFalse(controller.statistics.value.loading)
        assertEquals(october,controller.statistics.value.displayedScope)
        assertEquals(2L,controller.statistics.value.snapshot!!.contentVersion)
        runCurrent()
        assertEquals(listOf(october,november),repo.statisticsCalls)
    }

    @Test fun statisticsFailureRetainsContentButInitialFailureDoesNotInventZeroTotals()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        repo.statisticsFailure=true
        controller.loadStatistics(october);runCurrent()
        assertNull(controller.statistics.value.snapshot)
        assertNotNull(controller.statistics.value.error)
        assertFalse(controller.statistics.value.loading)
        repo.statisticsFailure=false
        controller.loadStatistics(october,force=true);runCurrent()
        repo.statisticsFailure=true
        controller.loadStatistics(november);runCurrent()
        assertEquals(october,controller.statistics.value.displayedScope)
        assertEquals(1234L,controller.statistics.value.snapshot!!.netExpense)
        assertNotNull(controller.statistics.value.error)
        assertFalse(controller.statistics.value.loading)
    }

    @Test fun olderStatisticsCannotReplaceTheLatestCompletedPeriod()=runTest {
        val repo=Repository(data)
        repo.ignoreCancellation=true
        repo.statisticsGate=CompletableDeferred()
        val oldGate=repo.statisticsGate!!
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadStatistics(october);runCurrent()
        repo.statisticsGate=null
        controller.loadStatistics(november);runCurrent()
        oldGate.complete(Unit);runCurrent()
        assertEquals(november,controller.statistics.value.scope)
        assertEquals(november,controller.statistics.value.snapshot!!.scope)
        assertEquals(0L,controller.statistics.value.snapshot!!.netExpense)
    }

    @Test fun changedLedgerContentInvalidatesBothQueryCaches()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        val changed=entry.copy(amountMinor=4567,revision=2)
        repo.state.value=data.copy(contentVersion=2,entries=listOf(changed));runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        assertEquals(2,repo.pageCalls.size)
        assertEquals(2,repo.statisticsCalls.size)
        assertEquals(4567L,controller.entries.value.entries.single().amountMinor)
        assertEquals(4567L,controller.statistics.value.snapshot!!.netExpense)
    }

    @Test fun dataChangesDuringReadCannotPublishAnOldVersion()=runTest {
        val repo=Repository(data)
        repo.pageGate=CompletableDeferred();repo.statisticsGate=CompletableDeferred()
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        repo.state.value=data.copy(contentVersion=2,entries=listOf(entry.copy(amountMinor=4567,revision=2)))
        repo.pageGate!!.complete(Unit);repo.statisticsGate!!.complete(Unit);runCurrent()
        assertEquals(2L,controller.entries.value.contentVersion)
        assertEquals(4567L,controller.entries.value.entries.single().amountMinor)
        assertEquals(2L,controller.statistics.value.snapshot!!.contentVersion)
        assertEquals(4567L,controller.statistics.value.snapshot!!.netExpense)
    }

    @Test fun replacingOrClosingDatasetDropsOldVisibleContentAndPendingResults()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        repo.state.value=data.copy(datasetId="replacement",entries=emptyList());runCurrent()
        assertTrue(controller.entries.value.entries.isEmpty())
        assertNull(controller.statistics.value.snapshot)
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        assertEquals("replacement",controller.entries.value.datasetId)
        assertEquals("replacement",controller.statistics.value.snapshot!!.datasetId)
        repo.state.value=null;runCurrent()
        assertFalse(controller.entries.value.loaded)
        assertNull(controller.statistics.value.snapshot)
    }

    @Test fun selectorPreferenceSaveDoesNotPublishAnUnrelatedSavedMessage()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.selectScope(november)
        runCurrent()
        assertNull(controller.ui.value.message)
        assertFalse(controller.ui.value.savingPreference)
        assertFalse(controller.ui.value.busy)
    }

    @Test fun preferenceUpdateDoesNotCancelAnExplicitStatisticsRefresh()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadStatistics(october);runCurrent()
        repo.statisticsGate=CompletableDeferred()
        controller.loadStatistics(october,force=true);runCurrent()
        repo.state.value=data.copy(contentVersion=2,preferences=listOf(UserPreference("theme","dark")))
        runCurrent()
        controller.loadStatistics(october)
        assertTrue(controller.statistics.value.loading)
        repo.statisticsFailure=true
        repo.statisticsGate!!.complete(Unit);runCurrent()
        assertNotNull(controller.statistics.value.error)
    }

    @Test fun preferenceUpdateDoesNotCancelAnInFlightNextPage()=runTest {
        val many=data.copy(entries=(1..51).map {entry.copy(id="00000000-0000-0000-0000-${it.toString().padStart(12,'0')}")})
        val repo=Repository(many)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.pageGate=CompletableDeferred()
        controller.loadEntries(october,append=true);runCurrent()
        repo.state.value=many.copy(contentVersion=2,preferences=listOf(UserPreference("theme","dark")))
        runCurrent()
        controller.loadEntries(october)
        assertTrue(controller.entries.value.loading)
        repo.pageGate!!.complete(Unit);runCurrent()
        assertEquals(51,controller.entries.value.entries.size)
        assertEquals(51,controller.entries.value.entries.map {it.id}.distinct().size)
        assertEquals(2L,controller.entries.value.contentVersion)
    }

    @Test fun preferenceOnlyUpdateRebasesTheCursorBeforeLoadingTheNextPage()=runTest {
        val many=data.copy(entries=(1..51).map {entry.copy(id="00000000-0000-0000-0000-${it.toString().padStart(12,'0')}")})
        val repo=Repository(many)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.state.value=many.copy(contentVersion=2,preferences=listOf(UserPreference("theme","dark")))
        runCurrent()
        controller.loadEntries(october,append=true);runCurrent()
        assertEquals(2L,repo.pageCalls.last().second!!.contentVersion)
        assertEquals(51,controller.entries.value.entries.size)
        assertEquals(2,repo.pageCalls.size)
    }

    @Test fun budgetChangesKeepQueryResultsWhileCurrentStateCarriesTheNewBudget()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        val budget=MonthlyBudget("ledger","2026-10",99900)
        repo.state.value=data.copy(contentVersion=2,budgets=listOf(budget));runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        assertEquals(listOf(budget),controller.ui.value.dataset!!.budgets)
        assertEquals(1,repo.pageCalls.size)
        assertEquals(1,repo.statisticsCalls.size)
        assertEquals(2L,controller.statistics.value.snapshot!!.contentVersion)
    }

    @Test fun failedContentRefreshDoesNotRebaseAnOldCursorToChangedFinancialData()=runTest {
        val many=data.copy(entries=(1..51).map {entry.copy(id="00000000-0000-0000-0000-${it.toString().padStart(12,'0')}")})
        val repo=Repository(many)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        repo.state.value=many.copy(contentVersion=2,entries=many.entries.drop(1));runCurrent()
        repo.pageFailure=true
        controller.loadEntries(october);runCurrent()
        repo.state.value=repo.state.value!!.copy(contentVersion=3,preferences=listOf(UserPreference("theme","dark")));runCurrent()
        repo.pageFailure=false
        controller.loadEntries(october,append=true);runCurrent()
        assertNull(repo.pageCalls.last().second)
        assertEquals(50,controller.entries.value.entries.size)
        assertEquals(3L,controller.entries.value.contentVersion)
    }

    @Test fun externalReturnRetriesTheVisibleDetailsAfterAuthorizationFailure()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.navigate {it.copy(page="明细",detailsScope=november)}
        repo.pageLocked=true
        controller.loadEntries(november);runCurrent()
        assertNotNull(controller.entries.value.error)
        repo.pageLocked=false
        controller.resumeVisibleQueries();runCurrent()
        assertEquals(2,repo.pageCalls.size)
        assertNull(controller.entries.value.error)
        assertTrue(controller.entries.value.loaded)
        assertEquals(november,controller.entries.value.contentScope)
        assertTrue(repo.statisticsCalls.isEmpty())
    }

    @Test fun externalReturnRetriesTheVisibleStatisticsAfterAuthorizationFailure()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.navigate {it.copy(page="统计",statisticsScope=november)}
        repo.statisticsLocked=true
        controller.loadStatistics(november);runCurrent()
        assertNotNull(controller.statistics.value.error)
        repo.statisticsLocked=false
        controller.resumeVisibleQueries();runCurrent()
        assertEquals(2,repo.statisticsCalls.size)
        assertNull(controller.statistics.value.error)
        assertEquals(november,controller.statistics.value.snapshot!!.scope)
        assertTrue(repo.pageCalls.isEmpty())
    }

    @Test fun externalReturnReplacesPendingDetailsAndIgnoresItsLateLockedFailure()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);runCurrent()
        controller.navigate {it.copy(page="明细",detailsScope=november)}
        repo.ignoreCancellation=true;repo.pageLocked=true;repo.pageGate=CompletableDeferred()
        val oldGate=repo.pageGate!!
        controller.loadEntries(november);runCurrent()
        assertTrue(controller.entries.value.loading)
        repo.pageLocked=false;repo.pageGate=null
        controller.resumeVisibleQueries();runCurrent()
        assertFalse(controller.entries.value.loading)
        assertEquals(november,controller.entries.value.contentScope)
        oldGate.complete(Unit);runCurrent()
        assertNull(controller.entries.value.error)
        assertEquals(november,controller.entries.value.contentScope)
        assertEquals(3,repo.pageCalls.size)
    }

    @Test fun externalReturnReplacesPendingStatisticsAndIgnoresItsLateLockedFailure()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadStatistics(october);runCurrent()
        controller.navigate {it.copy(page="统计",statisticsScope=november)}
        repo.ignoreCancellation=true;repo.statisticsLocked=true;repo.statisticsGate=CompletableDeferred()
        val oldGate=repo.statisticsGate!!
        controller.loadStatistics(november);runCurrent()
        assertTrue(controller.statistics.value.loading)
        repo.statisticsLocked=false;repo.statisticsGate=null
        controller.resumeVisibleQueries();runCurrent()
        assertFalse(controller.statistics.value.loading)
        assertEquals(november,controller.statistics.value.snapshot!!.scope)
        oldGate.complete(Unit);runCurrent()
        assertNull(controller.statistics.value.error)
        assertEquals(november,controller.statistics.value.snapshot!!.scope)
        assertEquals(3,repo.statisticsCalls.size)
    }

    @Test fun externalReturnKeepsSuccessfulVisibleQueriesInCache()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.loadEntries(october);controller.loadStatistics(october);runCurrent()
        controller.navigate {it.copy(page="明细",detailsScope=october)}
        controller.resumeVisibleQueries();runCurrent()
        assertFalse(controller.entries.value.loading)
        controller.navigate {it.copy(page="统计",statisticsScope=october)}
        controller.resumeVisibleQueries();runCurrent()
        assertFalse(controller.statistics.value.loading)
        assertEquals(1,repo.pageCalls.size)
        assertEquals(1,repo.statisticsCalls.size)
    }

    @Test fun externalReturnDoesNotStartQueriesForEditorOrSettingsPages()=runTest {
        val repo=Repository(data)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.navigate {it.copy(page="记一笔")}
        controller.resumeVisibleQueries();runCurrent()
        controller.navigate {it.copy(page="设置")}
        controller.resumeVisibleQueries();runCurrent()
        assertTrue(repo.pageCalls.isEmpty())
        assertTrue(repo.statisticsCalls.isEmpty())
    }

    @Test fun externalReturnRestartsAFailedNextPageFromTheFirstPageWithoutDuplicateRows()=runTest {
        val many=data.copy(entries=(1..51).map {entry.copy(id="00000000-0000-0000-0000-${it.toString().padStart(12,'0')}")})
        val repo=Repository(many)
        val controller=AppController(repo,Environment(),backgroundScope)
        runCurrent()
        controller.navigate {it.copy(page="明细",detailsScope=october)}
        controller.loadEntries(october);runCurrent()
        repo.pageLocked=true
        controller.loadEntries(october,append=true);runCurrent()
        assertNotNull(controller.entries.value.error)
        repo.pageLocked=false
        controller.resumeVisibleQueries();runCurrent()
        assertNull(controller.entries.value.error)
        assertEquals(50,controller.entries.value.entries.size)
        assertNull(repo.pageCalls.last().second)
        controller.loadEntries(october,append=true);runCurrent()
        assertEquals(51,controller.entries.value.entries.size)
        assertEquals(51,controller.entries.value.entries.map {it.id}.distinct().size)
    }

}
