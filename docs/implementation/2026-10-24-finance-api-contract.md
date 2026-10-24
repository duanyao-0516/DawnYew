# Offline finance API contract — 2026-10-24

API_FREEZE 1. Kotlin declarations below are implemented in `shared/core` under package `com.dawnyew.core`. All new entities are scalar serializable records. Existing Entry/Category and old nine wire shapes are unchanged. Calls use `Command.Finance(action)`; commands publish one contentVersion increment and one outer receipt. New entity IDs are caller-generated canonical UUIDs; `SaveX.expectedRevision=null` creates only, update requires current revision. Stored revision is managed by engine, not trusted from draft.

`LedgerState` and `LedgerEntityBatch` append, in this exact order: `accounts:List<Account>`, `entryDetails:List<EntryDetails>`, `entryTags:List<EntryTag>`, `transfers:List<AccountTransfer>`, `dimensions:List<Dimension>`, `contacts:List<Contact>`, `obligations:List<Obligation>`, `recurringPlans:List<RecurringPlan>`, `recurringOccurrences:List<RecurringOccurrence>`, `savingsPlans:List<SavingsPlan>`, `savingsMovements:List<SavingsMovement>`, `savingsWishes:List<SavingsWish>`, `budgetAllocations:List<BudgetAllocation>`, `budgetReallocations:List<BudgetReallocation>`; all default empty. `ledgerEntityTypes` preserves first nine then appends those fourteen names. New UI-consumed preferences: amountDisplay=precise|compact, continueEntry=true|false, defaultAccountId=canonical UUID|none. Shared validatePreference validates command/full-state/batch paths. Deleting the default account atomically changes this value to none; drafts ignore a default account when missing, archived, in another ledger or owned by an obligation. No financial amounts are stored in preferences.

Identity: entryDetails=entryId; entryTags=(entryId,tagId); all other new records=id. Additional unique keys: Dimension(ledgerId,kind,name), Contact(ledgerId,name), Account(ledgerId,name), RecurringOccurrence(planId,index), Obligation.virtualAccountId, non-null SavingsWish.fulfilledMovementId, BudgetAllocation(ledgerId,yearMonth,categoryId), non-null BudgetReallocation.reversalOfId, non-null RecurringOccurrence.entryId/transferId, non-null SavingsMovement.transferId. All amounts are Long minor units; openingBalanceMinor alone is signed (abs<=Money.MAX), allocations allow zero, other amounts positive. Counts/indices are Int. Transfers have no creationOperationId; caller id + outer receipt supply identity. Each recurring Entry receives its own UUID creationOperationId.

Old SaveEntry adds trailing `details:EntryDetails?=null, tagIds:List<String>?=null`; old AddRefund adds same. Null preserves existing associations (new record defaults); non-null details.entryId is replaced by saved Entry id, allowing empty id draft for new Entry. Explicit SetEntryDetails with null clears to defaults. Tag list is replacement, never additive. Any change increments Entry.revision. Refund excludedFromBudget must false; query reads current parent dynamically.

SaveObligation creation requires initialPrincipal, atomically creates obligation, virtual account and first transfer; editing forbids initialPrincipal and preserves kind/account/opening. Obligation virtual account may only be used by its own RecordObligation actions. RecordObligation transferId=null adds; non-null edits with expectedRevision. SetObligationTransferDeleted handles deletion/restoration under remaining-balance validation.

Savings endpoints are explicit; double-null means no transfer. For every movement kind from-only subtracts from managed funds, to-only adds, both transfer, regardless of progress direction. Savings movement transferId and Wish fulfilledMovementId are engine-owned. WISH changes only through fulfill/cancel. Plans with history are archived, not deleted. Recurring saved new plans process at most their first due occurrence. ProcessRecurring processes up to limit (1..100) across due active plans; no future period, paused or archived executions. ExecuteRecurring permits one due period, optionally skip; nextIndex includes skipped periods. Existing generated entries can be soft-deleted/purged; occurrence remains and deleted result reference clears on purge.

Budget allocation current amount is authoritative. Reallocation rows are immutable; reversal creates one linked reverse row. Delete allocation with any history is rejected. SetBudget(null) clears monthly parent, allocations and histories atomically. Lower total rejects child sum overflow.

