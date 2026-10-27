package com.dawnyew.core.personality

import com.dawnyew.core.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SbtiTest {
    private fun answers(value: Int = 2): Map<String, Int> =
        (1..30).associate { "q$it" to value } + ("drink_gate_q1" to 1)

    private fun recordedAnswers(line: String): Map<String, Int> {
        val encoded = line.split(';')[1]
        return encoded.take(30).mapIndexed { index, c -> "q${index + 1}" to c.digitToInt() }.toMap() +
            ("drink_gate_q1" to encoded[30].digitToInt()) +
            if (encoded.length == 32) mapOf("drink_gate_q2" to encoded[31].digitToInt()) else emptyMap()
    }

    private fun case(name: String): SbtiResult = SbtiScoring.score(
        recordedAnswers(SbtiSourceOracle.cases.single { it.startsWith("$name;") }),
    )

    @Test
    fun catalogPreservesAllQuestionsOptionsResultTextDimensionsAndTemplates() {
        assertEquals((1..30).map { "q$it" }, SbtiCatalog.questions.map { it.id })
        assertEquals(27, SbtiCatalog.types.size)
        assertEquals(27, SbtiCatalog.types.map { it.code }.toSet().size)
        assertEquals(15, SbtiCatalog.dimensions.size)
        assertEquals(25, SbtiCatalog.normalTemplates.size)
        assertEquals(45, SbtiCatalog.dimensions.sumOf { it.explanations.size })
        val allQuestions = SbtiCatalog.questions + SbtiCatalog.hobbyQuestion + SbtiCatalog.drinkQuestion
        val canonical = buildList {
            allQuestions.forEach { q ->
                add(listOf(q.id, q.text, q.dimensionId.orEmpty()).plus(q.options.flatMap { listOf(it.label, it.value.toString()) }).joinToString("\t"))
            }
            SbtiCatalog.types.forEach { add(listOf(it.code, it.name, it.intro, it.description, it.imageFile).joinToString("\t")) }
            SbtiCatalog.dimensions.forEach { d ->
                add((listOf(d.id, d.name, d.model) + listOf("L", "M", "H").map { d.explanations.getValue(it) }).joinToString("\t"))
            }
            SbtiCatalog.normalTemplates.forEach { add("${it.code}\t${it.pattern}") }
        }.joinToString("\n")
        assertEquals(SbtiSourceOracle.catalogSha256, Sha256.hex(canonical.encodeToByteArray()))
        assertEquals("6c24c9f2a4d3548010af44c9e1322c206f64f73d", SbtiCatalog.SOURCE_COMMIT)
        assertEquals("bfc7bbe66ae9a361220b2816651250b8769b0158c6aab8da882e6f8b2f22cea5", SbtiCatalog.SOURCE_SHA256)
        assertTrue(SbtiCatalog.SOURCE_URL.contains(SbtiCatalog.SOURCE_COMMIT))
    }

    @Test
    fun everyRecordedSourceCaseMatchesAllFifteenScoresAndFullStableRanking() {
        assertEquals(294, SbtiSourceOracle.cases.size)
        for (line in SbtiSourceOracle.cases) {
            val fields = line.split(';')
            val result = SbtiScoring.score(recordedAnswers(line))
            assertEquals(fields[2], result.type.code, fields[0])
            assertEquals(fields[3], result.mode.name, fields[0])
            assertEquals(fields[4], result.dimensions.joinToString("") { it.score.toString() }, fields[0])
            assertEquals(fields[5], result.dimensions.joinToString("") { it.level }, fields[0])
            assertEquals(fields[6], result.ranked.joinToString("|") { "${it.type.code},${it.distance},${it.exact},${it.similarity}" }, fields[0])
        }
    }

    @Test
    fun reversedOptionsKeepSourceValueRatherThanVisiblePosition() {
        for (id in listOf("q14", "q27")) {
            val question = SbtiCatalog.questions.single { it.id == id }
            assertEquals(listOf(3, 2, 1), question.options.map { it.value })
            val result = SbtiScoring.score(answers(1) + (id to question.options.first().value))
            assertEquals(4, result.dimensions.single { it.id == question.dimensionId }.score)
        }
    }

    @Test
    fun allDimensionScoreBoundariesAndExplanationsArePreserved() {
        for ((first, second, level) in listOf(Triple(1, 1, "L"), Triple(1, 2, "L"), Triple(1, 3, "M"), Triple(2, 3, "H"), Triple(3, 3, "H"))) {
            val input = (1..30).associate { "q$it" to if (it % 2 == 1) first else second } + ("drink_gate_q1" to 1)
            val result = SbtiScoring.score(input)
            assertEquals(15, result.dimensions.size)
            result.dimensions.forEachIndexed { i, actual ->
                val expected = SbtiCatalog.dimensions[i]
                assertEquals(first + second, actual.score)
                assertEquals(level, actual.level)
                assertEquals(expected.id, actual.id)
                assertEquals(expected.name, actual.name)
                assertEquals(expected.model, actual.model)
                assertEquals(expected.explanations.getValue(level), actual.explanation)
            }
        }
    }

    @Test
    fun everyNormalTemplateIncludingImsbAndWocRemainsOrdinaryAndExact() {
        val results = SbtiSourceOracle.cases.filter { it.startsWith("exact-template-") }.map { line ->
            val result = SbtiScoring.score(recordedAnswers(line))
            assertEquals(line.substringBefore(';').removePrefix("exact-template-"), result.type.code)
            assertEquals(SbtiResultMode.NORMAL, result.mode)
            assertEquals(0, result.ranked.first().distance)
            assertEquals(15, result.ranked.first().exact)
            assertEquals(100, result.ranked.first().similarity)
            result.type.code
        }
        assertEquals(25, results.toSet().size)
        assertTrue("IMSB" in results && "WOC!" in results)
    }

    @Test
    fun drunkOverridesNormalAndFallbackButSixtyPercentRemainsNormal() {
        assertEquals("DRUNK", case("DRUNK-overrides-normal").type.code)
        assertEquals(SbtiResultMode.DRUNK, case("DRUNK-overrides-fallback").mode)
        assertEquals(SbtiResultMode.NORMAL, case("drink-without-DRUNK").mode)
        assertEquals("HHHH", case("fallback-below-60").type.code)
        assertEquals(57, case("fallback-below-60").ranked.first().similarity)
        assertEquals(SbtiResultMode.FALLBACK, case("fallback-below-60").mode)
        assertEquals(60, case("normal-at-60").ranked.first().similarity)
        assertEquals(SbtiResultMode.NORMAL, case("normal-at-60").mode)
    }

    @Test
    fun exactDimensionsBreakDistanceTieAndFullTiesKeepSourceTemplateOrder() {
        val exactTie = case("tie-distance-exact-decides").ranked.take(2)
        assertEquals(listOf("BOSS", "ATM-er"), exactTie.map { it.type.code })
        assertEquals(exactTie[0].distance, exactTie[1].distance)
        assertTrue(exactTie[0].exact > exactTie[1].exact)
        assertEquals(listOf("CTRL", "THAN-K"), case("tie-stable-library-order").ranked.take(2).map { it.type.code })
    }

    @Test
    fun missingUnknownAndHiddenQuestionAnswersAreRejected() {
        assertFailsWith<IllegalArgumentException> { SbtiScoring.score(emptyMap()) }
        for (id in answers().keys) {
            assertFailsWith<IllegalArgumentException>(id) { SbtiScoring.score(answers() - id) }
        }
        for (id in listOf("", "q0", "q31", "unknown", "drink_gate_q3")) {
            assertFailsWith<IllegalArgumentException>(id) { SbtiScoring.score(answers() + (id to 1)) }
            assertFailsWith<IllegalArgumentException>(id) { SbtiScoring.score((answers() - "q1") + (id to 1)) }
        }
        for (hobby in listOf(1, 2, 4)) {
            for (drink in listOf(1, 2)) {
                assertFailsWith<IllegalArgumentException> {
                    SbtiScoring.score(answers() + ("drink_gate_q1" to hobby) + ("drink_gate_q2" to drink))
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { SbtiScoring.score(answers() + ("drink_gate_q1" to 3)) }
    }

    @Test
    fun everyQuestionRejectsValuesOutsideItsActualOptions() {
        for (number in 1..30) {
            for (value in listOf(Int.MIN_VALUE, -1, 0, 4, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { SbtiScoring.score(answers() + ("q$number" to value)) }
            }
        }
        for (value in listOf(Int.MIN_VALUE, 0, 5, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SbtiScoring.score(answers() + ("drink_gate_q1" to value)) }
        }
        for (value in listOf(Int.MIN_VALUE, 0, 3, 4, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                SbtiScoring.score(answers() + ("drink_gate_q1" to 3) + ("drink_gate_q2" to value))
            }
        }
        // The hobby question really has a fourth option; it is complete and non-scoring.
        assertEquals(SbtiScoring.score(answers()), SbtiScoring.score(answers() + ("drink_gate_q1" to 4)))
    }
}
