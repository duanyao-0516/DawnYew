package com.dawnyew.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VisibleOrderTest {
    @Test fun hiddenAndArchivedRecordsKeepTheirSlotsWhenVisibleItemsMove() {
        assertEquals(
            listOf("visible-c", "hidden", "visible-a", "archived", "visible-b"),
            mergeVisibleOrder(listOf("visible-a", "hidden", "visible-b", "archived", "visible-c"), listOf("visible-c", "visible-a", "visible-b")),
        )
    }

    @Test fun recordsFromAnotherScopeCannotBeMergedIntoThisScope() {
        assertFailsWith<IllegalArgumentException> { mergeVisibleOrder(listOf("a", "b"), listOf("b", "other-ledger")) }
        assertFailsWith<IllegalArgumentException> { mergeVisibleOrder(listOf("a", "b"), listOf("b", "b")) }
    }
}
