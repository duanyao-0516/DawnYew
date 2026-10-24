package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FinanceNavigation(val page: String = "账户", val id: String? = null, val parentId: String? = null, val activityScope: ActivityScope? = null, val sourceLedgerIds: Set<String>? = null, val sourceStatisticsScope: QueryScope? = null)
private val financeTabs = setOf("首页", "账户", "统计", "更多", "我的")
fun isPrimaryFinanceDestination(page: String, objectId: String? = null): Boolean = objectId == null && page in financeTabs
data class FinanceSnapshotUi(
    val snapshot: FinanceSnapshot? = null, val ledgerIds: Set<String> = emptySet(),
    val datasetId: String? = null, val contentVersion: Long? = null,
    val loading: Boolean = false, val error: String? = null,
)
data class ActivityPageUi(
    val scope: ActivityScope? = null, val activities: List<FinancialActivity> = emptyList(),
    val nextCursor: ActivityCursor? = null, val datasetId: String? = null, val contentVersion: Long? = null,
    val loading: Boolean = false, val loaded: Boolean = false, val error: String? = null,
    val contentScope: ActivityScope? = if (loaded) scope else null,
) { val displayedScope get() = contentScope ?: scope }
data class ActivitySummaryUi(
    val scope: ActivityScope? = null, val summary: ActivitySummary? = null,
    val datasetId: String? = null, val contentVersion: Long? = null,
    val loading: Boolean = false, val error: String? = null,
)

/** The presentation seam for finance pages. All drafts and routes stay in process memory. */
class FinanceController internal constructor(private val app: AppController, private val coroutineScope: CoroutineScope) {
    private val mutableNavigation = MutableStateFlow(FinanceNavigation())
    val navigation = mutableNavigation.asStateFlow()
    private data class Frame(val navigation: UiNavigation, val finance: FinanceNavigation)
    private val frames = mutableListOf<Frame>()
    private val snapshotRead = FinancialRead(app.repository, coroutineScope, "资产读取失败，请重试", app.repository::financeSnapshot)
    private val calendarRead = FinancialRead(app.repository, coroutineScope, "日历读取失败，请重试", app.repository::activitySummary)
    private val activitySummaryRead = FinancialRead(app.repository, coroutineScope, "流水汇总读取失败，请重试", app.repository::activitySummary)
    private val profileRead = FinancialRead(app.repository, coroutineScope, "记账汇总读取失败，请重试", app.repository::activitySummary)
    private val pendingRead = FinancialRead(app.repository, coroutineScope, "未入账汇总读取失败，请重试", app.repository::activitySummary)
    private val activityRead = ActivityPagingCoordinator(app.repository, coroutineScope, 50)
    private val homeRead = ActivityPagingCoordinator(app.repository, coroutineScope, 3)
    val snapshot: StateFlow<FinanceSnapshotUi> = snapshotRead.state.map {
        FinanceSnapshotUi(it.data, it.query.orEmpty(), it.datasetId, it.contentVersion, it.loading, it.error)
    }.stateIn(coroutineScope, SharingStarted.Eagerly, FinanceSnapshotUi())
    val activities: StateFlow<ActivityPageUi> = activityRead.state
    val homeActivities: StateFlow<ActivityPageUi> = homeRead.state
    val activitySummary: StateFlow<ActivitySummaryUi> = activitySummaryRead.summaryState()
    val calendar: StateFlow<ActivitySummaryUi> = calendarRead.summaryState()
    val pending: StateFlow<ActivitySummaryUi> = pendingRead.summaryState()
    val profile: StateFlow<ActivitySummaryUi> = profileRead.summaryState()
    fun loadProfile(ledgerIds: Set<String>, force: Boolean = false) = profileRead.load(ActivityScope(ledgerIds=ledgerIds), force)

    private fun FinancialRead<ActivityScope, ActivitySummary>.summaryState() = state.map {
        ActivitySummaryUi(it.query, it.data, it.datasetId, it.contentVersion, it.loading, it.error)
    }.stateIn(coroutineScope, SharingStarted.Eagerly, ActivitySummaryUi())

