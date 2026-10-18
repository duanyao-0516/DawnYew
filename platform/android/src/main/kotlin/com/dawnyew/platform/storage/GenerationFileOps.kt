package com.dawnyew.platform.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File operations are injectable so tests can fail before/after the rename linearization point. */
internal open class GenerationFileOps(private val sync: (File) -> Unit = {}) {
    open fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }
    open fun replaceAtomic(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    open fun syncDirectory(directory: File) = sync(directory)
    open fun copySynced(source: File, target: File, checkCancelled: () -> Unit, ensureSpace: (Long) -> Unit) {
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) { ensureSpace(count.toLong()); output.write(buffer, 0, count) }
                }
                output.fd.sync()
            }
        }
    }
    open fun remove(file: File) {
        if (file.exists() && !file.deleteRecursively()) throw IOException("无法清理本机文件：${file.name}")
    }
    fun atomic(file: File, text: String, renamed: () -> Unit = {}) {
        val temporary = File(file.parentFile, file.name + ".new")
        writeSynced(temporary, text)
        replaceAtomic(temporary, file)
        renamed() // The rename already happened, even if the following directory fsync fails.
        syncDirectory(file.parentFile!!)
    }
}
