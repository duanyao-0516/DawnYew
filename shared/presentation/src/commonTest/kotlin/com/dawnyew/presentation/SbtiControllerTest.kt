package com.dawnyew.presentation

import com.dawnyew.core.personality.SbtiResultMode
import kotlin.random.Random
import kotlin.test.*

class SbtiControllerTest {
    private class Fixture(seed: Int = 41) {
        var scope: PersonalityDataScope? = PersonalityDataScope("test-data", 1)
        val controller = SbtiController({ scope }, Random(seed))
        init { controller.updateAccess(true, 1) }
        val state get() = controller.state.value
        fun start() = controller.start(state.generation)
        fun choose(id: String, value: Int) {
            controller.openQuestion(state.generation, id)
            controller.answer(state.generation, id, value)
        }
        fun complete(value: Int = 1) {
            state.questions.map { it.id }.forEach { choose(it, value) }
            controller.submit(state.generation)
        }
    }

    @Test fun randomOrderIsCompleteAndStableUntilExplicitRestart() {
        for (seed in 0..50) {
            val f = Fixture(seed); f.start()
            val ids = f.state.questions.map { it.id }
            assertEquals(31, ids.size)
            assertEquals(31, ids.toSet().size)
            assertEquals((1..30).map { "q$it" }.toSet() + "drink_gate_q1", ids.toSet())
            assertTrue(ids.indexOf("drink_gate_q1") in 1..30)
            f.controller.suspendAccess(); f.controller.updateAccess(true, 2)
            f.controller.openIntro(f.state.generation); f.controller.resume(f.state.generation)
            assertEquals(ids, f.state.questions.map { it.id })
        }
    }

    @Test fun dynamicFollowUpIsAdjacentAndRemovingItDiscardsItsAnswer() {
        val f = Fixture(); f.start()
        f.choose("drink_gate_q1", 3)
        assertEquals(32, f.state.questions.size)
        val gate = f.state.questions.indexOfFirst { it.id == "drink_gate_q1" }
        assertEquals("drink_gate_q2", f.state.questions[gate + 1].id)
        f.choose("drink_gate_q2", 2)
        f.controller.openQuestion(f.state.generation, "drink_gate_q1")
        val old = f.state.generation
        f.controller.answer(old, "drink_gate_q1", 1)
        assertEquals(31, f.state.questions.size)
        assertFalse("drink_gate_q2" in f.state.answers)
        assertEquals("drink_gate_q1", f.state.questions[f.state.questionIndex].id)
        f.controller.next(old)
        assertEquals("drink_gate_q1", f.state.questions[f.state.questionIndex].id)
        f.choose("drink_gate_q1", 3)
        assertFalse("drink_gate_q2" in f.state.answers)
        f.controller.openQuestion(old, "drink_gate_q2")
        assertEquals("drink_gate_q1", f.state.questions[f.state.questionIndex].id)
    }

    @Test fun incompleteSubmitLocatesFirstVisibleMissingAnswer() {
        val f = Fixture(); f.start()
        val first = f.state.questions.first().id
        f.choose(first, 2)
        f.choose("drink_gate_q1", 3)
        val missing = f.state.questions.first { it.id !in f.state.answers }.id
        f.controller.submit(f.state.generation)
        assertEquals(missing, f.state.questions[f.state.questionIndex].id)
        assertEquals(SbtiStage.QUESTIONS, f.state.stage)
        assertNotNull(f.state.message)
        assertNull(f.state.result)
    }

    @Test fun sourceScoresUseValuesRatherThanOptionPositionsAndEditInvalidatesResult() {
        val f = Fixture(); f.start(); f.complete()
        assertEquals("DEAD", f.state.result?.type?.code)
        assertEquals(SbtiStage.RESULT, f.state.stage)
        f.controller.openQuestion(f.state.generation, "q14")
        assertNotNull(f.state.result)
        assertEquals(3, f.state.questions[f.state.questionIndex].options.first().value)
        f.controller.answer(f.state.generation, "q14", 1)
        assertNotNull(f.state.result)
        f.controller.answer(f.state.generation, "q14", 3)
        assertNull(f.state.result)
        f.controller.submit(f.state.generation)
        assertEquals(4, f.state.result?.dimensions?.single { it.id == "A1" }?.score)
    }

