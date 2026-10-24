package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ForegroundAccess(val ready:Boolean=false,val sessionEpoch:Long=0,val databaseEpoch:Long=0)
data class RecurringFailure(val planId:String,val name:String,val message:String)
data class RecurringUiState(
    val running:Boolean=false,
    val attempted:Int=0,
    val generated:Int=0,
    val failures:List<RecurringFailure> = emptyList(),
    val hasMore:Boolean=false,
    val limitReached:Boolean=false,
    val blockedByRecovery:Boolean=false,
)

/** Foreground-only, bounded execution using the same write/recovery gate as user commands. */
class RecurringCoordinator(private val app:AppController,private val scope:CoroutineScope) {
    private data class Identity(val sessionEpoch:Long,val databaseEpoch:Long,val datasetId:String)
    private data class FailedRevision(val planId:String,val revision:Long)
    private val mutable=MutableStateFlow(RecurringUiState())
    val state:StateFlow<RecurringUiState> = mutable.asStateFlow()
    private var access=ForegroundAccess()
    private var identity:Identity?=null
    private var allowance=100
    private val failed=mutableSetOf<FailedRevision>()
    private var runner:Job?=null
    private var operation:Job?=null
    private var closed=false
    private val observer=scope.launch {
        val gates=combine(app.ui,app.pendingOperation,app.editor,app.financialEditorOpen,app.repository.pendingOperation) { _,_,_,_,_ -> Unit }
        combine(gates,app.repository.state) { _,_ -> Unit }.collect { refresh() }
    }

    fun updateAccess(access:ForegroundAccess) {
        if(closed) return
        this.access=access
        // Explicit repeated events also recheck the clock, without granting another batch.
        refresh()
    }

    fun continueDue():Boolean {
        if(closed) return false
        synchronizeIdentity()
        val token=identity ?: return false
        if(runner!=null || !canSubmit(token) || duePlans().isEmpty()) return false
        allowance=100
        failed.clear()
        mutable.value=RecurringUiState()
        refresh()
        return true
    }

    fun close() {
        if(closed) return
        closed=true
        observer.cancel()
        cancelWork()
        mutable.update { it.copy(running=false) }
    }

    private fun currentIdentity():Identity? = app.repository.state.value?.let {
        Identity(access.sessionEpoch,access.databaseEpoch,it.datasetId)
    }

    private fun synchronizeIdentity() {
        val next=currentIdentity()
        if(next!=null && next!=identity) {
            cancelWork()
            identity=next
            allowance=100
            failed.clear()
            mutable.value=RecurringUiState()
        }
        if(!access.ready || next==null || next!=identity) cancelWork()
    }

    private fun cancelWork() {
        operation?.cancel()
        runner?.cancel()
        operation=null
        runner=null
        mutable.update { it.copy(running=false) }
    }

    private fun authorized(token:Identity):Boolean = !closed && access.ready && identity==token &&
        currentIdentity()==token && app.ui.value.dataset?.datasetId==token.datasetId

    private fun hasRecovery():Boolean = app.pendingOperation.value!=null || app.repository.pendingOperation.value!=null

    private fun canSubmit(token:Identity):Boolean = authorized(token) && !app.ui.value.busy && !hasRecovery() &&
        app.editor.value==null && !app.financialEditorOpen.value

    private fun duePlans():List<RecurringPlan> {
        val now=app.environment.localNow()
        return app.repository.state.value?.recurringPlans.orEmpty().filter { plan ->
            !plan.paused && !plan.archived && recurringNextOccurrence(plan)?.let { it<=now }==true
        }.sortedWith(compareBy<RecurringPlan> { recurringNextOccurrence(it) }.thenBy { it.id })
    }

    private fun publishSummary() {
        val hasMore=duePlans().isNotEmpty()
        mutable.update { it.copy(hasMore=hasMore,limitReached=allowance==0 && hasMore,blockedByRecovery=hasRecovery()) }
    }

    private fun refresh() {
        if(closed) return
        synchronizeIdentity()
        publishSummary()
        val token=identity ?: return
        if(runner!=null || allowance==0 || !canSubmit(token)) return
        if(duePlans().none { FailedRevision(it.id,it.revision) !in failed }) return
        val job=scope.launch(start=CoroutineStart.LAZY) {
            val self=currentCoroutineContext()[Job]
            try {
                mutable.update { it.copy(running=true) }
                runBatch(token)
            } finally {
                // A canceled old identity must not clear or restart its replacement's work.
                if(runner===self) {
                    runner=null
                    mutable.update { it.copy(running=false) }
                    refresh()
                }
            }
        }
        runner=job
        job.start()
    }

    private suspend fun runBatch(token:Identity) {
        while(allowance>0 && canSubmit(token)) {
            currentCoroutineContext().ensureActive()
            val plan=duePlans().firstOrNull { FailedRevision(it.id,it.revision) !in failed } ?: break
            if(!canSubmit(token)) break
            var committed=false
            val operationId=app.environment.id()
            val job=app.performAutomatic(Command.Finance(FinanceAction.ExecuteRecurring(plan.id,plan.revision)),operationId) {
                if(authorized(token)) committed=true
            } ?: break
            operation=job
            allowance--
            mutable.update { it.copy(attempted=it.attempted+1) }
            if(!authorized(token)) job.cancel()
            try {
                job.join()
                currentCoroutineContext().ensureActive()
            } finally {
                if(operation===job) operation=null
                if(!currentCoroutineContext().isActive) job.cancel()
            }
            if(!authorized(token)) break
            // performAutomatic validates and acknowledges only this operation's exact receipt.
            // Unknown outcomes and failed acknowledgements stay in the existing recovery UI.
            if(hasRecovery()) break
            if(committed && !job.isCancelled) {
                mutable.update { it.copy(generated=it.generated+1) }
            } else {
                failed+=FailedRevision(plan.id,plan.revision)
                val message=app.ui.value.message ?: if(job.isCancelled) "周期交易处理已取消，请稍后重试" else "周期交易处理失败，请稍后重试"
                mutable.update { it.copy(failures=it.failures.filterNot { failure -> failure.planId==plan.id }+RecurringFailure(plan.id,plan.name,message)) }
            }
            publishSummary()
            // Permit foreground, editor and dataset changes between every individual command.
            yield()
        }
        if(authorized(token)) publishSummary()
    }
}
