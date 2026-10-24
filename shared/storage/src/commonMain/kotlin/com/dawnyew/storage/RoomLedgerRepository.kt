package com.dawnyew.storage

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.dawnyew.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The writer transaction owns both domain validation and persistence of its receipt/version. */
class RoomLedgerRepository(
    private val database: DawnYewDatabase,
    private val environment: DomainEnvironment,
    private val createIfEmpty: Boolean = true,
) : LedgerRepository {
    private val engine = DomainEngine(environment)
    private val dao = database.ledgerDao()
    private val gate = Mutex()
    private val current = MutableStateFlow<LedgerState?>(null)
    private var closed = false
    private var cachedRows: Rows? = null
    override val state: StateFlow<LedgerState?> = current.asStateFlow()

    override suspend fun initialize() = gate.withLock {
        requireOpen()
        val committed = database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val stored = readRows()
                if (stored.metadata != null) decode(stored) to stored
                else {
                    if (!createIfEmpty) corrupt("已有数据库缺少元信息，拒绝自动初始化")
                    if (!stored.isEmpty()) corrupt("数据库缺少数据集元信息")
                    val initial = engine.initialState()
                    validateState(initial, environment::normalize)
                    val rows = Rows.from(initial, environment)
                    persist(stored, rows)
                    initial to rows
                }
            }
        }
        current.value = committed.first
        cachedRows = committed.second
    }

    override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> = gate.withLock {
        requireInitialized()
        val result = database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val metadata = dao.metadata()
                val previous = cachedRows?.takeIf { it.metadata == metadata } ?: readRows()
                val previousState = if (previous === cachedRows) requireNotNull(current.value) else decode(previous)
                val mutation = engine.apply(previousState, command, operationId, expectedVersion)
                val next = Rows.from(mutation.state, environment, previousState, previous)
                persist(previous, next)
                mutation to next
            }
        }
        current.value = result.first.state
        cachedRows = result.second
        result.first.resultIds
    }

    override suspend fun lookupOperation(operationId:String):OperationOutcome? = database.readOperationOutcome(operationId)

    override suspend fun queryEntryPage(scope: QueryScope, cursor: EntryCursor?, limit: Int): EntryPage = database.readEntryPage(scope,cursor,limit)

    override suspend fun queryActivityPage(scope:ActivityScope,cursor:ActivityCursor?,limit:Int):ActivityPage = database.readActivityPage(scope,cursor,limit)

    override suspend fun panelStatistics(request:PanelRequest):PanelStatistics = database.readPanelStatistics(request)

    override suspend fun accountHistory(request:AccountHistoryRequest):AccountHistorySnapshot = database.readAccountHistory(request)

    override suspend fun activitySummary(scope:ActivityScope):ActivitySummary = database.readActivitySummary(scope)

    override suspend fun findCaptureDuplicates(fingerprint:String?,amountMinor:Long?,localDate:String?):CaptureDuplicateSummary = database.readCaptureDuplicates(fingerprint,amountMinor,localDate)

    override suspend fun statistics(scope: QueryScope): StatisticsSnapshot = database.readStatistics(scope)

    suspend fun <T> exportSnapshot(block: suspend SnapshotReader.() -> T): T = database.exportSnapshot(block)

    override suspend fun snapshot(): LedgerState = gate.withLock {
        requireInitialized()
        database.useReaderConnection { connection ->
            connection.deferredTransaction { decode(readRows()) }
        }
    }

    override suspend fun replace(state: LedgerState) = gate.withLock {
        requireInitialized()
        validateState(state, environment::normalize)
        val replacement = Rows.from(state, environment)
        database.useWriterConnection { connection ->
            connection.immediateTransaction { persist(readRows(), replacement) }
        }
        current.value = state
        cachedRows = replacement
    }

    override suspend fun close() = gate.withLock {
        if (!closed) {
            closed = true
            database.close()
            current.value = null
            cachedRows = null
        }
    }

    private fun requireOpen() { check(!closed) { "数据库已关闭" } }
    private fun requireInitialized() { requireOpen(); check(current.value != null) { "数据库尚未初始化" } }
    private fun corrupt(message: String): Nothing = throw DomainException(ErrorCode.CORRUPT_DATA, message)

    private fun decode(rows: Rows): LedgerState {
        val metadata = rows.metadata ?: corrupt("数据库缺少数据集元信息")
        val decoded = try {
            LedgerState(metadata.datasetId, metadata.contentVersion,
                rows.ledgers.map { json.decodeFromString<Ledger>(it.payload) },
                rows.categories.map { json.decodeFromString<Category>(it.payload) },
                rows.entries.map { json.decodeFromString<Entry>(it.payload) },
                rows.groups.map { json.decodeFromString<DeletionGroup>(it.payload) },
                rows.budgets.map { json.decodeFromString<MonthlyBudget>(it.payload) },
                rows.sources.map { json.decodeFromString<SourceIdentity>(it.payload) },
                rows.batches.map { json.decodeFromString<ImportBatch>(it.payload) },
                rows.members.map { json.decodeFromString<ImportMember>(it.payload) },
                rows.preferences.map { json.decodeFromString<UserPreference>(it.payload) },
                rows.receipts.map { json.decodeFromString<OperationReceipt>(it.payload) },
                accounts=rows.finance.accounts.map {json.decodeFromString<Account>(it.payload)},
                entryDetails=rows.finance.entryDetails.map {json.decodeFromString<EntryDetails>(it.payload)},
                entryTags=rows.finance.entryTags.map {json.decodeFromString<EntryTag>(it.payload)},
                transfers=rows.finance.transfers.map {json.decodeFromString<AccountTransfer>(it.payload)},
                dimensions=rows.finance.dimensions.map {json.decodeFromString<Dimension>(it.payload)},
                contacts=rows.finance.contacts.map {json.decodeFromString<Contact>(it.payload)},
                obligations=rows.finance.obligations.map {json.decodeFromString<Obligation>(it.payload)},
                recurringPlans=rows.finance.recurringPlans.map {json.decodeFromString<RecurringPlan>(it.payload)},
                recurringOccurrences=rows.finance.recurringOccurrences.map {json.decodeFromString<RecurringOccurrence>(it.payload)},
                savingsPlans=rows.finance.savingsPlans.map {json.decodeFromString<SavingsPlan>(it.payload)},
                savingsMovements=rows.finance.savingsMovements.map {json.decodeFromString<SavingsMovement>(it.payload)},
                savingsWishes=rows.finance.savingsWishes.map {json.decodeFromString<SavingsWish>(it.payload)},
                budgetAllocations=rows.finance.budgetAllocations.map {json.decodeFromString<BudgetAllocation>(it.payload)},
                budgetReallocations=rows.finance.budgetReallocations.map {json.decodeFromString<BudgetReallocation>(it.payload)},

            ).also { validateState(it, environment::normalize) }
        } catch (_: IllegalArgumentException) { corrupt("数据库内容校验失败，请保留文件并从有效备份恢复") }
        if (Rows.from(decoded, environment) != rows) corrupt("数据库字段与内容不一致")
        return decoded
    }

    private suspend fun readRows() = Rows(dao.metadata(), dao.ledgerRows(), dao.categoryRows(),
        dao.entryRows(), dao.deletionGroupRows(), dao.budgetRows(), dao.sourceIdentityRows(),
        dao.importBatchRows(), dao.importMemberRows(), dao.preferenceRows(), dao.receiptRows(),FinanceStoredRows.read(dao))

    private suspend fun persist(old: Rows, next: Rows) {
        deleteFinancialRows(old.finance,next.finance,dao)
        // Child rows leave first; declared deferred FKs are checked after every final row is in place.
        for (row in removed(old.members, next.members) { it.batchId to it.entryId }) dao.deleteImportMemberRow(row.batchId, row.entryId)
        val oldSources = old.sources.associateBy { it.id }
        val changedSourceKeys = changed(old.sources, next.sources) { it.id }.filter { new ->
            oldSources[new.id]?.let { listOf(it.provider, it.scope, it.eventKind, it.externalId) != listOf(new.provider, new.scope, new.eventKind, new.externalId) } == true
        }.map { it.id }
        deleteBatches(removed(old.sources, next.sources) { it.id }.map { it.id } + changedSourceKeys, dao::deleteSourceIdentityRows)
        val removedEntries = removed(old.entries, next.entries) { it.id }
        deleteBatches(removedEntries.filter { it.parentExpenseId != null }.map { it.id }, dao::deleteEntryRows)
        deleteBatches(removedEntries.filter { it.parentExpenseId == null }.map { it.id }, dao::deleteEntryRows)
        for (row in removed(old.budgets, next.budgets) { it.ledgerId to it.yearMonth }) dao.deleteBudgetRow(row.ledgerId, row.yearMonth)
        deleteBatches(removed(old.categories, next.categories) { it.id }.map { it.id }, dao::deleteCategoryRows)
        deleteBatches(removed(old.groups, next.groups) { it.id }.map { it.id }, dao::deleteDeletionGroupRows)
        deleteBatches(removed(old.batches, next.batches) { it.id }.map { it.id }, dao::deleteImportBatchRows)
        deleteBatches(removed(old.ledgers, next.ledgers) { it.id }.map { it.id }, dao::deleteLedgerRows)
        deleteBatches(removed(old.preferences, next.preferences) { it.key }.map { it.key }, dao::deletePreferenceRows)
        deleteBatches(removed(old.receipts, next.receipts) { it.operationId }.map { it.operationId }, dao::deleteReceiptRows)

        // Restore may swap unique names between existing IDs. Stage only modified keys inside this transaction.
        val ledgers = changed(old.ledgers, next.ledgers) { it.id }
        val oldLedgers = old.ledgers.associateBy { it.id }
        for (row in ledgers) if (oldLedgers[row.id]?.let { it.normalizedName != row.normalizedName } == true) {
            dao.stageLedgerName(row.id, "\u0001dawnyew-ledger:${row.id}")
        }
        val categories = changed(old.categories, next.categories) { it.id }
        val oldCategories = old.categories.associateBy { it.id }
        for (row in categories) if (oldCategories[row.id]?.let { listOf(it.ledgerId, it.direction, it.normalizedName) != listOf(row.ledgerId, row.direction, row.normalizedName) } == true) {
            dao.stageCategoryName(row.id, "\u0001dawnyew-category:${row.id}")
        }
        val batches = changed(old.batches, next.batches) { it.id }
        val oldBatches = old.batches.associateBy { it.id }
        for (row in batches) if (oldBatches[row.id]?.let { it.submissionId != row.submissionId } == true) {
            dao.stageSubmissionId(row.id, "\u0001dawnyew-batch:${row.id}")
        }
        upsertBatches(ledgers, dao::upsertLedgerRows)
        upsertBatches(categories, dao::upsertCategoryRows)
        upsertBatches(changed(old.groups, next.groups) { it.id }, dao::upsertDeletionGroupRows)
        val entries = changed(old.entries, next.entries) { it.id }
        val oldEntries = old.entries.associateBy { it.id }
        for (row in entries) if (oldEntries[row.id]?.let { it.creationOperationId != row.creationOperationId } == true) {
            dao.stageCreationOperation(row.id, "\u0001dawnyew-creation:${row.id}")
        }
        upsertBatches(entries.filter { it.parentExpenseId == null }, dao::upsertEntryRows)
        upsertBatches(entries.filter { it.parentExpenseId != null }, dao::upsertEntryRows)
        upsertBatches(changed(old.budgets, next.budgets) { it.ledgerId to it.yearMonth }, dao::upsertBudgetRows)
        upsertBatches(batches, dao::upsertImportBatchRows)
        upsertBatches(changed(old.sources, next.sources) { it.id }, dao::upsertSourceIdentityRows)
        upsertBatches(changed(old.members, next.members) { it.batchId to it.entryId }, dao::upsertImportMemberRows)
        upsertBatches(changed(old.preferences, next.preferences) { it.key }, dao::upsertPreferenceRows)
        upsertBatches(changed(old.receipts, next.receipts) { it.operationId }, dao::upsertReceiptRows)
        upsertFinancialRows(old.finance,next.finance,dao)
        if (old.metadata != next.metadata) dao.upsertMetadata(requireNotNull(next.metadata))
    }
}

