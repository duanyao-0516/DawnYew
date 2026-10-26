package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlin.test.*

class CaptureDirectionTest {
    private val now = "2026-10-25T12:00:00"
    private val data = LedgerState(
        "dataset",
        ledgers = listOf(Ledger("l", "日常"), Ledger("other", "其他")),
        categories = listOf(
            Category("income-z", "l", Direction.INCOME, "奖金", sortOrder = 2),
            Category("income-later", "l", Direction.INCOME, "其他收入", sortOrder = 3),
            Category("income-disabled", "l", Direction.INCOME, "停用收入", enabled = false, sortOrder = -2),
            Category("income-other", "other", Direction.INCOME, "另一账本收入", sortOrder = -3),
            Category("expense-z", "l", Direction.EXPENSE, "购物", sortOrder = 0),
            Category("expense-disabled", "l", Direction.EXPENSE, "停用支出", enabled = false, sortOrder = -2),
            Category("expense-other", "other", Direction.EXPENSE, "另一账本支出", sortOrder = -3),
            Category("income-a", "l", Direction.INCOME, "工资", sortOrder = 2),
            Category("expense-a", "l", Direction.EXPENSE, "餐饮", sortOrder = 0),
        ),
    )

    @Test fun incomeCaptureSelectsEnabledIncomeCategoryInItsLedgerWithStableOrdering() {
        for (categories in listOf(data.categories, data.categories.reversed())) {
            val draft = EditorDraft.create(data.copy(categories = categories), "ocr", now, "l",
                capture = CaptureDraftUi(amount = "12.34", direction = Direction.INCOME))
            assertEquals(EntryKind.INCOME, draft.kind)
            assertEquals("income-a", draft.categoryId)
            assertEquals("l", draft.ledgerId)
        }
    }

    @Test fun expenseAndMissingDirectionSelectEnabledExpenseCategoryInItsLedger() {
        for (direction in listOf(Direction.EXPENSE, null)) {
            for (categories in listOf(data.categories, data.categories.reversed())) {
                val draft = EditorDraft.create(data.copy(categories = categories), "ocr", now, "l",
                    capture = CaptureDraftUi(amount = "12.34", direction = direction))
                assertEquals(EntryKind.EXPENSE, draft.kind)
                assertEquals("expense-a", draft.categoryId)
            }
        }
    }

    @Test fun incomeWithoutAnAvailableIncomeCategoryDoesNotFallBackToExpense() {
        val withoutIncome = data.copy(categories = data.categories.filterNot {
            it.ledgerId == "l" && it.direction == Direction.INCOME && it.enabled
        })
        val draft = EditorDraft.create(withoutIncome, "ocr", now, "l",
            capture = CaptureDraftUi(amount = "12.34", direction = Direction.INCOME))
        assertEquals(EntryKind.INCOME, draft.kind)
        assertNull(draft.categoryId)
        assertFailsWith<DomainException> { draft.copy(confirmed = true).command() }
    }

    @Test fun captureRequiresConfirmationAndSavesItsDirectionWithPositiveAmount() {
        for (direction in listOf(Direction.INCOME, Direction.EXPENSE, null)) {
            val draft = EditorDraft.create(data, "ocr", now, "l", capture = CaptureDraftUi(
                amount = "35.50", direction = direction, fingerprint = "image-hash", engineVersion = "offline-v1",
            ))
            assertFalse(draft.confirmed)
            assertFailsWith<DomainException> { draft.command() }

            val command = assertIs<Command.SaveEntry>(draft.copy(confirmed = true).command())
            val income = direction == Direction.INCOME
            assertEquals(if (income) EntryKind.INCOME else EntryKind.EXPENSE, command.kind)
            assertEquals(if (income) "income-a" else "expense-a", command.categoryId)
            assertEquals(3550L, command.amountMinor)
            assertEquals(OriginKind.OCR, command.originKind)
            assertEquals("image-hash", command.captureFingerprint)
            assertEquals("offline-v1", command.captureEngineVersion)
        }
    }

    @Test fun editingAndLinkedRefundKeepTheirExistingKindAndCategoryOverCaptureDirection() {
        val expense = entry("expense", EntryKind.EXPENSE, "expense-z")
        val income = entry("income", EntryKind.INCOME, "income-z")
        val refund = entry("refund", EntryKind.REFUND, "expense-z").copy(parentExpenseId = expense.id)
        val withEntries = data.copy(entries = listOf(expense, income, refund))
        for (existing in withEntries.entries) {
            val captureDirection = if (existing.kind == EntryKind.INCOME) Direction.EXPENSE else Direction.INCOME
            val draft = EditorDraft.create(withEntries, "edit", now, "other", editId = existing.id,
                capture = CaptureDraftUi(amount = "35.50", direction = captureDirection))
            assertEquals(existing.kind, draft.kind)
            assertEquals(existing.categoryId, draft.categoryId)
            assertEquals(existing.ledgerId, draft.ledgerId)
            val command = assertIs<Command.SaveEntry>(draft.copy(confirmed = true).command())
            assertEquals(existing.kind, command.kind)
            assertEquals(existing.amountMinor, command.amountMinor)
        }

        val linkedRefund = EditorDraft.create(withEntries, "new-refund", now, "other", refundParentId = expense.id,
            capture = CaptureDraftUi(amount = "1.98", direction = Direction.INCOME))
        assertEquals(EntryKind.REFUND, linkedRefund.kind)
        assertEquals(expense.categoryId, linkedRefund.categoryId)
        assertEquals(expense.ledgerId, linkedRefund.ledgerId)
        assertFailsWith<DomainException> { linkedRefund.command() }
        val command = assertIs<Command.AddRefund>(linkedRefund.copy(confirmed = true).command())
        assertEquals(expense.id, command.expenseId)
        assertEquals(198L, command.amountMinor)
    }

    private fun entry(id: String, kind: EntryKind, categoryId: String) = Entry(
        id, "l", kind, 1234, categoryId, now,
        createdAt = now, updatedAt = now, creationOperationId = "create-$id",
    )
}