Repository additions:
```kotlin
suspend fun queryActivityPage(scope:ActivityScope, cursor:ActivityCursor?=null, limit:Int=50):ActivityPage
suspend fun financeSnapshot(ledgerIds:Set<String> = emptySet()):FinanceSnapshot
```
`StatisticsSnapshot` appends `budgetExpense:Long=expense, budgetRefunds:Long=refunds`; `budgetUsed=max(checkedAdd(budgetExpense,-budgetRefunds),0)`. SQL must populate both independently of ordinary expense/refunds.
Activity default excludes deleted; `deleted=true` queries recycle projection. Scope matches selected tags ANY, selected account either transfer endpoint; project/merchant/tags filters exclude transfers. Budget filter for transfer is always false. Archived ledger filter only concerns ledger; archived accounts/dimensions retain activity. Category filter excludes transfer. Family ordering uses name strings: TRANSFER before ENTRY at equal occurrence. Cursor is bound to complete scope/dataset/version. min/max inclusive. IDs unique only within family, never assume a global activity UUID.

## Entity and command declarations
```kotlin
package com.dawnyew.core

import kotlinx.serialization.Serializable

@Serializable enum class AccountType { CASH, BANK, CREDIT, E_WALLET, INVESTMENT, RECEIVABLE, PAYABLE, PREPAID }
@Serializable data class Account(val id:String, val ledgerId:String, val name:String, val type:AccountType=AccountType.CASH, val openingBalanceMinor:Long=0, val openingAt:String, val archived:Boolean=false, val hidden:Boolean=false, val includedInAssets:Boolean=true, val note:String="", val sortOrder:Int=0, val revision:Long=1)
@Serializable data class EntryDetails(val entryId:String, val accountId:String?=null, val recorded:Boolean=true, val excludedFromBudget:Boolean=false, val reconciled:Boolean=false, val projectId:String?=null, val merchantId:String?=null)
@Serializable data class EntryTag(val entryId:String, val tagId:String)
@Serializable enum class TransferBusinessKind { MANUAL, OBLIGATION_PRINCIPAL, OBLIGATION_SETTLEMENT, SAVINGS }
@Serializable data class AccountTransfer(val id:String, val ledgerId:String, val fromAccountId:String?, val toAccountId:String?, val amountMinor:Long, val occurrence:String, val note:String="", val recorded:Boolean=true, val reconciled:Boolean=false, val deleted:Boolean=false, val revision:Long=1, val createdAt:String, val updatedAt:String, val businessKind:TransferBusinessKind=TransferBusinessKind.MANUAL, val businessId:String?=null) { val active get()=!deleted; val localDate get()=occurrence.substringBefore('T') }
@Serializable enum class DimensionKind { PROJECT, MERCHANT, TAG }
@Serializable data class Dimension(val id:String, val ledgerId:String, val kind:DimensionKind, val name:String, val archived:Boolean=false, val sortOrder:Int=0, val revision:Long=1)
@Serializable data class Contact(val id:String, val ledgerId:String, val name:String, val note:String="", val archived:Boolean=false, val revision:Long=1)
@Serializable enum class ObligationKind { BORROW, LEND, REIMBURSEMENT }
@Serializable data class Obligation(val id:String, val ledgerId:String, val kind:ObligationKind, val title:String, val virtualAccountId:String, val openingAt:String, val contactId:String?=null, val dueDate:String?=null, val note:String="", val archived:Boolean=false, val revision:Long=1)
@Serializable enum class RecurringFrequency { DAILY, WEEKLY, BIWEEKLY, MONTHLY, YEARLY, QUARTERLY, WEEKDAYS, WEEKENDS }
@Serializable enum class ActivityKind { EXPENSE, INCOME, REFUND, TRANSFER }
@Serializable data class RecurringPlan(val id:String, val ledgerId:String, val name:String, val kind:ActivityKind, val amountMinor:Long, val anchor:String, val frequency:RecurringFrequency, val totalOccurrences:Int?=null, val nextIndex:Int=0, val paused:Boolean=false, val archived:Boolean=false, val categoryId:String?=null, val accountId:String?=null, val fromAccountId:String?=null, val toAccountId:String?=null, val note:String="", val recorded:Boolean=true, val excludedFromBudget:Boolean=false, val reconciled:Boolean=false, val projectId:String?=null, val merchantId:String?=null, val revision:Long=1)
@Serializable enum class RecurringOccurrenceStatus { GENERATED, SKIPPED }
@Serializable data class RecurringOccurrence(val id:String, val planId:String, val index:Int, val scheduledAt:String, val status:RecurringOccurrenceStatus, val entryId:String?=null, val transferId:String?=null)
@Serializable enum class SavingsScenario { CHALLENGE, DREAM, GOAL, FLEXIBLE }
@Serializable data class SavingsPlan(val id:String, val ledgerId:String, val name:String, val scenario:SavingsScenario=SavingsScenario.GOAL, val targetMinor:Long?=null, val note:String="", val archived:Boolean=false, val revision:Long=1)
@Serializable enum class SavingsMovementKind { DEPOSIT, WITHDRAWAL, WISH }
@Serializable data class SavingsMovement(val id:String, val planId:String, val kind:SavingsMovementKind, val amountMinor:Long, val occurrence:String, val note:String="", val transferId:String?=null, val deleted:Boolean=false, val revision:Long=1) { val active get()=!deleted }
@Serializable data class SavingsWish(val id:String, val planId:String, val name:String, val amountMinor:Long, val fulfilledMovementId:String?=null, val revision:Long=1)
@Serializable data class BudgetAllocation(val id:String, val ledgerId:String, val yearMonth:String, val categoryId:String, val amountMinor:Long, val revision:Long=1)
@Serializable data class BudgetReallocation(val id:String, val ledgerId:String, val yearMonth:String, val fromAllocationId:String, val toAllocationId:String, val amountMinor:Long, val createdAt:String, val reversalOfId:String?=null)
/** Missing endpoint explicitly means funds outside managed accounts. Both missing means progress only. */
@Serializable data class TransferEndpoints(val fromAccountId:String?=null, val toAccountId:String?=null, val recorded:Boolean=true, val reconciled:Boolean=false)

@Serializable data class ObligationPrincipal(val accountId:String, val amountMinor:Long, val occurrence:String, val recorded:Boolean=true, val reconciled:Boolean=false, val note:String="")

/** New saves use caller-generated UUIDs. expectedRevision is null only when creating a new record. */
@Serializable sealed interface FinanceAction {
    @Serializable data class SaveAccount(val value:Account, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteAccount(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveDimension(val value:Dimension, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteDimension(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveContact(val value:Contact, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteContact(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class SetEntryDetails(val entryId:String, val details:EntryDetails?, val tagIds:List<String>, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveTransfer(val value:AccountTransfer, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class SetTransferDeleted(val id:String, val deleted:Boolean, val expectedRevision:Long):FinanceAction
    @Serializable data class PurgeTransfer(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveObligation(val value:Obligation, val expectedRevision:Long?=null, val initialPrincipal:ObligationPrincipal?=null):FinanceAction
    @Serializable data class DeleteObligation(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class RecordObligation(val obligationId:String, val accountId:String, val amountMinor:Long, val occurrence:String, val settlement:Boolean=false, val recorded:Boolean=true, val reconciled:Boolean=false, val note:String="", val transferId:String?=null, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class SetObligationTransferDeleted(val id:String, val deleted:Boolean, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveRecurringPlan(val value:RecurringPlan, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteRecurringPlan(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class ExecuteRecurring(val planId:String, val expectedRevision:Long, val skip:Boolean=false):FinanceAction
    @Serializable data class ProcessRecurring(val limit:Int=100):FinanceAction
    @Serializable data class SaveSavingsPlan(val value:SavingsPlan, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteSavingsPlan(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveSavingsMovement(val value:SavingsMovement, val endpoints:TransferEndpoints=TransferEndpoints(), val expectedRevision:Long?=null):FinanceAction
    @Serializable data class SetSavingsMovementDeleted(val id:String, val deleted:Boolean, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveSavingsWish(val value:SavingsWish, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteSavingsWish(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class FulfillSavingsWish(val id:String, val occurrence:String, val endpoints:TransferEndpoints=TransferEndpoints(), val expectedRevision:Long):FinanceAction
    @Serializable data class CancelSavingsWish(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class SaveBudgetAllocation(val value:BudgetAllocation, val expectedRevision:Long?=null):FinanceAction
    @Serializable data class DeleteBudgetAllocation(val id:String, val expectedRevision:Long):FinanceAction
    @Serializable data class ReallocateBudget(val fromAllocationId:String, val toAllocationId:String, val amountMinor:Long, val fromRevision:Long, val toRevision:Long):FinanceAction
    @Serializable data class ReverseBudgetReallocation(val id:String, val fromRevision:Long, val toRevision:Long):FinanceAction
}
```

