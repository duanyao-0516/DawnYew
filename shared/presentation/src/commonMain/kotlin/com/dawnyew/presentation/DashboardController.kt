package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PanelUiState(val request:PanelRequest?=null,val snapshot:PanelStatistics?=null,val loading:Boolean=false,val error:String?=null)
data class AccountHistoryUiState(val request:AccountHistoryRequest?=null,val snapshot:AccountHistorySnapshot?=null,val loading:Boolean=false,val error:String?=null)

/** Each panel owns its query slot; switching one panel's period cannot replace another's data. */
class DashboardController(private val repository:LedgerRepository,private val scope:CoroutineScope) {
    private val mutablePanels=MutableStateFlow<Map<String,PanelUiState>>(emptyMap())
    val panels:StateFlow<Map<String,PanelUiState>> = mutablePanels.asStateFlow()
    private val mutableHistory=MutableStateFlow(AccountHistoryUiState())
    val history:StateFlow<AccountHistoryUiState> = mutableHistory.asStateFlow()
    private val jobs=mutableMapOf<String,Job>()
    private val sources=mutableMapOf<String,LedgerState>()
    private val sequences=mutableMapOf<String,Long>()
    private var generation=0L
    private var historyJob:Job?=null
    private var historySource:LedgerState?=null
    private var historySequence=0L
    private fun LedgerState.sameDashboard(other:LedgerState)=hasSameQueryContent(other) && budgets==other.budgets && budgetAllocations==other.budgetAllocations && budgetReallocations==other.budgetReallocations
    private fun put(id:String,value:PanelUiState) {mutablePanels.value=mutablePanels.value+(id to value)}
    private fun ActivityScope.frozen()=copy(ledgerIds=ledgerIds.toSet(),kinds=kinds.toSet(),categoryIds=categoryIds.toSet(),accountIds=accountIds.toSet(),projectIds=projectIds.toSet(),merchantIds=merchantIds.toSet(),tagIds=tagIds.toSet(),weekdays=weekdays.toSet())

    fun loadPanel(request:PanelRequest,force:Boolean=false) {
        val current=repository.state.value ?: return
        val query=request.copy(scope=request.scope.frozen());val id=query.panelId;val existing=panels.value[id]
        if(!force && existing?.request==query && sources[id]?.sameDashboard(current)==true) {
            sources[id]=current
            if(existing.snapshot!=null) put(id,existing.copy(snapshot=existing.snapshot.copy(contentVersion=current.contentVersion)))
            if(existing.loading || existing.snapshot!=null && existing.error==null) return
        }
        jobs.remove(id)?.cancel()
        val token=(sequences[id] ?: 0)+1;sequences[id]=token;val epoch=generation
        sources[id]=current
        val retained=existing?.snapshot?.takeIf {it.datasetId==current.datasetId && it.request==query}
        put(id,PanelUiState(query,retained,loading=true))
        jobs[id]=scope.launch {
            try {
                val result=repository.panelStatistics(query)
                ensureActive()
                if(epoch!=generation || sequences[id]!=token) return@launch
                val latest=repository.state.value ?: run {reset();return@launch}
                if(!current.sameDashboard(latest)) {loadPanel(query,true);return@launch}
                if(result.request!=query || result.datasetId!=current.datasetId) throw DomainException(ErrorCode.CONFLICT,"面板读取结果已失效")
                sources[id]=latest
                put(id,PanelUiState(query,result.copy(contentVersion=latest.contentVersion)))
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:Exception) {if(epoch==generation && sequences[id]==token) put(id,PanelUiState(query,retained,error=if(error is DomainException) error.message else "面板读取失败，请重试"))}
        }
    }
    fun loadHistory(request:AccountHistoryRequest,force:Boolean=false) {
        val current=repository.state.value ?: return
        val query=request.copy(ledgerIds=request.ledgerIds.toSet(),accountIds=request.accountIds.toSet());val existing=history.value
        if(!force && existing.request==query && historySource?.sameDashboard(current)==true) {
            historySource=current
            if(existing.snapshot!=null) mutableHistory.value=existing.copy(snapshot=existing.snapshot.copy(contentVersion=current.contentVersion))
            if(existing.loading || existing.snapshot!=null && existing.error==null) return
        }
        historyJob?.cancel();val token=++historySequence;val epoch=generation;historySource=current
        val retained=existing.snapshot?.takeIf {it.datasetId==current.datasetId && it.request==query}
        mutableHistory.value=AccountHistoryUiState(query,retained,loading=true)
        historyJob=scope.launch {
            try {
                val result=repository.accountHistory(query)
                ensureActive()
                if(epoch!=generation || token!=historySequence) return@launch
                val latest=repository.state.value ?: run {reset();return@launch}
                if(!current.sameDashboard(latest)) {loadHistory(query,true);return@launch}
                if(result.request!=query || result.datasetId!=current.datasetId) throw DomainException(ErrorCode.CONFLICT,"资产趋势读取结果已失效")
                historySource=latest
                mutableHistory.value=AccountHistoryUiState(query,result.copy(contentVersion=latest.contentVersion))
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:Exception) {if(epoch==generation && token==historySequence) mutableHistory.value=AccountHistoryUiState(query,retained,error=if(error is DomainException) error.message else "资产趋势读取失败，请重试")}
        }
    }
    fun refreshLoaded(force:Boolean=false) {
        panels.value.values.mapNotNull {it.request}.forEach {loadPanel(it,force)}
        history.value.request?.let {loadHistory(it,force)}
    }
    fun reset() {
        generation++;jobs.values.forEach {it.cancel()};jobs.clear();sources.clear();sequences.clear()
        historySequence++;historyJob?.cancel();historyJob=null;historySource=null
        mutablePanels.value=emptyMap();mutableHistory.value=AccountHistoryUiState()
    }
}
