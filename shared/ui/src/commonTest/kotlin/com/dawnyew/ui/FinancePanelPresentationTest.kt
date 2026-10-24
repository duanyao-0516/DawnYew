package com.dawnyew.ui

import com.dawnyew.core.*
import com.dawnyew.presentation.PanelUiState
import kotlin.test.*

class FinancePanelPresentationTest {
    @Test fun moreBudgetSummaryUsesEndMonthAndRejectsTheCustomRangeOrStaleResult() {
        val range=QueryScope(setOf("ledger"),"2026-09-15","2026-10-10")
        val monthly=com.dawnyew.presentation.homeMonthlyScope(range)
        val state=LedgerState("dataset",contentVersion=7,budgets=listOf(MonthlyBudget("ledger","2026-09",90000),MonthlyBudget("ledger","2026-10",10000)))
        val finance=FinanceSnapshot(emptyList(),emptyList(),emptyList(),0,0,0)
        val stats=StatisticsSnapshot(7,monthly,0,1234,0,emptyList(),emptyList(),emptyList(),emptyList(),datasetId=state.datasetId)
        assertEquals("2026年10月 · 87.66 剩余",moreSummaries(finance,state,"ledger",stats,monthly)["预算"])
        assertNull(moreSummaries(finance,state,"ledger",stats.copy(scope=range),monthly)["预算"])
        assertNull(moreSummaries(finance,state,"ledger",stats.copy(contentVersion=6),monthly)["预算"])
        assertNull(moreSummaries(finance,state,"ledger",stats.copy(datasetId="old"),monthly)["预算"])
    }

    @Test fun fullMonthTrendRetainsFirstLastAndEveryZeroDay() {
        val scope = QueryScope(startDate = "2026-10-01", endDate = "2026-10-31")
        val trend = (1..31).map { day -> TrendPoint("2026-10-${day.toString().padStart(2, '0')}", 0, when(day) {1 -> 100;31 -> 3100;else -> 0}, 0) }
        val snapshot = StatisticsSnapshot(1, scope, 0, 3200, 0, emptyList(), emptyList(), emptyList(), trend)
        val points = homeTrendPoints(snapshot) { it.netExpense }
        assertEquals(31, points.size)
        assertEquals(FinanceChartPoint("2026-10-01", "10.1", 100), points.first())
        assertEquals(FinanceChartPoint("2026-10-31", "10.31", 3100), points.last())
        assertEquals(snapshot.netExpense, points.sumOf { it.value })
        assertEquals(29, points.count { it.value == 0L })
    }

    @Test fun customRangePointsKeepQueryBucketsAndMonthlyLabelsAreNotDayFragments() {
        val scope = QueryScope(startDate = "2026-01-15", endDate = "2026-10-10")
        val snapshot = StatisticsSnapshot(1, scope, 10, 20, 0, emptyList(), emptyList(), emptyList(), listOf(TrendPoint("2026-01", 10, 0, 0), TrendPoint("2026-10", 0, 20, 0)))
        val points = homeTrendPoints(snapshot) { it.expense }
        assertEquals(listOf("2026.01", "2026.10"), points.map { it.label })
        assertEquals(listOf("2026-01", "2026-10"), points.map { it.id })
        val first = com.dawnyew.presentation.drillScope(scope, period = points.first().id)
        val last = com.dawnyew.presentation.drillScope(scope, period = points.last().id)
        assertEquals("2026-01-15", first.startDate)
        assertEquals("2026-01-31", first.endDate)
        assertEquals("2026-10-01", last.startDate)
        assertEquals("2026-10-10", last.endDate)
    }

    @Test fun calendarNetIncludesRefundsAndExcludesTransfers() {
        val day = ActivityDaySummary("2026-10-01", 4, 1000, 500, 100, 900000)
        assertEquals(600L, calendarNetAmount(day))
        assertEquals(-400L, calendarNetAmount(day.copy(income = 0)))
        assertEquals(0L, calendarNetAmount(day.copy(income = 400)))
    }

