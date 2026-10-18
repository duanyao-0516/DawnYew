package com.dawnyew.presentation

import com.dawnyew.core.*

/** Narrow the complete source scope; absent arguments retain its existing filters. */
fun drillScope(source: QueryScope, categoryId: String? = null, period: String? = null, ledgerId: String? = null, categoryKind: EntryKind = EntryKind.EXPENSE): QueryScope {
    if (categoryId != null && source.kinds.isNotEmpty() && categoryKind !in source.kinds) invalid("所选类型不在当前统计范围内")
    if (categoryId != null && source.categoryIds.isNotEmpty() && categoryId !in source.categoryIds) invalid("所选分类不在当前统计范围内")
    if (ledgerId != null && source.ledgerIds.isNotEmpty() && ledgerId !in source.ledgerIds) invalid("所选账本不在当前统计范围内")
    val bounds = period?.let { if (it.length == 7) monthRange(it) else it to it }
    val start = bounds?.first?.let { maxOf(source.startDate, it) } ?: source.startDate
    val end = bounds?.second?.let { minOf(source.endDate, it) } ?: source.endDate
    if (start > end) invalid("所选时间不在当前统计范围内")
    return source.copy(startDate = start, endDate = end,
        ledgerIds = ledgerId?.let { setOf(it) } ?: source.ledgerIds,
        categoryIds = categoryId?.let { setOf(it) } ?: source.categoryIds,
        kinds = if (categoryId != null) setOf(categoryKind) else source.kinds)
}

/** Exact decimal long division: avoids multiplying a Long amount by 10000. */
fun percentageText(amount: Long, total: Long): String {
    if (amount < 0 || total <= 0) return "0.00"
    val integer = amount / total
    var remainder = amount % total
    fun nextDigit(): Int {
        var accumulated = 0L
        var digit = 0
        repeat(10) {
            if (accumulated >= total - remainder) { accumulated -= total - remainder; digit++ }
            else accumulated += remainder
        }
        remainder = accumulated
        return digit
    }
    val digits = buildString { append(integer); repeat(4) { append(nextDigit()) } }.toCharArray()
    if (nextDigit() >= 5) {
        var index = digits.lastIndex
        while (index >= 0 && digits[index] == '9') { digits[index] = '0'; index-- }
        if (index >= 0) digits[index]++ else return "1" + digits.concatToString().dropLast(2) + "." + digits.concatToString().takeLast(2)
    }
    val text = digits.concatToString()
    return text.dropLast(2).trimStart('0').ifEmpty { "0" } + "." + text.takeLast(2)
}

sealed interface StatisticsView {
    data class Content(val snapshot: StatisticsSnapshot) : StatisticsView
    data class Error(val message: String) : StatisticsView
    data object Loading : StatisticsView
}
fun statisticsView(state: LedgerState?, scope: QueryScope): StatisticsView = if (state == null) StatisticsView.Loading else try {
    val snapshot=Statistics.calculate(state, scope)
    // Validate derived totals before Compose reads getters; overflow must stay an error state.
    snapshot.netExpense
    snapshot.netIncome
    StatisticsView.Content(snapshot)
} catch (e: DomainException) { StatisticsView.Error(e.message) }
catch (_: Exception) { StatisticsView.Error("统计未完成，请重试或调整筛选") }

/** UI context remains in process memory, including potentially sensitive filter keywords. */
data class UiNavigation(
    val page: String = "明细", val tab: String = "明细", val selectedEntryId: String? = null,
    val detailsScope: QueryScope, val statisticsScope: QueryScope,
    val drillReturnScope: QueryScope? = null, val editorReturnPage: String = "明细",
) {
    val scope get() = if (page == "统计") statisticsScope else detailsScope
    fun withScope(value: QueryScope) = if (page == "统计") copy(statisticsScope = value) else copy(detailsScope = value)
}
