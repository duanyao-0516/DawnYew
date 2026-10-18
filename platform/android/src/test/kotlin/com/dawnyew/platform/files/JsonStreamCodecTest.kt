package com.dawnyew.platform.files
import com.dawnyew.core.*
import com.dawnyew.protocol.*
import java.io.*
import org.junit.Test
import kotlin.test.*
class JsonStreamCodecTest {
    private var n=0
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=value.trim()
    }
    private fun valid():ByteArray {val out=ByteArrayOutputStream();JsonStreamCodec.write(BackupProtocol.create(DomainEngine(env).initialState(),env.utcNow()),out);return out.toByteArray()}
    @Test fun streamedUtf8Roundtrip() {val restored=BackupProtocol.restore(JsonStreamCodec.read(valid().inputStream()));assertEquals("日常",restored.ledgers.single().name)}
    @Test fun duplicateFieldsAreRejected() {val raw=valid().toString(Charsets.UTF_8).replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1");assertFails {JsonStreamCodec.read(raw.byteInputStream())}}
    @Test fun trailingDocumentAndUnknownRootAreRejected() {assertFails {JsonStreamCodec.read((valid()+"{}".toByteArray()).inputStream())};val raw=valid().toString(Charsets.UTF_8).replaceFirst("{","{\"extra\":true,");assertFails {JsonStreamCodec.read(raw.byteInputStream())}}
    @Test fun floatVersionAndUtf16AreRejected() {val raw=valid().toString(Charsets.UTF_8);assertFails {JsonStreamCodec.read(raw.replace("\"formatVersion\":1","\"formatVersion\":1.0").byteInputStream())};assertFails {JsonStreamCodec.read(raw.toByteArray(Charsets.UTF_16).inputStream())}}
    @Test fun truncatedArrayIsRejected() {val bytes=valid();assertFails {JsonStreamCodec.read(bytes.copyOf(bytes.size-10).inputStream())}}
    @Test fun tokenBoundsAreEnforcedBeforeBackupValidation() {val raw="{\"createdAt\":\""+"a".repeat(33000)+"\"}";assertFails {JsonStreamCodec.read(raw.byteInputStream())}}
}
