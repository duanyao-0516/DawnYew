package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val DAWNYEW_CSV_ADAPTER_ID="dawnyew_csv"
const val DAWNYEW_CSV_MAX_RECORDS=10_000
const val DAWNYEW_CSV_MAX_BYTES=32*1024*1024
const val DAWNYEW_CSV_MAX_FIELD_BYTES=8*1024
val dawnYewCsvHeader=listOf("source_dataset","source_entry","type","amount","occurred_at","ledger","category","note")
val dawnYewCsvDescriptor=TemplateDescriptor(DAWNYEW_CSV_ADAPTER_ID,"DawnYew CSV v1",TemplateAvailability.AVAILABLE,"仅普通收支；完整财务数据使用JSON备份")
data class CsvPhysicalRecord(val cells:List<String>,val logicalRecordNumber:Int,val startLine:Int,val endLine:Int)
data class DawnYewCsvDocument(val facts:DocumentFacts,val records:List<CsvPhysicalRecord>)
data class CsvCategoryKey(val direction:Direction,val name:String)
data class PreparedCsvImport(val facts:DocumentFacts,val parsed:List<ParsedImportRow>,val mapping:ImportMappingPlan) {
    val defaultSelectedRows get()=parsed.filterIsInstance<ParsedImportRow.Valid>().map { it.rowNumber }.toSet()
}
data class CsvExportSummary(val includedCount:Int,val excludedRefundCount:Int,val excludedTransferCount:Int,val excludedDeletedCount:Int,val omittedDetailsCount:Int) {
    val excludedCount get()=excludedRefundCount+excludedTransferCount+excludedDeletedCount
}
data class PreparedCsvExport(val records:List<List<String>>,val summary:CsvExportSummary)
object DawnYewCsv {
    fun prepareImport(document:DawnYewCsvDocument,ledgerId:String,state:LedgerState,environment:DomainEnvironment,categoryMapping:Map<CsvCategoryKey,String> = emptyMap()):PreparedCsvImport {
        validateDigest(document.facts.fileDigest)
        if(document.facts.container!=DocumentContainer.CSV || document.facts.logicalRecordCount!=document.records.size || document.records.size>DAWNYEW_CSV_MAX_RECORDS || document.records.map { it.logicalRecordNumber }!=(1..document.records.size).toList()) invalid("CSV逻辑记录清单无效")
        val ledger=state.ledgers.firstOrNull { it.id==ledgerId } ?: invalid("导入目标账本不存在")
        if(ledger.archived) invalid("导入目标账本已归档")
        val adapter=CsvAdapter(ledgerId,state,environment,categoryMapping)
        val parsed=document.records.map { record ->
            val result=adapter.parseRow(record.cells,record.logicalRecordNumber)
            if(result is ParsedImportRow.Invalid) result.copy(reason="物理行${record.startLine}–${record.endLine}：${result.reason}") else result
        }
        val mapping=ImportMappingPlan(parsed.filterIsInstance<ParsedImportRow.Valid>().map { RowMapping(it.rowNumber,it.candidate.entry.ledgerId,it.candidate.entry.categoryId) },newCategories=adapter.newCategories.toList())
        return PreparedCsvImport(document.facts,parsed,mapping)
    }
    fun prepareExport(state:LedgerState,scope:QueryScope):PreparedCsvExport {
        val query=scope.toActivityScope()
        val selected=Statistics.calculate(state,scope).entries
        val normal=selected.filter { it.kind==EntryKind.EXPENSE || it.kind==EntryKind.INCOME }
        if(normal.size>DAWNYEW_CSV_MAX_RECORDS) invalid("CSV最多导出10000条，请缩小日期范围或使用完整备份")
        val ledgers=state.ledgers.associateBy { it.id };val categories=state.categories.associateBy { it.id }
        val details=state.entryDetails.associateBy { it.entryId };val tagged=state.entryTags.map { it.entryId }.toSet()
        val records=normal.map { e ->
            listOf(state.datasetId,e.id,e.kind.name,Money.format(e.amountMinor),e.occurrence,"'"+(ledgers[e.ledgerId]?.name ?: invalid("导出账本不存在")),"'"+(categories[e.categoryId]?.name ?: invalid("导出分类不存在")),"'"+e.note)
        }
        val deleted=activitySummarySnapshot(state,query.copy(deleted=true)).activityCount
        if(deleted>Int.MAX_VALUE) invalid("导出排除计数超出范围")
        return PreparedCsvExport(records,CsvExportSummary(records.size,selected.count { it.kind==EntryKind.REFUND },if(query.kinds.isEmpty()) activitySummarySnapshot(state,query.copy(kinds=setOf(ActivityKind.TRANSFER))).activityCount.toInt() else 0,deleted.toInt(),normal.count { it.id in tagged || details[it.id]?.let { d -> d!=EntryDetails(it.id) }==true }))
    }
}

