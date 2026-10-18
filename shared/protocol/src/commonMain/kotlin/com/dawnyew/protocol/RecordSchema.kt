package com.dawnyew.protocol

import com.dawnyew.core.invalid
import kotlinx.serialization.json.*

/** The v1 wire schema is complete: default-valued fields are required, and no scalar coercion is permitted. */
internal object RecordSchema {
    private enum class Kind { STRING, NULLABLE_STRING, BOOLEAN, INTEGER, DECIMAL_STRING }
    private data class Schema(val strings:String, val nullable:String="", val booleans:String="", val integers:String="", val decimals:String="") {
        val fields = buildMap {
            fun add(list:String, kind:Kind) { list.split(' ').filter { it.isNotEmpty() }.forEach { put(it,kind) } }
            add(strings,Kind.STRING);add(nullable,Kind.NULLABLE_STRING);add(booleans,Kind.BOOLEAN);add(integers,Kind.INTEGER);add(decimals,Kind.DECIMAL_STRING)
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
    )
    fun fields(type:String):Set<String> = schemas[type]?.fields?.keys ?: invalid("未知备份实体")
    fun validate(type:String,record:JsonObject) {
        val schema=schemas[type] ?: invalid("未知备份实体")
        if (record.keys != schema.fields.keys) invalid("备份记录缺少字段或含未知字段")
        schema.fields.forEach { (key,kind) ->
            val value=record.getValue(key)
            if (value==JsonNull && kind==Kind.NULLABLE_STRING) return@forEach
            val primitive=value as? JsonPrimitive ?: invalid("备份字段必须为标量")
            val valid=when(kind) {
                Kind.STRING,Kind.NULLABLE_STRING -> primitive.isString
                Kind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull!=null
                Kind.INTEGER -> !primitive.isString && Regex("-?(0|[1-9][0-9]*)").matches(primitive.content) && primitive.intOrNull!=null
                Kind.DECIMAL_STRING -> primitive.isString && Regex("0|[1-9][0-9]*").matches(primitive.content) && primitive.content.toLongOrNull()!=null
            }
            if (!valid) invalid("备份字段类型或整数范围无效")
            if (type=="importBatches" && key=="count" && primitive.content.toIntOrNull()==null) invalid("导入计数超出范围")
        }
    }
}
