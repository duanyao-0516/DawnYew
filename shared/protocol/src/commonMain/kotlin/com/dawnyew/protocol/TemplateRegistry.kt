package com.dawnyew.protocol

import com.dawnyew.core.*

enum class TemplateAvailability { AWAITING_SPECIFICATION, AVAILABLE }
data class TemplateDescriptor(val id:String,val name:String,val availability:TemplateAvailability,val reason:String)
/** No vendor layout is inferred. Register a real adapter only after samples and mapping review. */
object TemplateRegistry {
    val templates=listOf(
        dawnYewCsvDescriptor,
        TemplateDescriptor("wechat","微信历史账单",TemplateAvailability.AWAITING_SPECIFICATION,"等待账单模板样本与字段规则确认"),
        TemplateDescriptor("alipay","支付宝历史账单",TemplateAvailability.AWAITING_SPECIFICATION,"等待账单模板样本与字段规则确认"),
        TemplateDescriptor("dawnyew","DawnYew Excel 模板",TemplateAvailability.AWAITING_SPECIFICATION,"等待模板布局确认；当前可使用 JSON 完整备份"),
    )
}
data class SourceKey(val provider:String,val scope:String,val eventKind:String,val externalId:String)
data class ImportCandidate(val row:Int,val entry:Entry,val source:SourceIdentity?,val warnings:List<String>)
enum class DuplicateDisposition { NEW, SAME_SOURCE, SAME_LOCAL_ID, SOURCE_CHANGED, IDENTITY_CONFLICT, SUSPECTED }
object ImportDuplicates {
    fun classify(candidate:ImportCandidate,state:LedgerState):DuplicateDisposition {
        val source=candidate.source
        val local=state.entries.find { it.id==candidate.entry.id }
        val identity=source?.let { incoming -> state.sourceIdentities.find { it.identityKey()==incoming.identityKey() } }
        if (local!=null) {
            if (identity!=null && identity.entryId!=local.id) return DuplicateDisposition.IDENTITY_CONFLICT
            val prior=state.sourceIdentities.filter { it.entryId==local.id }
            val sameSources=if(source==null) prior.isEmpty() else prior.size==1 && sameSource(prior.single(),source)
            return if(sameImportedBusiness(local,candidate.entry) && (sameSources || isSameDatasetCsvIdentity(source,candidate.entry,state.datasetId) && (identity==null || sameSource(identity,source)))) DuplicateDisposition.SAME_LOCAL_ID else DuplicateDisposition.IDENTITY_CONFLICT
        }
        if(source!=null) {
            val existing=state.sourceIdentities.find { it.provider==source.provider && it.scope==source.scope && it.eventKind==source.eventKind && it.externalId==source.externalId }
            if(existing!=null) return if(sameSource(existing,source)) DuplicateDisposition.SAME_SOURCE else DuplicateDisposition.SOURCE_CHANGED
        }
        return if(state.entries.any { it.localDate==candidate.entry.localDate && it.amountMinor==candidate.entry.amountMinor && it.kind==candidate.entry.kind }) DuplicateDisposition.SUSPECTED else DuplicateDisposition.NEW
    }
}
interface BillTemplateAdapter {
    val descriptor:TemplateDescriptor
    val version:String
    val allowedMimeTypes:Set<String>
    val allowedContainers:Set<DocumentContainer>
    val sourceScopeVersion:Int
    fun inspect(facts:DocumentFacts,header:List<String>):TemplateProbe
    fun parseRow(cells:List<String>,rowNumber:Int):ParsedImportRow
}