## Query declarations
```kotlin
package com.dawnyew.core

/** Identity is family + id; ordering is occurrence DESC, family.name DESC, id DESC. */
enum class ActivityFamily { ENTRY, TRANSFER }
data class ActivityScope(val ledgerIds:Set<String> = emptySet(), val startDate:String="0001-01-01", val endDate:String="9999-12-31", val kinds:Set<ActivityKind> = emptySet(), val categoryIds:Set<String> = emptySet(), val minAmountMinor:Long?=null, val maxAmountMinor:Long?=null, val accountIds:Set<String> = emptySet(), val projectIds:Set<String> = emptySet(), val merchantIds:Set<String> = emptySet(), val tagIds:Set<String> = emptySet(), val keyword:String="", val recorded:Boolean?=null, val excludedFromBudget:Boolean?=null, val reconciled:Boolean?=null, val includeArchived:Boolean=true, val deleted:Boolean=false)
data class FinancialActivity(val family:ActivityFamily, val id:String, val ledgerId:String, val kind:ActivityKind, val amountMinor:Long, val occurrence:String, val note:String, val recorded:Boolean, val reconciled:Boolean, val excludedFromBudget:Boolean, val categoryId:String?=null, val accountId:String?=null, val fromAccountId:String?=null, val toAccountId:String?=null, val projectId:String?=null, val merchantId:String?=null, val tagIds:Set<String> = emptySet(), val businessKind:TransferBusinessKind?=null, val businessId:String?=null, val deleted:Boolean=false, val revision:Long=1) { val identity get()="${family.name}:$id"; val localDate get()=occurrence.substringBefore('T') }
data class ActivityCursor(val occurrence:String,val family:ActivityFamily,val id:String,val datasetId:String,val contentVersion:Long,val scope:ActivityScope)
data class ActivityPage(val activities:List<FinancialActivity>,val nextCursor:ActivityCursor?,val datasetId:String,val contentVersion:Long)
data class AccountBalance(val account:Account,val balanceMinor:Long)
data class ObligationBalance(val obligation:Obligation,val principalMinor:Long,val settledMinor:Long,val remainingMinor:Long,val pendingPrincipalMinor:Long,val pendingSettlementMinor:Long) { fun isOverdue(localDate:String)=remainingMinor>0 && obligation.dueDate?.let { it<localDate }==true }
data class SavingsProgress(val plan:SavingsPlan,val savedMinor:Long)
data class FinanceSnapshot(val accounts:List<AccountBalance>,val obligations:List<ObligationBalance>,val savings:List<SavingsProgress>,val assetsMinor:Long,val liabilitiesMinor:Long,val netWorthMinor:Long)
fun QueryScope.toActivityScope():ActivityScope = ActivityScope(ledgerIds,startDate,endDate,kinds.map { ActivityKind.valueOf(it.name) }.toSet(),categoryIds,keyword=keyword,includeArchived=includeArchived)

fun effectiveBudgetExcluded(state:LedgerState,entry:Entry):Boolean
fun activityPageSnapshot(state:LedgerState,scope:ActivityScope,cursor:ActivityCursor?=null,limit:Int=50):ActivityPage
fun recurringDate(plan:RecurringPlan,index:Int):String
object FinanceQueries { fun calculate(state:LedgerState,ledgerIds:Set<String> = emptySet()):FinanceSnapshot }
```

