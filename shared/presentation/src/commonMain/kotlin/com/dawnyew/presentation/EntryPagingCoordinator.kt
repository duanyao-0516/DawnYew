package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EntryPageUi(
    val scope:QueryScope?=null,val entries:List<Entry> = emptyList(),val nextCursor:EntryCursor?=null,
    val datasetId:String?=null,val contentVersion:Long?=null,val loading:Boolean=false,val loaded:Boolean=false,val error:String?=null,
)

/** DB-backed pages, with a small process-only cache for returning from a chart or detail. */
internal class EntryPagingCoordinator(private val repository:LedgerRepository,private val coroutineScope:CoroutineScope) {
    private val mutable=MutableStateFlow(EntryPageUi())
    val state:StateFlow<EntryPageUi> = mutable.asStateFlow()
    private var request:Job?=null
    private var sequence=0L
    private val cache=linkedMapOf<QueryScope,EntryPageUi>()

    fun clear() {sequence++;request?.cancel();cache.clear();mutable.value=EntryPageUi()}

    fun load(query:QueryScope,append:Boolean=false,force:Boolean=false) {
        val dataset=repository.state.value ?: return
        val existing=mutable.value
        if(append && (existing.scope!=query || existing.loading || existing.nextCursor==null)) return
        val cached=cache[query]?.takeIf {it.datasetId==dataset.datasetId && it.contentVersion==dataset.contentVersion}
        if(!append && !force && cached!=null) {sequence++;request?.cancel();mutable.value=cached;return}
        if(!append && !force && existing.loading && existing.scope==query && existing.datasetId==dataset.datasetId && existing.contentVersion==dataset.contentVersion) return
        request?.cancel()
        val token=++sequence
        val base=if(append) existing else EntryPageUi(scope=query,datasetId=dataset.datasetId,contentVersion=dataset.contentVersion)
        mutable.value=base.copy(loading=true,error=null)
        request=coroutineScope.launch {
            try {
                val page=repository.queryEntryPage(query,if(append) base.nextCursor else null,50)
                ensureActive()
                if(token!=sequence) return@launch
                val merged=if(append) base.entries+page.entries else page.entries
                val value=EntryPageUi(query,merged,page.nextCursor,page.datasetId,page.contentVersion,loaded=true)
                mutable.value=value
                cache.remove(query);cache[query]=value
                while(cache.size>4) cache.remove(cache.keys.first())
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:DomainException) {
                if(token!=sequence) return@launch
                if(append && error.code==ErrorCode.CONFLICT) load(query,force=true)
                else mutable.value=base.copy(loading=false,error=error.message)
            } catch(_:Exception) {
                if(token==sequence) mutable.value=base.copy(loading=false,error="明细读取失败，请重试")
            }
        }
    }
}
