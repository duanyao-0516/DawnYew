package com.dawnyew.core

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow

@Serializable enum class Direction { EXPENSE, INCOME }
@Serializable enum class EntryKind { EXPENSE, INCOME, REFUND }
@Serializable enum class OriginKind { MANUAL, OCR, IMPORT }
@Serializable data class Ledger(val id: String, val name: String, val archived: Boolean = false, val sortOrder: Int = 0, val revision: Long = 1, val currency: String = "CNY")
@Serializable data class Category(val id: String, val ledgerId: String, val direction: Direction, val name: String, val enabled: Boolean = true, val sortOrder: Int = 0, val revision: Long = 1)
@Serializable data class Entry(
    val id: String, val ledgerId: String, val kind: EntryKind, val amountMinor: Long,
    val categoryId: String, val occurrence: String, val note: String = "",
    val parentExpenseId: String? = null, val deletionGroupId: String? = null,
    val revision: Long = 1, val createdAt: String, val updatedAt: String,
    val originKind: OriginKind = OriginKind.MANUAL, val creationOperationId: String,
    val captureFingerprint: String? = null, val captureEngineVersion: String? = null,
) { val active get() = deletionGroupId == null; val localDate get() = occurrence.substringBefore('T') }
@Serializable data class DeletionGroup(val id: String, val deletedAt: String, val rootEntryId: String, val reason: String = "USER")
@Serializable data class MonthlyBudget(val ledgerId: String, val yearMonth: String, val amountMinor: Long, val revision: Long = 1)
@Serializable data class SourceIdentity(val id: String, val entryId: String, val provider: String, val scope: String, val eventKind: String, val externalId: String, val normalizationVersion: Int = 1, val canonicalSource: String, val sourceDigest: String)
@Serializable data class ImportBatch(val id: String, val submissionId: String, val adapterId: String, val adapterVersion: String, val fileDigest: String, val previewDigest: String, val mappingDigest: String, val createdAt: String, val status: String = "COMMITTED", val count: Int = 0)
@Serializable data class ImportMember(val batchId: String, val entryId: String, val committedRevision: Long, val committedContentDigest: String, val state: String = "PRESENT")
@Serializable data class OperationReceipt(val operationId: String, val commandType: String, val payloadDigest: String, val resultIds: List<String>, val committedAt: String)
@Serializable data class UserPreference(val key: String, val value: String)
@Serializable data class LedgerState(
    val datasetId: String, val contentVersion: Long = 1,
    val ledgers: List<Ledger> = emptyList(), val categories: List<Category> = emptyList(),
    val entries: List<Entry> = emptyList(), val deletionGroups: List<DeletionGroup> = emptyList(),
    val budgets: List<MonthlyBudget> = emptyList(), val sourceIdentities: List<SourceIdentity> = emptyList(),
    val importBatches: List<ImportBatch> = emptyList(), val importMembers: List<ImportMember> = emptyList(),
    val preferences: List<UserPreference> = emptyList(), val receipts: List<OperationReceipt> = emptyList(),
    val accounts:List<Account> = emptyList(), val entryDetails:List<EntryDetails> = emptyList(), val entryTags:List<EntryTag> = emptyList(),
    val transfers:List<AccountTransfer> = emptyList(), val dimensions:List<Dimension> = emptyList(), val contacts:List<Contact> = emptyList(),
    val obligations:List<Obligation> = emptyList(), val recurringPlans:List<RecurringPlan> = emptyList(), val recurringOccurrences:List<RecurringOccurrence> = emptyList(),
    val savingsPlans:List<SavingsPlan> = emptyList(), val savingsMovements:List<SavingsMovement> = emptyList(), val savingsWishes:List<SavingsWish> = emptyList(),
    val budgetAllocations:List<BudgetAllocation> = emptyList(), val budgetReallocations:List<BudgetReallocation> = emptyList(),
)

@Serializable sealed interface Command {
    @Serializable data class Finance(val action:FinanceAction):Command
    @Serializable data class CreateLedger(val name: String) : Command
    @Serializable data class UpdateLedger(val id: String, val name: String, val archived: Boolean, val sortOrder: Int) : Command
    @Serializable data class ReorderLedgers(val orderedIds:List<String>, val expectedVersion:Long) : Command
    @Serializable data class ReorderCategories(val ledgerId:String, val direction:Direction, val orderedIds:List<String>, val expectedVersion:Long) : Command
    @Serializable data class DeleteLedger(val id: String) : Command
    @Serializable data class SaveCategory(val ledgerId: String, val direction: Direction, val name: String, val id: String? = null, val enabled: Boolean = true, val sortOrder: Int = 0) : Command
    @Serializable data class DeleteCategory(val id: String) : Command
    @Serializable data class SaveEntry(val id: String? = null, val ledgerId: String, val kind: EntryKind, val amountMinor: Long, val categoryId: String, val occurrence: String, val note: String, val expectedRevision: Long? = null, val originKind: OriginKind = OriginKind.MANUAL, val captureFingerprint: String? = null, val captureEngineVersion: String? = null, val details:EntryDetails? = null, val tagIds:List<String>? = null) : Command
    @Serializable data class AddRefund(val expenseId: String, val amountMinor: Long, val occurrence: String, val note: String, val details:EntryDetails? = null, val tagIds:List<String>? = null) : Command
    @Serializable data class MoveEntry(val id: String, val ledgerId: String, val categoryId: String) : Command
    @Serializable data class SoftDelete(val id: String) : Command
    @Serializable data class RestoreGroup(val groupId: String) : Command
    @Serializable data class Purge(val id: String) : Command
    @Serializable data class SetBudget(val ledgerId: String, val yearMonth: String, val amountMinor: Long?) : Command
    @Serializable data class SetPreference(val key: String, val value: String) : Command
    @Serializable data class CommitImport(val plan: ImportPlan) : Command
    @Serializable data class GenerateDebugEntries(val ledgerId: String, val startDate: String, val endDate: String, val count: Int) : Command
    @Serializable data class UndoImport(val batchId: String) : Command
    @Serializable data object ClearAll : Command
}