private val json = Json { encodeDefaults = true }

private data class Rows(
    val metadata: MetadataRow?,
    val ledgers: List<LedgerRow>, val categories: List<CategoryRow>, val entries: List<EntryRow>,
    val groups: List<DeletionGroupRow>, val budgets: List<BudgetRow>, val sources: List<SourceIdentityRow>,
    val batches: List<ImportBatchRow>, val members: List<ImportMemberRow>,
    val preferences: List<PreferenceRow>, val receipts: List<ReceiptRow>,
    val finance:FinanceStoredRows,
) {
    fun isEmpty() = listOf(ledgers, categories, entries, groups, budgets, sources, batches, members, preferences, receipts).all { it.isEmpty() } && finance.isEmpty()

    companion object {
        fun from(state: LedgerState, environment: DomainEnvironment, before: LedgerState? = null, rows: Rows? = null) = Rows(
            MetadataRow(datasetId = state.datasetId, contentVersion = state.contentVersion),
            mapRows(state.ledgers,before?.ledgers,rows?.ledgers,{it.id},{r,i->r.copy(position=i.toLong())}) { value,i -> LedgerRow(value.id,environment.normalize(value.name.trim()),value.currency,value.archived,i.toLong(),json.encodeToString(value)) },
            mapRows(state.categories,before?.categories,rows?.categories,{it.id},{r,i->r.copy(position=i.toLong())}) { value,i -> CategoryRow(value.id,value.ledgerId,value.direction.name,environment.normalize(value.name.trim()),i.toLong(),json.encodeToString(value)) },
            mapRows(state.entries,before?.entries,rows?.entries,{it.id},{r,i->r.copy(position=i.toLong())}) { value,i -> EntryRow(value.id,value.ledgerId,value.kind.name,if(value.kind==EntryKind.INCOME) "INCOME" else "EXPENSE",value.amountMinor,value.categoryId,value.parentExpenseId,value.deletionGroupId,value.creationOperationId,value.occurrence,literalSearchKey(value.note),i.toLong(),json.encodeToString(value)) },
            mapRows(state.deletionGroups,before?.deletionGroups,rows?.groups,{it.id},{r,i->r.copy(position=i.toLong())}) { value,i -> DeletionGroupRow(value.id,i.toLong(),json.encodeToString(value)) },
            mapRows(state.budgets,before?.budgets,rows?.budgets,{it.ledgerId to it.yearMonth},{r,i->r.copy(position=i.toLong())}) { value,i -> BudgetRow(value.ledgerId,value.yearMonth,value.amountMinor,i.toLong(),json.encodeToString(value)) },
            mapRows(state.sourceIdentities,before?.sourceIdentities,rows?.sources,{it.id},{r,i->r.copy(position=i.toLong())}) { value,i -> SourceIdentityRow(value.id,value.entryId,value.provider,value.scope,value.eventKind,value.externalId,i.toLong(),json.encodeToString(value)) },
            mapRows(state.importBatches,before?.importBatches,rows?.batches,{it.id},{r,i->r.copy(position=i.toLong())}) { value,i -> ImportBatchRow(value.id,value.submissionId,i.toLong(),json.encodeToString(value)) },
            mapRows(state.importMembers,before?.importMembers,rows?.members,{it.batchId to it.entryId},{r,i->r.copy(position=i.toLong())}) { value,i -> ImportMemberRow(value.batchId,value.entryId,value.entryId.takeIf { value.state=="PRESENT" },value.state,i.toLong(),json.encodeToString(value)) },
            mapRows(state.preferences,before?.preferences,rows?.preferences,{it.key},{r,i->r.copy(position=i.toLong())}) { value,i -> PreferenceRow(value.key,i.toLong(),json.encodeToString(value)) },
            mapRows(state.receipts,before?.receipts,rows?.receipts,{it.operationId},{r,i->r.copy(position=i.toLong())}) { value,i -> ReceiptRow(value.operationId,i.toLong(),json.encodeToString(value)) },
            FinanceStoredRows.from(state,before,rows?.finance),
        )
    }
}

