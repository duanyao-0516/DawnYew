package com.dawnyew.platform.files

import com.dawnyew.core.invalid
import java.io.*
import java.util.PriorityQueue

/** Private scratch records. Sorted runs and fan-in bound memory independently of backup size. */
internal class CanonicalRecordStore(private val directory: File, private val checkCancelled: () -> Unit, private val ensureSpace: (Long) -> Unit,
    private val openInput: (File) -> InputStream = { FileInputStream(it) }) {
    data class Record(val key: String, val canonical: ByteArray)
    private val buffer = ArrayList<Record>()
    private var bufferedBytes = 0L
    private val levels = ArrayList<MutableList<File>>()
    private var sequence = 0L
    var count = 0L; private set
    init { check(directory.mkdirs()) { "无法创建备份排序目录" } }
    fun add(key: String, canonical: ByteArray) {
        checkCancelled()
        if (count == Long.MAX_VALUE) invalid("备份记录计数超出范围")
        buffer.add(Record(key, canonical)); bufferedBytes += canonical.size + key.length * 2L; count++
        if (buffer.size >= 128 || bufferedBytes >= 4L * 1024 * 1024) flush()
    }
    private fun nextFile() = File(directory, "run-${sequence++}")
    private fun flush() {
        if (buffer.isEmpty()) return
        checkCancelled(); buffer.sortBy { it.key }
        val file = nextFile()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            var previous: String? = null
            for (record in buffer) { if (record.key == previous) invalid("备份实体标识重复"); write(out, record); previous = record.key }
        }
        buffer.clear(); bufferedBytes = 0; addRun(file, 0)
    }
    fun finishInput() = flush()
    fun finish(): File {
        flush()
        if (levels.isEmpty()) nextFile().also { it.createNewFile(); addRun(it, 0) }
        var current = levels.flatMap { it }
        while (current.size > 1) {
            val next = ArrayList<File>()
            for (group in current.chunked(16)) {
                checkCancelled()
                if (group.size == 1) { next.add(group.single()); continue }
                val output = nextFile(); merge(group, output); next.add(output)
                group.forEach { if (!it.delete()) throw IOException("无法清理备份排序暂存") }
            }
            current = next
        }
        return current.single()
    }
    private fun addRun(file: File, level: Int) {
        if (levels.size == level) levels.add(ArrayList())
        levels[level].add(file)
        if (levels[level].size == 16) {
            val group = levels[level].toList(); val merged = nextFile(); merge(group, merged)
            group.forEach { if (!it.delete()) throw IOException("无法清理备份排序暂存") }
            levels[level].clear(); addRun(merged, level + 1)
        }
    }
    private data class Head(val record: Record, val reader: Reader)
    private fun merge(inputs: List<File>, output: File) {
        val readers = ArrayList<Reader>(inputs.size)
        var primaryFailure: Throwable? = null
        try {
            inputs.forEach { readers.add(Reader(it, openInput)) }
            val queue = PriorityQueue<Head>(compareBy { it.record.key })
            readers.forEach { reader -> reader.next()?.let { queue.add(Head(it, reader)) } }
            DataOutputStream(BufferedOutputStream(FileOutputStream(output))).use { out ->
                var previous: String? = null
                while (queue.isNotEmpty()) {
                    checkCancelled(); val head = queue.remove()
                    if (head.record.key == previous) invalid("备份实体标识重复")
                    write(out, head.record); previous = head.record.key
                    head.reader.next()?.let { queue.add(Head(it, head.reader)) }
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var failure = primaryFailure
            readers.forEach { reader ->
                try { reader.close() }
                catch (close: Throwable) {
                    if (failure == null) failure = close else if (failure !== close) failure!!.addSuppressed(close)
                }
            }
            if (primaryFailure == null) failure?.let { throw it }
        }
    }
    private fun write(output: DataOutputStream, record: Record) {
        checkCancelled(); val key = record.key.toByteArray(Charsets.UTF_8)
        ensureSpace(8L + key.size + record.canonical.size)
        output.writeInt(key.size); output.write(key); output.writeInt(record.canonical.size); output.write(record.canonical)
    }
    class Reader(file: File, openInput: (File) -> InputStream = { FileInputStream(it) }) : Closeable {
        private val input = DataInputStream(BufferedInputStream(openInput(file)))
        fun next(): Record? {
            input.mark(1); if (input.read() < 0) return null; input.reset()
            fun bytes(limit: Int): ByteArray {
                val length = input.readInt()
                if (length !in 0..limit) throw IOException("备份排序暂存损坏")
                return ByteArray(length).also(input::readFully)
            }
            val key = bytes(128 * 1024).toString(Charsets.UTF_8)
            return Record(key, bytes(8 * 1024 * 1024))
        }
        override fun close() = input.close()
    }
}
