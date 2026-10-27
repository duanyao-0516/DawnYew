package com.dawnyew.presentation

import kotlin.test.*

class MbtiControllerTest {
    private class Fixture {
        var scope: PersonalityDataScope? = PersonalityDataScope("test-data", 1)
        val controller = MbtiController { scope }
        init { controller.updateAccess(true, 1) }
        fun token() = controller.state.value.generation
        fun start() = controller.start(token())
        fun answerAll(value: Int = 1) {
            for (id in 1..93) {
                controller.openQuestion(token(), id - 1)
                controller.answer(token(), id, value)
            }
        }
    }

    @Test fun incompleteSubmissionLocatesFirstUnansweredAndNeutralCountsAsAnswered() {
        val f = Fixture(); f.start()
        f.controller.answer(f.token(), 1, 3)
        f.controller.submit(f.token())
        val state = f.controller.state.value
        assertEquals(1, state.answers.size)
        assertEquals(1, state.questionIndex)
        assertEquals(MbtiStage.QUESTIONS, state.stage)
        assertNull(state.result)
        assertNotNull(state.message)
    }

    @Test fun completionShowsSourceResultAndChangingAnswerInvalidatesIt() {
        val f = Fixture(); f.start(); f.answerAll()
        f.controller.submit(f.token())
        assertEquals("ENTJ", f.controller.state.value.result?.type)
        assertEquals(MbtiStage.RESULT, f.controller.state.value.stage)
        f.controller.openQuestion(f.token(), 0)
        assertNotNull(f.controller.state.value.result, "Viewing answers does not alter a result")
        f.controller.answer(f.token(), 1, 1)
        assertNotNull(f.controller.state.value.result, "Selecting the same answer is not an edit")
        f.controller.answer(f.token(), 1, 5)
        assertNull(f.controller.state.value.result)
        f.controller.submit(f.token())
        assertNotNull(f.controller.state.value.result)
    }

    @Test fun accessRevocationHidesAnswersButResumingPreservesThemAndRejectsOldCallbacks() {
        val f = Fixture(); f.start()
        f.controller.answer(f.token(), 1, 2)
        val old = f.token()
        f.controller.updateAccess(false, 2)
        assertFalse(f.controller.state.value.visible)
        assertTrue(f.controller.state.value.answers.isEmpty())
        assertNull(f.controller.state.value.result)
        assertFalse(f.controller.state.value.hasAttempt)
        f.controller.answer(old, 1, 5)
        f.controller.updateAccess(true, 2)
        assertEquals(2, f.controller.state.value.answers[1])
        f.controller.answer(old, 1, 5)
        assertEquals(2, f.controller.state.value.answers[1])
    }

    @Test fun latestDataEpochIsCheckedOnEveryActionEvenWithoutAnAccessEvent() {
        val f = Fixture(); f.start(); f.controller.answer(f.token(), 1, 1)
        val old = f.token()
        f.scope = PersonalityDataScope("test-data", 2)
        f.controller.answer(old, 1, 5)
        assertTrue(f.controller.state.value.answers.isEmpty())
        assertFalse(f.controller.state.value.hasAttempt)
        assertEquals(MbtiStage.INTRO, f.controller.state.value.stage)
        f.controller.updateAccess(true, 1)
        assertTrue(f.controller.state.value.answers.isEmpty())
    }

    @Test fun unavailableDataClearsAndCannotBeResurrected() {
        val f = Fixture(); f.start(); f.answerAll(); f.controller.submit(f.token())
        f.scope = null; f.controller.synchronizeDataScope()
        assertFalse(f.controller.state.value.visible)
        f.scope = PersonalityDataScope("test-data", 1)
        f.controller.updateAccess(true, 1)
        assertNull(f.controller.state.value.result)
        assertTrue(f.controller.state.value.answers.isEmpty())
        assertFalse(f.controller.state.value.hasAttempt)
    }

    @Test fun navigationConsumesOnlyInternalBackAndStaleNextDoesNotSkipQuestions() {
        val f = Fixture()
        assertFalse(f.controller.back(f.token()))
        f.start(); f.controller.answer(f.token(), 1, 1)
        val old = f.token()
        f.controller.next(old)
        f.controller.next(old)
        assertEquals(1, f.controller.state.value.questionIndex)
        assertTrue(f.controller.back(f.token()))
        assertEquals(0, f.controller.state.value.questionIndex)
        assertTrue(f.controller.back(f.token()))
        assertEquals(MbtiStage.INTRO, f.controller.state.value.stage)
        assertFalse(f.controller.back(f.token()))
        f.controller.resume(f.token())
        assertEquals(MbtiStage.QUESTIONS, f.controller.state.value.stage)
        assertEquals(1, f.controller.state.value.answers[1])
    }

    @Test fun invalidOrStaleQuestionActionsCannotModifyAnswers() {
        val f = Fixture(); f.start()
        f.controller.answer(f.token(), 0, 1)
        f.controller.answer(f.token(), 1, 0)
        f.controller.answer(f.token(), 1, 6)
        f.controller.answer(f.token(), 2, 5)
        f.controller.openQuestion(f.token(), -1)
        f.controller.openQuestion(f.token(), 93)
        assertTrue(f.controller.state.value.answers.isEmpty())
        assertEquals(0, f.controller.state.value.questionIndex)
    }

    @Test fun clearRemovesCompletedResultAndNewControllerHasNoProgress() {
        val f = Fixture(); f.start(); f.answerAll(3); f.controller.submit(f.token())
        assertEquals("ESTJ", f.controller.state.value.result?.type)
        assertEquals(93, f.controller.state.value.result?.neutralCount)
        val old = f.token()
        f.controller.clear(old)
        assertFalse(f.controller.state.value.hasAttempt)
        assertTrue(f.controller.state.value.answers.isEmpty())
        assertNull(f.controller.state.value.result)
        f.controller.start(old)
        assertFalse(f.controller.state.value.hasAttempt)
        val fresh = MbtiController { f.scope }
        fresh.updateAccess(true, 1)
        assertFalse(fresh.state.value.hasAttempt)
    }

    @Test fun sameAccessRefreshIsIdempotentAndDoesNotInvalidateVisibleHandlers() {
        val f = Fixture(); f.start()
        val token = f.token()
        f.controller.updateAccess(true, 1)
        assertEquals(token, f.token())
        f.controller.answer(token, 1, 1)
        assertEquals(1, f.controller.state.value.answers[1])
        f.controller.suspendAccess()
        assertFalse(f.controller.state.value.visible)
        f.controller.updateAccess(true, 1)
        assertEquals(1, f.controller.state.value.answers[1])
    }
}
