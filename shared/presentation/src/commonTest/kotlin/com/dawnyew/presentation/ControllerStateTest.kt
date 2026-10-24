package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerStateTest {
    private fun AppController.change(event:EditorChange)=changeEditor(editor.value!!.operationId,event)
    private val data=LedgerState("dataset",ledgers=listOf(Ledger("l1","日常")),categories=listOf(Category("c1","l1",Direction.EXPENSE,"餐饮")))
    private class Environment:DomainEnvironment {
        private var next=0
        override fun id()="operation-${next++}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=value
    }
    private class Repository(data:LedgerState):LedgerRepository {
        override val state=MutableStateFlow<LedgerState?>(data)
        override val pendingOperation=MutableStateFlow<PendingOperationRecovery?>(null)
        var outcome:OperationOutcome?=null
        val acknowledgements=mutableListOf<String>()
        var acknowledgementFailure=false
        var acknowledgementGate:CompletableDeferred<Unit>?=null
        override suspend fun lookupOperation(operationId:String)=outcome
        override suspend fun acknowledgeOperation(operationId:String) {
            acknowledgementGate?.await()
            if(acknowledgementFailure) throw IllegalStateException("fixture acknowledgement failure")
            acknowledgements+=operationId
            if(pendingOperation.value?.operationId==operationId) pendingOperation.value=null
        }
        val calls=mutableListOf<Pair<Command,String>>()
        val pageCalls=mutableListOf<EntryCursor?>()
        val pages=ArrayDeque<EntryPage>()
        var pageConflict=false
        var duplicateResult=CaptureDuplicateSummary(false,false,false)
        var duplicateGate:CompletableDeferred<Unit>?=null
        var duplicateFailure=false
        val duplicateCalls=mutableListOf<Triple<String?,Long?,String?>>()
        var failure=false
        var gate:CompletableDeferred<Unit>?=null
        override suspend fun initialize()=Unit
        override suspend fun execute(command:Command,operationId:String,expectedVersion:Long?):List<String> {
            calls+=command to operationId;gate?.await()
            if(failure) throw IllegalStateException("fixture failure")
            if(command is Command.SetPreference) {
                val old=state.value!!
                state.value=old.copy(contentVersion=old.contentVersion+1,preferences=old.preferences.filterNot {it.key==command.key}+UserPreference(command.key,command.value))
            }
            return listOf("entry")
        }
        override suspend fun findCaptureDuplicates(fingerprint:String?,amountMinor:Long?,localDate:String?):CaptureDuplicateSummary {
            duplicateCalls+=Triple(fingerprint,amountMinor,localDate)
            duplicateGate?.await()
            if(duplicateFailure) throw IllegalStateException("fixture duplicate lookup failure")
            return duplicateResult
        }
        override suspend fun queryEntryPage(scope:QueryScope,cursor:EntryCursor?,limit:Int):EntryPage {
            pageCalls+=cursor
            if(pageConflict) {pageConflict=false;conflict("fixture stale cursor")}
            return if(pages.isNotEmpty()) pages.removeFirst() else EntryPage(emptyList(),null,state.value!!.datasetId,state.value!!.contentVersion)
        }
        override suspend fun statistics(scope:QueryScope)=Statistics.calculate(state.value!!,scope)
        override suspend fun snapshot()=state.value!!
        override suspend fun replace(state:LedgerState) {this.state.value=state}
        override suspend fun close()=Unit
    }
    @Test fun controllerKeepsWholeDraftForNewObserverButNewProcessStartsEmpty()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1",capture=CaptureDraftUi("12.34",note="待确认",engineVersion="v1"))
        runCurrent();controller.change(EditorChange.Note("已修改"));controller.change(EditorChange.Confirmed(true))
        val newObserver=controller.editor.value!!
        assertEquals("已修改",newObserver.note)
        assertTrue(newObserver.confirmed)
        assertEquals("v1",newObserver.capture?.engineVersion)
        assertNull(AppController(repo,Environment(),backgroundScope).editor.value)
    }
    @Test fun failedSavePreservesInputAndReusesOperationIdOnRetry()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1");controller.change(EditorChange.Amount("12.34"));controller.change(EditorChange.Note("午餐"))
        val id=controller.editor.value!!.operationId;repo.failure=true
        controller.saveEditor(false) {};runCurrent()
        assertEquals("午餐",controller.editor.value?.note)
        assertFalse(controller.ui.value.busy)
        repo.failure=false;controller.saveEditor(false) {};runCurrent()
        assertEquals(listOf(id,id),repo.calls.map {it.second})
        assertNull(controller.editor.value)
    }
    @Test fun doubleClickCannotSubmitTwiceAndStaleCompletionCannotClearNewCapture()=runTest {
        val repo=Repository(data);repo.gate=CompletableDeferred()
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1");controller.change(EditorChange.Amount("12.34"))
        controller.saveEditor(false) {};controller.saveEditor(false) {};runCurrent()
        assertEquals(1,repo.calls.size)
        controller.beginEditor("l1",capture=CaptureDraftUi("56.78"))
        val newId=controller.editor.value!!.operationId
        repo.gate!!.complete(Unit);runCurrent()
        assertEquals(newId,controller.editor.value?.operationId)
        assertEquals("56.78",controller.editor.value?.amount)
        assertFalse(controller.editor.value!!.confirmed)
    }
    @Test fun replacementClearsEditorAndNavigationTogether()=runTest {
        val controller=AppController(Repository(data),Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1",capture=CaptureDraftUi("12.34"))
        controller.resetTransientState()
        assertNull(controller.editor.value)
        assertEquals("首页",controller.navigation.value.page)
    }
    @Test fun invalidStatisticsIsErrorAndNeverEmptyContent() {
        assertIs<StatisticsView.Error>(statisticsView(data,QueryScope(startDate="2026-02-30",endDate="2026-03-01")))
        assertIs<StatisticsView.Loading>(statisticsView(null,QueryScope()))
    }
    @Test fun entryPagingPassesCursorAndConflictRefreshesWithoutDuplicateRows()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        val query=QueryScope(startDate="2026-10-01",endDate="2026-10-31")
        val entry=Entry("e1","l1",EntryKind.EXPENSE,100,"c1","2026-10-18T12:00:00",createdAt="now",updatedAt="now",creationOperationId="op1")
        val cursor=EntryCursor(entry.occurrence,entry.id,data.datasetId,data.contentVersion,query)
        repo.pages.addLast(EntryPage(listOf(entry),cursor,data.datasetId,data.contentVersion))
        controller.loadEntries(query);runCurrent()
        assertEquals(listOf(entry),controller.entries.value.entries)
        repo.pageConflict=true
        repo.pages.addLast(EntryPage(listOf(entry),null,data.datasetId,data.contentVersion))
        controller.loadEntries(query,append=true);runCurrent()
        assertEquals(listOf(null,cursor,null),repo.pageCalls)
        assertEquals(listOf(entry),controller.entries.value.entries)
        assertFalse(controller.entries.value.loading)
    }
    @Test fun duplicateWarningRequiresSeparateAcknowledgementButDoesNotDeleteOrBlockConfirmedSave()=runTest {
        val repo=Repository(data);repo.duplicateResult=CaptureDuplicateSummary(true,true,true);repo.duplicateGate=CompletableDeferred()
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1",capture=CaptureDraftUi("12.34","2026-10-17T12:00:00",fingerprint="image-hash"))
        assertTrue(controller.editor.value!!.duplicateCheckPending)
        controller.saveEditor(false) {};runCurrent();assertTrue(repo.calls.isEmpty())
        repo.duplicateGate!!.complete(Unit);runCurrent()
        assertTrue(controller.editor.value!!.capture!!.duplicateWarning)
        assertEquals(3,controller.editor.value!!.duplicateMessages.size)
        assertEquals(Triple("image-hash",1234L,"2026-10-17"),repo.duplicateCalls.single())
        controller.change(EditorChange.Confirmed(true));controller.saveEditor(false) {};runCurrent()
        assertTrue(repo.calls.isEmpty())
        controller.change(EditorChange.DuplicateAcknowledged(true));controller.saveEditor(false) {};runCurrent()
        assertEquals(1,repo.calls.size)
        assertNull(controller.editor.value)
    }
    @Test fun staleInputEventCannotReplaceTheCurrentEditorIdentity()=runTest {
        val controller=AppController(Repository(data),Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1");val old=controller.editor.value!!
        controller.beginEditor("l1");val current=controller.editor.value!!
        controller.changeEditor(old.operationId,EditorChange.Note("late keyboard event"))
        assertEquals(current.operationId,controller.editor.value!!.operationId)
        assertEquals("",controller.editor.value!!.note)
    }
    @Test fun duplicateCheckFailureKeepsDraftAndCanBeRetried()=runTest {
        val repo=Repository(data);repo.duplicateFailure=true
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1",capture=CaptureDraftUi("12.34"));runCurrent()
        assertNotNull(controller.editor.value!!.duplicateCheckError)
        controller.change(EditorChange.Confirmed(true));controller.saveEditor(false) {};runCurrent()
        assertTrue(repo.calls.isEmpty())
        repo.duplicateFailure=false;controller.retryCaptureDuplicateCheck();runCurrent()
        assertNull(controller.editor.value!!.duplicateCheckError)
        assertFalse(controller.editor.value!!.duplicateCheckPending)
    }
    @Test fun postCommitUiFailureIsNotReportedAsFailedSave()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.perform(Command.CreateLedger("新账本")) {throw IllegalStateException("fixture UI failure")};runCurrent()
        assertEquals(1,repo.calls.size)
        assertTrue(controller.ui.value.message!!.startsWith("数据已保存"))
        assertTrue(repo.acknowledgements.isEmpty())
        assertNotNull(controller.pendingOperation.value?.outcome)
        assertFalse(controller.ui.value.busy)
    }
    @Test fun saveAndContinueRetainsIncomeLedgerAndSelectedCategory()=runTest {
        val incomeData=data.copy(categories=data.categories+Category("income","l1",Direction.INCOME,"工资"))
        val repo=Repository(incomeData);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1")
        controller.change(EditorChange.Kind(EntryKind.INCOME));controller.change(EditorChange.Category("income"));controller.change(EditorChange.Amount("123.45"));controller.change(EditorChange.Note("奖金"))
        val old=controller.editor.value!!
        controller.saveEditor(true) {};runCurrent()
        val next=controller.editor.value!!
        assertEquals(EntryKind.INCOME,next.kind)
        assertEquals("income",next.categoryId)
        assertEquals("l1",next.ledgerId)
        assertNotEquals(old.operationId,next.operationId)
        assertEquals("",next.amount);assertEquals("",next.note)
        assertNull(next.capture);assertFalse(next.dirty)
    }
    @Test fun staleRenderedNoteChangeCannotRestorePendingAfterDuplicateResult()=runTest {
        val repo=Repository(data);repo.duplicateGate=CompletableDeferred();repo.duplicateResult=CaptureDuplicateSummary(true,false,false)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1",capture=CaptureDraftUi("12.34",fingerprint="image-hash"));runCurrent()
        val rendered=controller.editor.value!!
        assertTrue(rendered.duplicateCheckPending)
        repo.duplicateGate!!.complete(Unit);runCurrent()
        assertFalse(controller.editor.value!!.duplicateCheckPending)
        controller.changeEditor(rendered.operationId,EditorChange.Note("typed before recomposition"))
        val result=controller.editor.value!!
        assertEquals("typed before recomposition",result.note)
        assertFalse(result.duplicateCheckPending)
        assertTrue(result.capture!!.duplicateWarning)
        assertEquals(1,result.duplicateMessages.size)
    }

    @Test fun laterFieldEventsCannotEraseInFlightCheckOrAnotherFieldChange()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1",capture=CaptureDraftUi("12.34"));runCurrent()
        val rendered=controller.editor.value!!
        repo.duplicateGate=CompletableDeferred()
        controller.changeEditor(rendered.operationId,EditorChange.Amount("56.78"))
        controller.changeEditor(rendered.operationId,EditorChange.Note("same rendered frame"))
        controller.changeEditor(rendered.operationId,EditorChange.Confirmed(true))
        advanceTimeBy(150);runCurrent()
        assertEquals("56.78",controller.editor.value!!.amount)
        assertTrue(controller.editor.value!!.duplicateCheckPending)
        assertFalse(controller.editor.value!!.confirmed)
        repo.duplicateGate!!.complete(Unit);runCurrent()
        assertEquals(5678L,repo.duplicateCalls.last().second)
        assertEquals("same rendered frame",controller.editor.value!!.note)
        assertFalse(controller.editor.value!!.duplicateCheckPending)
    }
    @Test fun continuationDropsInvalidSelectionsWithoutRetainingSensitiveFields() {
        val selected=EditorDraft.create(data,"first","2026-10-18T10:00:00","l1").copy(amount="12.34",note="secret")
        val disabled=data.copy(categories=data.categories.map {it.copy(enabled=false)})
        val next=selected.continuation(disabled,"next","2026-10-18T12:00:00")
        assertEquals("l1",next.ledgerId);assertNull(next.categoryId)
        val archived=selected.continuation(data.copy(ledgers=data.ledgers.map {it.copy(archived=true)}),"next","2026-10-18T12:00:00")
        assertNull(archived.ledgerId);assertNull(archived.categoryId)
        assertEquals("",archived.amount);assertEquals("",archived.note)
    }

    @Test fun successfulCallbackRunsBeforeDurableOperationAcknowledgement()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        var callbackRan=false
        controller.perform(Command.CreateLedger("新账本"),"pending-op") {
            assertTrue(repo.acknowledgements.isEmpty());callbackRan=true
        }
        runCurrent()
        assertTrue(callbackRan)
        assertEquals(listOf("pending-op"),repo.acknowledgements)
    }
    @Test fun unconfirmedRecoveryBlocksDifferentOperationButAllowsSameIdRetry()=runTest {
        val repo=Repository(data);repo.pendingOperation.value=PendingOperationRecovery("pending-op",null)
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.perform(Command.CreateLedger("不应保存"),"new-op");runCurrent()
        assertTrue(repo.calls.isEmpty());assertTrue(repo.acknowledgements.isEmpty())
        controller.perform(Command.CreateLedger("同一操作重试"),"pending-op");runCurrent()
        assertEquals(listOf("pending-op"),repo.calls.map {it.second})
    }

    @Test fun startupRecoveryRemainsUntilExplicitAcknowledgement()=runTest {
        val repo=Repository(data);val recovery=PendingOperationRecovery("pending-op",OperationOutcome("pending-op","SaveEntry",listOf("saved-entry")))
        repo.pendingOperation.value=recovery
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        assertEquals(recovery,controller.pendingOperation.value)
        assertTrue(repo.acknowledgements.isEmpty())
        controller.acknowledgePendingOperation("stale-dialog");runCurrent()
        assertEquals(recovery,controller.pendingOperation.value)
        controller.acknowledgePendingOperation("pending-op");runCurrent()
        assertEquals(listOf("pending-op"),repo.acknowledgements)
        assertNull(controller.pendingOperation.value)
    }
    @Test fun failedAcknowledgementKeepsRecoveryVisibleAndCommittedEditorIsClearedOnlyAfterConfirmation()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1");controller.change(EditorChange.Amount("12.34"))
        val operationId=controller.editor.value!!.operationId
        repo.pendingOperation.value=PendingOperationRecovery(operationId,OperationOutcome(operationId,"SaveEntry",listOf("entry")));runCurrent()
        repo.acknowledgementFailure=true
        controller.acknowledgePendingOperation(operationId);runCurrent()
        assertNotNull(controller.pendingOperation.value)
        assertNotNull(controller.editor.value)
        assertFalse(controller.ui.value.busy)
        repo.acknowledgementFailure=false
        controller.acknowledgePendingOperation(operationId);runCurrent()
        assertNull(controller.pendingOperation.value)
        assertNull(controller.editor.value)
        assertEquals("首页",controller.navigation.value.page)
        assertEquals("首页",controller.navigation.value.tab)
    }
    @Test fun failedNormalAcknowledgementDoesNotReportFailedCommitOrAllowNextWrite()=runTest {
        val repo=Repository(data);repo.acknowledgementFailure=true
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.perform(Command.CreateLedger("账本"),"saved-op");runCurrent()
        assertNotNull(controller.pendingOperation.value!!.outcome)
        assertTrue(controller.ui.value.message!!.startsWith("数据已保存"))
        controller.perform(Command.CreateLedger("第二次"),"next-op");runCurrent()
        assertEquals(1,repo.calls.size)
    }
    @Test fun acknowledgementInFlightKeepsSavingBusyAndDoesNotSubmitAgain()=runTest {
        val repo=Repository(data);repo.acknowledgementGate=CompletableDeferred()
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.perform(Command.CreateLedger("账本"),"saved-op");runCurrent()
        assertTrue(controller.ui.value.busy)
        controller.perform(Command.CreateLedger("第二次"),"next-op");runCurrent()
        assertEquals(1,repo.calls.size)
        repo.acknowledgementGate!!.complete(Unit);runCurrent()
        assertFalse(controller.ui.value.busy)
        assertEquals(listOf("saved-op"),repo.acknowledgements)
    }

    @Test fun initialDatasetRestoresValidLedgerAndPeriodButIgnoresStaleReferences()=runTest {
        val restored=data.copy(ledgers=data.ledgers+Ledger("l2","旅行"),preferences=listOf(UserPreference("lastLedgerId","l2"),UserPreference("lastPeriod","2026-04")))
        val controller=AppController(Repository(restored),Environment(),backgroundScope);runCurrent()
        assertEquals(setOf("l2"),controller.navigation.value.scope.ledgerIds)
        assertEquals("2026-04-01",controller.navigation.value.scope.startDate)
        assertEquals("2026-04-30",controller.navigation.value.scope.endDate)
        assertEquals(controller.navigation.value.homeScope,controller.navigation.value.detailsScope)
        assertEquals(controller.navigation.value.homeScope,controller.navigation.value.statisticsScope)
        val stale=AppController(Repository(restored.copy(preferences=listOf(UserPreference("lastLedgerId","missing"),UserPreference("lastPeriod","bad")))),Environment(),backgroundScope);runCurrent()
        assertEquals(setOf("l1"),stale.navigation.value.scope.ledgerIds)
        assertEquals("2026-10-01",stale.navigation.value.scope.startDate)
    }

    @Test fun homeRangePersistsAsOnePairAndStatisticsSelectionCannotOverwriteIt()=runTest {
        val repo=Repository(data.copy(preferences=listOf(UserPreference("lastPeriod","2026-04"))))
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        val home=controller.navigation.value.homeScope.copy(startDate="2026-09-15",endDate="2026-10-10")
        controller.selectScope(home);runCurrent()
        assertEquals(listOf(Command.SetPreference("lastHomeRange","2026-09-15/2026-10-10")),repo.calls.map {it.first})
        controller.finance.openStatistics("分类统计",home)
        controller.finance.showActivities(drillScope(home,period="2026-10").toActivityScope())
        assertTrue(controller.finance.back());assertEquals(home,controller.finance.navigation.value.sourceStatisticsScope)
        assertTrue(controller.finance.back());assertEquals(home,controller.navigation.value.homeScope)
        controller.finance.selectTab("统计")
        controller.selectScope(controller.navigation.value.statisticsScope.copy(startDate="2026-11-01",endDate="2026-11-30"));runCurrent()
        val restored=AppController(Repository(repo.state.value!!),Environment(),backgroundScope);runCurrent()
        assertEquals(home,restored.navigation.value.homeScope)
        assertEquals("2026-11-01",restored.navigation.value.statisticsScope.startDate)
        assertEquals("2026-11-30",restored.navigation.value.detailsScope.endDate)
        assertEquals(1,repo.calls.count {(it.first as? Command.SetPreference)?.key=="lastHomeRange"})
        val invalid=AppController(Repository(data.copy(preferences=listOf(UserPreference("lastPeriod","2026-04"),UserPreference("lastHomeRange","invalid")))),Environment(),backgroundScope);runCurrent()
        assertEquals("2026-04-01",invalid.navigation.value.homeScope.startDate)
        assertEquals("2026-04-30",invalid.navigation.value.homeScope.endDate)
    }

    @Test fun explicitSelectionsCoalesceAndWaitForMoneyOperationAcknowledgement()=runTest {
        val repo=Repository(data.copy(ledgers=data.ledgers+Ledger("l2","旅行")));repo.gate=CompletableDeferred()
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.beginEditor("l1");controller.change(EditorChange.Amount("12.34"))
        val entryOperation=controller.editor.value!!.operationId
        controller.saveEditor(false) {};runCurrent()
        controller.navigate {it.copy(page="明细")}
        controller.selectScope(controller.navigation.value.scope.copy(ledgerIds=setOf("l2"),startDate="2026-11-01",endDate="2026-11-30"))
        controller.selectScope(controller.navigation.value.scope.copy(startDate="2026-12-01",endDate="2026-12-31"))
        runCurrent();assertEquals(1,repo.calls.size)
        assertIs<Command.SaveEntry>(repo.calls.single().first)
        repo.gate!!.complete(Unit);runCurrent()
        assertEquals(listOf(Command.SetPreference("lastLedgerId","l2"),Command.SetPreference("lastPeriod","2026-12")),repo.calls.drop(1).map {it.first})
        assertEquals(entryOperation,repo.acknowledgements.first())
        assertEquals(3,repo.acknowledgements.size)
        assertEquals(setOf("l2"),controller.navigation.value.scope.ledgerIds)
        assertEquals("2026-12-01",controller.navigation.value.scope.startDate)
        controller.queryStatistics(controller.navigation.value.scope);controller.loadEntries(controller.navigation.value.scope);runCurrent()
        assertEquals(3,repo.calls.size)
    }
    @Test fun allLedgersIsProcessOnlyAndDoesNotPersistAnInvalidIdentity()=runTest {
        val repo=Repository(data);val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        assertEquals(setOf("l1"),controller.navigation.value.scope.ledgerIds)
        controller.selectScope(controller.navigation.value.scope.copy(ledgerIds=emptySet()));runCurrent()
        assertTrue(repo.calls.isEmpty())
        assertTrue(controller.navigation.value.scope.ledgerIds.isEmpty())
    }
    @Test fun queuedPreferenceCannotOverwriteUnconfirmedPendingOperation()=runTest {
        val repo=Repository(data.copy(ledgers=data.ledgers+Ledger("l2","旅行")))
        repo.pendingOperation.value=PendingOperationRecovery("money-op",OperationOutcome("money-op","SaveEntry",listOf("entry")))
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.selectScope(controller.navigation.value.scope.copy(ledgerIds=setOf("l2")));runCurrent()
        assertTrue(repo.calls.isEmpty())
        controller.acknowledgePendingOperation("money-op");runCurrent()
        assertEquals(Command.SetPreference("lastLedgerId","l2"),repo.calls.single().first)
        assertEquals("money-op",repo.acknowledgements.first())
    }
    @Test fun dataReplacementAndResetApplyNewPreferencesAndDropOldQueuedChoices()=runTest {
        val repo=Repository(data.copy(ledgers=data.ledgers+Ledger("l2","旅行")))
        val controller=AppController(repo,Environment(),backgroundScope);runCurrent()
        controller.setBusy(true)
        controller.selectScope(controller.navigation.value.scope.copy(ledgerIds=setOf("l2")));runCurrent()
        val restored=data.copy(datasetId="restored",preferences=listOf(UserPreference("lastLedgerId","l1"),UserPreference("lastPeriod","2025-05")))
        repo.state.value=restored;controller.resetTransientState();controller.setBusy(false);runCurrent()
        assertTrue(repo.calls.isEmpty())
        assertEquals(setOf("l1"),controller.navigation.value.scope.ledgerIds)
        assertEquals("2025-05-01",controller.navigation.value.scope.startDate)
        val sameDataset=restored.copy(preferences=listOf(UserPreference("lastPeriod","2024-06")))
        repo.state.value=sameDataset;controller.resetTransientState();runCurrent()
        assertEquals("2024-06-01",controller.navigation.value.scope.startDate)
    }

}
