package com.dawnyew.protocol

import com.dawnyew.core.invalid
import kotlinx.serialization.json.*

/** The v1 wire schema is complete: default-valued fields are required, and no scalar coercion is permitted. */
object RecordSchema {
    private enum class Kind { STRING, NULLABLE_STRING, BOOLEAN, INTEGER, NULLABLE_INTEGER, DECIMAL_STRING, NULLABLE_DECIMAL_STRING, SIGNED_DECIMAL_STRING }
    private data class Schema(val strings:String, val nullable:String="", val booleans:String="", val integers:String="", val decimals:String="", val nullableIntegers:String="", val nullableDecimals:String="", val signedDecimals:String="") {
        val fields = buildMap {
            fun add(list:String, kind:Kind) { list.split(' ').filter { it.isNotEmpty() }.forEach { put(it,kind) } }
            add(strings,Kind.STRING);add(nullable,Kind.NULLABLE_STRING);add(booleans,Kind.BOOLEAN);add(integers,Kind.INTEGER);add(decimals,Kind.DECIMAL_STRING);add(nullableIntegers,Kind.NULLABLE_INTEGER);add(nullableDecimals,Kind.NULLABLE_DECIMAL_STRING);add(signedDecimals,Kind.SIGNED_DECIMAL_STRING)
        }
    }
    private val schemas = mapOf(
        "ledgers" to Schema("id name currency",booleans="archived",integers="sortOrder",decimals="revision"),
        "categories" to Schema("id ledgerId direction name",booleans="enabled",integers="sortOrder",decimals="revision"),
        "entries" to Schema("id ledgerId kind categoryId occurrence note createdAt updatedAt originKind creationOperationId",nullable="parentExpenseId deletionGroupId captureFingerprint captureEngineVersion",decimals="amountMinor revision"),
        "deletionGroups" to Schema("id deletedAt rootEntryId reason"),
        "budgets" to Schema("ledgerId yearMonth",decimals="amountMinor revision"),
        "sourceIdentities" to Schema("id entryId provider scope eventKind externalId canonicalSource sourceDigest",integers="normalizationVersion"),
        "importBatches" to Schema("id submissionId adapterId adapterVersion fileDigest previewDigest mappingDigest createdAt status",decimals="count"),
        "importMembers" to Schema("batchId entryId committedContentDigest state",decimals="committedRevision"),
        "preferences" to Schema("key value"),
        "accounts" to Schema("id ledgerId name type openingAt note",booleans="archived hidden includedInAssets",integers="sortOrder",decimals="revision",signedDecimals="openingBalanceMinor"),
        "entryDetails" to Schema("entryId",nullable="accountId projectId merchantId",booleans="recorded excludedFromBudget reconciled"),
        "entryTags" to Schema("entryId tagId"),
        "transfers" to Schema("id ledgerId occurrence note createdAt updatedAt businessKind",nullable="fromAccountId toAccountId businessId",booleans="recorded reconciled deleted",decimals="amountMinor revision"),
        "dimensions" to Schema("id ledgerId kind name",booleans="archived",integers="sortOrder",decimals="revision"),
        "contacts" to Schema("id ledgerId name note",booleans="archived",decimals="revision"),
        "obligations" to Schema("id ledgerId kind title virtualAccountId openingAt note",nullable="contactId dueDate",booleans="archived",decimals="revision"),
        "recurringPlans" to Schema("id ledgerId name kind anchor frequency note",nullable="categoryId accountId fromAccountId toAccountId projectId merchantId",booleans="paused archived recorded excludedFromBudget reconciled",integers="nextIndex",decimals="amountMinor revision",nullableIntegers="totalOccurrences"),
        "recurringOccurrences" to Schema("id planId scheduledAt status",nullable="entryId transferId",integers="index"),
        "savingsPlans" to Schema("id ledgerId name scenario note",booleans="archived",decimals="revision",nullableDecimals="targetMinor"),
        "savingsMovements" to Schema("id planId kind occurrence note",nullable="transferId",booleans="deleted",decimals="amountMinor revision"),
        "savingsWishes" to Schema("id planId name",nullable="fulfilledMovementId",decimals="amountMinor revision"),
        "budgetAllocations" to Schema("id ledgerId yearMonth categoryId",decimals="amountMinor revision"),
        "budgetReallocations" to Schema("id ledgerId yearMonth fromAllocationId toAllocationId createdAt",nullable="reversalOfId",decimals="amountMinor"),
    )
    fun fields(type:String,formatVersion:Int=2):Set<String> { requireType(type,formatVersion);return schemas.getValue(type).fields.keys }
    private fun requireType(type:String,formatVersion:Int) { if(type !in BackupProtocol.entityTypes(formatVersion)) invalid("未知备份实体") }
    fun validate(type:String,record:JsonObject,formatVersion:Int=2) {
        requireType(type,formatVersion)
        val schema=schemas[type] ?: invalid("未知备份实体")
        if (record.keys != schema.fields.keys) invalid("备份记录缺少字段或含未知字段")
        schema.fields.forEach { (key,kind) ->
            val value=record.getValue(key)
            if (value==JsonNull && kind in setOf(Kind.NULLABLE_STRING,Kind.NULLABLE_INTEGER,Kind.NULLABLE_DECIMAL_STRING)) return@forEach
            val primitive=value as? JsonPrimitive ?: invalid("备份字段必须为标量")
            val valid=when(kind) {
                Kind.STRING,Kind.NULLABLE_STRING -> primitive.isString
                Kind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull!=null
                Kind.INTEGER,Kind.NULLABLE_INTEGER -> !primitive.isString && Regex("-?(0|[1-9][0-9]*)").matches(primitive.content) && primitive.intOrNull!=null
                Kind.DECIMAL_STRING,Kind.NULLABLE_DECIMAL_STRING -> primitive.isString && Regex("0|[1-9][0-9]*").matches(primitive.content) && primitive.content.toLongOrNull()!=null
                Kind.SIGNED_DECIMAL_STRING -> primitive.isString && Regex("0|-[1-9][0-9]*|[1-9][0-9]*").matches(primitive.content) && primitive.content.toLongOrNull()!=null
            }
            if (!valid) invalid("备份字段类型或整数范围无效")
            if (type=="importBatches" && key=="count" && primitive.content.toIntOrNull()==null) invalid("导入计数超出范围")
        }
    }
}
