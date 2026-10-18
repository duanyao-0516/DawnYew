package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StatisticsPageUi(
    val scope:QueryScope?=null,val datasetId:String?=null,val contentVersion:Long?=null,
    val snapshot:StatisticsSnapshot?=null,val loading:Boolean=false,val error:String?=null,
) {
    val displayedScope:QueryScope? get()=snapshot?.scope ?: scope
    val refreshing:Boolean get()=loading && snapshot!=null
}

/** Controller-owned statistics survive page changes without restarting from an empty screen. */
internal class StatisticsCoordinator(private val repository:LedgerRepository,private val coroutineScope:CoroutineScope) {
    private val mutable=MutableStateFlow(StatisticsPageUi())
    val state:StateFlow<StatisticsPageUi> = mutable.asStateFlow()
    private var request:Job?=null
    private var sequence=0L
    private var source:LedgerState?=null
    private var displayedSource:LedgerState?=null
    private var requestSource:LedgerState?=null
    private val cache=linkedMapOf<QueryScope,StatisticsPageUi>()

    fun clear() {sequence++;request?.cancel();source=null;displayedSource=null;requestSource=null;cache.clear();mutable.value=StatisticsPageUi()}

    private fun StatisticsPageUi.atVersion(version:Long)=copy(contentVersion=version,snapshot=snapshot?.copy(contentVersion=version))
    private fun synchronize(dataset:LedgerState) {
        val previous=source
        if(previous!=null && previous.hasSameQueryContent(dataset)) {
            cache.keys.toList().forEach {query->cache[query]=cache.getValue(query).atVersion(dataset.contentVersion)}
            if(displayedSource?.hasSameQueryContent(dataset)==true) {
                mutable.value=mutable.value.atVersion(dataset.contentVersion);displayedSource=dataset
            }
        } else cache.clear()
        source=dataset
    }

    fun load(query:QueryScope,force:Boolean=false) {
        val dataset=repository.state.value ?: return
        synchronize(dataset)
        val existing=mutable.value
        if(!force && existing.loading && existing.scope==query && requestSource?.hasSameQueryContent(dataset)==true) return
        val cached=cache[query]
        if(!force && cached!=null) {
            sequence++;request?.cancel()
            cache.remove(query);cache[query]=cached
            mutable.value=cached;displayedSource=dataset
            return
        }
        request?.cancel()
        val token=++sequence
        requestSource=dataset
        val retained=cached ?: existing.takeIf {it.datasetId==dataset.datasetId}
        val base=(retained ?: StatisticsPageUi(datasetId=dataset.datasetId,contentVersion=dataset.contentVersion)).copy(scope=query,loading=false,error=null)
        mutable.value=base.copy(loading=true)
        request=coroutineScope.launch {
            try {
                val snapshot=repository.statistics(query)
                // Derived totals can overflow even if each individual aggregate fits in Long.
                snapshot.netExpense;snapshot.netIncome
                ensureActive()
                if(token!=sequence) return@launch
                val current=repository.state.value
                if(current==null) {clear();return@launch}
                if(!dataset.hasSameQueryContent(current)) {load(query,force=true);return@launch}
                val value=StatisticsPageUi(query,current.datasetId,current.contentVersion,snapshot.copy(contentVersion=current.contentVersion))
                synchronize(current)
                displayedSource=current
                mutable.value=value
                cache.remove(query);cache[query]=value
                while(cache.size>4) cache.remove(cache.keys.first())
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:DomainException) {if(token==sequence) mutable.value=base.copy(error=error.message)}
            catch(_:Exception) {if(token==sequence) mutable.value=base.copy(error="统计读取失败，请重试")}
        }
    }
}
