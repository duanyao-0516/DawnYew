package com.dawnyew.core

import kotlin.test.*

class DomainTest {
    private var counter = 0
    private val env = object : DomainEnvironment {
        override fun id() = "00000000-0000-4000-8000-${(++counter).toString().padStart(12, '0')}"
        override fun localNow() = "2026-10-18T12:00:00"
        override fun utcNow() = "2026-10-18T04:00:00Z"
        override fun normalize(value: String) = value.trim()
    }
    private val engine = DomainEngine(env)
    private fun apply(s: LedgerState, c: Command, op: String = env.id()) = engine.apply(s, c, op).state
    private fun expense(s: LedgerState, amount: Long = 10000): LedgerState = apply(s, Command.SaveEntry(
        ledgerId = s.ledgers.first().id, kind = EntryKind.EXPENSE, amountMinor = amount,
        categoryId = s.categories.first { it.direction == Direction.EXPENSE }.id,
        occurrence = "2026-10-15T10:00:00", note = "lunch"))

    @Test fun exactMoneyRejectsRoundingAndOverflow() {
        assertEquals(1234, Money.parse("12.34"))
        assertEquals(1, Money.parse("0.01"))
        listOf("0", "-1", "1.001", "NaN", "1000000000", "1e2").forEach { assertFailsWith<DomainException> { Money.parse(it) } }
        assertEquals("12.34", Money.format(1234))
    }
    @Test fun receiptReplayIsIdempotentAndPayloadMismatchRejected() {
        val s = engine.initialState()
        val cmd = Command.CreateLedger("Travel")
        val first = engine.apply(s, cmd, "00000000-0000-4000-8000-900000000001")
        val second = engine.apply(first.state, cmd, "00000000-0000-4000-8000-900000000001")
        assertEquals(first.state, second.state)
        assertEquals(first.resultIds, second.resultIds)
        assertFailsWith<DomainException> { engine.apply(first.state, Command.CreateLedger("Other"), "00000000-0000-4000-8000-900000000001") }
    }
    @Test fun refundsCannotExceedExpenseAndUseTheirOwnPeriod() {
        var s = expense(engine.initialState())
        val e = s.entries.single()
        s = apply(s, Command.AddRefund(e.id, 7000, "2026-10-16T11:00:00", "partial"))
        assertFailsWith<DomainException> { apply(s, Command.AddRefund(e.id, 3001, "2026-10-17T11:00:00", "")) }
        val stats = Statistics.calculate(s, QueryScope(startDate = "2026-10-16", endDate = "2026-10-17"))
        assertEquals(-7000, stats.netExpense)
        assertEquals(7000, stats.refunds)
    }
    @Test fun expenseCannotShrinkBelowRefundOrChangeKind() {
        var s = expense(engine.initialState())
        val e = s.entries.single()
        s = apply(s, Command.AddRefund(e.id, 7000, "2026-10-16T11:00:00", ""))
        assertFailsWith<DomainException> { apply(s, Command.SaveEntry(e.id, e.ledgerId, e.kind, 6999, e.categoryId, e.occurrence, "", e.revision)) }
        val refund = s.entries.first { it.kind == EntryKind.REFUND }
        s = apply(s, Command.SoftDelete(refund.id))
        assertFailsWith<DomainException> { apply(s, Command.SaveEntry(e.id, e.ledgerId, EntryKind.INCOME, 10000, s.categories.first { it.direction == Direction.INCOME }.id, e.occurrence, "", e.revision)) }
    }
    @Test fun groupDeleteDoesNotResurrectPreviouslyDeletedRefund() {
        var s = expense(engine.initialState())
        val e = s.entries.single()
        s = apply(s, Command.AddRefund(e.id, 1000, "2026-10-16T11:00:00", "old"))
        val old = s.entries.last()
        s = apply(s, Command.SoftDelete(old.id))
        val oldGroup = s.entries.last().deletionGroupId
        s = apply(s, Command.AddRefund(e.id, 2000, "2026-10-17T11:00:00", "new"))
        s = apply(s, Command.SoftDelete(e.id))
        val group = s.entries.first().deletionGroupId!!
        assertNotEquals(oldGroup, group)
        s = apply(s, Command.RestoreGroup(group))
        assertEquals(oldGroup, s.entries.first { it.id == old.id }.deletionGroupId)
        assertEquals(2, s.entries.count { it.deletionGroupId == null })
    }
    @Test fun movingExpenseMovesActiveRefundsAndRestoredOldRefundInheritsParent() {
        var s = expense(engine.initialState())
        val e = s.entries.single()
        s = apply(s, Command.AddRefund(e.id, 1000, "2026-10-16T11:00:00", ""))
        val old = s.entries.last()
        s = apply(s, Command.SoftDelete(old.id))
        s = apply(s, Command.CreateLedger("Travel"))
        val target = s.ledgers.last()
        val cat = s.categories.first { it.ledgerId == target.id && it.direction == Direction.EXPENSE }
        s = apply(s, Command.MoveEntry(e.id, target.id, cat.id))
        s = apply(s, Command.RestoreGroup(s.entries.first { it.id == old.id }.deletionGroupId!!))
        assertTrue(s.entries.all { it.ledgerId == target.id && it.categoryId == cat.id })
    }
    @Test fun purgeParentRemovesLinkedDeletedRefunds() {
        var s = expense(engine.initialState())
        val e = s.entries.single()
        s = apply(s, Command.AddRefund(e.id, 1000, "2026-10-16T11:00:00", ""))
        s = apply(s, Command.SoftDelete(e.id))
        s = apply(s, Command.Purge(e.id))
        assertTrue(s.entries.isEmpty())
    }
    @Test fun lastLedgerCannotBeArchived() {
        val s = engine.initialState()
        assertFailsWith<DomainException> { apply(s, Command.UpdateLedger(s.ledgers.single().id, "Daily", true, 0)) }
    }
    @Test fun archivedLedgerRejectsWrites() {
        var s = engine.initialState()
        s = apply(s, Command.CreateLedger("Other"))
        val ledger = s.ledgers.first()
        s = apply(s, Command.UpdateLedger(ledger.id, ledger.name, true, 0))
        assertFailsWith<DomainException> { expense(s) }
    }
    @Test fun staleVersionRejectedWithoutPartialMutation() {
        val s = engine.initialState()
        assertFailsWith<DomainException> { engine.apply(s, Command.CreateLedger("New"), env.id(), s.contentVersion - 1) }
        assertEquals(1, s.ledgers.size)
    }
    @Test fun futureOccurrenceAndInvalidDateAreRejected() {
        val s = engine.initialState()
        val base = Command.SaveEntry(ledgerId=s.ledgers.first().id, kind=EntryKind.EXPENSE, amountMinor=1, categoryId=s.categories.first().id, occurrence="2026-10-19T00:00:00", note="")
        assertFailsWith<DomainException> { apply(s, base) }
        assertFailsWith<DomainException> { apply(s, base.copy(occurrence="2026-02-30T00:00:00")) }
    }
    @Test fun originalExpenseCategoryShareIgnoresRefundsAndBudgetFloorsAtZero() {
        var s = expense(engine.initialState())
        val e = s.entries.single()
        s = apply(s, Command.AddRefund(e.id, 10000, "2026-10-16T11:00:00", ""))
        val scope = QueryScope(setOf(e.ledgerId), "2026-10-01", "2026-10-31")
        val stats = Statistics.calculate(s, scope)
        assertEquals(10000, stats.categories.sumOf { it.amountMinor })
        assertEquals(0, stats.budgetUsed)
    }
}
