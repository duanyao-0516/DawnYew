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
    @Serializable data class ReorderAccounts(val ledgerId:String, val accountType:AccountType, val orderedIds:List<String>, val expectedVersion:Long):FinanceAction
    @Serializable data class ReorderDimensions(val ledgerId:String, val kind:DimensionKind, val orderedIds:List<String>, val expectedVersion:Long):FinanceAction
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
