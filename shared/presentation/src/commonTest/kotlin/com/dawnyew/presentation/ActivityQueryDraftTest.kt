package com.dawnyew.presentation

import com.dawnyew.core.*
import kotlin.test.*

class ActivityQueryDraftTest {
    @Test fun editingAndResetDoNotChangeAppliedQuery() {
        val applied = ActivityScope(ledgerIds = setOf("00000000-0000-4000-8000-000000000001"), startDate = "2026-10-01", endDate = "2026-10-31", keyword = "原备注", deleted = true, includeArchived = false)
        val draft = ActivityQueryDraft(applied).copy(start = "2026-09-01", minimum = "12.34", scope = applied.copy(keyword = "待提交"))
        assertEquals("2026-10-01", applied.startDate)
        assertEquals("原备注", applied.keyword)
        assertEquals(1234L, draft.validatedScope().minAmountMinor)
        assertEquals(ActivityScope(deleted = true, includeArchived = false), draft.reset().validatedScope())
        assertEquals(applied, ActivityQueryDraft(applied).validatedScope())
    }

    @Test fun unrelatedPredicatesSurviveSingleFilterEdit() {
        val scope = ActivityScope(weekdays = setOf(1, 7), accountUnassigned = true, projectUnassigned = true, merchantUnassigned = true, tagUnassigned = true, recorded = false, reconciled = true)
        val next = ActivityQueryDraft(scope).copy(minimum = "1", maximum = "9.99").validatedScope()
        assertEquals(scope.copy(minAmountMinor = 100, maxAmountMinor = 999), next)
    }

    @Test fun ledgerChangeClearsCrossLedgerDependencies() {
        val scope = ActivityScope(ledgerIds = setOf("00000000-0000-4000-8000-000000000002"), categoryIds = setOf("00000000-0000-4000-8000-000000000004"), accountIds = setOf("00000000-0000-4000-8000-000000000005"), projectIds = setOf("00000000-0000-4000-8000-000000000006"), merchantIds = setOf("00000000-0000-4000-8000-000000000007"), tagIds = setOf("00000000-0000-4000-8000-000000000008"), weekdays = setOf(2))
        val next = ActivityQueryDraft(scope).selectLedgers(setOf("00000000-0000-4000-8000-000000000003")).validatedScope()
        assertEquals(setOf("00000000-0000-4000-8000-000000000003"), next.ledgerIds)
        assertTrue((next.categoryIds + next.accountIds + next.projectIds + next.merchantIds + next.tagIds).isEmpty())
        assertEquals(setOf(2), next.weekdays)
        val unassigned = ActivityQueryDraft(ActivityScope(accountUnassigned = true, projectUnassigned = true, merchantUnassigned = true, tagUnassigned = true)).selectLedgers(emptySet()).validatedScope()
        assertFalse(unassigned.accountUnassigned || unassigned.projectUnassigned || unassigned.merchantUnassigned || unassigned.tagUnassigned)
    }

    @Test fun zeroBoundsRoundTripWithoutThePositiveTransactionAmountRule() {
        val scope = ActivityScope(minAmountMinor = 0, maxAmountMinor = 0)
        assertEquals(scope, ActivityQueryDraft(scope).validatedScope())
        assertEquals(0L, ActivityQueryDraft(ActivityScope()).copy(minimum = "0").validatedScope().minAmountMinor)
        assertFailsWith<DomainException> { ActivityQueryDraft(ActivityScope()).copy(minimum = "0.000").validatedScope() }
    }

    @Test fun blankBoundsAreUnlimitedAndInvalidRangesNeverApply() {
        assertEquals(ActivityScope(), ActivityQueryDraft(ActivityScope()).copy(start = " ", end = " ", minimum = " ", maximum = " ").validatedScope())
        assertFailsWith<DomainException> { ActivityQueryDraft(ActivityScope()).copy(start = "2026-10-31", end = "2026-10-01").validatedScope() }
        assertFailsWith<DomainException> { ActivityQueryDraft(ActivityScope()).copy(minimum = "20", maximum = "10").validatedScope() }
        assertFailsWith<DomainException> { ActivityQueryDraft(ActivityScope()).copy(minimum = "abc").validatedScope() }
    }
}
