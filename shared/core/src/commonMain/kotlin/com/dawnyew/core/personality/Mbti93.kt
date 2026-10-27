package com.dawnyew.core.personality

enum class MbtiLetter(val label: String) {
    E("外倾"), I("内倾"), S("实感"), N("直觉"), T("思维"), F("情感"), J("判断"), P("知觉"),
}

data class Mbti93Question(
    val id: Int,
    val prompt: String,
    val optionA: String,
    val optionB: String,
    val firstLetter: MbtiLetter,
)

data class MbtiAxisScore(
    val first: MbtiLetter,
    val second: MbtiLetter,
    val firstScore: Int,
    val secondScore: Int,
) {
    val isTie: Boolean get() = firstScore == secondScore
}

data class Mbti93Result(
    val type: String,
    val typeName: String,
    val axes: List<MbtiAxisScore>,
    val neutralCount: Int,
)

object Mbti93Scoring {
    /**
     * Scores all 93 explicit answers using the pinned MyMBTI algorithm.
     * Neutral (3) is an answer worth zero points. Incomplete/invalid input is rejected.
     */
    fun score(answers: Map<Int, Int>): Mbti93Result {
        require(answers.size == 93 && answers.keys.all { it in 1..93 }) {
            "Exactly one answer is required for each question from 1 to 93"
        }
        require(answers.values.all { it in 1..5 }) { "Answers must be between 1 and 5" }

        val scores = IntArray(MbtiLetter.entries.size)
        for (question in Mbti93Catalog.questions) {
            val answer = answers.getValue(question.id)
            val firstIndex = question.firstLetter.ordinal
            when (answer) {
                1, 2 -> {
                    // Preserve the source's asymmetry: I/N/F/P give A +1 for 1,
                    // and A +2 for 2. Do not normalize this to the other polarity.
                    scores[firstIndex] += when (question.firstLetter) {
                        MbtiLetter.E, MbtiLetter.S, MbtiLetter.T, MbtiLetter.J -> 3 - answer
                        MbtiLetter.I, MbtiLetter.N, MbtiLetter.F, MbtiLetter.P -> answer
                    }
                }
                4, 5 -> scores[firstIndex xor 1] += answer - 3
            }
        }
        val axes = (0..6 step 2).map { firstIndex ->
            MbtiAxisScore(
                first = MbtiLetter.entries[firstIndex],
                second = MbtiLetter.entries[firstIndex + 1],
                firstScore = scores[firstIndex],
                secondScore = scores[firstIndex + 1],
            )
        }
        // Original showTotal8type uses >= on every axis, including an all-zero axis.
        val type = axes.joinToString("") {
            if (it.firstScore >= it.secondScore) it.first.name else it.second.name
        }
        return Mbti93Result(
            type = type,
            typeName = typeNames.getValue(type),
            axes = axes,
            neutralCount = answers.values.count { it == 3 },
        )
    }

    private val typeNames = mapOf(
        "ISTJ" to "物流师型人格",
        "ISFJ" to "守卫者型人格",
        "INFJ" to "提倡者型人格",
        "INTJ" to "建筑师型人格",
        "ISTP" to "鉴赏家型人格",
        "ISFP" to "探险家型人格",
        "INFP" to "调停者型人格",
        "INTP" to "逻辑学家型人格",
        "ESTP" to "企业家型人格",
        "ESFP" to "表演者型人格",
        "ENFP" to "竞选者型人格",
        "ENTP" to "辩论家型人格",
        "ESTJ" to "总经理型人格",
        "ESFJ" to "执政官型人格",
        "ENFJ" to "主人公型人格",
        "ENTJ" to "指挥官型人格",
    )
}
