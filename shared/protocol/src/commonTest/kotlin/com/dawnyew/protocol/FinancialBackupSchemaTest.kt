package com.dawnyew.protocol

import com.dawnyew.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class FinancialBackupSchemaTest {
    private fun id(n:Int)="00000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    @Test fun signedOpeningAndNullableTargetAreStrictDecimalStrings() {
        val account=Account(id(2),id(1),"Cash",openingBalanceMinor=-99999999999,openingAt="2026-10-01T00:00:00")
        val state=LedgerState(id(9),ledgers=listOf(Ledger(id(1),"Ledger")),accounts=listOf(account),savingsPlans=listOf(SavingsPlan(id(3),id(1),"Flexible",SavingsScenario.FLEXIBLE)))
        val doc=BackupProtocol.create(state,"2026-10-24T04:00:00Z")
        val record=doc.records.getValue("accounts").single()
        assertEquals(JsonPrimitive("-99999999999"),record.getValue("openingBalanceMinor"))
        assertEquals(JsonNull,doc.records.getValue("savingsPlans").single().getValue("targetMinor"))
        assertEquals(state,BackupProtocol.restore(doc))
        for(bad in listOf(JsonPrimitive(-1),JsonPrimitive("-0"),JsonPrimitive("+1"),JsonPrimitive("01"),JsonPrimitive("9223372036854775808"),JsonNull)) {
            assertFails {BackupProtocol.validateRecord("accounts",JsonObject(record+("openingBalanceMinor" to bad)))}
        }
        assertFails {BackupProtocol.validateRecord("accounts",JsonObject(record+("unknown" to JsonPrimitive(true))))}
        assertFails {BackupProtocol.validateRecord("accounts",record,formatVersion=1)}
    }
    @Test fun legacyCountsArePreservedWhileCandidateCountsAddZeros() {
        val counts=BackupProtocol.entityTypes(1).associateWith {if(it=="entries") 10L else 0L}
        val original=counts.toMap();val candidate=BackupProtocol.expectedCandidateCounts(1,counts)
        assertEquals(original,counts);assertEquals(23,candidate.size);assertEquals(10L,candidate.getValue("entries"))
        BackupProtocol.entityTypes.drop(9).forEach {assertEquals(0L,candidate.getValue(it))}
        assertFails {BackupProtocol.expectedCandidateCounts(1,counts+("accounts" to 0L))}
        assertFails {BackupProtocol.expectedCandidateCounts(99,counts)}
    }
}
