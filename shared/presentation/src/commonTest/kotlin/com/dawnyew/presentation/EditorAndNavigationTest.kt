package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlin.test.*

class EditorAndNavigationTest {
    private val state = LedgerState(
        datasetId = "dataset", ledgers = listOf(Ledger("l1", "日常", sortOrder = 0)),
        categories = listOf(Category("c1", "l1", Direction.EXPENSE, "餐饮", sortOrder = 0)),
    )

    @Test fun newCaptureResetsFieldsAndConfirmationWithNewOperationId() {
        val first = EditorDraft.create(state, "first", "2026-10-18T12:00:00", "l1", capture = CaptureDraftUi("12.34", note = "甲"))
        val second = EditorDraft.create(state, "second", "2026-10-18T12:00:00", "l1", capture = CaptureDraftUi("56.78", note = "乙"))
        assertNotEquals(first.operationId, second.operationId)
        assertEquals("56.78", second.amount)
        assertEquals("乙", second.note)
        assertFalse(second.confirmed)
        assertFailsWith<DomainException> { second.command() }
    }

    @Test fun captureKeepsOriginUntilExplicitConfirmedSave() {
        val draft = EditorDraft.create(state, "operation", "2026-10-18T12:00:00", "l1", capture = CaptureDraftUi("12.34", engineVersion = "engine", fingerprint = "fingerprint"))
        val command = draft.copy(confirmed = true).command() as Command.SaveEntry
        assertEquals(OriginKind.OCR, command.originKind)
        assertEquals("fingerprint", command.captureFingerprint)
        assertEquals("engine", command.captureEngineVersion)
    }

    @Test fun editingIdentityAndRevisionSurviveFieldChanges() {
        val entry = Entry("e1", "l1", EntryKind.EXPENSE, 1234, "c1", "2026-10-17T11:00:00", "原备注", revision = 4, createdAt = "now", updatedAt = "now", creationOperationId = "original")
        val draft = EditorDraft.create(state.copy(entries = listOf(entry)), "edit-op", "2026-10-18T12:00:00", "l1", editId = "e1")
        val command = draft.copy(note = "修改备注", dirty = true).command() as Command.SaveEntry
        assertEquals("e1", command.id)
        assertEquals(4L, command.expectedRevision)
        assertEquals("修改备注", command.note)
    }

    @Test fun allLedgersRequiresExplicitActualLedgerSelection() {
        val draft = EditorDraft.create(state, "new", "2026-10-18T12:00:00", null).copy(amount = "1.00")
        assertNull(draft.ledgerId)
        assertFailsWith<DomainException> { draft.command() }
    }

    @Test fun trendDrillPreservesFiltersAndClipsPartialMonth() {
        val scope = QueryScope(setOf("l1"), "2026-01-15", "2026-04-20", setOf(EntryKind.EXPENSE), setOf("c1"), "午餐", false)
        assertEquals(scope.copy(endDate = "2026-01-31"), drillScope(scope, period = "2026-01"))
    }

    @Test fun ledgerDrillPreservesCategoryAndKeyword() {
        val scope = QueryScope(startDate = "2026-01-01", endDate = "2026-01-31", categoryIds = setOf("c1"), keyword = "午餐")
        assertEquals(scope.copy(ledgerIds = setOf("l1")), drillScope(scope, ledgerId = "l1"))
    }

    @Test fun categoryShareDrillsOnlyOriginalExpenses() {
        val scope = QueryScope(startDate = "2026-01-01", endDate = "2026-01-31")
        assertEquals(setOf(EntryKind.EXPENSE), drillScope(scope, categoryId = "c1").kinds)
    }

    @Test fun percentageIsRoundedExactlyWithTwoDecimalsAndNoOverflow() {
        assertEquals("33.33", percentageText(1, 3))
        assertEquals("66.67", percentageText(2, 3))
        assertEquals("100.00", percentageText(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals("150.00", percentageText(3, 2))
    }
    @Test fun refundOnlyCategoryIsKeptWithoutChangingExpenseDenominator() {
        val expense=Entry("e1","l1",EntryKind.EXPENSE,1000,"c1","2026-10-18T12:00:00",createdAt="now",updatedAt="now",creationOperationId="op1")
        val refund=Entry("r1","l1",EntryKind.REFUND,300,"c2","2026-10-18T12:00:00",parentExpenseId="old",createdAt="now",updatedAt="now",creationOperationId="op2")
        val data=state.copy(entries=listOf(expense,refund),categories=state.categories+Category("c2","l1",Direction.EXPENSE,"购物"))
        val snapshot=Statistics.calculate(data,QueryScope(startDate="2026-10-01",endDate="2026-10-31"))
        val rows=categoryFinancialRows(snapshot,data)
        assertEquals(1000L,snapshot.expense)
        assertEquals(2,rows.size)
        assertEquals(-300L,rows.first {it.id=="c2"}.netExpense)
        assertEquals(700L,ledgerFinancialRows(snapshot,data).single().netExpense)
    }

    @Test fun derivedSurplusOverflowBecomesError() {
        val income=Entry("i1","l1",EntryKind.INCOME,Long.MAX_VALUE,"ci","2026-10-18T12:00:00",createdAt="now",updatedAt="now",creationOperationId="op1")
        val refund=Entry("r1","l1",EntryKind.REFUND,1,"c1","2026-10-18T12:00:00",parentExpenseId="old",createdAt="now",updatedAt="now",creationOperationId="op2")
        assertIs<StatisticsView.Error>(statisticsView(state.copy(entries=listOf(income,refund)),QueryScope(startDate="2026-10-01",endDate="2026-10-31")))
    }
    @Test fun refundOnlyScopeCannotDrillIntoOriginalExpenses() {
        val source=QueryScope(kinds=setOf(EntryKind.REFUND))
        assertFailsWith<DomainException> {drillScope(source,categoryId="c1")}
    }

    @Test fun refundDrillRetainsSourceKindsDatesAndOtherFilters() {
        val source=QueryScope(setOf("l1"),"2026-01-10","2026-02-20",setOf(EntryKind.REFUND),setOf("c1"),"商品",false)
        assertEquals(source,drillScope(source,categoryId="c1",categoryKind=EntryKind.REFUND))
        assertEquals(setOf(EntryKind.EXPENSE),drillScope(source.copy(kinds=setOf(EntryKind.REFUND,EntryKind.EXPENSE)),categoryId="c1").kinds)
        assertFailsWith<DomainException> {drillScope(source,categoryId="outside",categoryKind=EntryKind.REFUND)}
        assertFailsWith<DomainException> {drillScope(source,ledgerId="outside")}
    }

    @Test fun restoredCategoryArrayOrderCannotChangeDefaultSelection() {
        val restored=state.copy(categories=listOf(
            Category("education","l1",Direction.EXPENSE,"教育",sortOrder=50),
            Category("z-food","l1",Direction.EXPENSE,"另一餐饮",sortOrder=10),
            Category("disabled","l1",Direction.EXPENSE,"已停用",enabled=false,sortOrder=0),
            Category("income","l1",Direction.INCOME,"收入",sortOrder=0),
            Category("a-food","l1",Direction.EXPENSE,"餐饮",sortOrder=10),
        ))
        for(categories in listOf(restored.categories,restored.categories.reversed())) {
            val data=restored.copy(categories=categories)
            val draft=EditorDraft.create(data,"new","2026-10-18T12:00:00","l1")
            assertEquals("a-food",draft.categoryId)
            assertEquals("a-food",draft.copy(categoryId="removed").continuation(data,"next","2026-10-18T12:01:00").categoryId)
        }
    }

}