    @Test fun monthViewportKeepsSevenDistinctMeaningfulMonthLabels() {
        val result = chartMonthWindow(listOf(FinanceChartPoint("2026-10", "2026-10", 123)), "2026", "2026-10-24")
        assertEquals((6..12).map { "${it}月" }, result.map { it.label })
        assertEquals(123L, result.sumOf { it.value })
        assertEquals("2026-06", result.first().id)
    }

    @Test fun axisLabelsIncludeEndpointsWithoutCrowdingLastTwoTicks() {
        val indices = chartLabelIndices(12, 7)
        assertTrue(0 in indices && 11 in indices)
        assertFalse(10 in indices)
        assertTrue(indices.size <= 7)
        assertEquals(emptySet(), chartLabelIndices(0, 7))
    }

    @Test fun requestsPreserveLedgerAndDoNotExcludeUnrecordedOrdinaryTransactions() {
        val month = QueryScope(setOf("ledger-a"), "2026-10-01", "2026-10-31", keyword = "test", includeArchived = false)
        val annual = financePanelRequest("monthly", month, "2025")
        assertEquals("2025-01-01", annual.scope.startDate)
        assertEquals("2025-12-31", annual.scope.endDate)
        assertEquals(setOf("ledger-a"), annual.scope.ledgerIds)
        assertEquals("test", annual.scope.keyword)
        assertNull(annual.scope.recorded)
        assertFalse(annual.scope.includeArchived)
        assertTrue(financePanelRequest("category_monthly", month, "2026").scope.categoryIds.isEmpty())
        assertEquals(setOf("cat-a"), financePanelRequest("category_monthly", month, "2026", ActivityKind.INCOME, "cat-a").scope.categoryIds)
    }

    @Test fun oldDatasetVersionAndLoadingResultsAreNeverCurrent() {
        val state = LedgerState("dataset-a", contentVersion = 5)
        val request = PanelRequest("category", ActivityScope(startDate = "2026-10-01", endDate = "2026-10-31"))
        val snapshot = PanelStatistics(state.datasetId, state.contentVersion, request, emptyList())
        val ready = PanelUiState(request = request, snapshot = snapshot)
        assertSame(snapshot, currentPanel(state, ready, request))
        assertNull(currentPanel(state, ready.copy(loading = true), request))
        assertNull(currentPanel(state, ready.copy(error = "failed"), request))
        assertNull(currentPanel(state.copy(contentVersion = 6), ready, request))
        assertNull(currentPanel(state.copy(datasetId = "dataset-b"), ready, request))
        assertNull(currentPanel(state, ready, request.copy(scope = request.scope.copy(endDate = "2026-10-30"))))
    }

    @Test fun unrepresentableComparisonDoesNotWrapOrCrashTheComposition() {
        assertEquals("超出可显示范围", financeDifferenceText(Long.MAX_VALUE, -1))
        assertEquals("超出可显示范围", financeDifferenceText(1, Long.MIN_VALUE))
        assertEquals("-2.00", financeDifferenceText(100, 300))
    }
    @Test fun draggingPreservesHeadersAndOtherGroupsAndRejectsCrossGroupMoves() {
        val order = listOf("header-a", "a1", "a2", "header-b", "b1", "b2")
        val groups = mapOf("header-a" to null, "a1" to "a", "a2" to "a", "header-b" to null, "b1" to "b", "b2" to "b")
        assertEquals(listOf("header-a", "a2", "a1", "header-b", "b1", "b2"), reorderGroupKeys(order, "a1", "a2", groups))
        assertEquals(order, reorderGroupKeys(order, "a1", "b2", groups))
        assertEquals(order, reorderGroupKeys(order, "a1", "header-a", groups))
        assertEquals(order, reorderGroupKeys(order, "header-a", "a1", groups))
    }

}
