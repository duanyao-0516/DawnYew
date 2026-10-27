package com.dawnyew.app

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnyew.core.Command
import com.dawnyew.core.DomainException
import com.dawnyew.core.personality.SbtiResultMode
import com.dawnyew.presentation.SbtiStage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class SbtiDeviceTest {
    private val device = PersonalityDeviceDriver("sbti-verification")
    private val app get() = device.app
    private val instrumentation get() = device.instrumentation
    private val controller get() = app.controller.sbti
    private val state get() = controller.state.value

    private fun prepare() {
        instrumentation.runOnMainSync {
            app.lockStore.persist(false)
            app.session.configureLock(false)
            app.initialize()
        }
    }

    private fun openFrom(tab: String) {
        device.click(tab); device.click("工具箱"); device.click("SBTI 测试")
        device.await { app.controller.navigation.value.page == "SBTI 测试" && state.visible }
    }

    private fun choose(value: Int) {
        val question = state.questions[state.questionIndex]
        val index = question.options.indexOfFirst { it.value == value }
        assertTrue(index >= 0)
        device.click("选项 ${'A' + index}：${question.options[index].label}")
        device.await { state.answers[question.id] == value }
    }

    private fun openQuestion(id: String) {
        val before = state
        val index = before.questions.indexOfFirst { it.id == id }
        assertTrue(index >= 0)
        device.click(if (before.stage == SbtiStage.RESULT) "检查/修改答案" else "题号目录")
        val label = "第 ${index + 1} 题，${if (id in before.answers) "已答" else "未答"}${if (index == before.questionIndex) "，当前题" else ""}"
        device.click(label)
        device.await { state.stage == SbtiStage.QUESTIONS && state.questions[state.questionIndex].id == id }
    }

    private fun submitFromDirectory() {
        device.click("题号目录"); device.click("查看结果")
    }

    @Test fun normalAndHiddenResultsSurviveRecreationAndKeepMbtiIndependent() {
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.await { state.visible && app.controller.mbti.state.value.visible }
            instrumentation.runOnMainSync {
                controller.clear(state.generation)
                app.controller.mbti.start(app.controller.mbti.state.value.generation)
                app.controller.mbti.answer(app.controller.mbti.state.value.generation, 1, 3)
            }
            openFrom("更多")
            device.capture(scenario, "01-intro")
            device.click("开始测试")
            device.await { state.stage == SbtiStage.QUESTIONS }
            val order = state.questions.map { it.id }
            assertEquals(31, order.size)
            choose(1)
            scenario.recreate()
            device.await { state.visible && state.stage == SbtiStage.QUESTIONS }
            assertEquals(order, state.questions.map { it.id })
            assertEquals(1, state.answers[order.first()])
            scenario.moveToState(Lifecycle.State.CREATED)
            device.await { !state.visible }
            assertTrue(state.questions.isEmpty()); assertTrue(state.answers.isEmpty())
            scenario.moveToState(Lifecycle.State.RESUMED)
            device.await { state.visible }
            openFrom("我的"); device.click("继续本次测试")
            assertEquals(order, state.questions.map { it.id })
            for ((index, id) in order.withIndex()) {
                device.await { state.questions[state.questionIndex].id == id }
                choose(1)
                if (id == "q1") device.capture(scenario, "02-long-question")
                device.click(if (index == order.lastIndex) "查看结果" else "下一题")
            }
            device.await { state.stage == SbtiStage.RESULT }
            assertEquals("DEAD", state.result?.type?.code)
            assertTrue(state.result!!.dimensions.all { it.score == 2 })
            assertEquals(3, app.controller.mbti.state.value.answers[1])
            assertPosterVisible()
            device.capture(scenario, "03-normal-result")

            openQuestion("drink_gate_q1"); choose(3)
            assertEquals(32, state.questions.size)
            val gateIndex = state.questionIndex
            assertEquals("drink_gate_q2", state.questions[gateIndex + 1].id)
            device.click("下一题")
            device.await { state.questions[state.questionIndex].id == "drink_gate_q2" }
            choose(2); submitFromDirectory()
            device.await { state.result?.mode == SbtiResultMode.DRUNK }
            assertPosterVisible()
            device.capture(scenario, "04-drunk-result")

            openQuestion("drink_gate_q1"); choose(1)
            assertEquals(31, state.questions.size)
            assertFalse("drink_gate_q2" in state.answers)
            choose(3)
            assertEquals(32, state.questions.size)
            assertFalse("drink_gate_q2" in state.answers)
            submitFromDirectory()
            device.await { state.questions[state.questionIndex].id == "drink_gate_q2" && state.message != null }
            choose(1); submitFromDirectory()
            device.await { state.result?.type?.code == "DEAD" }

            device.click("再测一次")
            device.awaitUi { device.nodes().any { it.text?.toString() == "重新开始" && it.isVisibleToUser } }
            device.click("取消")
            assertEquals("DEAD", state.result?.type?.code)
            device.click("再测一次"); device.click("重新开始")
            device.await { state.stage == SbtiStage.QUESTIONS && state.answers.isEmpty() }
            assertEquals(31, state.questions.size)
            assertEquals(3, app.controller.mbti.state.value.answers[1])
        }
    }

    @Test fun pendingOperationHidesBothTestsAndRejectsStaleSbtiAnswer() = runBlocking {
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {
            device.await { state.visible }
            instrumentation.runOnMainSync { controller.clear(state.generation) }
            openFrom("更多"); device.click("开始测试"); choose(2)
            val id = state.questions[state.questionIndex].id
            val operation = app.environment.id()
            val theme = app.repository.state.value!!.preferences.find { it.key == "theme" }?.value ?: "system"
            try {
                app.repository.execute(Command.SetPreference("theme", theme), operation)
                device.await { app.controller.ui.value.dataset?.contentVersion == app.repository.state.value?.contentVersion }
                val old = state.generation
                assertFailsWith<DomainException> {
                    app.repository.execute(Command.SetPreference("theme", theme), app.environment.id())
                }
                device.await { !state.visible && !app.controller.mbti.state.value.visible }
                assertTrue(state.answers.isEmpty()); assertTrue(state.questions.isEmpty())
                instrumentation.runOnMainSync { controller.answer(old, id, 3) }
                app.repository.acknowledgeOperation(operation)
                device.await { state.visible && app.controller.mbti.state.value.visible }
                assertEquals(2, state.answers[id])
                instrumentation.runOnMainSync { controller.answer(old, id, 3) }
                assertEquals(2, state.answers[id])
            } finally { app.repository.acknowledgeOperation(operation) }
        }
    }

    @Test fun longQuestionDirectoryFallbackAndJpegPostersRemainUsable() {
        prepare()
        device.await { app.repository.state.value != null }
        val oldTheme = app.repository.state.value!!.preferences.find { it.key == "theme" }?.value ?: "system"
        device.setThemePreference("dark")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.await { state.visible }
            instrumentation.runOnMainSync { controller.clear(state.generation) }
            openFrom("更多"); device.click("开始测试")
            openQuestion("q1"); choose(1)
            device.capture(scenario, "05-large-question")
            device.click("题号目录"); device.click("关闭目录")
            device.awaitUi { device.nodes().none { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            device.click("题号目录")
            device.awaitUi { device.nodes().any { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            device.capture(scenario, "06-directory")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            device.awaitUi { device.nodes().none { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }

            // Independent source oracle: fallback-below-60 and exact-template-Dior-s.
            fillOracle(listOf(1,1,2,3,3,3,1,1,2,1,1,1,1,3,3,2,2,2,1,1,3,2,3,3,2,1,1,1,3,3))
            device.await { state.result?.type?.code == "HHHH" }
            assertEquals(57, state.result!!.ranked.first().similarity)
            assertPosterVisible(); device.capture(scenario, "07-fallback-result")
            fillOracle(listOf(2,2,3,3,2,2,2,2,2,2,3,3,2,2,3,3,2,2,3,3,2,2,3,3,1,1,3,3,1,1))
            device.await { state.result?.type?.code == "Dior-s" }
            assertPosterVisible(); device.capture(scenario, "08-jpeg-result")
            device.click("清除本次结果")
            device.awaitUi { device.nodes().any { it.text?.toString() == "确认清除" && it.isVisibleToUser } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            device.awaitUi { device.nodes().none { it.text?.toString() == "确认清除" && it.isVisibleToUser } }
            assertEquals(SbtiStage.RESULT, state.stage)
            device.click("清除本次结果"); device.click("确认清除")
            device.await { !state.hasAttempt }
            assertFalse(app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        } } finally { device.setThemePreference(oldTheme) }
    }

    private fun fillOracle(values: List<Int>) {
        instrumentation.runOnMainSync {
            controller.start(state.generation)
            values.forEachIndexed { index, value ->
                val id = "q${index + 1}"
                controller.openQuestion(state.generation, id)
                controller.answer(state.generation, id, value)
            }
            controller.openQuestion(state.generation, "drink_gate_q1")
            controller.answer(state.generation, "drink_gate_q1", 1)
            controller.submit(state.generation)
        }
    }

    private fun assertPosterVisible() {
        val type = state.result!!.type
        device.reveal("${type.code}（${type.name}）原版结果图")
        device.awaitUi { device.nodes().any { it.contentDescription?.toString() == "${type.code}（${type.name}）原版结果图" && it.isVisibleToUser } }
    }
}
