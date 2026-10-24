package com.dawnyew.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class ImportRow(val rowNumber: Int, val entry: Entry, val source: SourceIdentity? = null, val keepSuspectedDuplicate: Boolean = false)

/** An immutable reviewed selection. The platform also compares databaseEpoch and its authentication lease. */
@Serializable data class ImportPlan(
    val batchId: String, val submissionId: String, val datasetId: String, val contentVersion: Long,
    val databaseEpoch: Long, val adapterId: String, val adapterVersion: String,
    val fileDigest: String, val mappingDigest: String,
    val newLedgers: List<Ledger> = emptyList(), val newCategories: List<Category> = emptyList(),
    val rows: List<ImportRow>, val selectedRowsDigest: String = "", val previewDigest: String = "",
) {
    fun sealed(): ImportPlan {
        val selected = copy(selectedRowsDigest = importHash(importJson.encodeToJsonElement(rows)), previewDigest = "")
        return selected.copy(previewDigest = importHash(importJson.encodeToJsonElement(selected)))
    }
}

private val importJson = Json { encodeDefaults = true; explicitNulls = true }
fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { key ->
        codePointCount(key)
        JsonPrimitive(key).toString() + ":" + canonicalJson(value.getValue(key))
    }
    is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonPrimitive -> {
        if (value.isString) codePointCount(value.content)
        else if (value != JsonNull && value.booleanOrNull == null && !Regex("-?(0|[1-9][0-9]*)").matches(value.content)) invalid("协议不接受浮点数")
        if (!value.isString && value.content == "-0") "0" else value.toString()
    }
}
private fun importHash(value: JsonElement) = Sha256.hex(canonicalJson(value).encodeToByteArray())
fun SourceIdentity.identityKey(): List<String> = listOf(provider, scope, eventKind, externalId)
fun sameSource(a: SourceIdentity, b: SourceIdentity): Boolean = a.identityKey() == b.identityKey() &&
    a.normalizationVersion == b.normalizationVersion && a.canonicalSource == b.canonicalSource && a.sourceDigest == b.sourceDigest

/** CSV exported by this dataset may map an unchanged local record without replacing its original sources. */
fun isSameDatasetCsvIdentity(source:SourceIdentity?,entry:Entry,datasetId:String):Boolean = source!=null && source.provider=="DAWNYEW_CSV" && source.scope==datasetId && source.externalId==entry.id && source.entryId==entry.id && source.eventKind==entry.kind.name

/** Local business content excludes internal audit clocks, revision, and the creation command ID. */
fun sameImportedBusiness(a: Entry, b: Entry): Boolean = a.ledgerId == b.ledgerId && a.kind == b.kind &&
    a.amountMinor == b.amountMinor && a.categoryId == b.categoryId && a.occurrence == b.occurrence &&
    a.note == b.note && a.parentExpenseId == b.parentExpenseId && a.deletionGroupId == b.deletionGroupId &&
    a.captureFingerprint == b.captureFingerprint && a.captureEngineVersion == b.captureEngineVersion

fun importContentDigest(entry: Entry, sources: List<SourceIdentity>): String = importHash(buildJsonObject {
    put("entry", importJson.encodeToJsonElement(entry))
    put("sources", importJson.encodeToJsonElement(sources.filter { it.entryId == entry.id }.sortedBy { it.id }))
})

