package com.dawnyew.platform.files

import com.dawnyew.core.*
import com.dawnyew.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class FinancialBackupCodecTest {
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=value.trim()
    }
    private fun doc()=BackupProtocol.create(DomainEngine(env).initialState(),env.utcNow())
    private fun bytes(doc:BackupDocument)=ByteArrayOutputStream().also {JsonStreamCodec.write(doc,it)}.toByteArray()
    @Test fun legacySourceMetadataStaysExactAndMissingNewTablesVisitAsEmpty()=runBlocking {
        val current=doc();val types=BackupProtocol.entityTypes(1).toSet()
        val unsigned=current.copy(formatVersion=1,records=current.records.filterKeys {it in types},counts=current.counts.filterKeys {it in types},sha256="")
        val legacy=unsigned.copy(sha256=BackupProtocol.digest(unsigned))
        // Version deliberately follows entities: field order cannot select the protocol schema.
        val tree=BackupProtocol.json.parseToJsonElement(bytes(legacy).toString(Charsets.UTF_8)).jsonObject
        val reversed=JsonObject(linkedMapOf("entities" to tree.getValue("entities"))+tree.filterKeys {it!="entities"})
        val root=Files.createTempDirectory("legacy-finance-").toFile()
        try {JsonStreamCodec.prepare(reversed.toString().byteInputStream(),root).use {prepared->
            assertEquals(1,prepared.metadata.formatVersion)
            assertEquals(legacy.counts,prepared.metadata.counts)
            assertEquals(legacy.sha256,prepared.metadata.sha256)
            var newRows=0
            prepared.visit("accounts") {newRows+=it.size}
            assertEquals(0,newRows)
        }} finally {root.deleteRecursively()}
    }
    @Test fun currentFormatCarriesSignedAccountOpeningThroughProductionStaging()=runBlocking {
        val initial=DomainEngine(env).initialState()
        val account=Account(env.id(),initial.ledgers.single().id,"信用卡",AccountType.CREDIT,-12345,"2026-10-24T00:00:00")
        val document=BackupProtocol.create(initial.copy(accounts=listOf(account)),env.utcNow())
        val root=Files.createTempDirectory("current-finance-").toFile()
        try {JsonStreamCodec.prepare(bytes(document).inputStream(),root).use {prepared->
            assertEquals(2,prepared.metadata.formatVersion)
            var rows=emptyList<Account>()
            prepared.visit("accounts") {rows=rows+BackupProtocol.decodeRecords("accounts",it).accounts}
            assertEquals(listOf(account),rows)
        }} finally {root.deleteRecursively()}
    }
    @Test fun legacyVersionCannotSmuggleNewEntityArrays() {
        val document=doc()
        val bytes=bytes(document).toString(Charsets.UTF_8).replace("\"formatVersion\":2","\"formatVersion\":1")
        assertFails {JsonStreamCodec.read(bytes.byteInputStream())}
    }
}
