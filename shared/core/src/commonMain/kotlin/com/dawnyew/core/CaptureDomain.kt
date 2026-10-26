package com.dawnyew.core

internal fun requireCaptureDataset(state: LedgerState, datasetId: String) {
    validateId(datasetId)
    if (state.datasetId != datasetId) conflict("截图所属账本数据已切换，请重新截屏")
}

/** No UI confirmation policy or platform authorization belongs in this mutation. */
internal fun recordCapture(original: LedgerState, command: Command.RecordCapture, operationId: String, env: DomainEnvironment): Mutation {
    Money.requireValid(command.amountMinor)
    validateDigest(command.fingerprint)
    if (command.engineVersion.isBlank()) invalid("识别引擎版本无效")
    validateOccurrence(command.capturedAt)
    val occurrence = command.occurrence?.takeIf { it.isNotBlank() } ?: command.capturedAt
    validateOccurrence(occurrence)
    if (occurrence > env.localNow()) invalid("不能记录未来发生的账单")
    if (command.note.contains('\u0000')) invalid("备注不能含空字符")
    codePointCount(command.note)

    // This check runs on the same state as the write. Deleted matches remain deleted.
    original.entries.firstOrNull { it.captureFingerprint == command.fingerprint }?.let {
        return Mutation(original, listOf(it.id))
    }
    val availableLedgers = original.ledgers.filterNot { it.archived }
    val preferredLedger = original.preferences.firstOrNull { it.key == "lastLedgerId" }?.value
    val ledger = if (command.ledgerId != null) {
        availableLedgers.firstOrNull { it.id == command.ledgerId } ?: invalid("所选账本已不可用，请重新选择")
    } else availableLedgers.firstOrNull { it.id == preferredLedger }
        ?: availableLedgers.minWithOrNull(compareBy<Ledger> { it.sortOrder }.thenBy { it.id })
        ?: invalid("没有可用账本")

    var state = original
    val availableCategories = state.categories.filter { it.ledgerId == ledger.id && it.direction == command.direction && it.enabled }
    val otherName = if (command.direction == Direction.INCOME) "其他收入" else "其他支出"
    val category = if (command.categoryId != null) {
        availableCategories.firstOrNull { it.id == command.categoryId } ?: invalid("所选分类已不可用或收支类型不符，请重新选择")
    } else availableCategories.firstOrNull { it.name == otherName }
        ?: availableCategories.minWithOrNull(compareBy<Category> { it.sortOrder }.thenBy { it.id })
        ?: run {
            // Preserve disabled user categories; create an unused name in the same direction.
            val names = state.categories.filter { it.ledgerId == ledger.id && it.direction == command.direction }.map { it.name }.toSet()
            var name = "截图记账"
            var suffix = 2
            while (name in names) { name = "截图记账${suffix++}" }
            Category(env.id(), ledger.id, command.direction, name).also { state = state.copy(categories = state.categories + it) }
        }
    val decisions = buildList {
        if (command.occurrence.isNullOrBlank()) add("时间采用截图时间")
        if (command.ledgerId == null) add("默认账本：${ledger.name}")
        if (command.categoryId == null) add("默认分类：${category.name}")
    }
    val suffix = if (decisions.isEmpty()) "" else "[截图记账：${decisions.joinToString("；")}]"
    val note = captureNote(command.note, suffix)
    val now = env.utcNow()
    val entry = Entry(env.id(), ledger.id, if (command.direction == Direction.INCOME) EntryKind.INCOME else EntryKind.EXPENSE,
        command.amountMinor, category.id, occurrence, note, createdAt = now, updatedAt = now,
        originKind = OriginKind.OCR, creationOperationId = operationId,
        captureFingerprint = command.fingerprint, captureEngineVersion = command.engineVersion)
    state = state.copy(entries = state.entries + entry)
    val accountId = state.preferences.firstOrNull { it.key == "defaultAccountId" }?.value?.takeIf { id ->
        state.accounts.any { it.id == id && it.ledgerId == ledger.id && !it.archived && it.openingAt <= occurrence } &&
            state.obligations.none { it.virtualAccountId == id }
    }
    state = updateEntryAssociations(state, entry.id, EntryDetails(entry.id, accountId), emptyList())
    return Mutation(state, listOf(entry.id))
}

/** Keep the decision record even when an OCR merchant string reaches the note limit. */
private fun captureNote(source: String, suffix: String): String {
    val separator = if (source.isNotEmpty() && suffix.isNotEmpty()) "\n" else ""
    val limit = 500 - codePointCount(suffix) - separator.length
    var end = 0
    var count = 0
    while (end < source.length && count < limit) {
        end += if (source[end].isHighSurrogate()) 2 else 1
        count++
    }
    return source.substring(0, end) + separator + suffix
}

internal fun undoCapture(state: LedgerState, command: Command.UndoCapture, env: DomainEnvironment): Mutation {
    validateId(command.entryId); validateId(command.creationOperationId)
    if (command.expectedRevision <= 0) invalid("撤销版本无效")
    val entry = state.entries.firstOrNull { it.id == command.entryId } ?: invalid("这笔截图账单已不存在")
    if (entry.originKind != OriginKind.OCR || entry.creationOperationId != command.creationOperationId) conflict("这笔账单不属于该截图操作")
    if (state.entries.any { it.parentExpenseId == entry.id }) conflict("账单已有退款记录，请到详情中处理")
    if (!entry.active) {
        if (entry.revision != Money.add(command.expectedRevision, 1)) conflict("账单已更新，请到详情中核对")
        return Mutation(state, listOf(entry.id))
    }
    if (entry.revision != command.expectedRevision) conflict("账单已修改，请到详情中核对后删除")
    val group = DeletionGroup(env.id(), env.utcNow(), entry.id)
    val removed = entry.copy(deletionGroupId = group.id, revision = Money.add(entry.revision, 1), updatedAt = group.deletedAt)
    return Mutation(state.copy(entries = state.entries.map { if (it.id == entry.id) removed else it },
        deletionGroups = state.deletionGroups + group), listOf(entry.id))
}
