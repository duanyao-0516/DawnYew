package com.dawnyew.core

import kotlinx.serialization.json.*
import kotlin.test.*

class CaptureBookingDomainTest {
    private var sequence = 0
    private val env = object : DomainEnvironment {
        override fun id() = "00000000-0000-4000-8000-${(++sequence).toString().padStart(12, '0')}"
        override fun localNow() = "2026-10-25T12:00:00"
        override fun utcNow() = "2026-10-25T04:00:00Z"
        override fun normalize(value: String) = value.trim()
    }
    private val engine = DomainEngine(env)
    private val capturedAt = "2026-10-25T11:30:00"

    // Decode the public command wire contract so the first test can fail at runtime before
    // the new command subtype exists, rather than failing compilation on a missing symbol.
    private fun record(state: LedgerState, direction: Direction = Direction.EXPENSE,
                       fingerprint: String = "a".repeat(64), ledgerId: String? = null,
                       categoryId: String? = null, occurrence: String? = null,
                       amount: Long = 1234, datasetId: String = state.datasetId): Command = Json.decodeFromJsonElement(buildJsonObject {
        put("type", "RecordCapture"); put("datasetId", datasetId); put("amountMinor", amount)
        put("direction", direction.name); put("capturedAt", capturedAt); put("fingerprint", fingerprint)
        put("engineVersion", "offline-v1"); put("note", "商家")
        ledgerId?.let { put("ledgerId", it) }; categoryId?.let { put("categoryId", it) }
        occurrence?.let { put("occurrence", it) }
    })

    private fun undo(state: LedgerState, entry: Entry, revision: Long = entry.revision,
                     creationOperationId: String = entry.creationOperationId): Command = Json.decodeFromJsonElement(buildJsonObject {
        put("type", "UndoCapture"); put("datasetId", state.datasetId); put("entryId", entry.id)
        put("creationOperationId", creationOperationId); put("expectedRevision", revision)
    })

    @Test fun defaultBookingUsesPreferredLedgerOtherCategoryAndCapturedTime() {
        var state = engine.initialState()
        state = engine.apply(state, Command.CreateLedger("旅行"), env.id()).state
        val ledger = state.ledgers.last()
        state = state.copy(preferences = listOf(UserPreference("lastLedgerId", ledger.id)))
        val operationId = env.id()
        val result = engine.apply(state, record(state), operationId)
        val entry = result.state.entries.single()
        assertEquals(ledger.id, entry.ledgerId)
        assertEquals("其他支出", result.state.categories.single { it.id == entry.categoryId }.name)
        assertEquals(capturedAt, entry.occurrence)
        assertEquals(1234, entry.amountMinor)
        assertEquals(EntryKind.EXPENSE, entry.kind)
        assertEquals(OriginKind.OCR, entry.originKind)
        assertEquals(operationId, entry.creationOperationId)
        assertEquals("a".repeat(64), entry.captureFingerprint)
        assertEquals("offline-v1", entry.captureEngineVersion)
        assertTrue(entry.note.contains("截图时间"))
        assertTrue(entry.note.contains("默认账本"))
        assertTrue(entry.note.contains("默认分类"))
        assertEquals(listOf(entry.id), result.resultIds)
        assertEquals("RecordCapture", result.state.receipts.last().commandType)
    }

    @Test fun explicitIncomeSelectionsPreservePositiveAmountAndRecognizedOccurrence() {
        val state = engine.initialState()
        val category = state.categories.first { it.direction == Direction.INCOME }
        val occurrence = "2026-10-24T09:00:00"
        val result = engine.apply(state, record(state, Direction.INCOME, ledgerId = category.ledgerId,
            categoryId = category.id, occurrence = occurrence), env.id()).state
        val entry = result.entries.single()
        assertEquals(EntryKind.INCOME, entry.kind)
        assertEquals(1234, entry.amountMinor)
        assertEquals(category.id, entry.categoryId)
        assertEquals(occurrence, entry.occurrence)
        assertEquals("商家", entry.note)
    }

    @Test fun stalePreferredLedgerFallsBackBySortOrderThenId() {
        var state = engine.initialState()
        state = engine.apply(state, Command.CreateLedger("旅行"), env.id()).state
        state = state.copy(ledgers = state.ledgers.map { it.copy(sortOrder = 0) }.reversed(),
            preferences = listOf(UserPreference("lastLedgerId", env.id())))
        val booked = engine.apply(state, record(state), env.id()).state.entries.single()
        assertEquals(state.ledgers.minBy { it.id }.id, booked.ledgerId)
    }