    @Test fun drunkRequiresTheVisibleFollowUpAndIsRemovedWhenGateChanges() {
        val f = Fixture(); f.start()
        f.state.questions.map { it.id }.forEach { f.choose(it, 1) }
        f.choose("drink_gate_q1", 3)
        f.controller.submit(f.state.generation)
        assertEquals("drink_gate_q2", f.state.questions[f.state.questionIndex].id)
        f.choose("drink_gate_q2", 2)
        f.controller.submit(f.state.generation)
        assertEquals(SbtiResultMode.DRUNK, f.state.result?.mode)
        f.choose("drink_gate_q1", 4)
        f.controller.submit(f.state.generation)
        assertEquals(SbtiResultMode.NORMAL, f.state.result?.mode)
        assertEquals("DEAD", f.state.result?.type?.code)
    }

    @Test fun invalidHiddenAndStaleActionsDoNotChangeAnswersOrNavigation() {
        val f = Fixture(); f.start()
        val id = f.state.questions.first().id
        val token = f.state.generation
        f.controller.answer(token, id, 4)
        f.controller.answer(token, "drink_gate_q2", 2)
        f.controller.openQuestion(token, "not-a-question")
        f.controller.openQuestion(token, "drink_gate_q2")
        assertTrue(f.state.answers.isEmpty())
        assertEquals(0, f.state.questionIndex)
        f.controller.answer(token, id, 1)
        f.controller.next(token); f.controller.next(token)
        assertEquals(1, f.state.questionIndex)
        f.controller.answer(token, id, 3)
        assertEquals(1, f.state.answers[id])
    }

    @Test fun pauseHidesProjectionButPreservesAttemptAndScopeChangeClearsIt() {
        val f = Fixture(); f.start()
        val id = f.state.questions.first().id
        f.choose(id, 2)
        val order = f.state.questions.map { it.id }
        val old = f.state.generation
        f.controller.suspendAccess()
        assertFalse(f.state.visible)
        assertTrue(f.state.answers.isEmpty())
        assertTrue(f.state.questions.isEmpty())
        assertNull(f.state.result)
        f.controller.updateAccess(true, 2)
        assertEquals(2, f.state.answers[id])
        assertEquals(order, f.state.questions.map { it.id })
        f.controller.answer(old, id, 3)
        assertEquals(2, f.state.answers[id])
        f.scope = PersonalityDataScope("test-data", 2)
        f.controller.answer(f.state.generation, id, 3)
        assertFalse(f.state.hasAttempt)
        assertTrue(f.state.answers.isEmpty())
        assertEquals(SbtiStage.INTRO, f.state.stage)
        f.start(); f.complete()
        f.scope = null; f.controller.synchronizeDataScope()
        assertFalse(f.state.visible)
        f.scope = PersonalityDataScope("test-data", 2)
        f.controller.updateAccess(true, 2)
        assertFalse(f.state.hasAttempt)
    }

    @Test fun backClearAndRestartHaveExplicitSessionSemantics() {
        val f = Fixture()
        assertFalse(f.controller.back(f.state.generation))
        f.start()
        assertTrue(f.controller.back(f.state.generation))
        assertEquals(SbtiStage.INTRO, f.state.stage)
        f.controller.resume(f.state.generation)
        f.complete()
        assertTrue(f.controller.back(f.state.generation))
        assertEquals(SbtiStage.INTRO, f.state.stage)
        f.controller.resume(f.state.generation)
        assertEquals(SbtiStage.RESULT, f.state.stage)
        f.start()
        assertTrue(f.state.answers.isEmpty())
        assertNull(f.state.result)
        val old = f.state.generation
        f.controller.clear(old); f.controller.start(old)
        assertFalse(f.state.hasAttempt)
    }

    @Test fun commonAccessAffectsBothTestsButActionsRemainIndependent() {
        val f = Fixture()
        val mbti = MbtiController { f.scope }
        val access = PersonalityTestsAccess(listOf(mbti, f.controller))
        access.updateAccess(true, 1)
        mbti.start(mbti.state.value.generation)
        mbti.answer(mbti.state.value.generation, 1, 3)
        val mbtiToken = mbti.state.value.generation
        f.start(); f.complete(); f.controller.clear(f.state.generation)
        assertEquals(mbtiToken, mbti.state.value.generation)
        assertEquals(3, mbti.state.value.answers[1])
        access.suspendAccess()
        assertFalse(mbti.state.value.visible); assertFalse(f.state.visible)
        access.updateAccess(true, 2)
        assertEquals(3, mbti.state.value.answers[1])
        f.scope = PersonalityDataScope("test-data", 2)
        access.synchronizeDataScope()
        assertFalse(mbti.state.value.hasAttempt); assertFalse(f.state.hasAttempt)
    }
}
