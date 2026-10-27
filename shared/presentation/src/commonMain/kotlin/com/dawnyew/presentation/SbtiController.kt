package com.dawnyew.presentation

import com.dawnyew.core.personality.SbtiCatalog
import com.dawnyew.core.personality.SbtiQuestion
import com.dawnyew.core.personality.SbtiResult
import com.dawnyew.core.personality.SbtiScoring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

enum class SbtiStage { INTRO, QUESTIONS, RESULT }

data class SbtiUiState(
    val visible: Boolean = false,
    val generation: Long = 0,
    val stage: SbtiStage = SbtiStage.INTRO,
    val hasAttempt: Boolean = false,
    val questions: List<SbtiQuestion> = emptyList(),
    val questionIndex: Int = 0,
    val answers: Map<String, Int> = emptyMap(),
    val result: SbtiResult? = null,
    val message: String? = null,
)

/** One process-only attempt. Base order is generated only by explicit start/restart. */
class SbtiController(
    currentDataScope: () -> PersonalityDataScope?,
    private val random: Random = Random.Default,
) : PersonalityTestAccess {
    private val mutableState = MutableStateFlow(SbtiUiState())
    val state: StateFlow<SbtiUiState> = mutableState.asStateFlow()
    private var stage = SbtiStage.INTRO
    private var hasAttempt = false
    private var baseQuestions: List<SbtiQuestion> = emptyList()
    private var currentQuestionId: String? = null
    private var answers: Map<String, Int> = emptyMap()
    private var result: SbtiResult? = null
    private var message: String? = null
    private val access = PersonalityAccessSession(currentDataScope, ::resetAttempt, { message = null }, ::publish)

    override fun updateAccess(ready: Boolean, sessionEpoch: Long) = access.updateAccess(ready, sessionEpoch)
    override fun suspendAccess() = access.suspendAccess()
    override fun synchronizeDataScope() = access.synchronizeDataScope()

    fun openIntro(token: Long) {
        if (access.accept(token)) navigate(SbtiStage.INTRO)
    }

    fun start(token: Long) {
        if (!access.accept(token)) return
        resetAttempt()
        val regular = SbtiCatalog.questions.shuffled(random).toMutableList()
        regular.add(random.nextInt(1, regular.size + 1), SbtiCatalog.hobbyQuestion)
        baseQuestions = regular.toList()
        currentQuestionId = baseQuestions.first().id
        hasAttempt = true
        navigate(SbtiStage.QUESTIONS)
    }

    fun resume(token: Long) {
        if (access.accept(token) && hasAttempt) navigate(if (result != null) SbtiStage.RESULT else SbtiStage.QUESTIONS)
    }

    fun answer(token: Long, questionId: String, value: Int) {
        if (!access.accept(token) || !hasAttempt || stage != SbtiStage.QUESTIONS || questionId != currentQuestionId) return
        val question = visibleQuestions().firstOrNull { it.id == questionId } ?: return
        if (question.options.none { it.value == value } || answers[questionId] == value) return
        val previouslyExpanded = answers[SbtiCatalog.hobbyQuestion.id] == 3
        answers = answers + (questionId to value)
        if (questionId == SbtiCatalog.hobbyQuestion.id && value != 3) answers = answers - SbtiCatalog.drinkQuestion.id
        if (previouslyExpanded != (answers[SbtiCatalog.hobbyQuestion.id] == 3)) access.invalidateCallbacks()
        result = null
        message = null
        publish()
    }

    fun openQuestion(token: Long, questionId: String) {
        if (!access.accept(token) || !hasAttempt || visibleQuestions().none { it.id == questionId }) return
        currentQuestionId = questionId
        navigate(SbtiStage.QUESTIONS)
    }

    fun next(token: Long) {
        if (!access.accept(token) || !hasAttempt || stage != SbtiStage.QUESTIONS) return
        if (currentQuestionId !in answers) {
            message = "请选择当前题的答案"
            publish()
            return
        }
        val questions = visibleQuestions()
        val index = questions.indexOfFirst { it.id == currentQuestionId }
        if (index == questions.lastIndex) submit(token)
        else {
            currentQuestionId = questions[index + 1].id
            navigate(SbtiStage.QUESTIONS)
        }
    }

    fun submit(token: Long) {
        if (!access.accept(token) || !hasAttempt || stage != SbtiStage.QUESTIONS) return
        val missing = visibleQuestions().filter { it.id !in answers }
        if (missing.isNotEmpty()) {
            currentQuestionId = missing.first().id
            navigate(SbtiStage.QUESTIONS)
            message = "还有 ${missing.size} 题未作答，已定位第一道未答题"
            publish()
            return
        }
        result = SbtiScoring.score(answers)
        navigate(SbtiStage.RESULT)
    }

    fun back(token: Long): Boolean {
        if (!access.accept(token)) return true
        when (stage) {
            SbtiStage.INTRO -> return false
            SbtiStage.RESULT -> navigate(SbtiStage.INTRO)
            SbtiStage.QUESTIONS -> {
                val questions = visibleQuestions()
                val index = questions.indexOfFirst { it.id == currentQuestionId }
                if (index <= 0) navigate(SbtiStage.INTRO)
                else {
                    currentQuestionId = questions[index - 1].id
                    navigate(SbtiStage.QUESTIONS)
                }
            }
        }
        return true
    }

    fun clear(token: Long) {
        if (!access.accept(token)) return
        resetAttempt()
        access.invalidateCallbacks()
        publish()
    }

    private fun visibleQuestions(): List<SbtiQuestion> {
        if (answers[SbtiCatalog.hobbyQuestion.id] != 3) return baseQuestions
        val index = baseQuestions.indexOfFirst { it.id == SbtiCatalog.hobbyQuestion.id }
        return baseQuestions.toMutableList().apply { add(index + 1, SbtiCatalog.drinkQuestion) }
    }

    private fun navigate(destination: SbtiStage) {
        stage = destination
        message = null
        access.invalidateCallbacks()
        publish()
    }

    private fun resetAttempt() {
        stage = SbtiStage.INTRO
        hasAttempt = false
        baseQuestions = emptyList()
        currentQuestionId = null
        answers = emptyMap()
        result = null
        message = null
    }

    private fun publish() {
        mutableState.value = if (access.visible) {
            val questions = visibleQuestions()
            SbtiUiState(true, access.generation, stage, hasAttempt, questions,
                questions.indexOfFirst { it.id == currentQuestionId }.coerceAtLeast(0), answers.toMap(), result, message)
        } else SbtiUiState(generation = access.generation)
    }
}
