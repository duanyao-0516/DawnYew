package com.dawnyew.presentation

import com.dawnyew.core.*

/** Financial values come from the repository's single statistics transaction, never a UI re-query. */
data class FinancialRow(val id:String,val label:String,val expense:Long,val refunds:Long) {
    val netExpense get()=Money.add(expense,-refunds)
}
fun categoryFinancialRows(snapshot:StatisticsSnapshot,state:LedgerState):List<FinancialRow> = snapshot.categoryBreakdown
    .map {group->
        val category=state.categories.find {it.id==group.id}
        val ledger=state.ledgers.find {it.id==category?.ledgerId}
        val label=if(snapshot.scope.ledgerIds.size!=1) "${ledger?.name ?: "未知账本"} · ${group.label}" else group.label
        FinancialRow(group.id,label,group.expense,group.refunds)
    }.sortedWith(compareByDescending<FinancialRow> {it.expense}.thenBy {it.id})
fun ledgerFinancialRows(snapshot:StatisticsSnapshot,state:LedgerState):List<FinancialRow> = snapshot.ledgerBreakdown
    .map {group->FinancialRow(group.id,group.label,group.expense,group.refunds)}
    .sortedWith(compareByDescending<FinancialRow> {it.netExpense}.thenBy {it.id})