private class CsvAdapter(val ledgerId:String,val state:LedgerState,val env:DomainEnvironment,val mapping:Map<CsvCategoryKey,String>):BillTemplateAdapter {
    override val descriptor=dawnYewCsvDescriptor
    override val version="1"
    override val allowedMimeTypes=setOf("text/csv","application/csv","text/comma-separated-values","application/vnd.ms-excel","text/plain","application/octet-stream")
    override val allowedContainers=setOf(DocumentContainer.CSV)
    override val sourceScopeVersion=1
    val newCategories=mutableListOf<Category>()
    override fun inspect(facts:DocumentFacts,header:List<String>):TemplateProbe = if(facts.container==DocumentContainer.CSV && header==dawnYewCsvHeader) TemplateProbe.ExactMatch(descriptor.id,version) else TemplateProbe.Unsupported("不是DawnYew CSV v1表头")
    override fun parseRow(cells:List<String>,rowNumber:Int):ParsedImportRow = try {
        if(cells.size!=8) invalid("CSV每条交易必须恰有8列")
        if(cells.any { it.encodeToByteArray().size>DAWNYEW_CSV_MAX_FIELD_BYTES }) invalid("CSV字段超过8KiB")
        val sourceDataset=cells[0];val sourceEntry=cells[1];validateId(sourceDataset);validateId(sourceEntry)
        val kind=when(cells[2]) { "EXPENSE" -> EntryKind.EXPENSE;"INCOME" -> EntryKind.INCOME;else -> invalid("CSV只支持EXPENSE和INCOME") }
        if(!Regex("[0-9]{1,9}\\.[0-9]{2}").matches(cells[3])) invalid("CSV金额必须为正数且固定两位小数")
        val amount=Money.parse(cells[3]);val date=cells[4];validateOccurrence(date)
        fun text(index:Int):String { if(!cells[index].startsWith("'")) invalid("CSV文本字段缺少单引号标记");return cells[index].substring(1) }
        fun label(raw:String,max:Int):String { val value=env.normalize(raw.trim());if(codePointCount(value) !in 1..max || value.any { it.code<32 }) invalid("CSV名称格式无效");return value }
        val ledgerName=label(text(5),30);val categoryName=label(text(6),20);val note=text(7)
        if(codePointCount(note)>500 || note.contains('\u0000')) invalid("CSV备注最多500个字符")
        val direction=if(kind==EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
        val key=CsvCategoryKey(direction,categoryName)
        val category=if(key in mapping) {
            state.categories.firstOrNull { it.id==mapping[key] }?.also { if(it.ledgerId!=ledgerId || it.direction!=direction || !it.enabled) invalid("所选分类归属或状态无效") } ?: invalid("映射分类不存在")
        } else (state.categories+newCategories).firstOrNull { it.ledgerId==ledgerId && it.direction==direction && it.name==categoryName }?.also { if(!it.enabled) invalid("同名分类已停用，请明确映射其他分类") }
            ?: Category(env.id(),ledgerId,direction,categoryName,sortOrder=(state.categories+newCategories).filter { it.ledgerId==ledgerId && it.direction==direction }.maxOfOrNull { it.sortOrder }?.let { if(it==Int.MAX_VALUE) invalid("分类排序超出范围");it+1 } ?: 0).also { newCategories+=it }
        val id=if(sourceDataset==state.datasetId) sourceEntry else env.id()
        val old=if(sourceDataset==state.datasetId) state.entries.firstOrNull { it.id==id } else null
        val e=(old ?: Entry(id,ledgerId,kind,amount,category.id,date,createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id(),originKind=OriginKind.IMPORT)).copy(ledgerId=ledgerId,kind=kind,amountMinor=amount,categoryId=category.id,occurrence=date,note=note)
        val canonical=canonicalJson(buildJsonObject { put("source_dataset",sourceDataset);put("source_entry",sourceEntry);put("type",kind.name);put("amount",Money.format(amount));put("occurred_at",date);put("ledger",ledgerName);put("category",categoryName);put("note",note) })
        val source=SourceIdentity(env.id(),e.id,"DAWNYEW_CSV",sourceDataset,kind.name,sourceEntry,canonicalSource=canonical,sourceDigest=Sha256.hex(canonical.encodeToByteArray()))
        ParsedImportRow.Valid(ImportCandidate(rowNumber,e,source,emptyList()))
    } catch(error:DomainException) { ParsedImportRow.Invalid(rowNumber,error.message) }
}
