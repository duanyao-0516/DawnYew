package com.dawnyew.presentation

import com.dawnyew.core.personality.Mbti93Catalog
import com.dawnyew.core.personality.Mbti93Result
import com.dawnyew.core.personality.Mbti93Scoring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MbtiStage { INTRO, QUESTIONS, RESULT }

data class MbtiUiState(
    val visible: Boolean = false,
    val generation: Long = 0,
    val stage: MbtiStage = MbtiStage.INTRO,
    val hasAttempt: Boolean = false,
    val questionIndex: Int = 0,
    val answers: Map<Int, Int> = emptyMap(),
    val result: Mbti93Result? = null,
    val message: String? = null,
)

/** Main-thread, process-only state. Answers never enter a saved-state bundle or ledger command. */
class MbtiController(currentDataScope: () -> PersonalityDataScope?) : PersonalityTestAccess {
    private val mutableState = MutableStateFlow(MbtiUiState())
    val state: StateFlow<MbtiUiState> = mutableState.asStateFlow()
    private var stage = MbtiStage.INTRO
    private var hasAttempt = false
    private var questionIndex = 0
    private var answers: Map<Int, Int> = emptyMap()
    private var result: Mbti93Result? = null
    private var message: String? = null
    private val access = PersonalityAccessSession(currentDataScope, ::resetAttempt, { message = null }, ::publish)

    override fun updateAccess(ready: Boolean, sessionEpoch: Long) = access.updateAccess(ready, sessionEpoch)
    override fun suspendAccess() = access.suspendAccess()
    override fun synchronizeDataScope() = access.synchronizeDataScope()

    fun openIntro(token: Long) {
        if (!accept(token)) return
        navigate(MbtiStage.INTRO)
    }

    fun start(token: Long) {
        if (!accept(token)) return
        resetAttempt()
        hasAttempt = true
        navigate(MbtiStage.QUESTIONS)
    }

    fun resume(token: Long) {
        if (!accept(token) || !hasAttempt) return
        navigate(if (result != null) MbtiStage.RESULT else MbtiStage.QUESTIONS)
    }

    fun answer(token: Long, questionId: Int, value: Int) {
        if (!accept(token) || !hasAttempt || stage != MbtiStage.QUESTIONS ||
            questionId != Mbti93Catalog.questions[questionIndex].id || value !in 1..5) return
        if (answers[questionId] == value) return
        answers = answers + (questionId to value)
        result = null
        message = null
        publish()
    }

    fun openQuestion(token: Long, index: Int) {
        if (!accept(token) || !hasAttempt || index !in Mbti93Catalog.questions.indices) return
        questionIndex = index
        navigate(MbtiStage.QUESTIONS)
    }

    fun next(token: Long) {
        if (!accept(token) || !hasAttempt || stage != MbtiStage.QUESTIONS) return
        if (Mbti93Catalog.questions[questionIndex].id !in answers) {
            message = "请选择当前题的答案"
            publish()
            return
        }
        if (questionIndex == Mbti93Catalog.questions.lastIndex) submit(token)
        else {
            questionIndex++
            navigate(MbtiStage.QUESTIONS)
        }
    }

    fun submit(token: Long) {
        if (!accept(token) || !hasAttempt || stage != MbtiStage.QUESTIONS) return
        val missing = Mbti93Catalog.questions.indexOfFirst { it.id !in answers }
        if (missing >= 0) {
            questionIndex = missing
            navigate(MbtiStage.QUESTIONS)
            message = "还有 ${Mbti93Catalog.questions.size - answers.size} 题未作答，已定位第一道未答题"
            publish()
            return
        }
        result = Mbti93Scoring.score(answers)
        navigate(MbtiStage.RESULT)
    }

    /** Returns false only when the outer route should handle back. Stale callbacks are consumed. */
    fun back(token: Long): Boolean {
        if (!accept(token)) return true
        when (stage) {
            MbtiStage.INTRO -> return false
            MbtiStage.RESULT -> navigate(MbtiStage.INTRO)
            MbtiStage.QUESTIONS -> {
                if (questionIndex == 0) navigate(MbtiStage.INTRO)
                else {
                    questionIndex--
                    navigate(MbtiStage.QUESTIONS)
                }
            }
        }
        return true
    }

    fun clear(token: Long) {
        if (!accept(token)) return
        resetAttempt()
        access.invalidateCallbacks()
        publish()
    }

    private fun accept(token: Long): Boolean = access.accept(token)

    private fun navigate(destination: MbtiStage) {
        stage = destination
        message = null
        access.invalidateCallbacks()
        publish()
    }

    private fun resetAttempt() {
        stage = MbtiStage.INTRO
        hasAttempt = false
        questionIndex = 0
        answers = emptyMap()
        result = null
        message = null
    }

    private fun publish() {
        mutableState.value = if (access.visible) MbtiUiState(
            visible = true, generation = access.generation, stage = stage, hasAttempt = hasAttempt,
            questionIndex = questionIndex, answers = answers.toMap(), result = result, message = message,
        ) else MbtiUiState(generation = access.generation)
    }
}
