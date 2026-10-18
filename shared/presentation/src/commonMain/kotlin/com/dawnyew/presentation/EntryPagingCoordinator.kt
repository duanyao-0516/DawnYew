package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EntryPageUi(
    val scope:QueryScope?=null,val entries:List<Entry> = emptyList(),val nextCursor:EntryCursor?=null,
    val datasetId:String?=null,val contentVersion:Long?=null,val loading:Boolean=false,val loaded:Boolean=false,val error:String?=null,
    val contentScope:QueryScope?=if(loaded) scope else null,
) {
    val displayedScope:QueryScope? get()=contentScope ?: scope
    val refreshing:Boolean get()=loading && loaded
}

/** DB-backed pages, retaining completed content while a replacement request runs. */
internal class EntryPagingCoordinator(private val repository:LedgerRepository,private val coroutineScope:CoroutineScope) {
    private val mutable=MutableStateFlow(EntryPageUi())
    val state:StateFlow<EntryPageUi> = mutable.asStateFlow()
    private var request:Job?=null
    private var sequence=0L
    private var source:LedgerState?=null
    private var displayedSource:LedgerState?=null
    private var requestSource:LedgerState?=null
    private val cache=linkedMapOf<QueryScope,EntryPageUi>()

    fun clear() {sequence++;request?.cancel();source=null;displayedSource=null;requestSource=null;cache.clear();mutable.value=EntryPageUi()}

    private fun EntryPageUi.atVersion(version:Long)=copy(contentVersion=version,nextCursor=nextCursor?.copy(contentVersion=version))
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

    fun load(query:QueryScope,append:Boolean=false,force:Boolean=false) {
        val dataset=repository.state.value ?: return
        synchronize(dataset)
        val existing=mutable.value
        if(append && (existing.scope!=query || existing.contentScope!=query || existing.loading || existing.nextCursor==null)) return
        if(append && (existing.datasetId!=dataset.datasetId || existing.contentVersion!=dataset.contentVersion)) {load(query,force=true);return}
        if(!append && !force && existing.loading && existing.scope==query && requestSource?.hasSameQueryContent(dataset)==true) return
        val cached=cache[query]
        if(!append && !force && cached!=null) {
            sequence++;request?.cancel()
            cache.remove(query);cache[query]=cached
            mutable.value=cached;displayedSource=dataset
            return
        }
        request?.cancel()
        val token=++sequence
        requestSource=dataset
        val retained=if(append) existing else cached ?: existing.takeIf {it.loaded && it.datasetId==dataset.datasetId}
        val base=(retained ?: EntryPageUi(datasetId=dataset.datasetId,contentVersion=dataset.contentVersion)).copy(scope=query,
            nextCursor=retained?.nextCursor?.takeIf {retained.contentScope==query},loading=false,error=null)
        mutable.value=base.copy(loading=true)
        request=coroutineScope.launch {
            try {
                val page=repository.queryEntryPage(query,if(append) base.nextCursor else null,50)
                ensureActive()
                if(token!=sequence) return@launch
                val current=repository.state.value
                if(current==null) {clear();return@launch}
                if(!dataset.hasSameQueryContent(current)) {load(query,force=true);return@launch}
                val merged=if(append) base.entries+page.entries else page.entries
                val value=EntryPageUi(query,merged,page.nextCursor,page.datasetId,page.contentVersion,loaded=true).atVersion(current.contentVersion)
                synchronize(current)
                displayedSource=current
                mutable.value=value
                cache.remove(query);cache[query]=value
                while(cache.size>4) cache.remove(cache.keys.first())
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:DomainException) {
                if(token!=sequence) return@launch
                if(append && error.code==ErrorCode.CONFLICT) load(query,force=true)
                else mutable.value=base.copy(error=error.message)
            } catch(_:Exception) {
                if(token==sequence) mutable.value=base.copy(error="明细读取失败，请重试")
            }
        }
    }
}
