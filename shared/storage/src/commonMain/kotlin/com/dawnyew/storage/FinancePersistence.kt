package com.dawnyew.storage

import com.dawnyew.core.*

internal data class FinanceStoredRows(    val accounts:List<AccountRow> = emptyList(),
    val entryDetails:List<EntryDetailsRow> = emptyList(),
    val entryTags:List<EntryTagRow> = emptyList(),
    val transfers:List<TransferRow> = emptyList(),
    val dimensions:List<DimensionRow> = emptyList(),
    val contacts:List<ContactRow> = emptyList(),
    val obligations:List<ObligationRow> = emptyList(),
    val recurringPlans:List<RecurringPlanRow> = emptyList(),
    val recurringOccurrences:List<RecurringOccurrenceRow> = emptyList(),
    val savingsPlans:List<SavingsPlanRow> = emptyList(),
    val savingsMovements:List<SavingsMovementRow> = emptyList(),
    val savingsWishes:List<SavingsWishRow> = emptyList(),
    val budgetAllocations:List<BudgetAllocationRow> = emptyList(),
    val budgetReallocations:List<BudgetReallocationRow> = emptyList()) {
    fun isEmpty()=listOf(accounts,entryDetails,entryTags,transfers,dimensions,contacts,obligations,recurringPlans,recurringOccurrences,savingsPlans,savingsMovements,savingsWishes,budgetAllocations,budgetReallocations).all { it.isEmpty() }
    companion object {
        suspend fun read(dao:LedgerDao)=FinanceStoredRows(accounts=dao.accountsRows(),entryDetails=dao.entryDetailsRows(),entryTags=dao.entryTagsRows(),transfers=dao.transfersRows(),dimensions=dao.dimensionsRows(),contacts=dao.contactsRows(),obligations=dao.obligationsRows(),recurringPlans=dao.recurringPlansRows(),recurringOccurrences=dao.recurringOccurrencesRows(),savingsPlans=dao.savingsPlansRows(),savingsMovements=dao.savingsMovementsRows(),savingsWishes=dao.savingsWishesRows(),budgetAllocations=dao.budgetAllocationsRows(),budgetReallocations=dao.budgetReallocationsRows())
        fun from(state:LedgerState,before:LedgerState?=null,rows:FinanceStoredRows?=null)=FinanceStoredRows(
            accounts=mapRows(state.accounts,before?.accounts,rows?.accounts,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->accountsRow(v,i.toLong())},
            entryDetails=mapRows(state.entryDetails,before?.entryDetails,rows?.entryDetails,{it.entryId},{r,i->r.copy(position=i.toLong())}) {v,i->entryDetailsRow(v,i.toLong())},
            entryTags=mapRows(state.entryTags,before?.entryTags,rows?.entryTags,{it.entryId to it.tagId},{r,i->r.copy(position=i.toLong())}) {v,i->entryTagsRow(v,i.toLong())},
            transfers=mapRows(state.transfers,before?.transfers,rows?.transfers,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->transfersRow(v,i.toLong())},
            dimensions=mapRows(state.dimensions,before?.dimensions,rows?.dimensions,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->dimensionsRow(v,i.toLong())},
            contacts=mapRows(state.contacts,before?.contacts,rows?.contacts,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->contactsRow(v,i.toLong())},
            obligations=mapRows(state.obligations,before?.obligations,rows?.obligations,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->obligationsRow(v,i.toLong())},
            recurringPlans=mapRows(state.recurringPlans,before?.recurringPlans,rows?.recurringPlans,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->recurringPlansRow(v,i.toLong())},
            recurringOccurrences=mapRows(state.recurringOccurrences,before?.recurringOccurrences,rows?.recurringOccurrences,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->recurringOccurrencesRow(v,i.toLong())},
            savingsPlans=mapRows(state.savingsPlans,before?.savingsPlans,rows?.savingsPlans,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->savingsPlansRow(v,i.toLong())},
            savingsMovements=mapRows(state.savingsMovements,before?.savingsMovements,rows?.savingsMovements,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->savingsMovementsRow(v,i.toLong())},
            savingsWishes=mapRows(state.savingsWishes,before?.savingsWishes,rows?.savingsWishes,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->savingsWishesRow(v,i.toLong())},
            budgetAllocations=mapRows(state.budgetAllocations,before?.budgetAllocations,rows?.budgetAllocations,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->budgetAllocationsRow(v,i.toLong())},
            budgetReallocations=mapRows(state.budgetReallocations,before?.budgetReallocations,rows?.budgetReallocations,{it.id},{r,i->r.copy(position=i.toLong())}) {v,i->budgetReallocationsRow(v,i.toLong())},
        )
    }
}

