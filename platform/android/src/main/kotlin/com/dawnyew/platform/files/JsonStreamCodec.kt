package com.dawnyew.platform.files

import com.dawnyew.core.*
import com.dawnyew.protocol.*
import com.fasterxml.jackson.core.*
import kotlinx.serialization.json.*
import java.io.*
import java.util.UUID

/** A fully checksummed, privately staged backup. Contains file handles/metadata, never a whole dataset tree. */
class PreparedBackup internal constructor(
    val metadata: BackupDocument,
    private val directory: File,
    private val records: Map<String, File>,
    private val checkCancelled: () -> Unit,
) : Closeable {
    private var closed = false
    suspend fun visit(type: String, consumer: suspend (List<JsonObject>) -> Unit) {
        check(!closed)
        CanonicalRecordStore.Reader(records.getValue(type)).use { input ->
            var batch = ArrayList<JsonObject>(128)
            var batchBytes = 0L
            while (true) {
                checkCancelled(); val record = input.next() ?: break
                batch.add(BackupProtocol.json.parseToJsonElement(record.canonical.toString(Charsets.UTF_8)).jsonObject)
                batchBytes += record.canonical.size
                if (batch.size == 128 || batchBytes >= 4L * 1024 * 1024) { consumer(batch); batch = ArrayList(128); batchBytes = 0 }
            }
            if (batch.isNotEmpty()) { checkCancelled(); consumer(batch) }
        }
    }
    internal fun digest(): String {
        val hash = JsonStreamCodec.startDigest(metadata)
        for (type in BackupProtocol.entityTypes) CanonicalRecordStore.Reader(records.getValue(type)).use { input ->
            while (true) { checkCancelled(); val record = input.next() ?: break; JsonStreamCodec.hashRecord(hash, type, record.canonical) }
        }
        return hash.finishHex()
    }
    override fun close() {
        if (!closed) { closed = true; if (directory.exists() && !directory.deleteRecursively()) throw IOException("备份暂存未能清理，请重试") }
    }
}