## Activity totals and daily calendar (API_FREEZE 1 addition)
```kotlin
data class ActivityDaySummary(val date:String,val count:Long,val income:Long,val expense:Long,val refunds:Long,val transferAmount:Long)
data class ActivitySummary(val income:Long,val expense:Long,val refunds:Long,val transferAmount:Long,val activityCount:Long,val days:List<ActivityDaySummary>)
suspend fun LedgerRepository.activitySummary(scope:ActivityScope):ActivitySummary // actual interface member
fun activitySummarySnapshot(state:LedgerState,scope:ActivityScope):ActivitySummary
fun validateActivityScope(scope:ActivityScope)
fun validateActivityCursor(scope:ActivityScope,cursor:ActivityCursor?,datasetId:String,contentVersion:Long,limit:Int)
```
Days are sparse, date ascending, same exact ActivityScope semantics. Counts are Long; no UI pagination scanning for totals. ProcessRecurring is a strict atomic operation; foreground coordination should ExecuteRecurring per plan with an aggregate 100-period cap to surface each blocked plan while allowing other plans to continue.

## Reviewed execution clarifications

- Recorded-only obligation accounting is authoritative. Pending settlements do not reserve remaining funds and may coexist; changing a settlement to recorded must atomically reject any overpayment. Both remaining balance and net assets use only active, recorded transfers. Pending principal cannot finance a recorded settlement.
- Recurring future template account compatibility uses the next unprocessed scheduled occurrence, rather than the original anchor. This permits selecting an account opened after the original plan began. `fun recurringNextOccurrence(plan:RecurringPlan):String?` returns that date, or null for an exhausted finite plan; pause/archive do not change schedule identity. Exhausted templates still require existing same-ledger, non-virtual accounts but have no date to bind. Generated historical results retain their actual occurrence and own references.
- Budget history retains allocation/category identity; displayed names follow current category labels. History never adds a second amount to current allocation.