internal suspend fun deleteFinancialRows(old:FinanceStoredRows,next:FinanceStoredRows,dao:LedgerDao) {
    for(row in removed(old.entryTags,next.entryTags) {it.entryId to it.tagId}) dao.deleteEntryTags(row.entryId,row.tagId)
    deleteBatches(removed(old.entryDetails,next.entryDetails) {it.entryId}.map {it.entryId},dao::deleteEntryDetails)
    deleteBatches(removed(old.savingsWishes,next.savingsWishes) {it.id}.map {it.id},dao::deleteSavingsWishes)
    deleteBatches(removed(old.savingsMovements,next.savingsMovements) {it.id}.map {it.id},dao::deleteSavingsMovements)
    deleteBatches(removed(old.recurringOccurrences,next.recurringOccurrences) {it.id}.map {it.id},dao::deleteRecurringOccurrences)
    deleteBatches(removed(old.budgetReallocations,next.budgetReallocations) {it.id}.map {it.id},dao::deleteBudgetReallocations)
    deleteBatches(removed(old.budgetAllocations,next.budgetAllocations) {it.id}.map {it.id},dao::deleteBudgetAllocations)
    deleteBatches(removed(old.recurringPlans,next.recurringPlans) {it.id}.map {it.id},dao::deleteRecurringPlans)
    deleteBatches(removed(old.transfers,next.transfers) {it.id}.map {it.id},dao::deleteTransfers)
    deleteBatches(removed(old.obligations,next.obligations) {it.id}.map {it.id},dao::deleteObligations)
    deleteBatches(removed(old.savingsPlans,next.savingsPlans) {it.id}.map {it.id},dao::deleteSavingsPlans)
    deleteBatches(removed(old.contacts,next.contacts) {it.id}.map {it.id},dao::deleteContacts)
    deleteBatches(removed(old.dimensions,next.dimensions) {it.id}.map {it.id},dao::deleteDimensions)
    deleteBatches(removed(old.accounts,next.accounts) {it.id}.map {it.id},dao::deleteAccounts)
    val previousRecurringOccurrences=old.recurringOccurrences.associateBy {it.id}
    deleteBatches(changed(old.recurringOccurrences,next.recurringOccurrences) {it.id}.filter {v->previousRecurringOccurrences[v.id]?.let {listOf(it.planId,it.index,it.entryId,it.transferId)!=listOf(v.planId,v.index,v.entryId,v.transferId)}==true}.map {it.id},dao::deleteRecurringOccurrences)
    val previousSavingsMovements=old.savingsMovements.associateBy {it.id}
    deleteBatches(changed(old.savingsMovements,next.savingsMovements) {it.id}.filter {v->previousSavingsMovements[v.id]?.transferId!=v.transferId}.map {it.id},dao::deleteSavingsMovements)
    val previousBudgetAllocations=old.budgetAllocations.associateBy {it.id}
    deleteBatches(changed(old.budgetAllocations,next.budgetAllocations) {it.id}.filter {v->previousBudgetAllocations[v.id]?.let {listOf(it.ledgerId,it.yearMonth,it.categoryId)!=listOf(v.ledgerId,v.yearMonth,v.categoryId)}==true}.map {it.id},dao::deleteBudgetAllocations)
    val previousObligations=old.obligations.associateBy {it.id}
    deleteBatches(changed(old.obligations,next.obligations) {it.id}.filter {v->previousObligations[v.id]?.let {listOf(it.virtualAccountId)!=listOf(v.virtualAccountId)}==true}.map {it.id},dao::deleteObligations)
}

