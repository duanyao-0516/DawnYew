package com.dawnyew.ui

import androidx.compose.ui.text.TextRange
import kotlin.test.*

class EntryComposerStateTest {
    private fun keys(vararg keys: String): AmountEditResult {
        var value = AmountEditResult("", TextRange.Zero)
        keys.forEach { key -> value = editEntryAmount(value.text, value.selection, key); assertNull(value.error) }
        return value
    }

    @Test fun consecutiveDigitsBackspaceAndDecimalKeepTheWholeExpression() {
        assertEquals("17", keys("1", "7").text)
        assertEquals("12.34", keys("1", "7", "⌫", "2", ".", "3", "4").text)
        assertEquals("0.50", keys(".", "5", "=").text)
    }

    @Test fun reversedSelectionReplacementAndDeletionPreserveSurroundingText() {
        assertEquals(AmountEditResult("15.34", TextRange(2)), editEntryAmount("12.34", TextRange(2, 1), "5"))
        assertEquals(AmountEditResult("1.34", TextRange(1)), editEntryAmount("12.34", TextRange(1, 2), "⌫"))
        assertEquals(AmountEditResult("12.34", TextRange.Zero), editEntryAmount("12.34", TextRange.Zero, "⌫"))
    }

    @Test fun operatorsUseExactMoneyRoundingAndRetainInvalidInputForCorrection() {
        assertEquals("7.00", keys("3", "+", "4", "=").text)
        assertEquals("0.13", keys("1", "÷", "8", "=").text)
        assertEquals("6.00", keys("9", "−", "3", "=").text)
        listOf("", "1/0", "1..2", "1-2", "(2+3", "1.234").forEach { expression ->
            val result = editEntryAmount(expression, TextRange(expression.length), "=")
            assertEquals(expression, result.text)
            assertNotNull(result.error, expression)
        }
    }

    @Test fun rejectedLengthAndEmptyBackspaceAreNotTextChanges() {
        val full = "1+".repeat(48)
        val rejected = editEntryAmount(full, TextRange(full.length), "2")
        assertEquals(full, rejected.text)
        assertNotNull(rejected.error)
        assertEquals(AmountEditResult("", TextRange.Zero), editEntryAmount("", TextRange.Zero, "⌫"))
    }

    @Test fun inputRequestsKeepSelectionsAndNewDraftStateStartsClean() {
        val state = EntryComposerState(EntryInputMode.CALCULATOR, 5, 6)
        state.noteSelection = TextRange(1, 3)
        state.requestInput(EntryInputMode.NOTE)
        val firstRequest = state.inputRequest
        state.requestInput(EntryInputMode.NOTE)
        assertTrue(state.inputRequest > firstRequest)
        state.requestInput(EntryInputMode.CALCULATOR)
        assertEquals(TextRange(1, 3), state.noteSelection)
        state.openDialog(EntryComposerDialog.Date)
        assertEquals(EntryInputMode.NONE, state.inputMode)
        val next = EntryComposerState(EntryInputMode.CALCULATOR, 0, 0)
        assertNull(next.dialog)
        assertEquals(TextRange.Zero, next.amountSelection)
        assertNull(next.noteComposition)
    }
}