## DawnYew CSV v1 integration

Protocol entry points in `com.dawnyew.protocol`:
```kotlin
object DawnYewCsv {
    fun prepareImport(document:DawnYewCsvDocument, ledgerId:String, state:LedgerState, environment:DomainEnvironment, categoryMapping:Map<CsvCategoryKey,String> = emptyMap()):PreparedCsvImport
    fun prepareExport(state:LedgerState, scope:QueryScope):PreparedCsvExport
}
```
Android stream boundary in `com.dawnyew.platform.tabular`:
```kotlin
object DawnYewCsvCodec {
    fun read(input:InputStream, mimeType:String="text/csv", checkCancelled:()->Unit={}):DawnYewCsvDocument
    fun encode(prepared:PreparedCsvExport, checkCancelled:()->Unit={}):ByteArray
    fun write(output:OutputStream, bytes:ByteArray, checkCancelled:()->Unit={})
}
```

`read` verifies UTF-8, optional BOM, exact `DawnYew CSV,1` framing and ordered header before parsing records. Limits are 32 MiB of original input bytes, 10,000 logical transaction records and 8 KiB UTF-8 per decoded field. RFC quoting, doubled quotes, embedded CR/LF and physical line ranges are preserved; logical record numbers, not physical lines, drive selection and the 10,000-record limit. Caller owns streams. Encoding performs the same export bounds before a destination is created, and cancellation callbacks are checked while reading/encoding/writing.

Only active EXPENSE/INCOME are exported under the supplied QueryScope. `CsvExportSummary` exposes includedCount, excludedRefundCount, excludedTransferCount, excludedDeletedCount and omittedDetailsCount, so UI can disclose scope and CSV omissions before writing. All ledger/category/note cells carry one apostrophe marker; import requires and removes exactly that marker, preserving formula-looking text as data. Amount uses fixed two decimals and source UUIDs are mandatory.

`PreparedCsvImport.defaultSelectedRows` contains structurally valid rows. `ImportPreviewer` still reports source duplicates, conflicts and suspected duplicates; suspected rows need an explicit keep selection. Exact normalized direction/name matches reuse a target category, missing categories appear in mapping.newCategories for preview, and disabled/invalid mappings require correction. For this CSV adapter only, the sealed commit plan creates categories referenced by selected NEW rows; unselected or duplicate-only rows cannot create unused categories. UI displays `preview.plan.newCategories` and submits that sealed plan through the existing CommitImport gate.

Source identity is `(DAWNYEW_CSV, source_dataset, type, source_entry)` with canonical source content digest. Exact repeated source content skips, changed content conflicts. Reimport into the originating dataset can reuse its existing Entry only when sameImportedBusiness agrees and any matching source agrees; this narrow identity path keeps the original Entry and its existing source records unchanged. Imported new rows keep normal batch membership, revision/digest protection and atomic UndoImport semantics.
