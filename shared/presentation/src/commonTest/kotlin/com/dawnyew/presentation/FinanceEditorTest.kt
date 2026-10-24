package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlin.test.*

class FinanceEditorTest {
    private val entry = Entry("e", "l", EntryKind.EXPENSE, 1234, "c", "2026-10-24T10:00:00", "old", revision = 4, createdAt = "now", updatedAt = "now", creationOperationId = "op")
    private val data = LedgerState("dataset", ledgers = listOf(Ledger("l", "日常")), categories = listOf(Category("c", "l", Direction.EXPENSE, "餐饮")), entries = listOf(entry),
        accounts = listOf(Account("a", "l", "现金", openingAt = "2026-01-01T00:00:00")),
        entryDetails = listOf(EntryDetails("e", "a", recorded = false, excludedFromBudget = true, reconciled = true, projectId = "project", merchantId = "shop")),
        entryTags = listOf(EntryTag("e", "tag")))

    @Test fun reopeningAnEntryPreservesAllFinancialSelectionsAndSavesAtomically() {
        val draft = EditorDraft.create(data, "edit", "2026-10-24T12:00:00", "l", editId = "e")
        assertEquals("a", draft.accountId)
        assertFalse(draft.recorded)
        assertTrue(draft.excludedFromBudget)
        assertEquals(setOf("tag"), draft.tagIds)
        val command = draft.copy(note = "changed").command() as Command.SaveEntry
        assertEquals(4, command.expectedRevision)
        assertEquals(data.entryDetails.single(), command.details)
        assertEquals(listOf("tag"), command.tagIds)
    }

    @Test fun refundCopiesAccountButBudgetExclusionRemainsParentOwned() {
        val draft = EditorDraft.create(data, "refund", "2026-10-24T12:00:00", "l", refundParentId = "e").copy(amount = "2")
        assertEquals("a", draft.accountId)
        assertFalse(draft.excludedFromBudget)
        val command = draft.command() as Command.AddRefund
        assertEquals("a", command.details?.accountId)
        assertFalse(command.details!!.excludedFromBudget)
    }

    @Test fun defaultAccountMustBeWritableInTheSelectedLedger() {
        val preferred = data.copy(preferences = listOf(UserPreference("defaultAccountId", "a")))
        assertEquals("a", EditorDraft.create(preferred, "new", "2026-10-24T12:00:00", "l").accountId)
        assertNull(EditorDraft.create(preferred, "new", "2026-10-24T12:00:00", "other").accountId)
        assertNull(EditorDraft.create(preferred.copy(accounts = preferred.accounts.map { it.copy(archived = true) }), "new", "2026-10-24T12:00:00", "l").accountId)
    }

    @Test fun addingDetailsDoesNotBypassOcrConfirmation() {
        val draft = EditorDraft.create(data, "ocr", "2026-10-24T12:00:00", "l", capture = CaptureDraftUi(amount = "12.34"))
        assertFailsWith<DomainException> { draft.copy(accountId = "a", recorded = false).command() }
        val saved = draft.copy(accountId = "a", confirmed = true).command() as Command.SaveEntry
        assertEquals(OriginKind.OCR, saved.originKind)
        assertEquals("a", saved.details?.accountId)
    }
}