    fun loadSnapshot(ledgerIds: Set<String>, force: Boolean = false) = snapshotRead.load(ledgerIds, force)
    fun loadActivities(query: ActivityScope, append: Boolean = false, force: Boolean = false) {
        activityRead.load(query, append, force)
        activitySummaryRead.load(query, force)
    }
    fun loadHome(query: ActivityScope, calendarQuery: ActivityScope = query, force: Boolean = false) {
        homeRead.load(query, force = force)
        calendarRead.load(calendarQuery, force)
        pendingRead.load(query.copy(recorded = false), force)
    }
    fun open(page: String, id: String? = null, parentId: String? = null) {
        if(isPrimaryFinanceDestination(page,id)) selectTab(page)
        else openRoute(FinanceNavigation(page, id, parentId, sourceLedgerIds = mutableNavigation.value.sourceLedgerIds ?: app.navigation.value.scope.ledgerIds))
    }
    fun openStatistics(page:String,source:QueryScope) {
        if(isPrimaryFinanceDestination(page)) selectTab(page)
        else openRoute(FinanceNavigation(page,sourceLedgerIds=source.ledgerIds,sourceStatisticsScope=source))
    }
    fun selectStatisticsScope(value:QueryScope) {
        if(mutableNavigation.value.sourceStatisticsScope!=null) mutableNavigation.update {it.copy(sourceLedgerIds=value.ledgerIds,sourceStatisticsScope=value)}
        else app.navigate {it.copy(statisticsScope=value)}
    }
    private fun openRoute(route: FinanceNavigation) {
        if (route == mutableNavigation.value && route.page == app.navigation.value.page) return
        frames += Frame(app.navigation.value, mutableNavigation.value)
        mutableNavigation.value = route
        app.navigate { it.copy(page = route.page, selectedEntryId = if (route.page == "账单详情") route.id else it.selectedEntryId) }
    }
    fun showActivities(query: ActivityScope) {
        openRoute(FinanceNavigation("流水", activityScope = query, sourceLedgerIds = query.ledgerIds))
        loadActivities(query)
    }
    /** Applying search filters replaces the current result route, rather than pushing another back frame. */
    fun updateActivities(query: ActivityScope) {
        validateActivityScope(query)
        val current = mutableNavigation.value
        if (current.page !in setOf("流水", "明细")) { showActivities(query); return }
        mutableNavigation.value = current.copy(activityScope = query, sourceLedgerIds = query.ledgerIds)
        loadActivities(query)
    }
    fun openActivity(activity: FinancialActivity) = open(if (activity.family == ActivityFamily.ENTRY) "账单详情" else "转账详情", activity.id)
    fun selectTab(tab: String) {
        frames.clear()
        mutableNavigation.value = FinanceNavigation(tab)
        app.navigate { it.copy(page = tab, tab = tab, selectedEntryId = null) }
    }
    fun back(): Boolean {
        val frame = frames.removeLastOrNull() ?: return false
        mutableNavigation.value = frame.finance
        app.navigate { frame.navigation }
        frame.finance.activityScope?.let { loadActivities(it) }
        return true
    }
    internal fun reset() {
        frames.clear()
        mutableNavigation.value = FinanceNavigation()
        snapshotRead.clear(); activitySummaryRead.clear(); calendarRead.clear(); pendingRead.clear(); profileRead.clear()
        activityRead.clear(); homeRead.clear()
    }
    internal fun refreshLoaded(force: Boolean = false) {
        snapshotRead.refresh(force); activitySummaryRead.refresh(force); calendarRead.refresh(force); pendingRead.refresh(force); profileRead.refresh(force)
        activityRead.refresh(force); homeRead.refresh(force)
    }
    fun perform(action: FinanceAction, operationId: String, onSuccess: () -> Unit = {}): Job? =
        app.perform(Command.Finance(action), operationId) { onSuccess() }
}

private data class FinancialReadState<Q, T>(
    val query: Q? = null, val data: T? = null, val datasetId: String? = null,
    val contentVersion: Long? = null, val loading: Boolean = false, val error: String? = null,
)

