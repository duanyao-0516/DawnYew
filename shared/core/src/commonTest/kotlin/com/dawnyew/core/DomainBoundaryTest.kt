package com.dawnyew.core

import kotlin.test.*

class DomainBoundaryTest {
    private var n=0
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++n).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-18T12:00:00"
        override fun utcNow()="2026-10-18T04:00:00.123456Z"
        override fun normalize(value:String)=value.trim()
    }
    @Test fun overflowingRevisionOrVersionCannotWrapIntoValidData() {
        val engine=DomainEngine(env);val s=engine.initialState();val l=s.ledgers.single()
        assertFailsWith<DomainException> { engine.apply(s.copy(ledgers=listOf(l.copy(revision=Long.MAX_VALUE))),Command.UpdateLedger(l.id,l.name,false,0),env.id()) }
        assertFailsWith<DomainException> { engine.apply(s.copy(contentVersion=Long.MAX_VALUE),Command.CreateLedger("New"),env.id()) }
    }
    @Test fun canonicalCalendarBoundariesRejectOutsideSupportedYears() {
        assertEquals("9999-12-01" to "9999-12-31",monthRange("9999-12"))
        assertFailsWith<DomainException> { shiftMonth("9999-12",1) }
        assertFailsWith<DomainException> { shiftMonth("0001-01",-1) }
        assertFailsWith<DomainException> { monthRange("0000-01") }
        assertFailsWith<DomainException> { Statistics.calculate(DomainEngine(env).initialState(),QueryScope(startDate="0000-01-01")) }
    }
    @Test fun receiptAndHistoryTimestampsAreValidatedWithoutUsingCurrentClock() {
        validateUtc(env.utcNow())
        assertFailsWith<DomainException> { validateUtc("2026-10-18T04:00:60Z") }
        assertFailsWith<DomainException> { validateUtc("2026-10-18T04:00:00+00:00") }
        val engine=DomainEngine(env);val s=engine.apply(engine.initialState(),Command.CreateLedger("New"),env.id()).state
        assertFailsWith<DomainException> { validateState(s.copy(receipts=s.receipts+s.receipts)) }
    }
    @Test fun nonemptyDeletionGroupMustContainItsRootAndOnlyItsRefunds() {
        val engine=DomainEngine(env);val s=engine.initialState();val l=s.ledgers.first();val c=s.categories.first()
        val root=Entry(env.id(),l.id,EntryKind.EXPENSE,100,c.id,"2026-10-17T00:00:00",createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id())
        val other=root.copy(id=env.id(),creationOperationId=env.id())
        val group=DeletionGroup(env.id(),env.utcNow(),root.id)
        val valid=s.copy(entries=listOf(root.copy(deletionGroupId=group.id)),deletionGroups=listOf(group))
        validateState(valid)
        assertFailsWith<DomainException> { validateState(valid.copy(deletionGroups=listOf(group.copy(rootEntryId=env.id())))) }
        assertFailsWith<DomainException> { validateState(valid.copy(entries=valid.entries+other.copy(deletionGroupId=group.id))) }
        validateState(s.copy(deletionGroups=listOf(group)))
    }

}
