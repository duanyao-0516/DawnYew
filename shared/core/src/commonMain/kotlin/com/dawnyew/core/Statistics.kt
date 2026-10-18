package com.dawnyew.core

import kotlinx.datetime.*

data class QueryScope(
    val ledgerIds: Set<String> = emptySet(), val startDate: String = "0001-01-01", val endDate: String = "9999-12-31",
    val kinds: Set<EntryKind> = emptySet(), val categoryIds: Set<String> = emptySet(), val keyword: String = "", val includeArchived: Boolean = true,
)
data class AmountGroup(val id: String, val label: String, val amountMinor: Long)
data class FinancialAmountGroup(val id:String,val label:String,val expense:Long,val refunds:Long) { val netExpense get()=Money.add(expense,-refunds) }
data class TrendPoint(val period: String, val income: Long, val expense: Long, val refunds: Long) { val netExpense get() = Money.add(expense,-refunds) }
data class StatisticsSnapshot(val contentVersion: Long, val scope: QueryScope, val income: Long, val expense: Long, val refunds: Long,
    val entries: List<Entry>, val categories: List<AmountGroup>, val ledgers: List<AmountGroup>, val trend: List<TrendPoint>, val datasetId: String = "", val entryCount:Long=entries.size.toLong(), val categoryBreakdown:List<FinancialAmountGroup> = emptyList(), val ledgerBreakdown:List<FinancialAmountGroup> = emptyList()) {
    val netExpense get() = Money.add(expense,-refunds)
    val netIncome get() = Money.add(income,-netExpense)
    val budgetUsed get() = netExpense.coerceAtLeast(0)
}
object Statistics {
    fun calculate(state: LedgerState, scope: QueryScope): StatisticsSnapshot {
        validateQueryScope(scope)
        if(scope.startDate>scope.endDate) invalid("开始日期不能晚于结束日期")
        val start=try { LocalDate.parse(scope.startDate) } catch(_:Exception) { invalid("开始日期无效") }
        val end=try { LocalDate.parse(scope.endDate) } catch(_:Exception) { invalid("结束日期无效") }
        val allowed=state.ledgers.filter { scope.includeArchived || !it.archived }.map { it.id }.toSet()
        val entries=state.entries.filter { e -> e.active && e.ledgerId in allowed && (scope.ledgerIds.isEmpty() || e.ledgerId in scope.ledgerIds) && e.localDate>=scope.startDate && e.localDate<=scope.endDate &&
            (scope.kinds.isEmpty() || e.kind in scope.kinds) && (scope.categoryIds.isEmpty() || e.categoryId in scope.categoryIds) && (scope.keyword.isEmpty() || literalSearchKey(e.note).contains(literalSearchKey(scope.keyword))) }.sortedWith(compareByDescending<Entry> { it.occurrence }.thenByDescending { it.id })
        fun sum(list: List<Entry>)=list.fold(0L) { a,e -> Money.add(a,e.amountMinor) }
        val income=sum(entries.filter { it.kind==EntryKind.INCOME });val expense=sum(entries.filter { it.kind==EntryKind.EXPENSE });val refunds=sum(entries.filter { it.kind==EntryKind.REFUND })
        val categories=entries.filter { it.kind==EntryKind.EXPENSE }.groupBy { it.categoryId }.map { (id,list) -> AmountGroup(id,state.categories.find { it.id==id }?.name ?: "未知分类",sum(list)) }.sortedByDescending { it.amountMinor }
        val ledgers=entries.groupBy { it.ledgerId }.map { (id,list) -> AmountGroup(id,state.ledgers.find { it.id==id }?.name ?: "未知账本",Money.add(sum(list.filter { it.kind==EntryKind.EXPENSE }),-sum(list.filter { it.kind==EntryKind.REFUND }))) }.sortedByDescending { it.amountMinor }
        val daily=start.daysUntil(end)<=90
        val grouped=entries.groupBy { if(daily) it.localDate else it.localDate.take(7) }
        // Extremely broad historical scopes are grouped only over observed months, avoiding unbounded empty charts.
        val periods=if(start.year==1 || end.year==9999) grouped.keys.sorted() else buildList {
            var date=if(daily) start else LocalDate(start.year,start.month,1)
            while(date<=end) { add(if(daily) date.toString() else date.toString().take(7)); date=date.plus(1,if(daily) DateTimeUnit.DAY else DateTimeUnit.MONTH) }
        }
        val trend=periods.map { period -> val list=grouped[period].orEmpty();TrendPoint(period,sum(list.filter { it.kind==EntryKind.INCOME }),sum(list.filter { it.kind==EntryKind.EXPENSE }),sum(list.filter { it.kind==EntryKind.REFUND })) }
        val categoryBreakdown=entries.filter { it.kind!=EntryKind.INCOME }.groupBy { it.categoryId }.map { (id,list) -> FinancialAmountGroup(id,state.categories.find { it.id==id }?.name ?: "未知分类",sum(list.filter { it.kind==EntryKind.EXPENSE }),sum(list.filter { it.kind==EntryKind.REFUND })) }.sortedByDescending { it.expense }
        val ledgerBreakdown=entries.groupBy { it.ledgerId }.map { (id,list) -> FinancialAmountGroup(id,state.ledgers.find { it.id==id }?.name ?: "未知账本",sum(list.filter { it.kind==EntryKind.EXPENSE }),sum(list.filter { it.kind==EntryKind.REFUND })) }.sortedByDescending { it.netExpense }
        return StatisticsSnapshot(state.contentVersion,scope,income,expense,refunds,entries,categories,ledgers,trend,state.datasetId,entries.size.toLong(),categoryBreakdown,ledgerBreakdown)
    }
}