internal fun commitImport(original: LedgerState, plan: ImportPlan, env: DomainEnvironment): Mutation {
    validateId(plan.batchId); validateId(plan.submissionId); validateId(plan.datasetId)
    validateDigest(plan.fileDigest); validateDigest(plan.mappingDigest)
    if (plan.adapterId.isBlank() || plan.adapterVersion.isBlank() || plan.contentVersion < 1 || plan.databaseEpoch < 0) invalid("导入元数据无效")
    if (plan.rows.isEmpty() || plan.rows.size > 10_000 || plan.rows.any { it.rowNumber < 1 } || plan.rows.map { it.rowNumber }.distinct().size != plan.rows.size) invalid("选中行数量或位置无效")
    val sealed = plan.sealed()
    if (plan.selectedRowsDigest != sealed.selectedRowsDigest || plan.previewDigest != sealed.previewDigest) conflict("选中内容或映射已变化，请重新预览")
    original.importBatches.firstOrNull { it.submissionId == plan.submissionId }?.let { batch ->
        if (batch.id != plan.batchId || batch.adapterId != plan.adapterId || batch.adapterVersion != plan.adapterVersion ||
            batch.fileDigest != plan.fileDigest || batch.mappingDigest != plan.mappingDigest || batch.previewDigest != plan.previewDigest) conflict("导入提交标识对应不同内容")
        return Mutation(original, listOf(batch.id))
    }
    if (plan.datasetId != original.datasetId || plan.contentVersion != original.contentVersion) conflict("数据已变化，请重新预览")
    if (original.importBatches.any { it.id == plan.batchId }) conflict("导入批次标识已存在")
    if (plan.newLedgers.any { l -> original.ledgers.any { it.id == l.id } || l.archived || l.revision != 1L } ||
        plan.newCategories.any { c -> original.categories.any { it.id == c.id } || !c.enabled || c.revision != 1L }) invalid("新建账本或分类计划无效")
    var state = original.copy(ledgers = original.ledgers + plan.newLedgers, categories = original.categories + plan.newCategories)
    validateState(state, env::normalize)
    val newEntries = mutableListOf<Entry>()
    val sources = state.sourceIdentities.toMutableList()
    val byId = state.entries.associateBy { it.id }.toMutableMap()
    val bySource = sources.associateBy { it.identityKey() }.toMutableMap()
    val aliases = mutableMapOf<String, String>()
    val seenCandidates = mutableMapOf<String, ImportRow>()
    val sourcesByEntry = sources.groupBy { it.entryId }.mapValues { it.value.toMutableList() }.toMutableMap()
    val sourceIds = sources.map { it.id }.toMutableSet()
    fun suspectedKey(e: Entry) = Triple(e.kind, e.localDate, e.amountMinor)
    val suspectedKeys = byId.values.map(::suspectedKey).toMutableSet()
    // Parents are resolved before refunds, including when a duplicated parent has a different candidate UUID.
    for (row in plan.rows.sortedBy { if (it.entry.kind == EntryKind.REFUND) 1 else 0 }) {
        val candidate = row.entry
        listOfNotNull(candidate.id,candidate.ledgerId,candidate.categoryId,candidate.parentExpenseId,candidate.deletionGroupId,candidate.creationOperationId).forEach(::validateId)
        Money.requireValid(candidate.amountMinor); validateOccurrence(candidate.occurrence); validateUtc(candidate.createdAt); validateUtc(candidate.updatedAt)
        if (candidate.revision < 1 || codePointCount(candidate.note) > 500 || candidate.note.contains('\u0000')) invalid("候选账单字段无效")
        candidate.captureFingerprint?.let(::validateDigest)
        row.source?.let { validateSource(it); if (it.entryId != candidate.id) invalid("来源与候选账单不一致") }
        val e = candidate.copy(parentExpenseId = candidate.parentExpenseId?.let { aliases[it] ?: it })
        seenCandidates[e.id]?.let { previous ->
            val sameSources = if (row.source == null) previous.source == null else previous.source?.let { sameSource(it,row.source) } == true
            if (!sameImportedBusiness(previous.entry,e) || !sameSources) conflict("同批相同账单标识含不同内容")
        }
        seenCandidates[e.id] = row.copy(entry = e)
        val local = byId[e.id]
        val sourceMatch = row.source?.let { bySource[it.identityKey()] }
        if (local != null && sourceMatch != null && sourceMatch.entryId != local.id) conflict("本地标识与来源身份指向不同账单")
        if (local != null) {
            val localSources = sourcesByEntry[local.id].orEmpty()
            val sameSources = if (row.source == null) localSources.isEmpty() else localSources.size == 1 && sameSource(localSources.single(), row.source)
            val csvLocalMatch=isSameDatasetCsvIdentity(row.source,e,original.datasetId) && (sourceMatch==null || sameSource(sourceMatch,row.source))
            if (!sameImportedBusiness(local, e) || !(sameSources || csvLocalMatch)) conflict("相同账单标识含不同业务内容")
            aliases[e.id] = local.id
            continue
        }
        if (sourceMatch != null) {
            if (!sameSource(sourceMatch, row.source)) conflict("来源内容或规范化版本已变化")
            aliases[e.id] = sourceMatch.entryId
            continue
        }
        if (aliases.containsKey(e.id)) conflict("同批账单标识发生身份冲突")
        if (!e.active) invalid("表格追加不能导入回收站记录")
        Money.requireValid(e.amountMinor); validateOccurrence(e.occurrence)
        if (e.occurrence > env.localNow()) invalid("不能导入未来发生的账单")
        val ledger = state.ledgers.find { it.id == e.ledgerId } ?: invalid("导入目标账本不存在")
        val category = state.categories.find { it.id == e.categoryId } ?: invalid("导入目标分类不存在")
        if (ledger.archived || !category.enabled) invalid("导入目标账本已归档或分类已停用")
        val suspected = suspectedKey(e) in suspectedKeys
        if (suspected && !row.keepSuspectedDuplicate) conflict("疑似重复记录需要明确选择保留或跳过")
        val inserted = e.copy(revision = 1, createdAt = env.utcNow(), updatedAt = env.utcNow(), originKind = OriginKind.IMPORT)
        newEntries += inserted; byId[inserted.id] = inserted; aliases[e.id] = inserted.id; suspectedKeys += suspectedKey(inserted)
        row.source?.let { source ->
            if (!sourceIds.add(source.id)) conflict("来源标识已存在")
            sources += source; bySource[source.identityKey()] = source; sourcesByEntry.getOrPut(source.entryId) { mutableListOf() } += source
        }
    }
    state = state.copy(entries = state.entries + newEntries, sourceIdentities = sources)
    validateState(state, env::normalize)
    val batch = ImportBatch(plan.batchId, plan.submissionId, plan.adapterId, plan.adapterVersion, plan.fileDigest, plan.previewDigest, plan.mappingDigest, env.utcNow(), count = newEntries.size)
    val members = newEntries.map { ImportMember(batch.id, it.id, it.revision, importContentDigest(it, sourcesByEntry[it.id].orEmpty())) }
    return Mutation(state.copy(importBatches = state.importBatches + batch, importMembers = state.importMembers + members), listOf(batch.id))
}