    @Test fun noEnabledCategoryCreatesOneAtomicallyWithoutEnablingUsersDisabledCategory() {
        val initial = engine.initialState()
        val state = initial.copy(categories = initial.categories.map {
            if (it.direction == Direction.INCOME) it.copy(enabled = false, name = if (it.name == "其他收入") "截图记账" else it.name) else it
        })
        val result = engine.apply(state, record(state, Direction.INCOME), env.id()).state
        val entry = result.entries.single()
        val created = result.categories.single { it.id == entry.categoryId }
        assertTrue(created.enabled)
        assertEquals(Direction.INCOME, created.direction)
        assertTrue(created.name.startsWith("截图记账"))
        assertTrue(result.categories.filter { it.id in state.categories.map(Category::id) && it.direction == Direction.INCOME }.none { it.enabled })
        assertEquals(state.categories.size + 1, result.categories.size)
        assertEquals(state.contentVersion + 1, result.contentVersion)
        assertEquals(1, result.receipts.size)
    }

    @Test fun sameImageReturnsExistingEntryIncludingTrashAndNeverRevivesIt() {
        val state = engine.initialState()
        val first = engine.apply(state, record(state), env.id())
        val entry = first.state.entries.single()
        val same = engine.apply(first.state, record(first.state), env.id())
        assertEquals(listOf(entry.id), same.resultIds)
        assertEquals(1, same.state.entries.size)
        val deleted = engine.apply(same.state, undo(same.state, entry), env.id()).state
        val replay = engine.apply(deleted, record(deleted), env.id())
        assertEquals(listOf(entry.id), replay.resultIds)
        assertEquals(1, replay.state.entries.size)
        assertFalse(replay.state.entries.single().active)
    }

    @Test fun sameDayAmountWithDifferentImageRemainsTwoRealTransactions() {
        var state = engine.initialState()
        state = engine.apply(state, record(state), env.id()).state
        state = engine.apply(state, record(state, fingerprint = "b".repeat(64)), env.id()).state
        assertEquals(2, state.entries.size)
    }

    @Test fun replayUsesOneReceiptAndRejectsChangedPayload() {
        val state = engine.initialState()
        val operationId = env.id()
        val command = record(state)
        val first = engine.apply(state, command, operationId)
        assertEquals(first, engine.apply(first.state, command, operationId))
        assertFailsWith<DomainException> { engine.apply(first.state, record(state, amount = 99), operationId) }
    }

    @Test fun invalidSelectionsAndDatasetAreRejectedWithoutChangingState() {
        val state = engine.initialState()
        val income = state.categories.first { it.direction == Direction.INCOME }
        for (command in listOf(record(state, ledgerId = env.id()), record(state, categoryId = income.id),
            record(state, datasetId = env.id()), record(state, amount = 0), record(state, occurrence = "2026-10-26T00:00:00"))) {
            assertFailsWith<DomainException> { engine.apply(state, command, env.id()) }
        }
        assertTrue(state.entries.isEmpty())
        assertTrue(state.receipts.isEmpty())
    }

    @Test fun undoIsIdempotentButRejectsEditedEntryWrongOwnerAndLinkedRefunds() {
        val initial = engine.initialState()
        val state = engine.apply(initial, record(initial), env.id()).state
        val entry = state.entries.single()
        val operationId = env.id()
        val command = undo(state, entry)
        val deleted = engine.apply(state, command, operationId)
        assertFalse(deleted.state.entries.single().active)
        assertEquals(entry.revision + 1, deleted.state.entries.single().revision)
        assertEquals(deleted, engine.apply(deleted.state, command, operationId))
        assertFailsWith<DomainException> { engine.apply(state, undo(state, entry, creationOperationId = env.id()), env.id()) }
        val changed = engine.apply(state, Command.SaveEntry(entry.id, entry.ledgerId, entry.kind, 2345,
            entry.categoryId, entry.occurrence, "手动更正", entry.revision), env.id()).state
        assertFailsWith<DomainException> { engine.apply(changed, command, env.id()) }
        val refund = engine.apply(state, Command.AddRefund(entry.id, 1, capturedAt, "退款"), env.id()).state
        assertFailsWith<DomainException> { engine.apply(refund, command, env.id()) }
        val manual = state.copy(entries = listOf(entry.copy(originKind = OriginKind.MANUAL)))
        assertFailsWith<DomainException> { engine.apply(manual, command, env.id()) }
    }
}
