package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringCoordinatorTest {
    private class Environment:DomainEnvironment {
        private var n=0
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    private class Repo(val env:Environment,plans:Int=1,total:Int?=1):LedgerRepository {
        val engine=DomainEngine(env)
        fun data(plans:Int,total:Int?):LedgerState {
            val base=engine.initialState();val c=base.categories.first()
            return base.copy(recurringPlans=List(plans) {i->RecurringPlan(env.id(),c.ledgerId,"Plan $i",ActivityKind.EXPENSE,100,"2026-01-01T10:00:00",RecurringFrequency.DAILY,totalOccurrences=total,categoryId=c.id)})
        }
        override val state=MutableStateFlow<LedgerState?>(data(plans,total))
        override val pendingOperation=MutableStateFlow<PendingOperationRecovery?>(null)
        val calls=mutableListOf<Pair<Command,String>>()
        val acknowledgements=mutableListOf<String>()
        var gate:CompletableDeferred<Unit>?=null
        var failPlan:String?=null
        var acknowledgementFailure=false
        var receiptMismatch=false
        var active=0;var maxActive=0
        override suspend fun initialize()=Unit
        override suspend fun execute(command:Command,operationId:String,expectedVersion:Long?):List<String> {
            calls+=command to operationId;active++;maxActive=maxOf(maxActive,active)
            try {
                gate?.await()
                val action=(command as? Command.Finance)?.action
                if(action is FinanceAction.ExecuteRecurring && action.planId==failPlan) invalid("该计划账户已归档")
                val mutation=engine.apply(state.value!!,command,operationId,expectedVersion)
                state.value=mutation.state
                return mutation.resultIds
            } finally {active--}
        }
        override suspend fun lookupOperation(operationId:String):OperationOutcome? = if(receiptMismatch) null else state.value!!.receipts.find {it.operationId==operationId}?.let {OperationOutcome(it.operationId,it.commandType,it.resultIds)}
        override suspend fun acknowledgeOperation(operationId:String) {
            if(acknowledgementFailure) throw IllegalStateException("ack failure")
            acknowledgements+=operationId
            if(pendingOperation.value?.operationId==operationId) pendingOperation.value=null
        }
        override suspend fun snapshot()=state.value!!
        override suspend fun replace(state:LedgerState) {this.state.value=state}
        override suspend fun close()=Unit
    }
    @Test fun unlockedReadyRunsOnceAndRepeatedReadinessDoesNotDuplicate()=runTest {
        val env=Environment();val repo=Repo(env);val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();assertTrue(repo.calls.isEmpty())
        recurring.updateAccess(ForegroundAccess(true,1,1));repeat(10) {recurring.updateAccess(ForegroundAccess(true,1,1))};runCurrent()
        assertEquals(1,repo.calls.size);assertEquals(1,repo.state.value!!.entries.size)
        assertEquals(repo.calls.map {it.second},repo.acknowledgements);assertEquals(1,recurring.state.value.generated);assertFalse(recurring.state.value.hasMore)
        recurring.updateAccess(ForegroundAccess(false,1,1));recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent()
        assertEquals(1,repo.calls.size);recurring.close()
    }
    @Test fun oneIdentityStopsAtOneHundredUntilExplicitContinue()=runTest {
        val env=Environment();val repo=Repo(env,total=null);val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();recurring.updateAccess(ForegroundAccess(true,7,9));runCurrent()
        assertEquals(100,repo.calls.size);assertEquals(100,recurring.state.value.generated);assertTrue(recurring.state.value.hasMore);assertTrue(recurring.state.value.limitReached)
        repeat(5) {recurring.updateAccess(ForegroundAccess(false,7,9));recurring.updateAccess(ForegroundAccess(true,7,9))};runCurrent()
        assertEquals(100,repo.calls.size)
        assertTrue(recurring.continueDue());runCurrent();assertEquals(200,repo.calls.size);assertEquals(1,repo.maxActive)
        recurring.close()
    }
    @Test fun busyAndBothEditorKindsPauseWithoutConsumingTheDuePeriod()=runTest {
        val env=Environment();val repo=Repo(env);val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();app.setBusy(true);recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent();assertTrue(repo.calls.isEmpty())
        app.beginEditor(repo.state.value!!.ledgers.first().id);app.setBusy(false);runCurrent();assertTrue(repo.calls.isEmpty())
        app.setFinancialEditorOpen(true);app.clearEditor();runCurrent();assertTrue(repo.calls.isEmpty())
        app.setFinancialEditorOpen(false);runCurrent();assertEquals(1,repo.calls.size);recurring.close()
    }
    @Test fun unknownRecoveryBlocksAndIsNeverAcknowledgedByTheCoordinator()=runTest {
        val env=Environment();val repo=Repo(env);val unknown=env.id();repo.pendingOperation.value=PendingOperationRecovery(unknown,null)
        val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent()
        assertTrue(repo.calls.isEmpty());assertTrue(repo.acknowledgements.isEmpty());assertTrue(recurring.state.value.blockedByRecovery)
        repo.pendingOperation.value=null;runCurrent();assertEquals(1,repo.calls.size);assertFalse(unknown in repo.acknowledgements);recurring.close()
    }
    @Test fun domainFailureSkipsOnlyThatPlanAndShowsTheRealReason()=runTest {
        val env=Environment();val repo=Repo(env,plans=2);repo.failPlan=repo.state.value!!.recurringPlans.first().id
        val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent()
        assertEquals(2,repo.calls.size);assertEquals(1,repo.state.value!!.entries.size);assertEquals(1,recurring.state.value.generated)
        assertEquals("该计划账户已归档",recurring.state.value.failures.single().message)
        repeat(4) {recurring.updateAccess(ForegroundAccess(true,1,1))};runCurrent();assertEquals(2,repo.calls.size);recurring.close()
    }
    @Test fun backgroundCancelsInFlightAndEpochReplacementNeverContinuesOldBatch()=runTest {
        val env=Environment();val repo=Repo(env,total=null);val gate=CompletableDeferred<Unit>();repo.gate=gate
        val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent();assertEquals(1,repo.calls.size)
        recurring.updateAccess(ForegroundAccess(false,2,1));runCurrent();assertEquals(0,repo.active);assertTrue(repo.state.value!!.entries.isEmpty())
        repo.state.value=repo.data(1,1);repo.gate=null;recurring.updateAccess(ForegroundAccess(true,2,2));runCurrent()
        assertEquals(2,repo.calls.size);assertEquals(1,repo.state.value!!.entries.size);assertEquals(1,recurring.state.value.generated);recurring.close()
    }
    @Test fun acknowledgementFailureStopsBatchAndRetainsRecovery()=runTest {
        val env=Environment();val repo=Repo(env,total=null).apply {acknowledgementFailure=true};val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent()
        assertEquals(1,repo.calls.size);assertNotNull(app.pendingOperation.value);assertTrue(recurring.state.value.blockedByRecovery)
        assertFalse(recurring.continueDue());assertTrue(repo.acknowledgements.isEmpty());recurring.close()
    }
    @Test fun mismatchedOwnReceiptCannotBeAcknowledgedOrStartTheNextPeriod()=runTest {
        val env=Environment();val repo=Repo(env,total=null).apply {receiptMismatch=true};val app=AppController(repo,env,backgroundScope);val recurring=RecurringCoordinator(app,backgroundScope)
        runCurrent();recurring.updateAccess(ForegroundAccess(true,1,1));runCurrent()
        assertEquals(1,repo.calls.size);assertTrue(repo.acknowledgements.isEmpty());assertNotNull(app.pendingOperation.value)
        assertTrue(recurring.state.value.blockedByRecovery);recurring.close()
    }
}
