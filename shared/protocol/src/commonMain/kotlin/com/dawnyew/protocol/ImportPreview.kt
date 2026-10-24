package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

enum class DocumentContainer { XLSX, CSV, TEXT }
data class DocumentFacts(val mimeType:String,val container:DocumentContainer,val physicalRowCount:Int,val fileDigest:String,val logicalRecordCount:Int?=null) { val previewRecordCount get()=logicalRecordCount ?: physicalRowCount }
sealed interface TemplateProbe {
    data class ExactMatch(val adapterId:String,val version:String):TemplateProbe
    data class Unsupported(val reason:String):TemplateProbe
    data class Ambiguous(val adapterIds:Set<String>,val reason:String):TemplateProbe
}
object TemplateDetection {
    fun inspect(adapters:List<BillTemplateAdapter>,facts:DocumentFacts,header:List<String>):TemplateProbe {
        validateDigest(facts.fileDigest)
        if(facts.previewRecordCount<1) return TemplateProbe.Unsupported("文件没有可识别的记录")
        val matches=mutableListOf<TemplateProbe.ExactMatch>();val ambiguous=mutableSetOf<String>()
        adapters.filter { it.descriptor.availability==TemplateAvailability.AVAILABLE && facts.container in it.allowedContainers && facts.mimeType in it.allowedMimeTypes }.forEach { adapter ->
            when(val probe=adapter.inspect(facts,header)) {
                is TemplateProbe.ExactMatch -> { if(probe.adapterId!=adapter.descriptor.id || probe.version!=adapter.version) invalid("适配器检测结果不一致");matches+=probe }
                is TemplateProbe.Ambiguous -> ambiguous+=probe.adapterIds
                is TemplateProbe.Unsupported -> Unit
            }
        }
        val ids=matches.map { it.adapterId }.toSet()+ambiguous
        return when { ambiguous.isNotEmpty() || matches.size>1 -> TemplateProbe.Ambiguous(ids,"无法唯一确定模板，请明确选择或使用支持的文件")
            matches.size==1 -> matches.single()
            else -> TemplateProbe.Unsupported("没有匹配的已确认模板") }
    }
}
sealed interface ParsedImportRow {
    val rowNumber:Int
    data class Valid(val candidate:ImportCandidate):ParsedImportRow { override val rowNumber get()=candidate.row }
    data class Excluded(override val rowNumber:Int,val reason:String):ParsedImportRow
    data class NeedsDecision(override val rowNumber:Int,val reason:String):ParsedImportRow
    data class Invalid(override val rowNumber:Int,val reason:String):ParsedImportRow
}
@Serializable data class RowMapping(val rowNumber:Int,val ledgerId:String,val categoryId:String,val parentExpenseId:String?=null)
@Serializable data class ImportMappingPlan(val rows:List<RowMapping> = emptyList(),val newLedgers:List<Ledger> = emptyList(),val newCategories:List<Category> = emptyList()) {
    fun digest():String=Sha256.hex(canonicalJson(Json.encodeToJsonElement(this)).encodeToByteArray())
}
data class ImportSelection(val selectedRows:Set<Int>,val keepSuspectedRows:Set<Int> = emptySet())
data class PreviewContext(val batchId:String,val submissionId:String,val databaseEpoch:Long,val adapterId:String,val adapterVersion:String)
enum class PreviewDisposition { NEW, DUPLICATE, DUPLICATE_IN_TRASH, CONFLICT, NEEDS_DECISION, EXCLUDED, INVALID, SKIPPED }
data class PreviewRow(val rowNumber:Int,val disposition:PreviewDisposition,val message:String?=null)
data class ImportDependency(val rowNumber:Int,val parentRowNumber:Int?,val existingParentId:String?)
data class ImportImpact(val addedCount:Long,val income:Long,val expense:Long,val refunds:Long) { val netExpense get()=Money.add(expense,-refunds) }
data class ImportPreview(val rows:List<PreviewRow>,val counts:Map<PreviewDisposition,Long>,val impact:ImportImpact,val dependencies:List<ImportDependency>,val problems:List<String>,val plan:ImportPlan?) {
    val canCommit get()=plan!=null
}