enum class ErrorCode { VALIDATION, CONFLICT, LOCKED, CORRUPT_DATA, STORAGE, UNSUPPORTED, CANCELLED }
class DomainException(val code: ErrorCode, override val message: String) : IllegalArgumentException(message)
fun invalid(message: String): Nothing = throw DomainException(ErrorCode.VALIDATION, message)
fun conflict(message: String): Nothing = throw DomainException(ErrorCode.CONFLICT, message)
data class Mutation(val state: LedgerState, val resultIds: List<String>)
interface DomainEnvironment {
    fun id(): String
    fun localNow(): String
    fun utcNow(): String
    fun normalize(value: String): String
}
interface LedgerRepository {
    val state: StateFlow<LedgerState?>
    val pendingOperation:StateFlow<PendingOperationRecovery?> get()=noPendingOperation
    suspend fun lookupOperation(operationId:String):OperationOutcome? {
        validateId(operationId);val data=snapshot()
        data.receipts.firstOrNull { it.operationId==operationId }?.let { return OperationOutcome(operationId,it.commandType,it.resultIds) }
        data.importBatches.firstOrNull { it.submissionId==operationId && it.adapterId==DEBUG_BATCH_ADAPTER_ID }?.let {
            return OperationOutcome(operationId,"GenerateDebugEntries",listOf(it.id))
        }
        return data.entries.firstOrNull { it.creationOperationId==operationId }?.let { OperationOutcome(operationId,if(it.kind==EntryKind.REFUND) "AddRefund" else "SaveEntry",listOf(it.id)) }
    }
    suspend fun acknowledgeOperation(operationId:String) = Unit
    suspend fun initialize()
    suspend fun execute(command: Command, operationId: String, expectedVersion: Long? = null): List<String>
    suspend fun queryEntryPage(scope: QueryScope, cursor: EntryCursor? = null, limit: Int = 50): EntryPage = pageSnapshot(snapshot(),scope,cursor,limit)
    suspend fun findCaptureDuplicates(fingerprint:String?,amountMinor:Long?,localDate:String?):CaptureDuplicateSummary {
        val matches=snapshot().entries.filter { (fingerprint!=null && it.captureFingerprint==fingerprint) || (amountMinor!=null && localDate!=null && it.kind==EntryKind.EXPENSE && it.amountMinor==amountMinor && it.localDate==localDate) }
        return CaptureDuplicateSummary(matches.any { fingerprint!=null && it.captureFingerprint==fingerprint },matches.any { amountMinor!=null && localDate!=null && it.kind==EntryKind.EXPENSE && it.amountMinor==amountMinor && it.localDate==localDate },matches.any { !it.active })
    }
    suspend fun queryActivityPage(scope:ActivityScope, cursor:ActivityCursor?=null, limit:Int=50):ActivityPage = activityPageSnapshot(snapshot(),scope,cursor,limit)
    suspend fun activitySummary(scope:ActivityScope):ActivitySummary = activitySummarySnapshot(snapshot(),scope)
    suspend fun panelStatistics(request:PanelRequest):PanelStatistics = PanelQueries.calculate(snapshot(),request)
    suspend fun accountHistory(request:AccountHistoryRequest):AccountHistorySnapshot = AccountHistoryQueries.calculate(snapshot(),request)
    suspend fun financeSnapshot(ledgerIds:Set<String> = emptySet()):FinanceSnapshot = FinanceQueries.calculate(snapshot(),ledgerIds)
    suspend fun statistics(scope: QueryScope): StatisticsSnapshot = Statistics.calculate(snapshot(),scope)
    suspend fun snapshot(): LedgerState
    suspend fun replace(state: LedgerState)
    suspend fun close()
}

data class ImageHandle(val token: String)
data class OcrPoint(val x: Float, val y: Float)
data class OcrTextBlock(val text: String, val confidence: Float, val polygon: List<OcrPoint>)
data class OcrResult(val blocks: List<OcrTextBlock>, val engineVersion: String)
interface OcrEnginePort { suspend fun recognize(image: ImageHandle): OcrResult }
data class DocumentHandle(val token: String)
interface FileAccessPort { suspend fun select(mimeTypes: List<String>): DocumentHandle? }
enum class AuthenticationResult { SUCCESS, CANCELLED, UNAVAILABLE, LOCKED }
interface AuthPort { suspend fun authenticate(reason: String): AuthenticationResult }
interface TextNormalizationPort { fun nfc(value: String): String }
interface ClockPort { fun localNow(): String; fun utcNow(): String }
interface IdGenerator { fun newId(): String }
