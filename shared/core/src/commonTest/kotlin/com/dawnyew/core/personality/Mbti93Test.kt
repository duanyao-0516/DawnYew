package com.dawnyew.core.personality

import com.dawnyew.core.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Mbti93Test {
    private fun allAnswers(value: Int = 3): Map<Int, Int> = (1..93).associateWith { value }

    private fun Mbti93Result.rawScores(): List<Int> = axes.flatMap { listOf(it.firstScore, it.secondScore) }

    @Test
    fun catalogContainsEveryVisibleSourceLabelAndOriginalPolarityInOrder() {
        val questions = Mbti93Catalog.questions
        assertEquals((1..93).toList(), questions.map { it.id })
        assertTrue(questions.all { it.prompt.isNotBlank() && it.optionA.isNotBlank() && it.optionB.isNotBlank() })
        val canonical = questions.joinToString("\n") {
            listOf(it.id, it.prompt, it.optionA, it.optionB, it.firstLetter.name).joinToString("\t")
        }
        assertEquals(Mbti93SourceOracle.catalogSha256, Sha256.hex(canonical.encodeToByteArray()))
        assertEquals("你更喜欢：", questions[26].prompt)
        assertEquals("你更喜欢：", questions[92].prompt)
        assertEquals("492f8e08a6c5ab3a68f60ad24618e3b96ddf1dde", Mbti93Catalog.SOURCE_COMMIT)
        assertTrue(Mbti93Catalog.SOURCE_URL.contains(Mbti93Catalog.SOURCE_COMMIT))
    }

    @Test
    fun everyQuestionAndEveryAnswerMatchesRecordedOriginalJavaScriptScores() {
        assertEquals(465, Mbti93SourceOracle.isolatedScores.size)
        for (question in 1..93) {
            for (answer in 1..5) {
                val result = Mbti93Scoring.score(allAnswers() + (question to answer))
                assertEquals(
                    Mbti93SourceOracle.isolatedScores[(question - 1) * 5 + answer - 1],
                    result.rawScores(),
                    "question=$question answer=$answer",
                )
                assertEquals(if (answer == 3) 93 else 92, result.neutralCount)
            }
        }
    }

    @Test
    fun completeGoldenAndMixedAnswersMatchOriginalJavaScriptIncludingAll16Types() {
        assertEquals(40, Mbti93SourceOracle.completeCases.size)
        val reachedTypes = mutableSetOf<String>()
        for (line in Mbti93SourceOracle.completeCases) {
            val fields = line.split(';')
            val answers = fields[0].mapIndexed { index, answer -> index + 1 to answer.digitToInt() }.toMap()
            val result = Mbti93Scoring.score(answers)
            assertEquals(fields[1], result.type, fields[0])
            assertEquals(fields[2], result.typeName)
            assertEquals(fields[3].split(',').map { it.toInt() }, result.rawScores())
            assertEquals(fields[4].toInt(), result.neutralCount)
            reachedTypes += result.type
        }
        assertEquals(16, reachedTypes.size)
    }

    @Test
    fun allNeutralAnswersAreCompleteButHaveNoPreferenceAndUseOriginalTieBreak() {
        val result = Mbti93Scoring.score(allAnswers())
        assertEquals("ESTJ", result.type)
        assertEquals("总经理型人格", result.typeName)
        assertEquals(93, result.neutralCount)
        assertEquals(listOf(MbtiLetter.E, MbtiLetter.S, MbtiLetter.T, MbtiLetter.J), result.axes.map { it.first })
        assertEquals(listOf(MbtiLetter.I, MbtiLetter.N, MbtiLetter.F, MbtiLetter.P), result.axes.map { it.second })
        assertEquals(List(8) { 0 }, result.rawScores())
        assertTrue(result.axes.all { it.isTie })
    }

    @Test
    fun reversePolarityPreservesSourceAsymmetricFirstAndSecondChoices() {
        // Q2 A is P. The source gives its leftmost choice 1 point and next choice 2.
        assertEquals(1, Mbti93Scoring.score(allAnswers() + (2 to 1)).axes[3].secondScore)
        assertEquals(2, Mbti93Scoring.score(allAnswers() + (2 to 2)).axes[3].secondScore)
        assertEquals(2, Mbti93Scoring.score(allAnswers() + (1 to 1)).axes[3].firstScore)
        assertEquals(1, Mbti93Scoring.score(allAnswers() + (1 to 2)).axes[3].firstScore)
    }

    @Test
    fun missingOrExtraOrOutOfRangeQuestionIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> { Mbti93Scoring.score(emptyMap()) }
        for (question in 1..93) {
            assertFailsWith<IllegalArgumentException> { Mbti93Scoring.score(allAnswers() - question) }
        }
        for (question in listOf(Int.MIN_VALUE, -1, 0, 94, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { Mbti93Scoring.score(allAnswers() + (question to 3)) }
            assertFailsWith<IllegalArgumentException> { Mbti93Scoring.score((allAnswers() - 1) + (question to 3)) }
        }
    }

    @Test
    fun everyOutOfRangeAnswerIsRejectedBeforeScoring() {
        for (question in 1..93) {
            for (answer in listOf(Int.MIN_VALUE, -1, 0, 6, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { Mbti93Scoring.score(allAnswers() + (question to answer)) }
            }
        }
    }

    @Test
    fun scoringDoesNotMutateAnswersAndIsIndependentOfMapInsertionOrder() {
        val original = allAnswers(2)
        val snapshot = original.toMap()
        val reversed = original.entries.reversed().associate { it.toPair() }
        assertEquals(Mbti93Scoring.score(original), Mbti93Scoring.score(reversed))
        assertEquals(snapshot, original)
    }
}
