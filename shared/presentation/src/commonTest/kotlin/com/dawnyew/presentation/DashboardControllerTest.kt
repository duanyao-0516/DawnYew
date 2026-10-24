package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardControllerTest {
    private class Repo:LedgerRepository {
        override val state=MutableStateFlow<LedgerState?>(LedgerState("dataset"))
        var delayMillis=100L
        var calls=0
        var fail=false
        override suspend fun initialize()=Unit
        override suspend fun execute(command:Command,operationId:String,expectedVersion:Long?)=emptyList<String>()
        override suspend fun snapshot()=state.value!!
        override suspend fun replace(state:LedgerState) {this.state.value=state}
        override suspend fun close()=Unit
        override suspend fun accountHistory(request:AccountHistoryRequest):AccountHistorySnapshot {
            if(fail) throw DomainException(ErrorCode.STORAGE,"test failure")
            return AccountHistorySnapshot(state.value!!.datasetId,state.value!!.contentVersion,request,listOf(AccountHistoryPoint(request.startDate,100)))
        }
        override suspend fun panelStatistics(request:PanelRequest):PanelStatistics {
            calls++;if(fail) throw DomainException(ErrorCode.STORAGE,"test failure");val source=state.value!!
            withContext(NonCancellable) {delay(delayMillis)}
            return PanelStatistics(source.datasetId,source.contentVersion,request,listOf(PanelRow("result",request.scope.keyword,source.budgets.sumOf {it.amountMinor})))
        }
    }
    @Test fun panelsAreIndependentAndOldScopeAndResetResponsesCannotPublish()=runTest {
        val repo=Repo();val controller=DashboardController(repo,backgroundScope)
        controller.loadPanel(PanelRequest("daily",ActivityScope(keyword="old")));runCurrent()
        controller.loadPanel(PanelRequest("daily",ActivityScope(keyword="new")))
        controller.loadPanel(PanelRequest("category",ActivityScope(keyword="category")));runCurrent()
        advanceTimeBy(101);runCurrent()
        assertEquals("new",controller.panels.value["daily"]?.snapshot?.rows?.single()?.label)
        assertEquals("category",controller.panels.value["category"]?.snapshot?.rows?.single()?.label)
        controller.loadPanel(PanelRequest("daily",ActivityScope(keyword="reset")));runCurrent();controller.reset()
        advanceTimeBy(101);runCurrent();assertTrue(controller.panels.value.isEmpty())
    }
    @Test fun budgetChangesRefreshButPreferenceOnlyChangesDoNotRequery()=runTest {
        val repo=Repo().apply {delayMillis=0};val controller=DashboardController(repo,backgroundScope)
        val request=PanelRequest("budget_tracking",ActivityScope())
        controller.loadPanel(request);runCurrent();assertEquals(1,repo.calls)
        repo.state.value=repo.state.value!!.copy(contentVersion=2,preferences=listOf(UserPreference("theme","dark")))
        controller.refreshLoaded();runCurrent();assertEquals(1,repo.calls)
        assertEquals(2,controller.panels.value["budget_tracking"]?.snapshot?.contentVersion)
        repo.state.value=repo.state.value!!.copy(contentVersion=3,budgets=listOf(MonthlyBudget("ledger","2026-10",900)))
        controller.refreshLoaded();runCurrent();assertEquals(2,repo.calls)
        assertEquals(900,controller.panels.value["budget_tracking"]?.snapshot?.rows?.single()?.value)
    }
    @Test fun retryAfterRefreshFailureIsNotBlockedByRetainedSnapshot()=runTest {
        val repo=Repo().apply {delayMillis=0};val controller=DashboardController(repo,backgroundScope)
        val request=PanelRequest("daily",ActivityScope())
        val history=AccountHistoryRequest(startDate="2026-10-01",endDate="2026-10-02")
        controller.loadPanel(request);controller.loadHistory(history);runCurrent()
        repo.fail=true
        controller.loadPanel(request,true);controller.loadHistory(history,true);runCurrent()
        assertNotNull(controller.panels.value["daily"]?.snapshot);assertNotNull(controller.panels.value["daily"]?.error)
        assertNotNull(controller.history.value.snapshot);assertNotNull(controller.history.value.error)
        repo.fail=false
        controller.loadPanel(request);controller.loadHistory(history);runCurrent()
        assertNull(controller.panels.value["daily"]?.error);assertNull(controller.history.value.error)
        assertEquals(3,repo.calls)
    }

}
