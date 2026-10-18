package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BackupStrictTest {
    private var n=0
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=value.trim()
    }
    private fun backup()=BackupProtocol.create(DomainEngine(env).initialState(),env.utcNow())
    private fun changed(b:BackupDocument,type:String="ledgers",change:(JsonObject)->JsonObject):BackupDocument {
        val altered=b.copy(records=b.records + (type to b.records.getValue(type).map(change)))
        return altered.copy(sha256=BackupProtocol.digest(altered))
    }
    @Test fun missingDefaultsAndUnknownFieldsAreRejectedEvenWithValidDigest() {
        val b=backup()
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it - "archived") }) }
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("future" to JsonPrimitive(true))) }) }
    }
    @Test fun primitiveStringNumberAndBooleanCannotBeCoerced() {
        val b=backup()
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("archived" to JsonPrimitive("false"))) }) }
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("sortOrder" to JsonPrimitive("0"))) }) }
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("revision" to JsonPrimitive(1))) }) }
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("name" to JsonPrimitive(12))) }) }
    }
    @Test fun nonCanonicalUuidUtcAndRevisionOverflowAreRejected() {
        val b=backup()
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("id" to JsonPrimitive("not-uuid"))) }) }
        assertFails { BackupProtocol.restore(changed(b) { JsonObject(it + ("revision" to JsonPrimitive("9223372036854775808"))) }) }
        val metadata=b.copy(createdAt="2026-10-18T04:00:00+00:00")
        assertFails { BackupProtocol.restore(metadata.copy(sha256=BackupProtocol.digest(metadata))) }
    }
    @Test fun canonicalKeysAndValuesRejectUnpairedSurrogates() {
        assertFails { CanonicalJson.write(JsonObject(mapOf("\ud800" to JsonPrimitive("ok")))) }
        assertFails { CanonicalJson.write(JsonPrimitive("\udfff")) }
        assertEquals("0",CanonicalJson.write(Json.parseToJsonElement("-0")))
    }
    @Test fun outputArraysAreSortedByStablePrimaryKey() {
        val s=DomainEngine(env).initialState(); val shuffled=s.copy(categories=s.categories.reversed())
        assertEquals(BackupProtocol.create(s,env.utcNow()),BackupProtocol.create(shuffled,env.utcNow()))
    }
    @Test fun importedHistoryRoundtripsAndBatchCountIsDecimalString() {
        val engine=DomainEngine(env);val s=engine.initialState()
        val e=Entry(env.id(),s.ledgers.first().id,EntryKind.EXPENSE,123,s.categories.first().id,"2026-10-17T12:00:00",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id())
        val p=ImportPlan(env.id(),env.id(),s.datasetId,s.contentVersion,0,"fixture","1",Sha256.hex(byteArrayOf(1)),Sha256.hex(byteArrayOf(2)),rows=listOf(ImportRow(1,e))).sealed()
        val imported=engine.apply(s,Command.CommitImport(p),env.id()).state
        val b=BackupProtocol.create(imported,env.utcNow())
        assertTrue(b.records.getValue("importBatches").single().getValue("count").jsonPrimitive.isString)
        val restored=BackupProtocol.restore(b)
        assertEquals(imported.importMembers,restored.importMembers)
        assertTrue(engine.apply(restored,Command.UndoImport(p.batchId),env.id()).state.entries.isEmpty())
    }
    @Test fun restoreRejectsNonemptyDeletionGroupWithMissingRootButKeepsEmptyAuditGroup() {
        val engine=DomainEngine(env);var s=engine.initialState()
        s=engine.apply(s,Command.SaveEntry(ledgerId=s.ledgers.first().id,kind=EntryKind.EXPENSE,amountMinor=100,categoryId=s.categories.first().id,occurrence="2026-10-17T00:00:00",note=""),env.id()).state
        val id=s.entries.single().id;s=engine.apply(s,Command.SoftDelete(id),env.id()).state
        val b=BackupProtocol.create(s,env.utcNow())
        val broken=changed(b,"deletionGroups") { JsonObject(it + ("rootEntryId" to JsonPrimitive(env.id()))) }
        assertFailsWith<DomainException> { BackupProtocol.restore(broken) }
        val purged=engine.apply(s,Command.Purge(id),env.id()).state
        assertEquals(purged.deletionGroups,BackupProtocol.restore(BackupProtocol.create(purged,env.utcNow())).deletionGroups)
    }

}
