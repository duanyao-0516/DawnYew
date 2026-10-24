package com.dawnyew.storage

import com.dawnyew.core.*
import java.util.UUID

/** Every added entity participates in a real, connected graph (including the savings/transfer cycle). */
internal fun financialGraphFixture():LedgerState {
    fun id()=UUID.randomUUID().toString()
    val date="2026-10-17T10:00:00";val opening="2026-10-01T00:00:00";val utc="2026-10-18T04:00:00Z"
    val ledger=Ledger(id(),"Full graph");val food=Category(id(),ledger.id,Direction.EXPENSE,"Food");val transport=Category(id(),ledger.id,Direction.EXPENSE,"Transport")
    val cash=Account(id(),ledger.id,"Cash",openingAt=opening);val bank=Account(id(),ledger.id,"Bank",openingAt=opening)
    val payable=Account(id(),ledger.id,"Loan account",AccountType.PAYABLE,openingAt=opening,hidden=true)
    val contact=Contact(id(),ledger.id,"Person")
    val obligation=Obligation(id(),ledger.id,ObligationKind.BORROW,"Loan",payable.id,opening,contact.id)
    val principal=AccountTransfer(id(),ledger.id,payable.id,cash.id,1000,date,createdAt=utc,updatedAt=utc,businessKind=TransferBusinessKind.OBLIGATION_PRINCIPAL,businessId=obligation.id)
    val project=Dimension(id(),ledger.id,DimensionKind.PROJECT,"Travel");val tag=Dimension(id(),ledger.id,DimensionKind.TAG,"Work");val merchant=Dimension(id(),ledger.id,DimensionKind.MERCHANT,"Shop")
    val entry=Entry(id(),ledger.id,EntryKind.EXPENSE,100,food.id,date,createdAt=utc,updatedAt=utc,creationOperationId=id())
    val recurring=RecurringPlan(id(),ledger.id,"Daily",ActivityKind.EXPENSE,100,date,RecurringFrequency.DAILY,nextIndex=1,categoryId=food.id,accountId=cash.id)
    val occurrence=RecurringOccurrence(id(),recurring.id,0,date,RecurringOccurrenceStatus.GENERATED,entry.id)
    val plan=SavingsPlan(id(),ledger.id,"Goal",SavingsScenario.GOAL,1000)
    val deposit=SavingsMovement(id(),plan.id,SavingsMovementKind.DEPOSIT,500,date)
    val wishMovementId=id();val savingsTransferId=id()
    val wishMovement=SavingsMovement(wishMovementId,plan.id,SavingsMovementKind.WISH,100,date,transferId=savingsTransferId)
    val savingsTransfer=AccountTransfer(savingsTransferId,ledger.id,cash.id,bank.id,100,date,createdAt=utc,updatedAt=utc,businessKind=TransferBusinessKind.SAVINGS,businessId=wishMovementId)
    val wish=SavingsWish(id(),plan.id,"Book",100,wishMovementId)
    val allocation1=BudgetAllocation(id(),ledger.id,"2026-10",food.id,400);val allocation2=BudgetAllocation(id(),ledger.id,"2026-10",transport.id,600)
    val reallocation=BudgetReallocation(id(),ledger.id,"2026-10",allocation1.id,allocation2.id,100,utc)
    return LedgerState(id(),ledgers=listOf(ledger),categories=listOf(food,transport),entries=listOf(entry),budgets=listOf(MonthlyBudget(ledger.id,"2026-10",1000)),
        accounts=listOf(cash,bank,payable),entryDetails=listOf(EntryDetails(entry.id,cash.id,projectId=project.id,merchantId=merchant.id)),entryTags=listOf(EntryTag(entry.id,tag.id)),
        transfers=listOf(principal,savingsTransfer),dimensions=listOf(project,tag,merchant),contacts=listOf(contact),obligations=listOf(obligation),
        recurringPlans=listOf(recurring),recurringOccurrences=listOf(occurrence),savingsPlans=listOf(plan),savingsMovements=listOf(deposit,wishMovement),savingsWishes=listOf(wish),
        budgetAllocations=listOf(allocation1,allocation2),budgetReallocations=listOf(reallocation))
}
