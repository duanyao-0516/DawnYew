package com.dawnyew.presentation

import com.dawnyew.core.*

/** User input carries only the changed field. Asynchronous OCR state is controller-owned. */
sealed interface EditorChange {
    data class Amount(val value: String) : EditorChange
    data class Note(val value: String) : EditorChange
    data class Date(val value: String) : EditorChange
    data class Time(val value: String) : EditorChange
    data class Kind(val value: EntryKind) : EditorChange
    data class Ledger(val value: String?) : EditorChange
    data class Category(val value: String) : EditorChange
    data class Confirmed(val value: Boolean) : EditorChange
    data class DuplicateAcknowledged(val value: Boolean) : EditorChange
}

/** Process-memory only. Never serialize this object into saved-state bundles or files. */
data class EditorDraft(
    val operationId: String,
    val ledgerId: String?,
    val kind: EntryKind,
    val amount: String,
    val categoryId: String?,
    val date: String,
    val time: String,
    val note: String,
    val editId: String? = null,
    val expectedRevision: Long? = null,
    val refundParentId: String? = null,
    val capture: CaptureDraftUi? = null,
    val confirmed: Boolean = false,
    val dirty: Boolean = false,
    val duplicateCheckPending: Boolean = false,
    val duplicateCheckError: String? = null,
    val duplicateAcknowledged: Boolean = false,
    val duplicateMessages: List<String> = emptyList(),
) {
    val needsDiscardConfirmation get() = dirty || capture != null

    fun command(): Command {
        if (duplicateCheckPending) invalid("正在核对本机疑似重复记录，请稍后")
        duplicateCheckError?.let {invalid(it)}
        if (capture?.duplicateWarning == true && !duplicateAcknowledged) invalid("请核对疑似重复记录，并确认仍要保留本次账单")
        if (capture != null && !confirmed) invalid("请确认识别出的金额、日期、类型和分类")
        val minor = Money.parse(amount)
        val occurrence = date + "T" + if (time.length == 5) "$time:00" else time
        validateOccurrence(occurrence)
        return if (refundParentId != null) Command.AddRefund(refundParentId, minor, occurrence, note)
        else Command.SaveEntry(editId, ledgerId ?: invalid("请选择记入的实际账本"), kind, minor,
            categoryId ?: invalid("请选择分类"), occurrence, note, expectedRevision,
            if (capture != null) OriginKind.OCR else OriginKind.MANUAL, capture?.fingerprint, capture?.engineVersion)
    }

    /** A continuation is a new manual operation, retaining only still-valid selections. */
    fun continuation(state: LedgerState, operationId: String, now: String): EditorDraft {
        val nextKind = kind.takeIf { it != EntryKind.REFUND } ?: EntryKind.EXPENSE
        val direction = if (nextKind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
        val nextLedger = ledgerId?.takeIf { id -> state.ledgers.any { it.id == id && !it.archived } }
        val available = state.categories.filter { it.ledgerId == nextLedger && it.enabled && it.direction == direction }
        val nextCategory = categoryId?.takeIf { id -> available.any { it.id == id } } ?: available.minWithOrNull(compareBy<Category> { it.sortOrder }.thenBy { it.id })?.id
        return create(state, operationId, now, nextLedger).copy(kind = nextKind, categoryId = nextCategory)
    }

    companion object {
        fun create(state: LedgerState, operationId: String, now: String, defaultLedgerId: String?,
                   editId: String? = null, refundParentId: String? = null, capture: CaptureDraftUi? = null): EditorDraft {
            val existing = editId?.let { id -> state.entries.find { it.id == id && it.active } ?: invalid("这笔账单已不存在") }
            val parent = refundParentId?.let { id -> state.entries.find { it.id == id && it.active && it.kind == EntryKind.EXPENSE } ?: invalid("原支出已不存在") }
            val ledger = existing?.ledgerId ?: parent?.ledgerId ?: defaultLedgerId?.takeIf { id -> state.ledgers.any { it.id == id && !it.archived } }
            val kind = existing?.kind ?: if (parent != null) EntryKind.REFUND else EntryKind.EXPENSE
            val occurrence = existing?.occurrence ?: capture?.occurrence?.takeIf { it.isNotBlank() } ?: now
            val category = existing?.categoryId ?: parent?.categoryId ?: state.categories.filter { it.ledgerId == ledger && it.enabled && it.direction == Direction.EXPENSE }.minWithOrNull(compareBy<Category> { it.sortOrder }.thenBy { it.id })?.id
            return EditorDraft(operationId, ledger, kind, existing?.let { Money.format(it.amountMinor) } ?: capture?.amount.orEmpty(),
                category, occurrence.take(10), occurrence.substringAfter('T'), existing?.note ?: capture?.note.orEmpty(),
                existing?.id, existing?.revision, parent?.id, capture, confirmed = capture == null)
        }
    }
}
