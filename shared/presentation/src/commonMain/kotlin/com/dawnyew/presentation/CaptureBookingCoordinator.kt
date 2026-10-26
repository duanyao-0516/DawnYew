package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A process-owned request: reuse its operationId and payload when a commit needs retrying. */
data class CaptureBookingRequest(
    val operationId: String,
    val amountMinor: Long,
    val direction: Direction,
    val capturedAt: String,
    val fingerprint: String,
    val engineVersion: String,
    val occurrence: String? = null,
    val note: String = "",
    val ledgerId: String? = null,
    val categoryId: String? = null,
)

/** `created` means this request created the entry, including a receipt replay of that request. */
data class CaptureBookingOutcome(
    val datasetId: String,
    val operationId: String,
    val entryId: String,
    val creationOperationId: String,
    val revision: Long,
    val created: Boolean,
    val active: Boolean,
    val amountMinor: Long,
    val direction: Direction,
    val ledgerName: String,
    val categoryName: String,
    val occurrence: String,
    val note: String,
)

/**
 * Shared save/recovery flow, independent of an editor or foreground UI. The repository remains
 * the authority for app-lock access. Platform callers must also reject an expired capture/session
 * generation before calling; a dataset ID cannot detect replacing a database with the same backup.
 */
class CaptureBookingCoordinator(private val repository: LedgerRepository) {
    private val gate = Mutex()

    suspend fun record(request: CaptureBookingRequest, expectedDatasetId: String): CaptureBookingOutcome = gate.withLock {
        val command = Command.RecordCapture(expectedDatasetId, request.amountMinor, request.direction,
            request.capturedAt, request.fingerprint, request.engineVersion, request.occurrence,
            request.note, request.ledgerId, request.categoryId)
        val (state, entryId) = commit(command, request.operationId, expectedDatasetId, "RecordCapture")
        val entry = state.entries.firstOrNull { it.id == entryId }
            ?: uncertain("截图账单保存回执存在，但账单已不可用")
        if (entry.captureFingerprint != request.fingerprint || entry.kind == EntryKind.REFUND)
            uncertain("截图账单保存结果与本次图片不一致")
        val ledger = state.ledgers.firstOrNull { it.id == entry.ledgerId } ?: uncertain("截图账单的账本已不可用")
        val category = state.categories.firstOrNull { it.id == entry.categoryId } ?: uncertain("截图账单的分类已不可用")
        CaptureBookingOutcome(state.datasetId, request.operationId, entry.id, entry.creationOperationId,
            entry.revision, entry.creationOperationId == request.operationId, entry.active, entry.amountMinor,
            if (entry.kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE,
            ledger.name, category.name, entry.occurrence, entry.note)
    }

    /** Returns true only after a verified, acknowledged undo; replaying the same undo is safe. */
    suspend fun undo(datasetId: String, entryId: String, creationOperationId: String,
                     expectedRevision: Long, operationId: String): Boolean = gate.withLock {
        val command = Command.UndoCapture(datasetId, entryId, creationOperationId, expectedRevision)
        val (state, resultId) = commit(command, operationId, datasetId, "UndoCapture")
        val entry = state.entries.firstOrNull { it.id == resultId }
        if (resultId != entryId || entry == null || entry.active || entry.originKind != OriginKind.OCR ||
            entry.creationOperationId != creationOperationId || entry.revision != Money.add(expectedRevision, 1))
            uncertain("截图账单撤销结果已变化，请到详情核对")
        true
    }

    private suspend fun commit(command: Command, operationId: String, datasetId: String, commandType: String): Pair<LedgerState, String> {
        validateId(operationId); validateId(datasetId)
        snapshot(datasetId)
        repository.pendingOperation.value?.let {
            if (it.operationId != operationId) conflict("请先核对上一笔操作的保存结果")
        }
        val ids = repository.execute(command, operationId)
        val entryId = ids.singleOrNull() ?: uncertain("截图账单保存结果无效，请核对后重试")
        val expected = OperationOutcome(operationId, commandType, ids)
        if (repository.lookupOperation(operationId) != expected)
            uncertain("截图账单保存回执未能核实，请保留本次操作后重试")
        val committed = snapshot(datasetId)
        if (committed.entries.none { it.id == entryId }) uncertain("截图账单保存回执存在，但账单已不可用")
        repository.acknowledgeOperation(operationId)
        return snapshot(datasetId) to entryId
    }

    private suspend fun snapshot(datasetId: String): LedgerState = repository.snapshot().also {
        if (it.datasetId != datasetId) conflict("截图所属账本数据已切换，请重新截屏")
    }

    private fun uncertain(message: String): Nothing = throw DomainException(ErrorCode.STORAGE, message)
}