internal fun <T, K> removed(old: List<T>, next: List<T>, key: (T) -> K): List<T> {
    val keep = next.map(key).toSet()
    return old.filter { key(it) !in keep }
}
internal fun <T, K> changed(old: List<T>, next: List<T>, key: (T) -> K): List<T> {
    val previous = old.associateBy(key)
    return next.filter { previous[key(it)] != it }
}
internal suspend fun deleteBatches(keys: List<String>, delete: suspend (List<String>) -> Unit) {
    for (batch in keys.distinct().chunked(200)) delete(batch)
}
internal suspend fun <T> upsertBatches(rows: List<T>, upsert: suspend (List<T>) -> Unit) {
    for (batch in rows.chunked(200)) upsert(batch)
}


internal fun <T,R,K> mapRows(values:List<T>,before:List<T>?,rows:List<R>?,key:(T)->K,position:(R,Int)->R,encode:(T,Int)->R):List<R> {
    if(values === before && rows!=null) return rows
    val previous=if(before!=null && rows!=null) before.mapIndexed { i,value -> key(value) to (value to rows[i]) }.toMap() else emptyMap()
    return values.mapIndexed { i,value -> previous[key(value)]?.takeIf { it.first==value }?.let { position(it.second,i) } ?: encode(value,i) }
}
