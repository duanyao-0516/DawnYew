package com.dawnyew.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DebugDataTest {
    private var nextId = 100
    private var localNow = "2026-10-18T12:34:56"
    private val env = object : DomainEnvironment {
        override fun id() = "00000000-0000-4000-8000-${(++nextId).toString().padStart(12, '0')}"
        override fun localNow() = localNow
        override fun utcNow() = "2026-10-18T04:34:56Z"
        override fun normalize(value: String) = value.trim()
    }
    private val engine = DomainEngine(env)
    // The command is a serialized public contract, including before its implementation exists.
    private fun command(ledgerId: String, start: String = "2026-09-19", end: String = "2026-10-18", count: Int = 100): Command =
        Json.decodeFromString<Command>(buildJsonObject {
            put("type", "com.dawnyew.core.Command.GenerateDebugEntries")
            put("ledgerId", ledgerId); put("startDate", start); put("endDate", end); put("count", count)
        }.toString())
    private fun apply(state: LedgerState, command: Command, operationId: String = env.id()) = engine.apply(state, command, operationId)
    private fun failure(state: LedgerState, command: Command, code: ErrorCode = ErrorCode.VALIDATION) {
        assertEquals(code, assertFailsWith<DomainException> { apply(state, command) }.code)
    }

    @Test fun generatesExactReviewedBatchInSelectedLedgerWithEnabledMatchingCategories() {
        val initial = engine.initialState()
        val second = apply(initial, Command.CreateLedger("旅行")).state
        val ledger = second.ledgers.last()
        val disabled = second.categories.first { it.ledgerId == ledger.id }
        val before = second.copy(categories = second.categories.map { if (it.id == disabled.id) it.copy(enabled = false) else it })
        val result = apply(before, command(ledger.id))
        val state = result.state
        val batch = state.importBatches.single()
        assertEquals(listOf(batch.id), result.resultIds)
        assertEquals("dawnyew.debug.seed", batch.adapterId)
        assertEquals(100, batch.count)
        assertEquals(100, state.entries.size)
        assertEquals(100, state.importMembers.size)
        assertEquals(before.contentVersion + 1, state.contentVersion)
        assertEquals(before.ledgers, state.ledgers)
        assertEquals(before.categories, state.categories)
        val categories = state.categories.associateBy { it.id }
        assertTrue(state.entries.all {
            val category = categories.getValue(it.categoryId)
            it.ledgerId == ledger.id && category.ledgerId == ledger.id && category.enabled &&
                category.direction == if (it.kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
        })
        assertTrue(state.entries.count { it.kind == EntryKind.EXPENSE } > state.entries.count { it.kind == EntryKind.INCOME })
        assertTrue(state.entries.any { it.kind == EntryKind.INCOME })
        assertTrue(state.entries.all { it.kind != EntryKind.REFUND && it.originKind == OriginKind.IMPORT && it.note.contains("DEBUG 测试") && it.amountMinor in 1..Money.MAX })
        assertTrue(state.entries.map { it.amountMinor }.distinct().size > 1)
        assertTrue(state.sourceIdentities.isEmpty())
        validateState(state, env::normalize)
    }

    @Test fun coversInclusiveDatesAndNeverCreatesFutureTimes() {
        val state = engine.initialState()
        val generated = apply(state, command(state.ledgers.single().id)).state.entries
        assertEquals("2026-09-19", generated.minOf { it.localDate })
        assertEquals("2026-10-18", generated.maxOf { it.localDate })
        assertEquals(30, generated.map { it.localDate }.distinct().size)
        assertTrue(generated.all { it.occurrence <= localNow })
        localNow = "2026-10-18T00:00:00"
        val midnight = apply(state, command(state.ledgers.single().id, "2026-10-18", "2026-10-18", 10)).state.entries
        assertTrue(midnight.all { it.occurrence == localNow })
    }

    @Test fun acceptsMinimumAndMaximumCountsAndSparseLeapYearRanges() {
        val state = engine.initialState(); val ledgerId = state.ledgers.single().id
        assertEquals(1, apply(state, command(ledgerId, count = 1)).state.entries.size)
        assertEquals(10_000, apply(state, command(ledgerId, count = 10_000)).state.entries.size)
        val leap = apply(state, command(ledgerId, "2024-02-28", "2024-03-01", 3)).state.entries
        assertEquals(setOf("2024-02-28", "2024-02-29", "2024-03-01"), leap.map { it.localDate }.toSet())
        val sparse = apply(state, command(ledgerId, "0001-01-01", "2026-10-18", 2)).state.entries
        assertEquals(setOf("0001-01-01", "2026-10-18"), sparse.map { it.localDate }.toSet())
    }

    @Test fun supportsEitherSingleAvailableDirection() {
        val state = engine.initialState()
        for (direction in Direction.entries) {
            val before = state.copy(categories = state.categories.filter { it.direction == direction })
            val entries = apply(before, command(before.ledgers.single().id)).state.entries
            assertEquals(100, entries.size)
            assertTrue(entries.all { it.kind == if (direction == Direction.INCOME) EntryKind.INCOME else EntryKind.EXPENSE })
        }
    }

    @Test fun rejectsInvalidCountsDatesAndUnavailableTargetsBeforeAllocatingIds() {
        val state = engine.initialState(); val ledgerId = state.ledgers.single().id
        val invalidCommands = listOf(
            command(ledgerId, count = 0), command(ledgerId, count = -1), command(ledgerId, count = 10_001),
            command(ledgerId, "2026-10-19", "2026-10-19"), command(ledgerId, "2026-10-18", "2026-10-17"),
            command(ledgerId, "2026-02-30"), command(ledgerId, "2026-2-01"), command(ledgerId, "0000-01-01"),
            command(ledgerId, "2026-01-01T00:00:00"), command(env.id()), command("invalid-id"),
        )
        for (command in invalidCommands) {
            val operation = env.id(); val allocated = nextId
            assertEquals(ErrorCode.VALIDATION, assertFailsWith<DomainException> { apply(state, command, operation) }.code)
            assertEquals(allocated, nextId)
        }
        val additional = apply(state, Command.CreateLedger("归档目标")).state
        val archived = additional.copy(ledgers = additional.ledgers.map { if (it.id == ledgerId) it.copy(archived = true) else it })
        failure(archived, command(ledgerId))
        failure(state.copy(categories = emptyList()), command(ledgerId))
        failure(state.copy(categories = state.categories.map { it.copy(enabled = false) }), command(ledgerId))
        failure(additional.copy(categories = additional.categories.filter { it.ledgerId != ledgerId }), command(ledgerId))
    }

    @Test fun retriesSameOperationAndRejectsChangedParametersWithOrWithoutReceipt() {
        val before = engine.initialState(); val ledgerId = before.ledgers.single().id
        val operation = env.id(); val command = command(ledgerId)
        val first = engine.apply(before, command, operation, before.contentVersion)
        for (state in listOf(first.state, first.state.copy(receipts = emptyList()))) {
            val allocated = nextId
            val repeated = engine.apply(state, command, operation, before.contentVersion)
            assertEquals(state, repeated.state)
            assertEquals(first.resultIds, repeated.resultIds)
            assertEquals(allocated, nextId)
            for (changed in listOf(command(ledgerId, count = 101), command(ledgerId, start = "2026-09-20"), command(ledgerId, end = "2026-10-17"), command(env.id()))) {
                assertEquals(ErrorCode.CONFLICT, assertFailsWith<DomainException> { engine.apply(state, changed, operation) }.code)
            }
        }
        assertEquals(ErrorCode.CONFLICT, assertFailsWith<DomainException> { engine.apply(before, command, env.id(), before.contentVersion + 1) }.code)
    }

    @Test fun preservesExistingAndSuspectedDuplicateEntriesAndUndoesOnlyItsBatch() {
        val initial = engine.initialState(); val request = command(initial.ledgers.single().id)
        val generated = apply(initial, request).state
        val similar = generated.entries.first().copy(id = env.id(), originKind = OriginKind.MANUAL, creationOperationId = env.id())
        val before = initial.copy(entries = listOf(similar))
        val operation = env.id(); val result = apply(before, request, operation)
        assertEquals(101, result.state.entries.size)
        assertEquals(similar, result.state.entries.first())
        val another = apply(result.state, request)
        assertEquals(201, another.state.entries.size)
        val undone = apply(another.state, Command.UndoImport(result.resultIds.single())).state
        assertEquals(101, undone.entries.size)
        assertEquals(similar, undone.entries.first())
        assertEquals(another.state.entries.takeLast(100), undone.entries.drop(1))
        val retry = engine.apply(undone.copy(receipts = emptyList()), request, operation)
        assertEquals(undone.copy(receipts = emptyList()), retry.state)
        assertEquals(result.resultIds, retry.resultIds)
    }

    @Test fun editedMovedDeletedAndReferencedBatchMembersPreventWholeBatchUndo() {
        val before = apply(engine.initialState(), Command.CreateLedger("旅行")).state
        val result = apply(before, command(before.ledgers.first().id))
        val entry = result.state.entries.first { it.kind == EntryKind.EXPENSE }
        val otherCategory = before.categories.first { it.ledgerId == before.ledgers.last().id && it.direction == Direction.EXPENSE }
        val changes = listOf(
            Command.SaveEntry(entry.id, entry.ledgerId, entry.kind, entry.amountMinor, entry.categoryId, entry.occurrence, "已修改", entry.revision),
            Command.MoveEntry(entry.id, otherCategory.ledgerId, otherCategory.id),
            Command.SoftDelete(entry.id),
            Command.AddRefund(entry.id, 1, entry.occurrence, "真实退款"),
        )
        for (change in changes) {
            val changed = apply(result.state, change).state
            failure(changed, Command.UndoImport(result.resultIds.single()), ErrorCode.CONFLICT)
        }
    }

    @Test fun repositoryDefaultLookupRecoversOnlyDebugSubmissionsWithoutReceipts() = runTest {
        val before = engine.initialState(); val operation = env.id()
        val generated = apply(before, command(before.ledgers.single().id), operation)
        val data = MutableStateFlow<LedgerState?>(generated.state.copy(receipts = emptyList()))
        val repository = object : LedgerRepository {
            override val state = data
            override suspend fun initialize() = Unit
            override suspend fun snapshot() = requireNotNull(data.value)
            override suspend fun replace(state: LedgerState) { data.value = state }
            override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> = error("Read-only fixture")
            override suspend fun close() = Unit
        }
        assertEquals(OperationOutcome(operation, "GenerateDebugEntries", generated.resultIds), repository.lookupOperation(operation))
        repository.replace(repository.snapshot().let { it.copy(importBatches = it.importBatches.map { batch -> batch.copy(adapterId = "ordinary-import") }) })
        assertNull(repository.lookupOperation(operation))
    }
}
