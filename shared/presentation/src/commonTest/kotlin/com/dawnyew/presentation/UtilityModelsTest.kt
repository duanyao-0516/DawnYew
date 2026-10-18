package com.dawnyew.presentation

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class UtilityModelsTest {
    @Test fun rgbInputAcceptsChannelBoundsAndRejectsMalformedOrOutOfRangeValues() {
        assertEquals(RgbColor(0, 255, 17), parseRgbColor(" 0 ", "255", "017"))
        for (invalid in listOf("", " ", "-1", "256", "+1", "1.2", "1e2", "999999999999")) {
            assertNull(parseRgbColor(invalid, "0", "0"), invalid)
            assertNull(parseRgbColor("0", invalid, "0"), invalid)
            assertNull(parseRgbColor("0", "0", invalid), invalid)
        }
    }

    @Test fun hexInputExpandsShortFormAndFormatsCanonicalUppercase() {
        assertEquals(RgbColor(170, 187, 204), parseHexColor(" #aBc "))
        assertEquals(RgbColor(0, 106, 96), parseHexColor("#006a60"))
        assertEquals("#006A60", RgbColor(0, 106, 96).hex)
        for (invalid in listOf("", "006a60", "#12", "#1234", "#12345", "#12345678", "#ggg", "#12 345")) {
            assertNull(parseHexColor(invalid), invalid)
        }
    }

    @Test fun hsvMatchesPrimaryColorsAndAchromaticEndpoints() {
        assertEquals(RgbColor(255, 0, 0), HsvColor(0f, 1f, 1f).toRgb())
        assertEquals(RgbColor(0, 255, 0), HsvColor(120f, 1f, 1f).toRgb())
        assertEquals(RgbColor(0, 0, 255), HsvColor(240f, 1f, 1f).toRgb())
        assertEquals(RgbColor(255, 0, 0), HsvColor(360f, 1f, 1f).toRgb())
        assertEquals(RgbColor(0, 0, 0), HsvColor(17f, 0.8f, 0f).toRgb())
        assertEquals(RgbColor(255, 255, 255), HsvColor(271f, 0f, 1f).toRgb())
        assertEquals(HsvColor(0f, 0f, 0f), RgbColor(0, 0, 0).toHsv())
        assertEquals(HsvColor(0f, 0f, 1f), RgbColor(255, 255, 255).toHsv())
        val cyan = RgbColor(64, 128, 128).toHsv()
        assertEquals(180f, cyan.hue)
        assertEquals(0.5f, cyan.saturation)
        assertTrue(abs(cyan.brightness - 128f / 255f) < 0.0001f)
    }

    @Test fun rgbHsvRoundTripPreservesBoundaryAndInteriorChannels() {
        val channels = listOf(0, 1, 63, 127, 128, 254, 255)
        for (r in channels) for (g in channels) for (b in channels) {
            val color = RgbColor(r, g, b)
            assertEquals(color, color.toHsv().toRgb(), color.hex)
        }
        assertFailsWith<IllegalArgumentException> { RgbColor(256, 0, 0) }
        assertFailsWith<IllegalArgumentException> { HsvColor(Float.NaN, 1f, 1f) }
        assertFailsWith<IllegalArgumentException> { HsvColor(0f, -0.1f, 1f) }
        assertFailsWith<IllegalArgumentException> { HsvColor(0f, 1f, 1.1f) }
    }

    @Test fun decisionsRequireTwoToTwentyDistinctTrimmedNonemptyChoices() {
        assertNotNull(decisionOptionsError(listOf("一个")))
        assertNotNull(decisionOptionsError(List(21) { "选项 $it" }))
        assertNotNull(decisionOptionsError(listOf("A", "  ")))
        assertNotNull(decisionOptionsError(listOf(" A ", "A")))
        assertNull(decisionOptionsError(listOf(" A ", "B")))
        assertNull(decisionOptionsError(List(20) { "选项 $it" }))
        assertFailsWith<IllegalArgumentException> { chooseDecision(listOf("A", "A")) }
    }

    @Test fun decisionDrawCanReachEachChoiceAndUsesOneBoundedDraw() {
        val options = List(20) { "  选项 $it  " }
        for (index in options.indices) {
            val random = BoundedRandom(index)
            assertEquals("选项 $index", chooseDecision(options, random))
            assertEquals(listOf(20), random.bounds)
        }
        assertEquals("末项", chooseDecision(listOf("首项", "末项"), BoundedRandom(1)))
    }

    @Test fun decisionChoiceLengthAcceptsTwoHundredAndRejectsLongerInput() {
        assertNull(decisionOptionsError(listOf("甲".repeat(200), "乙")))
        assertNotNull(decisionOptionsError(listOf("甲".repeat(201), "乙")))
        assertFailsWith<IllegalArgumentException> { chooseDecision(listOf("甲".repeat(201), "乙")) }
    }

    @Test fun rockPaperScissorsCoversEveryOrderedPairFromFirstPlayersPerspective() {
        val expected = listOf(
            Triple(Hand.ROCK, Hand.ROCK, RpsOutcome.DRAW),
            Triple(Hand.ROCK, Hand.SCISSORS, RpsOutcome.WIN),
            Triple(Hand.ROCK, Hand.PAPER, RpsOutcome.LOSE),
            Triple(Hand.SCISSORS, Hand.ROCK, RpsOutcome.LOSE),
            Triple(Hand.SCISSORS, Hand.SCISSORS, RpsOutcome.DRAW),
            Triple(Hand.SCISSORS, Hand.PAPER, RpsOutcome.WIN),
            Triple(Hand.PAPER, Hand.ROCK, RpsOutcome.WIN),
            Triple(Hand.PAPER, Hand.SCISSORS, RpsOutcome.LOSE),
            Triple(Hand.PAPER, Hand.PAPER, RpsOutcome.DRAW),
        )
        for ((first, second, outcome) in expected) assertEquals(outcome, judgeRps(first, second))
    }

    @Test fun randomHandsCanReachAllThreeOutcomesWithThreeAsExclusiveBound() {
        val hands = (0..2).map { index ->
            val random = BoundedRandom(index)
            randomHand(random).also { assertEquals(listOf(3), random.bounds) }
        }
        assertEquals(Hand.entries.toSet(), hands.toSet())
    }

    private class BoundedRandom(private val index: Int) : Random() {
        val bounds = mutableListOf<Int>()
        override fun nextBits(bitCount: Int): Int = error("Expected a single bounded nextInt draw")
        override fun nextInt(until: Int): Int {
            bounds += until
            require(index in 0 until until)
            return index
        }
    }
}
