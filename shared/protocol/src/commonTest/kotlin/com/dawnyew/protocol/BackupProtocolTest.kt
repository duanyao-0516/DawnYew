package com.dawnyew.protocol
import com.dawnyew.core.*
import kotlinx.serialization.json.*
import kotlin.test.*
class BackupProtocolTest {
    private var n=0
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00Z"
        override fun normalize(value:String)=value.trim()
    }
    @Test fun roundtripPreservesMoneyAndCreationIdentity() {
        val engine=DomainEngine(env);val base=engine.initialState()
        val s=engine.apply(base,Command.SaveEntry(ledgerId=base.ledgers.first().id,kind=EntryKind.EXPENSE,amountMinor=99999999999,categoryId=base.categories.first().id,occurrence="2026-10-18T11:00:00",note="中文🧾"),env.id()).state
        val backup=BackupProtocol.create(s,env.utcNow())
        assertEquals("99999999999",backup.records.getValue("entries").single().getValue("amountMinor").jsonPrimitive.content)
        assertTrue(backup.records.getValue("entries").single().getValue("amountMinor").jsonPrimitive.isString)
        val restored=BackupProtocol.restore(backup,env::normalize)
        assertEquals(s.entries,restored.entries)
        assertTrue(restored.receipts.isEmpty())
    }
    @Test fun changingMetadataOrRecordInvalidatesDigest() {
        val b=BackupProtocol.create(DomainEngine(env).initialState(),env.utcNow())
        assertFails { BackupProtocol.restore(b.copy(datasetId=env.id())) }
        assertFails { BackupProtocol.restore(b.copy(createdAt="2026-10-19T00:00:00Z")) }
        val records=b.records.toMutableMap();records["ledgers"]=records.getValue("ledgers")+records.getValue("ledgers").first()
        assertFails { BackupProtocol.restore(b.copy(records=records)) }
    }
    @Test fun deletedRefundKeepsOldLedgerAndRoundtripsAfterParentMove() {
        val engine=DomainEngine(env);var s=engine.initialState()
        fun run(c:Command) { s=engine.apply(s,c,env.id()).state }
        run(Command.SaveEntry(ledgerId=s.ledgers.first().id,kind=EntryKind.EXPENSE,amountMinor=1000,categoryId=s.categories.first().id,occurrence="2026-10-16T00:00:00",note=""))
        val parent=s.entries.single();run(Command.AddRefund(parent.id,200,"2026-10-17T00:00:00",""));run(Command.SoftDelete(s.entries.last().id))
        run(Command.CreateLedger("旅行"));val dest=s.ledgers.last();run(Command.MoveEntry(parent.id,dest.id,s.categories.first { it.ledgerId==dest.id }.id))
        assertEquals(s.entries,BackupProtocol.restore(BackupProtocol.create(s,env.utcNow())).entries)
    }
    @Test fun canonicalWriterSortsKeysAndEscapesControlCharacters() {
        assertEquals("{\"a\":\"\\n\",\"z\":1}",CanonicalJson.write(buildJsonObject { put("z",1);put("a","\n") }))
    }
}