/** A single query slot; stale or cancelled results cannot replace a newer scope or generation. */
private class FinancialRead<Q : Any, T : Any>(
    private val repository: LedgerRepository, private val scope: CoroutineScope,
    private val failureMessage: String, private val read: suspend (Q) -> T,
) {
    private val mutable = MutableStateFlow(FinancialReadState<Q, T>())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var sequence = 0L
    private var requestSource: LedgerState? = null
    private var resultSource: LedgerState? = null

    fun clear() {
        sequence++; job?.cancel(); job = null
        requestSource = null; resultSource = null
        mutable.value = FinancialReadState()
    }
    fun refresh(force: Boolean) { mutable.value.query?.let { load(it, force) } }
    fun load(query: Q, force: Boolean = false) {
        val source = repository.state.value ?: return
        val old = mutable.value
        if (!force && old.query == query) {
            if (old.loading && requestSource?.hasSameQueryContent(source) == true) return
            if (!old.loading && old.error == null && resultSource?.hasSameQueryContent(source) == true) {
                mutable.value = old.copy(contentVersion = source.contentVersion)
                resultSource = source
                return
            }
        }
        val token = ++sequence
        job?.cancel(); requestSource = source
        val retained = old.data.takeIf { old.query == query && old.datasetId == source.datasetId }
        mutable.value = FinancialReadState(query, retained, source.datasetId, source.contentVersion, loading = true)
        job = scope.launch {
            try {
                val result = read(query)
                ensureActive()
                if (token != sequence) return@launch
                val current = repository.state.value ?: run { clear(); return@launch }
                if (!source.hasSameQueryContent(current)) { load(query, true); return@launch }
                resultSource = current
                mutable.value = FinancialReadState(query, result, current.datasetId, current.contentVersion)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == sequence) mutable.value = mutable.value.copy(loading = false, error = (failure as? DomainException)?.message ?: failureMessage)
            }
        }
    }
}

private class ActivityPagingCoordinator(
    private val repository: LedgerRepository, private val scope: CoroutineScope, private val pageSize: Int,
) {
    private val mutable = MutableStateFlow(ActivityPageUi())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var sequence = 0L
    private var resultSource: LedgerState? = null
    private var requestSource: LedgerState? = null

    fun clear() {
        sequence++; job?.cancel(); job = null
        resultSource = null; requestSource = null
        mutable.value = ActivityPageUi()
    }
    fun refresh(force: Boolean) { mutable.value.scope?.let { load(it, force = force) } }
    fun load(query: ActivityScope, append: Boolean = false, force: Boolean = false) {
        val source = repository.state.value ?: return
        val old = mutable.value
        if (append && (old.loading || old.scope != query || old.contentScope != query || old.nextCursor == null)) return
        if (append && (source.datasetId != old.datasetId || source.contentVersion != old.contentVersion)) {
            load(query, force = true); return
        }
        if (!append && !force && old.scope == query) {
            if (old.loading && requestSource?.hasSameQueryContent(source) == true) return
            if (!old.loading && old.loaded && old.error == null && resultSource?.hasSameQueryContent(source) == true) {
                mutable.value = old.copy(contentVersion = source.contentVersion, nextCursor = old.nextCursor?.copy(contentVersion = source.contentVersion))
                resultSource = source
                return
            }
        }
        val token = ++sequence
        job?.cancel(); requestSource = source
        val base = if (old.contentScope == query && old.datasetId == source.datasetId) old.copy(error = null) else ActivityPageUi(scope = query)
        mutable.value = base.copy(scope = query, loading = true)
        job = scope.launch {
            try {
                val page = repository.queryActivityPage(query, if (append) base.nextCursor else null, pageSize)
                ensureActive()
                if (token != sequence) return@launch
                val current = repository.state.value ?: run { clear(); return@launch }
                if (!source.hasSameQueryContent(current) || page.datasetId != source.datasetId || page.contentVersion != source.contentVersion) {
                    load(query, force = true); return@launch
                }
                resultSource = current
                mutable.value = ActivityPageUi(query, if (append) base.activities + page.activities else page.activities,
                    page.nextCursor?.copy(contentVersion = current.contentVersion), current.datasetId, current.contentVersion, loaded = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: DomainException) {
                if (token != sequence) return@launch
                if (append && failure.code == ErrorCode.CONFLICT) load(query, force = true)
                else mutable.value = base.copy(loading = false, error = failure.message)
            } catch (_: Exception) {
                if (token == sequence) mutable.value = base.copy(loading = false, error = "流水读取失败，请重试")
            }
        }
    }
}
