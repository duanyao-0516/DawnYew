package com.dawnyew.storage

import androidx.room.*
import com.dawnyew.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No records or plaintext backup are retained in this summary. */
data class CandidateSummary(val datasetId:String,val counts:Map<String,Long>,val activeEntryCount:Long,val deletedEntryCount:Long,val contentDigest:String="")
interface CandidateSink { suspend fun stage(batch:LedgerEntityBatch) }
interface SnapshotReader {
    val datasetId:String
    val contentVersion:Long
    val counts:Map<String,Long>
    suspend fun visit(type:String,onBatch:suspend (LedgerEntityBatch)->Unit)
}
internal val entityTables=linkedMapOf("ledgers" to "ledgers","categories" to "categories","entries" to "entries","deletionGroups" to "deletion_groups","budgets" to "budgets","sourceIdentities" to "source_identities","importBatches" to "import_batches","importMembers" to "import_members","preferences" to "preferences")
internal val streamJson=Json { encodeDefaults=true;explicitNulls=true }

/** One isolated candidate transaction. FK deferral allows arbitrary input entity and row ordering. */
suspend fun writeCandidate(database:DawnYewDatabase,datasetId:String,block:suspend CandidateSink.()->Unit):CandidateSummary {
    validateId(datasetId)
    return database.useWriterConnection { connection -> connection.immediateTransaction {
        val dao=database.ledgerDao()
        if(dao.metadata()!=null) invalid("候选数据库必须为新空库")
        entityTables.values.forEach { table -> connection.usePrepared("SELECT 1 FROM $table LIMIT 1") { if(it.step()) invalid("候选数据库不是空库") } }
        val counts=entityTables.keys.associateWith { 0L }.toMutableMap()
        fun position(type:String):Long { val next=counts.getValue(type);counts[type]=Money.add(next,1);return next }
        val sink=object:CandidateSink {
            override suspend fun stage(batch:LedgerEntityBatch) {
                validateEntityBatch(batch)
                batch.ledgers.chunked(128).forEach { dao.insertLedgerRows(it.map { v->LedgerRow(v.id,normalizeTextNfc(v.name.trim()),v.currency,v.archived,position("ledgers"),streamJson.encodeToString(v)) }) }
                batch.categories.chunked(128).forEach { dao.insertCategoryRows(it.map { v->CategoryRow(v.id,v.ledgerId,v.direction.name,normalizeTextNfc(v.name.trim()),position("categories"),streamJson.encodeToString(v)) }) }
                batch.entries.chunked(128).forEach { dao.insertEntryRows(it.map { v->EntryRow(v.id,v.ledgerId,v.kind.name,if(v.kind==EntryKind.INCOME) "INCOME" else "EXPENSE",v.amountMinor,v.categoryId,v.parentExpenseId,v.deletionGroupId,v.creationOperationId,v.occurrence,literalSearchKey(v.note),position("entries"),streamJson.encodeToString(v)) }) }
                batch.deletionGroups.chunked(128).forEach { dao.insertDeletionGroupRows(it.map { v->DeletionGroupRow(v.id,position("deletionGroups"),streamJson.encodeToString(v)) }) }
                batch.budgets.chunked(128).forEach { dao.insertBudgetRows(it.map { v->BudgetRow(v.ledgerId,v.yearMonth,v.amountMinor,position("budgets"),streamJson.encodeToString(v)) }) }
                batch.sourceIdentities.chunked(128).forEach { dao.insertSourceIdentityRows(it.map { v->SourceIdentityRow(v.id,v.entryId,v.provider,v.scope,v.eventKind,v.externalId,position("sourceIdentities"),streamJson.encodeToString(v)) }) }
                batch.importBatches.chunked(128).forEach { dao.insertImportBatchRows(it.map { v->ImportBatchRow(v.id,v.submissionId,position("importBatches"),streamJson.encodeToString(v)) }) }
                batch.importMembers.chunked(128).forEach { dao.insertImportMemberRows(it.map { v->ImportMemberRow(v.batchId,v.entryId,v.entryId.takeIf { v.state=="PRESENT" },v.state,position("importMembers"),streamJson.encodeToString(v)) }) }
                batch.preferences.chunked(128).forEach { dao.insertPreferenceRows(it.map { v->PreferenceRow(v.key,position("preferences"),streamJson.encodeToString(v)) }) }
            }
        }
        block(sink)
        dao.upsertMetadata(MetadataRow(datasetId=datasetId,contentVersion=1))
        inspectCandidate(connection).also { if(it.counts!=counts) invalid("候选数据库计数不一致") }
    } }
}

