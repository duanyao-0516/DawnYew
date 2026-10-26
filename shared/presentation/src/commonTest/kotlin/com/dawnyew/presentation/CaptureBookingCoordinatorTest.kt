package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CaptureBookingCoordinatorTest {
    private var sequence = 0
    private val env = object : DomainEnvironment {
        override fun id() = "00000000-0000-4000-8000-${(++sequence).toString().padStart(12, '0')}"
        override fun localNow() = "2026-10-25T12:00:00"
        override fun utcNow() = "2026-10-25T04:00:00Z"
        override fun normalize(value: String) = value.trim()
    }
    private val engine = DomainEngine(env)
    private fun request() = CaptureBookingRequest(env.id(), 1234, Direction.EXPENSE,
        "2026-10-25T11:30:00", "a".repeat(64), "offline-v1")

    /** Real domain mutations, with faults only at the storage boundary. */
    private inner class Repository : LedgerRepository {
        override val state = MutableStateFlow<LedgerState?>(engine.initialState())
        override val pendingOperation = MutableStateFlow<PendingOperationRecovery?>(null)
        val events = mutableListOf<String>()
        var authorized = true
        var acknowledged = false
        var badReceipt = false
        var receiptFailure = false
        var acknowledgeFailure = false
        var failAfterCommit = false
        var switchBeforeExecute = false
        var switchAfterExecute = false
        var switchBeforeAcknowledge = false
        private fun checkAccess() { if (!authorized) throw DomainException(ErrorCode.LOCKED, "locked") }
        override suspend fun initialize() = Unit
        override suspend fun snapshot(): LedgerState { checkAccess(); events += "snapshot"; return state.value!! }
        override suspend fun close() = Unit
        override suspend fun replace(state: LedgerState) { this.state.value = state }
        override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> {
            checkAccess(); events += "execute"
            if (switchBeforeExecute) state.value = engine.initialState()
            val result = engine.apply(state.value!!, command, operationId, expectedVersion)
            state.value = result.state
            pendingOperation.value = PendingOperationRecovery(operationId, null)
            if (switchAfterExecute) state.value = engine.initialState()
            if (failAfterCommit) throw IllegalStateException("uncertain write")
            return result.resultIds
        }
        override suspend fun lookupOperation(operationId: String): OperationOutcome? {
            checkAccess(); events += "lookup"
            if (receiptFailure) throw IllegalStateException("receipt unavailable")
            val receipt = state.value!!.receipts.firstOrNull { it.operationId == operationId } ?: return null
            return OperationOutcome(operationId, if (badReceipt) "OtherCommand" else receipt.commandType, receipt.resultIds)
        }
        override suspend fun acknowledgeOperation(operationId: String) {
            checkAccess(); events += "acknowledge"
            if (acknowledgeFailure) throw IllegalStateException("acknowledgement failed")
            if (switchBeforeAcknowledge) state.value = engine.initialState()
            acknowledged = true
            if (pendingOperation.value?.operationId == operationId) pendingOperation.value = null
        }
    }

    @Test fun recordReturnsVerifiedPersistedDetailsAndAcknowledgesReceipt() = runTest {
        val repository = Repository()
        val request = request()
        val result = CaptureBookingCoordinator(repository).record(request, repository.state.value!!.datasetId)
        val entry = repository.state.value!!.entries.single()
        assertEquals(entry.id, result.entryId)
        assertEquals(request.operationId, result.operationId)
        assertEquals(request.operationId, result.creationOperationId)
        assertEquals(1, result.revision)
        assertTrue(result.created)
        assertTrue(result.active)
        assertEquals(1234, result.amountMinor)
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals("日常", result.ledgerName)
        assertEquals("其他支出", result.categoryName)
        assertTrue(result.note.contains("截图时间"))
        assertTrue(repository.acknowledged)
        assertNull(repository.pendingOperation.value)
        assertTrue(repository.events.indexOf("execute") < repository.events.indexOf("lookup"))
        assertTrue(repository.events.indexOf("lookup") < repository.events.indexOf("acknowledge"))
    }

    @Test fun sameRequestAndSameImageAreIdempotentButDifferentRequestsAreReportedAsDuplicates() = runTest {
        val repository = Repository()
        val coordinator = CaptureBookingCoordinator(repository)
        val datasetId = repository.state.value!!.datasetId
        val request = request()
        val first = coordinator.record(request, datasetId)
        assertEquals(first, coordinator.record(request, datasetId))
        val duplicate = coordinator.record(request.copy(operationId = env.id()), datasetId)
        assertEquals(first.entryId, duplicate.entryId)
        assertEquals(first.creationOperationId, duplicate.creationOperationId)
        assertFalse(duplicate.created)
        assertEquals(1, repository.state.value!!.entries.size)
    }

    @Test fun lockAndDifferentDatasetPreventMutation() = runTest {
        val repository = Repository()
        val coordinator = CaptureBookingCoordinator(repository)
        val datasetId = repository.state.value!!.datasetId
        assertFailsWith<DomainException> { coordinator.record(request(), env.id()) }
        repository.authorized = false
        assertFailsWith<DomainException> { coordinator.record(request(), datasetId) }
        assertTrue(repository.state.value!!.entries.isEmpty())
        assertFalse(repository.acknowledged)
    }

    @Test fun datasetSwitchAtWriterBoundaryCannotPutCaptureIntoReplacementDataset() = runTest {
        val repository = Repository().apply { switchBeforeExecute = true }
        val datasetId = repository.state.value!!.datasetId
        assertFailsWith<DomainException> { CaptureBookingCoordinator(repository).record(request(), datasetId) }
        assertTrue(repository.state.value!!.entries.isEmpty())
        assertFalse(repository.acknowledged)
    }

    @Test fun datasetSwitchAfterCommitNeverProducesSuccessInAnotherDataset() = runTest {
        for (duringAck in listOf(false, true)) {
            val repository = Repository().apply { switchAfterExecute = !duringAck; switchBeforeAcknowledge = duringAck }
            val datasetId = repository.state.value!!.datasetId
            assertFailsWith<DomainException> { CaptureBookingCoordinator(repository).record(request(), datasetId) }
        }
    }

    @Test fun missingOrWrongReceiptAndAcknowledgementFailureNeverReturnSuccess() = runTest {
        for (mode in 0..2) {
            val repository = Repository().apply { badReceipt = mode == 0; receiptFailure = mode == 1; acknowledgeFailure = mode == 2 }
            val datasetId = repository.state.value!!.datasetId
            assertFails { CaptureBookingCoordinator(repository).record(request(), datasetId) }
            assertEquals(1, repository.state.value!!.entries.size)
            assertFalse(repository.acknowledged)
            assertNotNull(repository.pendingOperation.value)
        }
    }

    @Test fun uncertainWriteRemainsRecoverableAndRetryDoesNotDuplicate() = runTest {
        val repository = Repository().apply { failAfterCommit = true }
        val coordinator = CaptureBookingCoordinator(repository)
        val datasetId = repository.state.value!!.datasetId
        val request = request()
        assertFails { coordinator.record(request, datasetId) }
        assertFalse(repository.acknowledged)
        assertNotNull(repository.pendingOperation.value)
        repository.failAfterCommit = false
        assertTrue(coordinator.record(request, datasetId).active)
        assertEquals(1, repository.state.value!!.entries.size)
    }

    @Test fun undoVerifiesReceiptAndDuplicateCaptureAfterUndoStaysDeleted() = runTest {
        val repository = Repository()
        val coordinator = CaptureBookingCoordinator(repository)
        val datasetId = repository.state.value!!.datasetId
        val request = request()
        val result = coordinator.record(request, datasetId)
        val undoId = env.id()
        repeat(2) {
            assertTrue(coordinator.undo(datasetId, result.entryId, result.creationOperationId, result.revision, undoId))
        }
        val duplicate = coordinator.record(request.copy(operationId = env.id()), datasetId)
        assertFalse(duplicate.active)
        assertFalse(duplicate.created)
        assertEquals(1, repository.state.value!!.entries.size)
        assertEquals(1, repository.state.value!!.deletionGroups.size)
    }

    @Test fun oldNotificationCannotUndoAnEditedEntry() = runTest {
        val repository = Repository()
        val coordinator = CaptureBookingCoordinator(repository)
        val datasetId = repository.state.value!!.datasetId
        val result = coordinator.record(request(), datasetId)
        val entry = repository.state.value!!.entries.single()
        repository.state.value = engine.apply(repository.state.value!!, Command.SaveEntry(entry.id, entry.ledgerId,
            entry.kind, 9999, entry.categoryId, entry.occurrence, "修改", entry.revision), env.id()).state
        assertFailsWith<DomainException> { coordinator.undo(datasetId, result.entryId, result.creationOperationId, result.revision, env.id()) }
        assertTrue(repository.state.value!!.entries.single().active)
    }
}
