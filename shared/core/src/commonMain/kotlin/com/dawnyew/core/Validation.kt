package com.dawnyew.core

fun validateId(value: String) {
    if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)) invalid("标识必须为规范小写 UUID")
}
fun validateDigest(value: String) { if (!Regex("[0-9a-f]{64}").matches(value)) invalid("摘要格式无效") }
fun validateUtc(value: String) {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z").matches(value)) invalid("UTC 时间格式无效")
    validateOccurrence(value.take(19))
    try { kotlin.time.Instant.parse(value) } catch (_: Exception) { invalid("UTC 时间无效") }
}
fun validateSource(source: SourceIdentity) {
    validateId(source.id); validateId(source.entryId)
    if (source.normalizationVersion != 1 || source.identityKey().any { it.isBlank() || it.any { c -> c.code < 32 } }) invalid("来源身份或规范化版本无效")
    source.identityKey().forEach { codePointCount(it) }; codePointCount(source.canonicalSource)
    validateDigest(source.sourceDigest)
    if (source.sourceDigest != Sha256.hex(source.canonicalSource.encodeToByteArray())) invalid("来源摘要与规范化内容不一致")
}

/** Complete semantic validation used before restore or batch commit; never applies the device's current date to history. */
fun validateState(state: LedgerState, normalize: (String) -> String = ::normalizeTextNfc) {
    fun ensure(ok: Boolean, message: String) { if (!ok) invalid(message) }
    fun unique(values: List<String>, name: String) { ensure(values.size==values.toSet().size, "$name 标识重复") }
    fun validName(value: String, max: Int) { ensure(value==normalize(value.trim()) && codePointCount(value) in 1..max && value.none { it.code<32 }, "名称格式无效") }
    validateId(state.datasetId)
    (state.ledgers.map { it.id } + state.categories.flatMap { listOf(it.id,it.ledgerId) } +
        state.entries.flatMap { listOfNotNull(it.id,it.ledgerId,it.categoryId,it.parentExpenseId,it.deletionGroupId,it.creationOperationId) } +
        state.deletionGroups.flatMap { listOf(it.id,it.rootEntryId) } + state.budgets.map { it.ledgerId } +
        state.importBatches.flatMap { listOf(it.id,it.submissionId) } + state.importMembers.flatMap { listOf(it.batchId,it.entryId) }).forEach(::validateId)
    unique(state.receipts.map { it.operationId },"操作回执")
    unique(state.ledgers.map { it.id },"账本"); unique(state.categories.map { it.id },"分类");unique(state.entries.map { it.id },"账单")
    unique(state.deletionGroups.map { it.id },"删除组");unique(state.sourceIdentities.map { it.id },"来源")
    unique(state.importBatches.map { it.id },"导入批次");unique(state.importBatches.map { it.submissionId },"导入提交")
    unique(state.entries.map { it.creationOperationId },"记账操作");unique(state.preferences.map { it.key },"偏好")
    unique(state.ledgers.map { it.name },"账本名称")
    unique(state.categories.map { "${it.ledgerId}\u0000${it.direction}\u0000${it.name}" },"分类名称")
    unique(state.budgets.map { "${it.ledgerId}/${it.yearMonth}" },"预算")
    unique(state.sourceIdentities.map { "${it.provider}\u0000${it.scope}\u0000${it.eventKind}\u0000${it.externalId}" },"来源身份")
    unique(state.importMembers.map { "${it.batchId}/${it.entryId}" },"批次成员")
    ensure(state.contentVersion>0,"数据版本无效");ensure(state.ledgers.any { !it.archived },"至少保留一个可用账本")
    val ledgers=state.ledgers.associateBy { it.id };val categories=state.categories.associateBy { it.id };val entries=state.entries.associateBy { it.id }
    state.ledgers.forEach { validName(it.name,30);ensure(it.revision>0 && it.currency=="CNY","账本字段无效") }
    state.categories.forEach { validName(it.name,20);ensure(it.ledgerId in ledgers && it.revision>0,"分类引用无效") }
    val groups=state.deletionGroups.map { it.id }.toSet()
    state.deletionGroups.forEach { validateUtc(it.deletedAt); ensure(it.reason == "USER", "删除原因无效") }
    state.entries.forEach { e ->
        validateUtc(e.createdAt); validateUtc(e.updatedAt)
        e.captureFingerprint?.let(::validateDigest)
        ensure(e.captureEngineVersion == null || e.captureEngineVersion.isNotBlank(), "识别引擎版本无效")
        Money.requireValid(e.amountMinor);validateOccurrence(e.occurrence);ensure(e.revision>0 && codePointCount(e.note)<=500 && !e.note.contains('\u0000'),"账单字段无效")
        val c=categories[e.categoryId] ?: invalid("账单分类引用不存在")
        ensure(e.ledgerId in ledgers && c.ledgerId==e.ledgerId && c.direction==(if(e.kind==EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE),"账单分类归属无效")
        ensure(e.deletionGroupId==null || e.deletionGroupId in groups,"删除组引用不存在")
        if(e.kind==EntryKind.REFUND) {
            val p=entries[e.parentExpenseId] ?: invalid("退款原支出不存在")
            ensure(p.kind==EntryKind.EXPENSE,"退款不能关联非支出")
            if(e.active) ensure(p.active && e.ledgerId==p.ledgerId && e.categoryId==p.categoryId && e.occurrence>=p.occurrence,"退款归属或时间无效")
        } else ensure(e.parentExpenseId==null,"非退款记录含有父引用")
    }
    val deletedByGroup = state.entries.filter { !it.active }.groupBy { it.deletionGroupId }
    state.deletionGroups.forEach { group ->
        val members = deletedByGroup[group.id].orEmpty()
        if (members.isNotEmpty()) {
            val root = members.find { it.id == group.rootEntryId } ?: invalid("非空删除组缺少根成员")
            ensure(members.all { it.id == root.id || root.kind == EntryKind.EXPENSE && it.kind == EntryKind.REFUND && it.parentExpenseId == root.id }, "删除组含不属于根支出的成员")
        }
    }
    state.entries.filter { it.active && it.kind==EntryKind.REFUND }.groupBy { it.parentExpenseId }.forEach { (parent, refunds) ->
        ensure(refunds.fold(0L) { sum,e -> Money.add(sum,e.amountMinor) } <= entries.getValue(parent!!).amountMinor,"累计退款超过原支出")
    }
    state.budgets.forEach { b -> Money.requireValid(b.amountMinor);ensure(b.ledgerId in ledgers && b.revision>0,"预算引用无效"); validateOccurrence(b.yearMonth+"-01T00:00:00") }
    state.sourceIdentities.forEach { validateSource(it); ensure(it.entryId in entries,"来源引用无效") }
    val batches=state.importBatches.map { it.id }.toSet()
    state.importMembers.forEach { validateDigest(it.committedContentDigest); ensure(it.committedRevision > 0, "提交版本无效"); ensure(it.batchId in batches && it.state in setOf("PRESENT","REMOVED","UNDONE") && (it.state!="PRESENT" || it.entryId in entries),"导入成员引用无效") }
    val membersByBatch = state.importMembers.groupBy { it.batchId }
    state.importBatches.forEach { batch ->
        validateUtc(batch.createdAt); listOf(batch.fileDigest,batch.previewDigest,batch.mappingDigest).forEach(::validateDigest)
        ensure(batch.status in setOf("COMMITTED","UNDONE") && batch.count>=0 && batch.adapterId.isNotBlank() && batch.adapterVersion.isNotBlank(),"导入批次状态无效")
        val members = membersByBatch[batch.id].orEmpty()
        ensure(members.size == batch.count && members.all { if (batch.status == "UNDONE") it.state == "UNDONE" else it.state != "UNDONE" }, "导入批次成员状态不一致")
    }
    state.preferences.forEach { preference ->
        when (preference.key) {
            "theme" -> ensure(preference.value in setOf("system","light","dark"), "未知主题值")
            "lastLedgerId" -> validateId(preference.value)
            "lastPeriod" -> validateOccurrence(preference.value + "-01T00:00:00")
            "lastBackupAt" -> validateUtc(preference.value)
            else -> invalid("未知偏好字段")
        }
    }
    state.receipts.forEach { receipt ->
        validateId(receipt.operationId); receipt.resultIds.forEach(::validateId); validateDigest(receipt.payloadDigest); validateUtc(receipt.committedAt)
        ensure(receipt.commandType.isNotBlank(), "回执类型无效")
    }
}
