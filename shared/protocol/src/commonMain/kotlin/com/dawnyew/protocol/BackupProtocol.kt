package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/** Protocol integers wider than 32 bits are decimal strings. No floating point enters canonical records. */
object CanonicalJson {
    fun write(value: JsonElement): String = canonicalJson(value)
}

data class BackupDocument(val datasetId:String,val createdAt:String,val records:Map<String,List<JsonObject>>,val counts:Map<String,Long>,val sha256:String,val formatVersion:Int=2,val canonicalizationVersion:Int=1)

object BackupProtocol {
    const val FORMAT="dawnyew-backup"
    private val legacyEntityTypes=listOf("ledgers","categories","entries","deletionGroups","budgets","sourceIdentities","importBatches","importMembers","preferences")
    val entityTypes:List<String> get()=ledgerEntityTypes
    fun entityTypes(formatVersion:Int):List<String> = when(formatVersion) { 1->legacyEntityTypes;2->ledgerEntityTypes;else->invalid("不支持的备份版本，请使用兼容版本") }
    fun expectedCandidateCounts(formatVersion:Int,sourceCounts:Map<String,Long>):Map<String,Long> {
        if(sourceCounts.keys!=entityTypes(formatVersion).toSet() || sourceCounts.values.any { it<0 }) invalid("备份计数集合无效")
        return entityTypes.associateWith { sourceCounts[it] ?: 0L }
    }
    val json=Json { encodeDefaults=true; explicitNulls=true; ignoreUnknownKeys=false; isLenient=false; coerceInputValues=false }
    private val stringIntegers=setOf("amountMinor","revision","committedRevision","count","openingBalanceMinor","targetMinor")
    private fun wire(record:JsonObject)=JsonObject(record.mapValues { (k,v) -> if(k in stringIntegers && v!=JsonNull) JsonPrimitive(v.jsonPrimitive.content) else v })
    private fun internal(record:JsonObject):JsonObject = JsonObject(record.mapValues { (k,v) ->
        if(k in stringIntegers && v!=JsonNull) {
            val p=v as? JsonPrimitive ?: invalid("整数字段格式错误")
            if(!p.isString || !Regex(if(k=="openingBalanceMinor") "0|-[1-9][0-9]*|[1-9][0-9]*" else "0|[1-9][0-9]*").matches(p.content)) invalid("大整数字段必须为规范十进制字符串")
            JsonPrimitive(p.content.toLongOrNull() ?: invalid("整数超出范围"))
        } else v
    })
    private inline fun <reified T> encode(items:List<T>)=items.map { wire(json.encodeToJsonElement(it).jsonObject) }
    private inline fun <reified T> decode(records:List<JsonObject>)=records.map { json.decodeFromJsonElement<T>(internal(it)) }
    fun recordFields(type:String,formatVersion:Int=2):Set<String> = RecordSchema.fields(type,formatVersion)
    fun validateRecord(type:String,record:JsonObject,formatVersion:Int=2) = RecordSchema.validate(type,record,formatVersion)
    fun decodeRecords(type:String,records:List<JsonObject>,formatVersion:Int=2):LedgerEntityBatch {
        if(type !in entityTypes(formatVersion)) invalid("未知备份实体")
        records.forEach { validateRecord(type,it,formatVersion) }
        val batch=when(type) {
            "ledgers" -> LedgerEntityBatch(ledgers=decode(records))
            "categories" -> LedgerEntityBatch(categories=decode(records))
            "entries" -> LedgerEntityBatch(entries=decode(records))
            "deletionGroups" -> LedgerEntityBatch(deletionGroups=decode(records))
            "budgets" -> LedgerEntityBatch(budgets=decode(records))
            "sourceIdentities" -> LedgerEntityBatch(sourceIdentities=decode(records))
            "importBatches" -> LedgerEntityBatch(importBatches=decode(records))
            "importMembers" -> LedgerEntityBatch(importMembers=decode(records))
            "preferences" -> LedgerEntityBatch(preferences=decode(records))
            "accounts" -> LedgerEntityBatch(accounts=decode(records))
            "entryDetails" -> LedgerEntityBatch(entryDetails=decode(records))
            "entryTags" -> LedgerEntityBatch(entryTags=decode(records))
            "transfers" -> LedgerEntityBatch(transfers=decode(records))
            "dimensions" -> LedgerEntityBatch(dimensions=decode(records))
            "contacts" -> LedgerEntityBatch(contacts=decode(records))
            "obligations" -> LedgerEntityBatch(obligations=decode(records))
            "recurringPlans" -> LedgerEntityBatch(recurringPlans=decode(records))
            "recurringOccurrences" -> LedgerEntityBatch(recurringOccurrences=decode(records))
            "savingsPlans" -> LedgerEntityBatch(savingsPlans=decode(records))
            "savingsMovements" -> LedgerEntityBatch(savingsMovements=decode(records))
            "savingsWishes" -> LedgerEntityBatch(savingsWishes=decode(records))
            "budgetAllocations" -> LedgerEntityBatch(budgetAllocations=decode(records))
            "budgetReallocations" -> LedgerEntityBatch(budgetReallocations=decode(records))
            else -> invalid("未知备份实体")
        }
        validateEntityBatch(batch)
        return batch
    }
    fun encodeRecords(type:String,batch:LedgerEntityBatch,formatVersion:Int=2):List<JsonObject> {
        if(type !in entityTypes(formatVersion)) invalid("未知备份实体")
        return when(type) {
        "ledgers" -> encode(batch.ledgers)
        "categories" -> encode(batch.categories)
        "entries" -> encode(batch.entries)
        "deletionGroups" -> encode(batch.deletionGroups)
        "budgets" -> encode(batch.budgets)
        "sourceIdentities" -> encode(batch.sourceIdentities)
        "importBatches" -> encode(batch.importBatches)
        "importMembers" -> encode(batch.importMembers)
        "preferences" -> encode(batch.preferences)
        "accounts" -> encode(batch.accounts)
        "entryDetails" -> encode(batch.entryDetails)
        "entryTags" -> encode(batch.entryTags)
        "transfers" -> encode(batch.transfers)
        "dimensions" -> encode(batch.dimensions)
        "contacts" -> encode(batch.contacts)
        "obligations" -> encode(batch.obligations)
        "recurringPlans" -> encode(batch.recurringPlans)
        "recurringOccurrences" -> encode(batch.recurringOccurrences)
        "savingsPlans" -> encode(batch.savingsPlans)
        "savingsMovements" -> encode(batch.savingsMovements)
        "savingsWishes" -> encode(batch.savingsWishes)
        "budgetAllocations" -> encode(batch.budgetAllocations)
        "budgetReallocations" -> encode(batch.budgetReallocations)
        else -> invalid("未知备份实体")
        }
    }
    fun recordKey(type:String,r:JsonObject):String = when(type) {
        "budgets" -> r.getValue("ledgerId").jsonPrimitive.content+"/"+r.getValue("yearMonth").jsonPrimitive.content
        "importMembers" -> r.getValue("batchId").jsonPrimitive.content+"/"+r.getValue("entryId").jsonPrimitive.content
        "entryDetails" -> r.getValue("entryId").jsonPrimitive.content
        "entryTags" -> r.getValue("entryId").jsonPrimitive.content+"/"+r.getValue("tagId").jsonPrimitive.content
        "preferences" -> r.getValue("key").jsonPrimitive.content
        else -> r.getValue("id").jsonPrimitive.content
    }
    fun metadata(document:BackupDocument)=buildJsonObject {
        put("format",FORMAT);put("formatVersion",document.formatVersion);put("datasetId",document.datasetId);put("createdAt",document.createdAt);put("canonicalizationVersion",document.canonicalizationVersion)
        put("counts",buildJsonObject { entityTypes(document.formatVersion).forEach { put(it,document.counts.getValue(it).toString()) } })
    }
    fun digest(document:BackupDocument):String {
        val hash=Sha256().update((CanonicalJson.write(metadata(document))+"\n").encodeToByteArray())
        entityTypes(document.formatVersion).forEach { type -> document.records.getValue(type).sortedBy { recordKey(type,it) }.forEach { record ->
            hash.update((CanonicalJson.write(buildJsonObject { put("entityType",type);put("record",record) })+"\n").encodeToByteArray())
        } }
        return hash.finishHex()
    }
    fun create(state:LedgerState,createdAt:String):BackupDocument {
        validateState(state); validateUtc(createdAt)
        val unsorted=linkedMapOf("ledgers" to encode(state.ledgers),"categories" to encode(state.categories),"entries" to encode(state.entries),"deletionGroups" to encode(state.deletionGroups),"budgets" to encode(state.budgets),"sourceIdentities" to encode(state.sourceIdentities),"importBatches" to encode(state.importBatches),"importMembers" to encode(state.importMembers),"preferences" to encode(state.preferences),"accounts" to encode(state.accounts),"entryDetails" to encode(state.entryDetails),"entryTags" to encode(state.entryTags),"transfers" to encode(state.transfers),"dimensions" to encode(state.dimensions),"contacts" to encode(state.contacts),"obligations" to encode(state.obligations),"recurringPlans" to encode(state.recurringPlans),"recurringOccurrences" to encode(state.recurringOccurrences),"savingsPlans" to encode(state.savingsPlans),"savingsMovements" to encode(state.savingsMovements),"savingsWishes" to encode(state.savingsWishes),"budgetAllocations" to encode(state.budgetAllocations),"budgetReallocations" to encode(state.budgetReallocations))
        val records=unsorted.mapValues { (type,rows) -> rows.sortedBy { recordKey(type,it) } }
        val doc=BackupDocument(state.datasetId,createdAt,records,records.mapValues { it.value.size.toLong() },"")
        return doc.copy(sha256=digest(doc))
    }
    fun restore(document:BackupDocument,normalize:(String)->String=::normalizeTextNfc):LedgerState {
        if(document.canonicalizationVersion!=1) invalid("不支持的备份版本，请使用兼容版本")
        val sourceTypes=entityTypes(document.formatVersion)
        if(document.records.keys!=sourceTypes.toSet() || document.counts.keys!=sourceTypes.toSet()) invalid("备份实体集合不完整或含未知字段")
        validateId(document.datasetId); validateUtc(document.createdAt); validateDigest(document.sha256)
        sourceTypes.forEach { type ->
            if(document.counts[type]!=document.records.getValue(type).size.toLong()) invalid("备份计数不一致")
            document.records.getValue(type).forEach { RecordSchema.validate(type,it,document.formatVersion) }
        }
        if(document.sha256!=digest(document)) invalid("备份校验失败，文件可能损坏")
        val r=document.records
        val state=LedgerState(document.datasetId,ledgers=decode(r.getValue("ledgers")),categories=decode(r.getValue("categories")),entries=decode(r.getValue("entries")),deletionGroups=decode(r.getValue("deletionGroups")),budgets=decode(r.getValue("budgets")),sourceIdentities=decode(r.getValue("sourceIdentities")),importBatches=decode(r.getValue("importBatches")),importMembers=decode(r.getValue("importMembers")),preferences=decode(r.getValue("preferences")),
            accounts=decode<Account>(r["accounts"].orEmpty()),
            entryDetails=decode<EntryDetails>(r["entryDetails"].orEmpty()),
            entryTags=decode<EntryTag>(r["entryTags"].orEmpty()),
            transfers=decode<AccountTransfer>(r["transfers"].orEmpty()),
            dimensions=decode<Dimension>(r["dimensions"].orEmpty()),
            contacts=decode<Contact>(r["contacts"].orEmpty()),
            obligations=decode<Obligation>(r["obligations"].orEmpty()),
            recurringPlans=decode<RecurringPlan>(r["recurringPlans"].orEmpty()),
            recurringOccurrences=decode<RecurringOccurrence>(r["recurringOccurrences"].orEmpty()),
            savingsPlans=decode<SavingsPlan>(r["savingsPlans"].orEmpty()),
            savingsMovements=decode<SavingsMovement>(r["savingsMovements"].orEmpty()),
            savingsWishes=decode<SavingsWish>(r["savingsWishes"].orEmpty()),
            budgetAllocations=decode<BudgetAllocation>(r["budgetAllocations"].orEmpty()),
            budgetReallocations=decode<BudgetReallocation>(r["budgetReallocations"].orEmpty()))
        validateState(state,normalize)
        return state
    }
}