internal suspend fun upsertFinancialRows(old:FinanceStoredRows,next:FinanceStoredRows,dao:LedgerDao) {
    val oldAccounts=old.accounts.associateBy {it.id}
    for(row in changed(old.accounts,next.accounts) {it.id}) if(oldAccounts[row.id]?.let {it.normalizedName!=row.normalizedName || it.ledgerId!=row.ledgerId}==true) dao.stageAccountsName(row.id,"\u0001dawnyew-accounts:${row.id}")
    val oldDimensions=old.dimensions.associateBy {it.id}
    for(row in changed(old.dimensions,next.dimensions) {it.id}) if(oldDimensions[row.id]?.let {it.normalizedName!=row.normalizedName || it.ledgerId!=row.ledgerId || it.kind!=row.kind}==true) dao.stageDimensionsName(row.id,"\u0001dawnyew-dimensions:${row.id}")
    val oldContacts=old.contacts.associateBy {it.id}
    for(row in changed(old.contacts,next.contacts) {it.id}) if(oldContacts[row.id]?.let {it.normalizedName!=row.normalizedName || it.ledgerId!=row.ledgerId}==true) dao.stageContactsName(row.id,"\u0001dawnyew-contacts:${row.id}")
    val oldSavingsWishes=old.savingsWishes.associateBy {it.id}
    for(row in changed(old.savingsWishes,next.savingsWishes) {it.id}) if(oldSavingsWishes[row.id]?.fulfilledMovementId!=row.fulfilledMovementId) dao.stageWishFulfillment(row.id)
    val oldBudgetReallocations=old.budgetReallocations.associateBy {it.id}
    for(row in changed(old.budgetReallocations,next.budgetReallocations) {it.id}) if(oldBudgetReallocations[row.id]?.reversalOfId!=row.reversalOfId) dao.stageReversal(row.id)
    upsertBatches(changed(old.accounts,next.accounts) {it.id},dao::upsertAccounts)
    upsertBatches(changed(old.dimensions,next.dimensions) {it.id},dao::upsertDimensions)
    upsertBatches(changed(old.contacts,next.contacts) {it.id},dao::upsertContacts)
    upsertBatches(changed(old.obligations,next.obligations) {it.id},dao::upsertObligations)
    upsertBatches(changed(old.savingsPlans,next.savingsPlans) {it.id},dao::upsertSavingsPlans)
    upsertBatches(changed(old.recurringPlans,next.recurringPlans) {it.id},dao::upsertRecurringPlans)
    upsertBatches(changed(old.transfers,next.transfers) {it.id},dao::upsertTransfers)
    upsertBatches(changed(old.entryDetails,next.entryDetails) {it.entryId},dao::upsertEntryDetails)
    upsertBatches(changed(old.entryTags,next.entryTags) {it.entryId to it.tagId},dao::upsertEntryTags)
    upsertBatches(changed(old.recurringOccurrences,next.recurringOccurrences) {it.id},dao::upsertRecurringOccurrences)
    upsertBatches(changed(old.savingsMovements,next.savingsMovements) {it.id},dao::upsertSavingsMovements)
    upsertBatches(changed(old.savingsWishes,next.savingsWishes) {it.id},dao::upsertSavingsWishes)
    upsertBatches(changed(old.budgetAllocations,next.budgetAllocations) {it.id},dao::upsertBudgetAllocations)
    upsertBatches(changed(old.budgetReallocations,next.budgetReallocations) {it.id},dao::upsertBudgetReallocations)
}
