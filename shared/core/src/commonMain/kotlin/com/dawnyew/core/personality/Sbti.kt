package com.dawnyew.core.personality

import kotlin.math.abs
import kotlin.math.roundToInt

data class SbtiOption(val label: String, val value: Int)

data class SbtiQuestion(
    val id: String,
    val text: String,
    val dimensionId: String?,
    val options: List<SbtiOption>,
)

data class SbtiType(
    val code: String,
    val name: String,
    val intro: String,
    val description: String,
    val imageFile: String,
)

enum class SbtiResultMode { NORMAL, DRUNK, FALLBACK }

data class SbtiMatch(val type: SbtiType, val distance: Int, val exact: Int, val similarity: Int)

data class SbtiDimensionScore(
    val id: String,
    val name: String,
    val model: String,
    val score: Int,
    val level: String,
    val explanation: String,
)

data class SbtiResult(
    val type: SbtiType,
    val mode: SbtiResultMode,
    val dimensions: List<SbtiDimensionScore>,
    val ranked: List<SbtiMatch>,
)

internal data class SbtiDimension(
    val id: String,
    val name: String,
    val model: String,
    val explanations: Map<String, String>,
)

internal data class SbtiTemplate(val code: String, val pattern: String)

object SbtiScoring {
    /** Scores the complete visible questionnaire using the pinned original algorithm. */
    fun score(answers: Map<String, Int>): SbtiResult {
        val visibleQuestions = SbtiCatalog.questions + SbtiCatalog.hobbyQuestion +
            if (answers[SbtiCatalog.hobbyQuestion.id] == 3) listOf(SbtiCatalog.drinkQuestion) else emptyList()
        require(answers.keys == visibleQuestions.map { it.id }.toSet()) {
            "Exactly one answer is required for each currently visible question"
        }
        require(visibleQuestions.all { question -> question.options.any { it.value == answers[question.id] } }) {
            "Every answer must use a value from its question's options"
        }

        val dimensions = SbtiCatalog.dimensions.map { dimension ->
            val score = SbtiCatalog.questions.filter { it.dimensionId == dimension.id }
                .sumOf { answers.getValue(it.id) }
            val level = when (score) {
                in 2..3 -> "L"
                4 -> "M"
                else -> "H"
            }
            SbtiDimensionScore(
                id = dimension.id,
                name = dimension.name,
                model = dimension.model,
                score = score,
                level = level,
                explanation = dimension.explanations.getValue(level),
            )
        }
        val userVector = dimensions.map { levelNumber(it.level.single()) }
        val types = SbtiCatalog.types.associateBy { it.code }
        val ranked = SbtiCatalog.normalTemplates.mapIndexed { index, template ->
            val vector = template.pattern.filter { it != '-' }.map(::levelNumber)
            val differences = userVector.zip(vector) { user, expected -> abs(user - expected) }
            val distance = differences.sum()
            index to SbtiMatch(
                type = types.getValue(template.code),
                distance = distance,
                exact = differences.count { it == 0 },
                // Math.round in the original JS rounds positive half values upwards.
                similarity = ((1.0 - distance / 30.0) * 100).roundToInt().coerceAtLeast(0),
            )
        }.sortedWith(
            compareBy<Pair<Int, SbtiMatch>> { it.second.distance }
                .thenByDescending { it.second.exact }
                .thenByDescending { it.second.similarity }
                // Preserve the source array's order for complete ties on every platform.
                .thenBy { it.first },
        ).map { it.second }

        val bestNormal = ranked.first()
        val mode = when {
            answers[SbtiCatalog.drinkQuestion.id] == 2 -> SbtiResultMode.DRUNK
            bestNormal.similarity < 60 -> SbtiResultMode.FALLBACK
            else -> SbtiResultMode.NORMAL
        }
        val type = when (mode) {
            SbtiResultMode.DRUNK -> types.getValue("DRUNK")
            SbtiResultMode.FALLBACK -> types.getValue("HHHH")
            SbtiResultMode.NORMAL -> bestNormal.type
        }
        return SbtiResult(type = type, mode = mode, dimensions = dimensions, ranked = ranked)
    }

    private fun levelNumber(level: Char): Int = when (level) {
        'L' -> 1
        'M' -> 2
        'H' -> 3
        else -> error("Unknown SBTI level: $level")
    }
}
