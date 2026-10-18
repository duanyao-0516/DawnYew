package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AppUiState(val dataset:LedgerState?=null,val busy:Boolean=false,val message:String?=null,val savingPreference:Boolean=false)
data class CaptureDraftUi(val amount:String="",val occurrence:String="",val note:String="",val warnings:List<String> = emptyList(),val supported:Boolean=true,val engineVersion:String?=null,val fingerprint:String?=null,val duplicateWarning:Boolean=false)
data class TemplateUi(val name:String,val reason:String)
val billTemplates = com.dawnyew.protocol.TemplateRegistry.templates.map { TemplateUi(it.name,it.reason) }
interface PlatformActions {
    fun exportBackup(encrypted:Boolean,password:CharArray)
    fun restoreBackup(password:CharArray)
    fun chooseScreenshot()
    fun setAppLock(enabled:Boolean)
    fun clearAllData()
    fun restoreProtectionBackup()
    fun clearProtectionBackup()
}

/** A lifecycle-owned coordinator. UI never obtains a DAO or Android type. */
class AppController(val repository:LedgerRepository,val environment:DomainEnvironment,private val scope:CoroutineScope) {
    private val mutable=MutableStateFlow(AppUiState())
    val ui:StateFlow<AppUiState> = mutable.asStateFlow()
    private val mutablePendingOperation=MutableStateFlow(repository.pendingOperation.value)
    val pendingOperation:StateFlow<PendingOperationRecovery?> = mutablePendingOperation.asStateFlow()
    private val paging = EntryPagingCoordinator(repository, scope)
    val entries: StateFlow<EntryPageUi> = paging.state
    private val statisticsCoordinator=StatisticsCoordinator(repository,scope)
    val statistics:StateFlow<StatisticsPageUi> = statisticsCoordinator.state
    fun loadStatistics(query:QueryScope,force:Boolean=false)=statisticsCoordinator.load(query,force)
    fun loadEntries(query: QueryScope, append: Boolean = false, force: Boolean = false) = paging.load(query, append, force)
    /** Called after external-result authorization is restored; supersede reads holding the old authorization. */
    fun resumeVisibleQueries() {
        val navigation=mutableNavigation.value
        when(navigation.page) {
            "明细" -> paging.load(navigation.scope,force=entries.value.loading || entries.value.error!=null)
            "统计" -> statisticsCoordinator.load(navigation.scope,force=statistics.value.loading || statistics.value.error!=null)
        }
    }
    suspend fun queryStatistics(query: QueryScope): StatisticsView = try {
        val snapshot = repository.statistics(query)
        snapshot.netExpense; snapshot.netIncome
        StatisticsView.Content(snapshot)
    } catch(cancelled: CancellationException) {throw cancelled}
      catch(error: DomainException) {StatisticsView.Error(error.message)}
      catch(_: Exception) {StatisticsView.Error("统计读取失败，请重试")}
    private var duplicateJob:Job?=null
    private val mutableEditor = MutableStateFlow<EditorDraft?>(null)
    val editor: StateFlow<EditorDraft?> = mutableEditor.asStateFlow()
    private val initialRange = monthRange(environment.localNow().take(7))
    private val initialScope = QueryScope(startDate = initialRange.first, endDate = initialRange.second)
    private val mutableNavigation = MutableStateFlow(UiNavigation(detailsScope = initialScope, statisticsScope = initialScope))
    val navigation: StateFlow<UiNavigation> = mutableNavigation.asStateFlow()
    private var navigationDatasetId:String?=null
    private val queuedPreferences=linkedMapOf<String,String>()
    private var preferenceJob:Job?=null
    private var preferenceGeneration=0L
    private fun restoreNavigationPreferences(data:LedgerState) {
        navigationDatasetId=data.datasetId
        val preferred=data.preferences.firstOrNull {it.key=="lastLedgerId"}?.value
        val active=data.ledgers.filterNot {it.archived}.sortedWith(compareBy<Ledger> {it.sortOrder}.thenBy {it.id})
        val ledger=active.firstOrNull {it.id==preferred} ?: active.firstOrNull()
        val period=data.preferences.firstOrNull {it.key=="lastPeriod"}?.value
        val range=period?.let {runCatching {monthRange(it)}.getOrNull()} ?: monthRange(environment.localNow().take(7))
        val query=QueryScope(ledgerIds=ledger?.let {setOf(it.id)} ?: emptySet(),startDate=range.first,endDate=range.second)
        mutableNavigation.value=UiNavigation(detailsScope=query,statisticsScope=query)
    }
    /** Only explicit selector actions persist preferences; statistics drills and queries do not. */
    fun selectScope(value:QueryScope) {
        val old=mutableNavigation.value.scope
        mutableNavigation.update {it.withScope(value)}
        val data=mutable.value.dataset ?: return
        if(old.ledgerIds!=value.ledgerIds) value.ledgerIds.singleOrNull()?.takeIf {id->data.ledgers.any {it.id==id && !it.archived}}?.let {queuedPreferences["lastLedgerId"]=it}
        if(old.startDate!=value.startDate || old.endDate!=value.endDate) {
            val period=value.startDate.take(7)
            if(runCatching {monthRange(period)}.isSuccess) queuedPreferences["lastPeriod"]=period
        }
        flushPreferences()
    }
    private fun flushPreferences() {
        if(preferenceJob?.isActive==true || queuedPreferences.isEmpty()) return
        val generation=preferenceGeneration
        preferenceJob=scope.launch {
            while(queuedPreferences.isNotEmpty() && generation==preferenceGeneration) {
                combine(ui,pendingOperation) {state,pending->!state.busy && pending==null}.first {it}
                if(generation!=preferenceGeneration) return@launch
                val (key,value)=queuedPreferences.entries.firstOrNull()?.let {it.key to it.value} ?: return@launch
                if(mutable.value.dataset?.preferences?.any {it.key==key && it.value==value}==true) {
                    queuedPreferences.remove(key);continue
                }
                var committed=false
                val job=performOperation(Command.SetPreference(key,value),environment.id(),savingPreference=true) {
                    committed=true
                    if(generation==preferenceGeneration && queuedPreferences[key]==value) queuedPreferences.remove(key)
                }
                job?.join()
                // A failed write retains the queued choice for the next explicit selection; never spin/retry forever.
                if(!committed) return@launch
            }
        }
    }
    private fun clearQueuedPreferences() {
        preferenceGeneration++
        preferenceJob?.cancel();preferenceJob=null
        queuedPreferences.clear()
    }
    fun navigate(transform: (UiNavigation) -> UiNavigation) { mutableNavigation.update(transform) }
    fun beginEditor(defaultLedgerId: String?, editId: String? = null, refundParentId: String? = null, capture: CaptureDraftUi? = null) {
        val data = mutable.value.dataset ?: return
        val draft=try {EditorDraft.create(data, environment.id(), environment.localNow(), defaultLedgerId, editId, refundParentId, capture)}
            catch(error:DomainException) {notify(error.message);return}
        duplicateJob?.cancel()
        mutableEditor.value = draft.copy(duplicateCheckPending=capture!=null)
        mutableNavigation.update {it.copy(editorReturnPage=if(it.page=="记一笔") it.editorReturnPage else it.page,page="记一笔")}
        if(capture!=null) checkCaptureDuplicates(draft.operationId)
    }
    fun changeEditor(operationId: String, change: EditorChange) {
        if(mutable.value.busy) return
        val data=mutable.value.dataset ?: return
        var checkId:String?=null
        mutableEditor.update {current->
            if(current==null || current.operationId!=operationId) return@update current
            fun categoryFor(ledgerId:String?,kind:EntryKind):String? {
                val direction=if(kind==EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
                val available=data.categories.filter {it.ledgerId==ledgerId && it.direction==direction && it.enabled}
                return current.categoryId?.takeIf {id->available.any {it.id==id}} ?: available.minWithOrNull(compareBy<Category> {it.sortOrder}.thenBy {it.id})?.id
            }
            val next=when(change) {
                is EditorChange.Amount -> current.copy(amount=change.value)
                is EditorChange.Note -> current.copy(note=change.value)
                is EditorChange.Date -> current.copy(date=change.value)
                is EditorChange.Time -> current.copy(time=change.value)
                is EditorChange.Kind -> if(current.kind==EntryKind.REFUND || change.value==EntryKind.REFUND) current else current.copy(kind=change.value,categoryId=categoryFor(current.ledgerId,change.value))
                is EditorChange.Ledger -> if(current.kind==EntryKind.REFUND) current else current.copy(ledgerId=change.value,categoryId=categoryFor(change.value,current.kind))
                is EditorChange.Category -> if(current.kind==EntryKind.REFUND) current else current.copy(categoryId=change.value)
                is EditorChange.Confirmed -> if(current.duplicateCheckPending || current.duplicateCheckError!=null) current else current.copy(confirmed=change.value)
                is EditorChange.DuplicateAcknowledged -> if(current.duplicateCheckPending || current.duplicateCheckError!=null) current else current.copy(duplicateAcknowledged=change.value)
            }
            if(next==current) return@update current
            val fieldChanged=change !is EditorChange.Confirmed && change !is EditorChange.DuplicateAcknowledged
            if(next.capture!=null && (next.amount!=current.amount || next.date!=current.date)) {
                checkId=operationId
                next.copy(dirty=true,confirmed=false,duplicateAcknowledged=false,duplicateCheckPending=true,duplicateCheckError=null)
            } else next.copy(dirty=true,confirmed=if(fieldChanged && next.capture!=null) false else next.confirmed)
        }
        checkId?.let {checkCaptureDuplicates(it,150)}
    }
    fun retryCaptureDuplicateCheck() {
        val draft=mutableEditor.value ?: return
        if(draft.capture==null || draft.duplicateCheckPending) return
        mutableEditor.value=draft.copy(duplicateCheckPending=true,duplicateCheckError=null)
        checkCaptureDuplicates(draft.operationId)
    }
    private fun checkCaptureDuplicates(operationId:String,debounceMillis:Long=0) {
        duplicateJob?.cancel()
        duplicateJob=scope.launch {
            if(debounceMillis>0) delay(debounceMillis)
            val draft=mutableEditor.value?.takeIf {it.operationId==operationId} ?: return@launch
            val capture=draft.capture ?: return@launch
            try {
                val amount=runCatching {Money.parse(draft.amount)}.getOrNull()
                val date=draft.date.takeIf {runCatching {validateOccurrence(it+"T00:00:00")}.isSuccess}
                val duplicates=repository.findCaptureDuplicates(capture.fingerprint,amount,date)
                ensureActive()
                val messages=buildList {
                    if(duplicates.hasSameImage) add("本机已有相同图片摘要的记录，请核对是否重复记账。")
                    if(duplicates.hasSameDayAmount) add("本机已有同日同额的消费记录，可能是重复，也可能是另一笔真实消费。")
                    if(duplicates.hasDeletedMatch) add("匹配记录包含回收站内容，请先核对原记录。")
                }
                mutableEditor.update {current->
                    if(current?.operationId!=operationId) current else current.copy(
                        capture=capture.copy(duplicateWarning=messages.isNotEmpty()),duplicateMessages=messages,
                        duplicateCheckPending=false,duplicateCheckError=null,duplicateAcknowledged=false,
                        confirmed=if(messages.isNotEmpty()) false else current.confirmed)
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {mutableEditor.update {current->if(current?.operationId!=operationId) current else current.copy(duplicateCheckPending=false,duplicateCheckError="疑似重复检查未完成，请重试后保存")}}
        }
    }
    fun clearEditor() {duplicateJob?.cancel();mutableEditor.value=null}
    fun resetTransientState() {
        clearEditor()
        paging.clear()
        statisticsCoordinator.clear()
        clearQueuedPreferences()
        navigationDatasetId=null
        val data=repository.state.value
        if(data!=null) restoreNavigationPreferences(data)
        else mutableNavigation.value = UiNavigation(detailsScope = initialScope, statisticsScope = initialScope)
    }
    fun saveEditor(keep: Boolean, onSuccess: () -> Unit) {
        val current = mutableEditor.value ?: return
        val command = try { current.command() } catch (e: DomainException) { notify(e.message); return }
        perform(command, current.operationId) {
            if (mutableEditor.value?.operationId == current.operationId) {
                mutableEditor.value = if (keep) mutable.value.dataset?.let { data ->
                    current.continuation(data, environment.id(), environment.localNow())
                } else null
                onSuccess()
            }
        }
    }
    init {
        scope.launch { repository.state.collect { data ->
            if(data==null || mutable.value.dataset?.datasetId?.let {it!=data.datasetId}==true) {paging.clear();statisticsCoordinator.clear()}
            mutable.update { it.copy(dataset=data) }
            if(data!=null && navigationDatasetId!=data.datasetId) {clearQueuedPreferences();restoreNavigationPreferences(data)}
        } }
        scope.launch { repository.pendingOperation.collect { mutablePendingOperation.value=it } }
    }
    /** Called only by the recovery dialog's explicit acknowledgement button. */
    fun acknowledgePendingOperation(operationId:String) {
        val recovery=mutablePendingOperation.value?.takeIf {it.operationId==operationId} ?: return
        if(mutable.value.busy) return
        mutable.update {it.copy(busy=true)}
        scope.launch {
            try {
                repository.acknowledgeOperation(operationId)
                mutablePendingOperation.update {if(it?.operationId==operationId) null else it}
                if(recovery.outcome!=null && mutableEditor.value?.operationId==operationId) {
                    clearEditor()
                    mutableNavigation.update {it.copy(page="明细",tab="明细")}
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {notify("确认结果未能保存，请重试；上次操作状态仍保留")}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
    fun perform(command:Command,operationId:String=environment.id(),onSuccess:(List<String>)->Unit={}):Job? =
        performOperation(command,operationId,savingPreference=false,onSuccess)
    private fun performOperation(command:Command,operationId:String,savingPreference:Boolean,onSuccess:(List<String>)->Unit):Job? {
        if(mutable.value.busy) return null
        val pending=mutablePendingOperation.value
        if(pending!=null && pending.operationId!=operationId) {notify("请先确认上次操作的保存结果");return null}
        mutable.update { it.copy(busy=true,savingPreference=savingPreference) }
        return scope.launch {
            try {
                val ids=try {repository.execute(command,operationId)}
                catch(cancelled:CancellationException) {throw cancelled}
                catch(error:Exception) {
                    repository.pendingOperation.value?.let {mutablePendingOperation.value=it}
                    notify(if(error is DomainException) error.message else "操作未完成，请重试")
                    return@launch
                }
                val committed=PendingOperationRecovery(operationId,OperationOutcome(operationId,command::class.simpleName ?: "Command",ids))
                if(!savingPreference) notify("已保存")
                try {onSuccess(ids)}
                catch(cancelled:CancellationException) {throw cancelled}
                catch(_:Exception) {
                    mutablePendingOperation.value=committed
                    notify("数据已保存，界面刷新未完成，请确认结果后到明细核对")
                    return@launch
                }
                try {
                    repository.acknowledgeOperation(operationId)
                    mutablePendingOperation.update {if(it?.operationId==operationId) null else it}
                } catch(cancelled:CancellationException) {throw cancelled}
                catch(_:Exception) {
                    mutablePendingOperation.value=committed
                    notify("数据已保存，结果确认未完成，请确认后继续")
                }
            } finally {mutable.update {it.copy(busy=false,savingPreference=false)}}
        }
    }
    fun notify(message:String) { mutable.update { it.copy(message=message) } }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    fun setBusy(busy:Boolean) { mutable.update { it.copy(busy=busy) } }
}