/** The production path stages/sorts records on disk, with no backup-wide record-count or byte ceiling. */
object JsonStreamCodec {
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNameLength(128).maxNumberLength(20).build()).build()
    private val rootFields = setOf("format", "formatVersion", "datasetId", "createdAt", "entities", "manifest")
    private val manifestFields = setOf("canonicalizationVersion", "counts", "sha256")
    private val defaultCancellation: () -> Unit = { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("操作取消") }

    fun prepare(input: InputStream, stagingDirectory: File, checkCancelled: () -> Unit = defaultCancellation,
        ensureSpace: (Long) -> Unit = { bytes -> if (stagingDirectory.usableSpace < 128L * 1024 * 1024 + bytes) throw IOException("备份暂存空间不足") }): PreparedBackup {
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "无法创建备份暂存目录" }
        val directory = File(stagingDirectory, "json-${UUID.randomUUID()}")
        check(directory.mkdir()) { "无法创建备份暂存目录" }
        try {
            val stores = BackupProtocol.entityTypes.associateWith { CanonicalRecordStore(File(directory, it), checkCancelled, ensureSpace) }
            val actualCounts = BackupProtocol.entityTypes.associateWith { 0L }.toMutableMap()
            val metadata = parse(input, checkCancelled, { type, record ->
                checkCancelled()
                BackupProtocol.validateRecord(type, record)
                stores.getValue(type).add(BackupProtocol.recordKey(type, record), CanonicalJson.write(record).toByteArray(Charsets.UTF_8))
                actualCounts[type] = stores.getValue(type).count
            }, { type -> stores.getValue(type).finishInput() })
            if (metadata.counts != actualCounts) invalid("备份计数不一致")
            val sorted = stores.mapValues { (_, store) -> store.finish() }
            val result = PreparedBackup(metadata, directory, sorted, checkCancelled)
            if (result.digest() != metadata.sha256) invalid("备份校验失败，文件可能损坏")
            return result
        } catch (error: Throwable) {
            if (!directory.deleteRecursively()) error.addSuppressed(IOException("备份暂存清理失败"))
            throw error
        }
    }

    /** Compatibility helper for existing small-fixture tests. Production uses prepare(). */
    fun read(input: InputStream): BackupDocument {
        val records = BackupProtocol.entityTypes.associateWith { ArrayList<JsonObject>() }
        val metadata = parse(input, defaultCancellation, { type, record -> records.getValue(type).add(record) })
        return metadata.copy(records = records)
    }
    /** Compatibility helper for callers already holding a small immutable document. */
    fun write(document: BackupDocument, output: OutputStream) {
        factory.createGenerator(output, JsonEncoding.UTF8).use { g ->
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET); writePrefix(g, document.datasetId, document.createdAt)
            g.writeObjectFieldStart("entities")
            for (type in BackupProtocol.entityTypes) {
                g.writeArrayFieldStart(type)
                document.records.getValue(type).sortedBy { BackupProtocol.recordKey(type, it) }.forEach { g.writeRawValue(CanonicalJson.write(it)) }
                g.writeEndArray()
            }
            g.writeEndObject(); writeManifest(g, document.counts, document.sha256); g.writeEndObject(); g.flush()
        }
    }

    /** Caller holds a stable storage read transaction throughout every visit and the final manifest. */
    suspend fun writeStreaming(datasetId: String, createdAt: String, counts: Map<String, Long>, output: OutputStream,
        checkCancelled: () -> Unit = defaultCancellation,
        visit: suspend (String, suspend (JsonObject) -> Unit) -> Unit) {
        val metadata = BackupDocument(datasetId, createdAt, emptyMap(), counts, "")
        validateId(datasetId); validateUtc(createdAt)
        if (counts.keys != BackupProtocol.entityTypes.toSet() || counts.values.any { it < 0 }) invalid("导出计数无效")
        val hash = startDigest(metadata)
        factory.createGenerator(output, JsonEncoding.UTF8).use { g ->
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET); writePrefix(g, datasetId, createdAt); g.writeObjectFieldStart("entities")
            for (type in BackupProtocol.entityTypes) {
                checkCancelled(); g.writeArrayFieldStart(type)
                var count = 0L; var previous: String? = null
                visit(type) { record ->
                    checkCancelled(); BackupProtocol.validateRecord(type, record)
                    val key = BackupProtocol.recordKey(type, record)
                    if (previous?.let { key <= it } == true) invalid("导出记录顺序无效或标识重复")
                    val canonical = CanonicalJson.write(record)
                    g.writeRawValue(canonical); hashRecord(hash, type, canonical.toByteArray(Charsets.UTF_8))
                    previous = key; count++
                }
                if (count != counts.getValue(type)) invalid("导出快照计数发生变化")
                g.writeEndArray()
            }
            checkCancelled(); g.writeEndObject(); writeManifest(g, counts, hash.finishHex()); g.writeEndObject(); g.flush()
        }
    }
    private fun writePrefix(g: JsonGenerator, datasetId: String, createdAt: String) {
        g.writeStartObject(); g.writeStringField("format", BackupProtocol.FORMAT); g.writeNumberField("formatVersion", 1)
        g.writeStringField("datasetId", datasetId); g.writeStringField("createdAt", createdAt)
    }
    private fun writeManifest(g: JsonGenerator, counts: Map<String, Long>, digest: String) {
        g.writeObjectFieldStart("manifest"); g.writeNumberField("canonicalizationVersion", 1); g.writeObjectFieldStart("counts")
        BackupProtocol.entityTypes.forEach { g.writeStringField(it, counts.getValue(it).toString()) }
        g.writeEndObject(); g.writeStringField("sha256", digest); g.writeEndObject()
    }
    internal fun startDigest(metadata: BackupDocument) = Sha256().update((CanonicalJson.write(BackupProtocol.metadata(metadata)) + "\n").toByteArray(Charsets.UTF_8))
    internal fun hashRecord(hash: Sha256, type: String, canonical: ByteArray) {
        hash.update(("{\"entityType\":\"$type\",\"record\":").toByteArray(Charsets.UTF_8)); hash.update(canonical); hash.update("}\n".toByteArray(Charsets.UTF_8))
    }

    private fun parse(input: InputStream, checkCancelled: () -> Unit, record: (String, JsonObject) -> Unit, typeComplete: (String) -> Unit = {}): BackupDocument {
        val checked = object : FilterInputStream(input) {
            override fun read(): Int { checkCancelled(); return super.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { checkCancelled(); return `in`.read(bytes, offset, length) }
            override fun close() {} // The caller owns its stream.
        }
        val buffered = BufferedInputStream(checked); buffered.mark(4)
        val prefix = ByteArray(4); var n = 0
        while (n < 4) { val count = buffered.read(prefix, n, 4 - n); if (count < 0) break; if (count > 0) n += count }
        buffered.reset()
        if ((0 until n).any { prefix[it] == 0.toByte() } || (n >= 2 && (prefix[0].toInt() and 255) in listOf(0xff, 0xfe))) invalid("备份必须使用 UTF-8")
        factory.createParser(buffered).use { p ->
            p.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            fun next(): JsonToken? { checkCancelled(); return p.nextToken() }
            fun field(allowed: Set<String>, seen: MutableSet<String>): String {
                if (p.currentToken() != JsonToken.FIELD_NAME) invalid("JSON 对象字段无效")
                val key = p.currentName()
                if (key.length > 128 || key !in allowed || !seen.add(key) || seen.size > 128) invalid("未知、重复或过长的 JSON 属性")
                return key
            }
            fun scalar(): JsonElement = when (p.currentToken()) {
                JsonToken.VALUE_STRING -> {
                    val value = p.text
                    if (codePointCount(value) > 32768 || value.toByteArray(Charsets.UTF_8).size > 65536) invalid("文本字段过长")
                    JsonPrimitive(value)
                }
                JsonToken.VALUE_NUMBER_INT -> JsonPrimitive(p.longValue)
                JsonToken.VALUE_TRUE -> JsonPrimitive(true)
                JsonToken.VALUE_FALSE -> JsonPrimitive(false)
                JsonToken.VALUE_NULL -> JsonNull
                else -> invalid("备份字段必须为标量且不接受浮点数")
            }
            fun text(value: JsonElement): String { val primitive = value as? JsonPrimitive ?: invalid("备份文本字段类型错误"); if (!primitive.isString) invalid("备份文本字段类型错误"); return primitive.content }
            fun version(value: JsonElement): Int { val primitive = value as? JsonPrimitive ?: invalid("备份版本类型错误"); if (primitive.isString || primitive.intOrNull != 1) invalid("不支持的备份版本"); return 1 }
            if (next() != JsonToken.START_OBJECT) invalid("备份根节点必须为对象")
            val seen = mutableSetOf<String>(); val root = mutableMapOf<String, JsonElement>(); val manifest = mutableMapOf<String, JsonElement>()
            val counts = linkedMapOf<String, Long>()
            while (next() != JsonToken.END_OBJECT) {
                val key = field(rootFields, seen); next()
                when (key) {
                    "entities" -> {
                        if (p.currentToken() != JsonToken.START_OBJECT) invalid("实体集合必须为对象")
                        val types = mutableSetOf<String>()
                        while (next() != JsonToken.END_OBJECT) {
                            val type = field(BackupProtocol.entityTypes.toSet(), types)
                            if (next() != JsonToken.START_ARRAY) invalid("实体必须为数组")
                            while (next() != JsonToken.END_ARRAY) {
                                if (p.currentToken() != JsonToken.START_OBJECT) invalid("备份记录必须为对象")
                                val fields = mutableSetOf<String>(); val values = linkedMapOf<String, JsonElement>()
                                while (next() != JsonToken.END_OBJECT) { val name = field(BackupProtocol.recordFields(type), fields); next(); values[name] = scalar() }
                                val row = JsonObject(values); BackupProtocol.validateRecord(type, row); record(type, row)
                            }
                            typeComplete(type)
                        }
                        if (types != BackupProtocol.entityTypes.toSet()) invalid("备份实体集合不完整")
                    }
                    "manifest" -> {
                        if (p.currentToken() != JsonToken.START_OBJECT) invalid("备份清单必须为对象")
                        val keys = mutableSetOf<String>()
                        while (next() != JsonToken.END_OBJECT) {
                            val name = field(manifestFields, keys); next()
                            if (name == "counts") {
                                if (p.currentToken() != JsonToken.START_OBJECT) invalid("备份计数必须为对象")
                                val types = mutableSetOf<String>()
                                while (next() != JsonToken.END_OBJECT) {
                                    val type = field(BackupProtocol.entityTypes.toSet(), types); next(); val value = text(scalar())
                                    if (!Regex("0|[1-9][0-9]*").matches(value)) invalid("备份计数格式错误")
                                    counts[type] = value.toLongOrNull() ?: invalid("备份计数超出范围")
                                }
                                if (types != BackupProtocol.entityTypes.toSet()) invalid("备份计数不完整")
                            } else manifest[name] = scalar()
                        }
                        if (keys != manifestFields) invalid("备份清单不完整")
                    }
                    else -> root[key] = scalar()
                }
            }
            if (next() != null) invalid("备份包含尾随内容")
            if (seen != rootFields) invalid("备份字段不完整")
            if (text(root.getValue("format")) != BackupProtocol.FORMAT) invalid("不是 DawnYew 完整备份")
            val result = BackupDocument(text(root.getValue("datasetId")), text(root.getValue("createdAt")), emptyMap(), counts,
                text(manifest.getValue("sha256")), version(root.getValue("formatVersion")), version(manifest.getValue("canonicalizationVersion")))
            validateId(result.datasetId); validateUtc(result.createdAt); validateDigest(result.sha256)
            return result
        }
    }
}