internal fun undoImport(original: LedgerState, batchId: String): Mutation {
    val batch = original.importBatches.find { it.id == batchId } ?: invalid("导入批次不存在")
    if (batch.status == "UNDONE") return Mutation(original, listOf(batch.id))
    val members = original.importMembers.filter { it.batchId == batch.id }
    val ids = members.map { it.entryId }.toSet()
    val entriesById = original.entries.associateBy { it.id }
    val sourcesByEntry = original.sourceIdentities.groupBy { it.entryId }
    if (members.size != batch.count || members.any { member ->
            val e = entriesById[member.entryId]
            member.state != "PRESENT" || e == null || !e.active || e.revision != member.committedRevision || importContentDigest(e, sourcesByEntry[e.id].orEmpty()) != member.committedContentDigest
        }) conflict("批次成员已修改或移除，无法整批撤销")
    if (original.entries.any { it.id !in ids && it.parentExpenseId in ids } ||
        original.importMembers.any { it.batchId != batch.id && it.state == "PRESENT" && it.entryId in ids }) conflict("批次已被其他记录引用，无法撤销")
    return Mutation(original.copy(entryDetails=original.entryDetails.filterNot { it.entryId in ids },entryTags=original.entryTags.filterNot { it.entryId in ids },recurringOccurrences=original.recurringOccurrences.map { if(it.entryId in ids) it.copy(entryId=null) else it },entries = original.entries.filterNot { it.id in ids }, sourceIdentities = original.sourceIdentities.filterNot { it.entryId in ids },
        importBatches = original.importBatches.map { if (it.id == batch.id) it.copy(status = "UNDONE") else it },
        importMembers = original.importMembers.map { if (it.batchId == batch.id) it.copy(state = "UNDONE") else it }), listOf(batch.id))
}