object ImportPreviewer {
    fun preview(facts:DocumentFacts,context:PreviewContext,parsed:List<ParsedImportRow>,mapping:ImportMappingPlan,selection:ImportSelection,state:LedgerState,environment:DomainEnvironment):ImportPreview {
        validateDigest(facts.fileDigest)
        val recordCount=facts.previewRecordCount
        if(recordCount !in 0..10_000) invalid("预览记录最多10000条")
        if(parsed.size!=recordCount || parsed.map { it.rowNumber }.toSet()!=(1..recordCount).toSet()) invalid("预览必须覆盖全部记录且逻辑编号唯一")
        if(mapping.rows.map { it.rowNumber }.distinct().size!=mapping.rows.size || mapping.rows.any { it.rowNumber !in 1..recordCount }) invalid("映射行号无效")
        if(selection.selectedRows.any { it !in 1..recordCount } || !selection.selectedRows.containsAll(selection.keepSuspectedRows)) invalid("选择行号无效")
        val map=mapping.rows.associateBy { it.rowNumber }
        val mapped=parsed.map { row -> if(row is ParsedImportRow.Valid) {
            val candidate=row.candidate;val target=map[row.rowNumber]
            ParsedImportRow.Valid(if(target==null) candidate else candidate.copy(entry=candidate.entry.copy(ledgerId=target.ledgerId,categoryId=target.categoryId,parentExpenseId=target.parentExpenseId ?: candidate.entry.parentExpenseId)))
        } else row }
        val allCandidates=mapped.filterIsInstance<ParsedImportRow.Valid>().map { it.candidate }
        var seen=state
        val rows=mutableListOf<PreviewRow>();val problems=mutableListOf<String>();val selected=mutableListOf<ImportRow>()
        for(row in mapped) {
            val chosen=row.rowNumber in selection.selectedRows
            when(row) {
                is ParsedImportRow.Excluded -> { rows+=PreviewRow(row.rowNumber,PreviewDisposition.EXCLUDED,row.reason);if(chosen) problems+="被排除行不能提交" }
                is ParsedImportRow.Invalid -> { rows+=PreviewRow(row.rowNumber,PreviewDisposition.INVALID,row.reason);if(chosen) problems+="选中行存在格式错误" }
                is ParsedImportRow.NeedsDecision -> { rows+=PreviewRow(row.rowNumber,PreviewDisposition.NEEDS_DECISION,row.reason);if(chosen) problems+="选中行仍有未确认语义" }
                is ParsedImportRow.Valid -> {
                    if(!chosen) { rows+=PreviewRow(row.rowNumber,PreviewDisposition.SKIPPED);continue }
                    val candidate=row.candidate;val keep=row.rowNumber in selection.keepSuspectedRows
                    selected+=ImportRow(row.rowNumber,candidate.entry,candidate.source,keep)
                    val disposition=ImportDuplicates.classify(candidate,seen)
                    val status=when(disposition) {
                        DuplicateDisposition.NEW -> PreviewDisposition.NEW
                        DuplicateDisposition.SUSPECTED -> if(keep) PreviewDisposition.NEW else PreviewDisposition.NEEDS_DECISION
                        DuplicateDisposition.SOURCE_CHANGED,DuplicateDisposition.IDENTITY_CONFLICT -> PreviewDisposition.CONFLICT
                        DuplicateDisposition.SAME_SOURCE,DuplicateDisposition.SAME_LOCAL_ID -> {
                            val existingId=if(disposition==DuplicateDisposition.SAME_LOCAL_ID) candidate.entry.id else seen.sourceIdentities.first { it.identityKey()==candidate.source!!.identityKey() }.entryId
                            if(seen.entries.first { it.id==existingId }.active) PreviewDisposition.DUPLICATE else PreviewDisposition.DUPLICATE_IN_TRASH
                        }
                    }
                    rows+=PreviewRow(row.rowNumber,status)
                    if(status==PreviewDisposition.CONFLICT || status==PreviewDisposition.NEEDS_DECISION) problems+="选中行存在冲突或疑似重复待确认"
                    if(status==PreviewDisposition.NEW) seen=seen.copy(entries=seen.entries+candidate.entry,sourceIdentities=seen.sourceIdentities+listOfNotNull(candidate.source))
                }
            }
        }
        val dependencies=selected.filter { it.entry.kind==EntryKind.REFUND }.map { row ->
            val parent=row.entry.parentExpenseId
            ImportDependency(row.rowNumber,allCandidates.firstOrNull { it.entry.id==parent }?.row,state.entries.firstOrNull { it.id==parent && it.active && it.kind==EntryKind.EXPENSE }?.id)
        }
        dependencies.forEach { if(it.existingParentId==null && (it.parentRowNumber==null || it.parentRowNumber !in selection.selectedRows)) problems+="选中退款缺少选中的父支出或现有有效支出" }
        var plan:ImportPlan?=null;var impact=ImportImpact(0,0,0,0)
        if(selected.isEmpty()) problems+="至少选择一条可提交候选"
        if(problems.isEmpty()) {
            // CSV discovers categories before the user selects rows. Only inserted rows may
            // create them; a skipped or already imported source has no category side effect.
            val newCategories=if(context.adapterId==DAWNYEW_CSV_ADAPTER_ID) {
                val insertedRows=rows.filter { it.disposition==PreviewDisposition.NEW }.map { it.rowNumber }.toSet()
                val usedCategories=selected.filter { it.rowNumber in insertedRows }.map { it.entry.categoryId }.toSet()
                mapping.newCategories.filter { it.id in usedCategories }
            } else mapping.newCategories
            val candidatePlan=ImportPlan(context.batchId,context.submissionId,state.datasetId,state.contentVersion,context.databaseEpoch,context.adapterId,context.adapterVersion,facts.fileDigest,mapping.digest(),mapping.newLedgers,newCategories,selected).sealed()
            try {
                val simulation=DomainEngine(environment).previewImport(state,candidatePlan)
                val existing=state.entries.map { it.id }.toSet();val added=simulation.state.entries.filter { it.id !in existing }
                fun sum(kind:EntryKind)=added.filter { it.kind==kind }.fold(0L) { total,e->Money.add(total,e.amountMinor) }
                impact=ImportImpact(added.size.toLong(),sum(EntryKind.INCOME),sum(EntryKind.EXPENSE),sum(EntryKind.REFUND));plan=candidatePlan
            } catch(failure:DomainException) { problems+=failure.message }
        }
        return ImportPreview(rows,PreviewDisposition.entries.associateWith { status->rows.count { it.disposition==status }.toLong() },impact,dependencies,problems.distinct(),plan)
    }
}
