package com.dawnyew.app

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnyew.core.Command
import com.dawnyew.core.DomainException
import com.dawnyew.presentation.MbtiStage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

/** Runs only in the isolated .validation package; never alters the installed release dataset. */
@RunWith(AndroidJUnit4::class)
class MbtiDeviceTest {
    private val device = PersonalityDeviceDriver("mbti93-verification")
    private val instrumentation get() = device.instrumentation
    private val app get() = device.app

    @Test fun fullQuestionnaireSurvivesRotationAndBackgroundAndProducesOfflineScores() {
        assertTrue(app.packageName.endsWith(".validation"), "Use -PdawnyewIsolatedDeviceTest=true")
        instrumentation.runOnMainSync {
            app.lockStore.persist(false)
            app.session.configureLock(false)
            app.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.await { app.controller.mbti.state.value.visible && app.repository.state.value != null }
            instrumentation.runOnMainSync { app.controller.mbti.clear(app.controller.mbti.state.value.generation) }
            openFrom("更多")
            device.capture(scenario, "01-intro")
            device.click("开始测试")
            device.click("选项 1：更倾向 A")
            device.await { app.controller.mbti.state.value.answers[1] == 1 }
            device.click("下一题")
            device.click("选项 2：略倾向 A")
            device.await { app.controller.mbti.state.value.answers[2] == 2 }
            device.capture(scenario, "02-question")

            scenario.recreate()
            device.await { app.controller.mbti.state.value.visible && app.controller.mbti.state.value.questionIndex == 1 }
            assertEquals(2, app.controller.mbti.state.value.answers[2])
            scenario.moveToState(Lifecycle.State.CREATED)
            device.await { !app.controller.mbti.state.value.visible }
            assertTrue(app.controller.mbti.state.value.answers.isEmpty())
            scenario.moveToState(Lifecycle.State.RESUMED)
            device.await { app.controller.mbti.state.value.visible }
            openFrom("我的")
            device.click("继续本次测试")
            device.await { app.controller.mbti.state.value.stage == MbtiStage.QUESTIONS }
            assertEquals(2, app.controller.mbti.state.value.answers[2])

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            device.await { app.controller.mbti.state.value.questionIndex == 0 }
            // Exercise every production question and actual accessible choice/next controls.
            for (id in 1..93) {
                device.await { app.controller.mbti.state.value.questionIndex == id - 1 }
                device.click("选项 1：更倾向 A")
                device.await { app.controller.mbti.state.value.answers[id] == 1 }
                device.click(if (id == 93) "查看结果" else "下一题")
            }
            device.await { app.controller.mbti.state.value.stage == MbtiStage.RESULT }
            val result = assertNotNull(app.controller.mbti.state.value.result)
            assertEquals("ENTJ", result.type)
            assertEquals(listOf(16, 12, 10, 12, 32, 17, 28, 9), result.axes.flatMap { listOf(it.firstScore, it.secondScore) })
            device.capture(scenario, "03-result")

            device.click("检查/修改答案")
            device.click("第 1 题，已答")
            device.click("选项 5：更倾向 B")
            device.await { app.controller.mbti.state.value.result == null }
            device.click("题号目录")
            device.click("查看结果")
            device.await { app.controller.mbti.state.value.stage == MbtiStage.RESULT }
            assertEquals(listOf(16, 12, 10, 12, 32, 17, 26, 11),
                app.controller.mbti.state.value.result!!.axes.flatMap { listOf(it.firstScore, it.secondScore) })

            device.click("返回")
            device.await { app.controller.mbti.state.value.stage == MbtiStage.INTRO }
            device.click("查看本次结果")
            device.await { app.controller.mbti.state.value.stage == MbtiStage.RESULT }
            device.click("清除本次结果")
            device.awaitUi { device.nodes().any { it.text?.toString() == "确认清除" && it.isVisibleToUser } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            device.awaitUi { device.nodes().none { it.text?.toString() == "确认清除" && it.isVisibleToUser } }
            assertNotNull(app.controller.mbti.state.value.result)
            device.click("清除本次结果")
            device.click("确认清除")
            device.await { !app.controller.mbti.state.value.hasAttempt }
            assertTrue(app.controller.mbti.state.value.answers.isEmpty())

            // Prepare the full-neutral edge case through the real controller, then inspect its UI.
            instrumentation.runOnMainSync {
                val controller = app.controller.mbti
                controller.start(controller.state.value.generation)
                for (id in 1..93) {
                    controller.openQuestion(controller.state.value.generation, id - 1)
                    controller.answer(controller.state.value.generation, id, 3)
                }
                controller.submit(controller.state.value.generation)
            }
            device.await { app.controller.mbti.state.value.result?.neutralCount == 93 }
            assertTrue(device.nodes().any { it.text?.contains("倾向") == true })
            device.capture(scenario, "04-neutral-result")
            // No changes to ledger entities or persistence are involved in this tool.
            assertFalse(app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        }
    }

    @Test fun questionDirectoryKeepsItsControlsReachable() {
        assertTrue(app.packageName.endsWith(".validation"))
        instrumentation.runOnMainSync {
            app.lockStore.persist(false)
            app.session.configureLock(false)
            app.initialize()
        }
        val oldTheme = app.repository.state.value?.preferences?.find { it.key == "theme" }?.value ?: "system"
        device.setThemePreference("dark")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.await { app.controller.mbti.state.value.visible }
            instrumentation.runOnMainSync { app.controller.mbti.clear(app.controller.mbti.state.value.generation) }
            openFrom("更多")
            device.click("开始测试")
            device.click("题号目录")
            device.click("第 93 题，未答")
            device.await { app.controller.mbti.state.value.questionIndex == 92 }
            device.click("选项 3：两者相当")
            device.click("题号目录")
            device.click("关闭目录")
            device.awaitUi { device.nodes().none { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            device.click("题号目录")
            device.awaitUi { device.nodes().any { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            device.click("查看结果")
            device.await { app.controller.mbti.state.value.questionIndex == 0 && app.controller.mbti.state.value.message != null }
            assertEquals(3, app.controller.mbti.state.value.answers[93])
            device.capture(scenario, "05-directory-question")
            device.click("题号目录")
            device.awaitUi { device.nodes().any { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            device.capture(scenario, "06-directory")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            device.awaitUi { device.nodes().none { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            device.click("选项 1：更倾向 A")
            device.await { app.controller.mbti.state.value.answers[1] == 1 }
        } } finally { device.setThemePreference(oldTheme) }
    }

    @Test fun pendingOperationChangesAloneSuspendAndResumeAnswers() = runBlocking {
        assertTrue(app.packageName.endsWith(".validation"))
        instrumentation.runOnMainSync {
            app.lockStore.persist(false)
            app.session.configureLock(false)
            app.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            device.await { app.controller.mbti.state.value.visible && app.repository.state.value != null }
            instrumentation.runOnMainSync { app.controller.mbti.clear(app.controller.mbti.state.value.generation) }
            openFrom("更多")
            device.click("开始测试")
            device.click("选项 1：更倾向 A")
            val theme = app.repository.state.value?.preferences?.find { it.key == "theme" }?.value ?: "system"
            val operation = app.environment.id()
            try {
                // First publish a legitimate receipt without acknowledging it. The second request
                // only refreshes pending recovery, with no change to the ledger/UI state.
                app.repository.execute(Command.SetPreference("theme", theme), operation)
                device.await { app.controller.ui.value.dataset?.contentVersion == app.repository.state.value?.contentVersion }
                val old = app.controller.mbti.state.value.generation
                assertFailsWith<DomainException> {
                    app.repository.execute(Command.SetPreference("theme", theme), app.environment.id())
                }
                device.await { app.controller.pendingOperation.value != null }
                device.await { !app.controller.mbti.state.value.visible }
                assertTrue(app.controller.mbti.state.value.answers.isEmpty())
                instrumentation.runOnMainSync { app.controller.mbti.answer(old, 1, 5) }
                app.repository.acknowledgeOperation(operation)
                device.await { app.controller.mbti.state.value.visible }
                assertEquals(1, app.controller.mbti.state.value.answers[1])
                instrumentation.runOnMainSync { app.controller.mbti.answer(old, 1, 5) }
                assertEquals(1, app.controller.mbti.state.value.answers[1])
            } finally {
                app.repository.acknowledgeOperation(operation)
            }
        }
    }

    private fun openFrom(tab: String) {
        device.click(tab)
        device.click("工具箱")
        device.click("MBTI 测试")
        device.await { app.controller.navigation.value.page == "MBTI 测试" && app.controller.mbti.state.value.visible }
    }

}
