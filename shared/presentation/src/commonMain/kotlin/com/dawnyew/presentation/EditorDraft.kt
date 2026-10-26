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
    data class Account(val value: String?) : EditorChange
    data class Recorded(val value: Boolean) : EditorChange
    data class ExcludedFromBudget(val value: Boolean) : EditorChange
    data class Reconciled(val value: Boolean) : EditorChange
    data class Project(val value: String?) : EditorChange
    data class Merchant(val value: String?) : EditorChange
    data class Tags(val value: Set<String>) : EditorChange
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
    val accountId: String? = null,
    val recorded: Boolean = true,
    val excludedFromBudget: Boolean = false,
    val reconciled: Boolean = false,
    val projectId: String? = null,
    val merchantId: String? = null,
    val tagIds: Set<String> = emptySet(),
) {
    val needsDiscardConfirmation get() = dirty || capture != null

    fun command(): Command {
        if (duplicateCheckPending) invalid("正在核对本机疑似重复记录，请稍后")
        duplicateCheckError?.let {invalid(it)}
        if (capture?.duplicateWarning == true && !duplicateAcknowledged) invalid("请核对疑似重复记录，并确认仍要保留本次账单")
        if (capture != null && !confirmed) invalid("请确认识别出的金额、日期、类型和分类")
        val minor = AmountCalculator.evaluate(amount)
        val occurrence = date + "T" + if (time.length == 5) "$time:00" else time
        validateOccurrence(occurrence)
        val details = EntryDetails(editId.orEmpty(), accountId, recorded, if (kind == EntryKind.REFUND) false else excludedFromBudget, reconciled, projectId, merchantId)
        return if (refundParentId != null) Command.AddRefund(refundParentId, minor, occurrence, note, details, tagIds.sorted())
        else Command.SaveEntry(editId, ledgerId ?: invalid("请选择记入的实际账本"), kind, minor,
            categoryId ?: invalid("请选择分类"), occurrence, note, expectedRevision,
            if (capture != null) OriginKind.OCR else OriginKind.MANUAL, capture?.fingerprint, capture?.engineVersion, details, tagIds.sorted())
    }

    /** A continuation is a new manual operation, retaining only still-valid selections. */
    fun continuation(state: LedgerState, operationId: String, now: String): EditorDraft {
        val nextKind = kind.takeIf { it != EntryKind.REFUND } ?: EntryKind.EXPENSE
        val direction = if (nextKind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
        val nextLedger = ledgerId?.takeIf { id -> state.ledgers.any { it.id == id && !it.archived } }
        val available = state.categories.filter { it.ledgerId == nextLedger && it.enabled && it.direction == direction }
        val nextCategory = categoryId?.takeIf { id -> available.any { it.id == id } } ?: available.minWithOrNull(compareBy<Category> { it.sortOrder }.thenBy { it.id })?.id
        return create(state, operationId, now, nextLedger).copy(kind = nextKind, categoryId = nextCategory,
            accountId = accountId?.takeIf { state.writableAccount(it, nextLedger) }, recorded = recorded,
            excludedFromBudget = excludedFromBudget, reconciled = reconciled,
            projectId = projectId?.takeIf { state.writableDimension(it, nextLedger, DimensionKind.PROJECT) },
            merchantId = merchantId?.takeIf { state.writableDimension(it, nextLedger, DimensionKind.MERCHANT) },
            tagIds = tagIds.filter { state.writableDimension(it, nextLedger, DimensionKind.TAG) }.toSet())
    }

    companion object {
        fun create(state: LedgerState, operationId: String, now: String, defaultLedgerId: String?,
                   editId: String? = null, refundParentId: String? = null, capture: CaptureDraftUi? = null): EditorDraft {
            val existing = editId?.let { id -> state.entries.find { it.id == id && it.active } ?: invalid("这笔账单已不存在") }
            val parent = refundParentId?.let { id -> state.entries.find { it.id == id && it.active && it.kind == EntryKind.EXPENSE } ?: invalid("原支出已不存在") }
            val ledger = existing?.ledgerId ?: parent?.ledgerId ?: defaultLedgerId?.takeIf { id -> state.ledgers.any { it.id == id && !it.archived } }
            val kind = existing?.kind ?: when {
                parent != null -> EntryKind.REFUND
                capture?.direction == Direction.INCOME -> EntryKind.INCOME
                else -> EntryKind.EXPENSE
            }
            val categoryDirection = if (kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
            val occurrence = existing?.occurrence ?: capture?.occurrence?.takeIf { it.isNotBlank() } ?: now
            val category = existing?.categoryId ?: parent?.categoryId ?: state.categories.filter { it.ledgerId == ledger && it.enabled && it.direction == categoryDirection }.minWithOrNull(compareBy<Category> { it.sortOrder }.thenBy { it.id })?.id
            val details = (existing ?: parent)?.let { entry -> state.entryDetails.find { it.entryId == entry.id } }
            val defaultAccount = state.preferences.find { it.key == "defaultAccountId" }?.value?.takeIf { state.writableAccount(it, ledger) }
            return EditorDraft(operationId, ledger, kind, existing?.let { Money.format(it.amountMinor) } ?: capture?.amount.orEmpty(),
                category, occurrence.take(10), occurrence.substringAfter('T'), existing?.note ?: capture?.note.orEmpty(),
                existing?.id, existing?.revision, parent?.id, capture, confirmed = capture == null,
                accountId = if (existing != null) details?.accountId else details?.accountId?.takeIf { state.writableAccount(it, ledger) } ?: defaultAccount,
                recorded = if (existing != null) details?.recorded ?: true else true,
                excludedFromBudget = if (kind == EntryKind.REFUND) false else details?.excludedFromBudget ?: false,
                reconciled = if (existing != null) details?.reconciled ?: false else false,
                projectId = details?.projectId, merchantId = details?.merchantId,
                tagIds = state.entryTags.filter { it.entryId == (existing ?: parent)?.id }.map { it.tagId }.toSet())
        }
    }
}

internal fun LedgerState.writableAccount(id: String, ledgerId: String?): Boolean =
    accounts.any { it.id == id && it.ledgerId == ledgerId && !it.archived } && obligations.none { it.virtualAccountId == id }
internal fun LedgerState.writableDimension(id: String, ledgerId: String?, kind: DimensionKind): Boolean =
    dimensions.any { it.id == id && it.ledgerId == ledgerId && it.kind == kind && !it.archived }