internal suspend fun validateCandidateRelations(connection:Transactor) {
    suspend fun rejectRows(sql:String,message:String) { connection.usePrepared(sql) { if(it.step()) invalid(message) } }
    rejectRows("PRAGMA foreign_key_check","候选数据存在悬空引用")
    rejectRows("SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM ledgers WHERE archived=0)","至少保留一个可用账本")
    rejectRows("SELECT 1 FROM entries r JOIN entries p ON p.id=r.parentExpenseId WHERE r.kind='REFUND' AND (p.kind!='EXPENSE' OR (r.deletionGroupId IS NULL AND (p.deletionGroupId IS NOT NULL OR r.ledgerId!=p.ledgerId OR r.categoryId!=p.categoryId OR r.occurrence<p.occurrence))) LIMIT 1","退款归属或时间无效")
    rejectRows("SELECT r.parentExpenseId FROM entries r JOIN entries p ON p.id=r.parentExpenseId WHERE r.kind='REFUND' AND r.deletionGroupId IS NULL GROUP BY r.parentExpenseId HAVING SUM(r.amountMinor)>MAX(p.amountMinor) LIMIT 1","累计退款超过原支出")
    rejectRows("SELECT 1 FROM deletion_groups g JOIN entries e ON e.deletionGroupId=g.id LEFT JOIN entries r ON r.id=json_extract(g.payload,'$.rootEntryId') AND r.deletionGroupId=g.id WHERE r.id IS NULL OR (e.id!=r.id AND (r.kind!='EXPENSE' OR e.kind!='REFUND' OR e.parentExpenseId!=r.id)) LIMIT 1","删除组根成员或成员关系无效")
    rejectRows("SELECT 1 FROM import_batches b WHERE json_extract(b.payload,'$.count')!=(SELECT COUNT(*) FROM import_members m WHERE m.batchId=b.id) LIMIT 1","导入批次计数不一致")
    rejectRows("SELECT 1 FROM import_members m JOIN import_batches b ON b.id=m.batchId WHERE (json_extract(b.payload,'$.status')='UNDONE' AND m.memberState!='UNDONE') OR (json_extract(b.payload,'$.status')!='UNDONE' AND m.memberState='UNDONE') LIMIT 1","导入批次成员状态不一致")
}

/** Metadata, counts, and every sorted chunk share this same SQLite read snapshot. */
suspend fun <T> DawnYewDatabase.exportSnapshot(block:suspend SnapshotReader.()->T):T = useReaderConnection { connection -> connection.deferredTransaction {
    val meta=ledgerDao().metadata() ?: throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
    val counts=entityTables.mapValues { (_,table) -> connection.usePrepared("SELECT COUNT(*) FROM $table") { check(it.step());it.getLong(0) } }
    val reader=object:SnapshotReader {
        override val datasetId=meta.datasetId
        override val contentVersion=meta.contentVersion
        override val counts=counts
        override suspend fun visit(type:String,onBatch:suspend (LedgerEntityBatch)->Unit) {
            val table=entityTables[type] ?: invalid("未知备份实体")
            val keys=when(type) { "budgets"->listOf("ledgerId","yearMonth");"importMembers"->listOf("batchId","entryId");"preferences"->listOf("key");else->listOf("id") }
            var cursor:List<String>?=null
            while(true) {
                val where=if(cursor==null) "" else if(keys.size==1) " WHERE ${keys[0]}>?" else " WHERE ${keys[0]}>? OR (${keys[0]}=? AND ${keys[1]}>?)"
                val page=connection.usePrepared("SELECT ${keys.joinToString(",")},payload FROM $table$where ORDER BY ${keys.joinToString(",")} LIMIT 128") { statement ->
                    cursor?.let { c-> statement.bindText(1,c[0]);if(keys.size==2) { statement.bindText(2,c[0]);statement.bindText(3,c[1]) } }
                    buildList { while(statement.step()) add(keys.indices.map { statement.getText(it) } to statement.getText(keys.size)) }
                }
                if(page.isEmpty()) break
                val payloads=page.map { it.second }
                val batch=when(type) {
                    "ledgers"->LedgerEntityBatch(ledgers=payloads.map { streamJson.decodeFromString<Ledger>(it) })
                    "categories"->LedgerEntityBatch(categories=payloads.map { streamJson.decodeFromString<Category>(it) })
                    "entries"->LedgerEntityBatch(entries=payloads.map { streamJson.decodeFromString<Entry>(it) })
                    "deletionGroups"->LedgerEntityBatch(deletionGroups=payloads.map { streamJson.decodeFromString<DeletionGroup>(it) })
                    "budgets"->LedgerEntityBatch(budgets=payloads.map { streamJson.decodeFromString<MonthlyBudget>(it) })
                    "sourceIdentities"->LedgerEntityBatch(sourceIdentities=payloads.map { streamJson.decodeFromString<SourceIdentity>(it) })
                    "importBatches"->LedgerEntityBatch(importBatches=payloads.map { streamJson.decodeFromString<ImportBatch>(it) })
                    "importMembers"->LedgerEntityBatch(importMembers=payloads.map { streamJson.decodeFromString<ImportMember>(it) })
                    "preferences"->LedgerEntityBatch(preferences=payloads.map { streamJson.decodeFromString<UserPreference>(it) })
                    else->invalid("未知备份实体")
                }
                validateEntityBatch(batch);onBatch(batch);cursor=page.last().first
            }
        }
    }
    block(reader)
} }
