package com.dawnyew.presentation

import com.dawnyew.core.DomainException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmountCalculatorTest {
    @Test fun literalAmountsKeepExistingPrecision() {
        assertEquals(1234L, AmountCalculator.evaluate("12.34"))
        assertEquals(99_999_999_999L, AmountCalculator.evaluate("999999999.99"))
    }

    @Test fun arithmeticHasUsualPrecedenceAndParentheses() {
        assertEquals(1400L, AmountCalculator.evaluate("2+3*4"))
        assertEquals(2000L, AmountCalculator.evaluate("(2+3)*4"))
        assertEquals(150L, AmountCalculator.evaluate("9÷(2×3)"))
        assertEquals(200L, AmountCalculator.evaluate("3 - -1 - 2"))
    }

    @Test fun decimalArithmeticRoundsOnlyTheFinalAmount() {
        assertEquals(30L, AmountCalculator.evaluate("0.1+0.2"))
        assertEquals(100L, AmountCalculator.evaluate("1/3*3"))
        assertEquals(33L, AmountCalculator.evaluate("1/3"))
        assertEquals(1L, AmountCalculator.evaluate("1/200"))
        assertEquals(25L, AmountCalculator.evaluate("1/8+1/8"))
    }

    @Test fun cancellationDoesNotOverflowAnOtherwiseValidResult() {
        assertEquals(100L, AmountCalculator.evaluate("999999999.99/999999999.99"))
        assertEquals(12L, AmountCalculator.evaluate("(999999999.99-999999999.87)"))
    }

    @Test fun malformedOrNonpositiveResultsCannotBecomeAnEntry() {
        for (text in listOf("", "2+", "(1+2", "2(3)", "1/0", "0", "-2", "0.001", "1e3", "NaN", "1,000", "999999999.99+1")) {
            assertFailsWith<DomainException>(text) { AmountCalculator.evaluate(text) }
        }
    }

    @Test fun expressionAndIntermediateGrowthAreBounded() {
        assertFailsWith<DomainException> { AmountCalculator.evaluate("1+".repeat(200)+"1") }
        assertFailsWith<DomainException> { AmountCalculator.evaluate("999999999.99*999999999.99*999999999.99") }
    }
}
