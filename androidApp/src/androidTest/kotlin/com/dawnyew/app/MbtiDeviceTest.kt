package com.dawnyew.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.Command
import com.dawnyew.core.DomainException
import com.dawnyew.presentation.MbtiStage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.*

/** Runs only in the isolated .validation package; never alters the installed release dataset. */
@RunWith(AndroidJUnit4::class)
class MbtiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<DawnYewApplication>()

    @Test fun fullQuestionnaireSurvivesRotationAndBackgroundAndProducesOfflineScores() {
        assertTrue(app.packageName.endsWith(".validation"), "Use -PdawnyewIsolatedDeviceTest=true")
        instrumentation.runOnMainSync {
            app.lockStore.persist(false)
            app.session.configureLock(false)
            app.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { app.controller.mbti.state.value.visible && app.repository.state.value != null }
            instrumentation.runOnMainSync { app.controller.mbti.clear(app.controller.mbti.state.value.generation) }
            openFrom("更多")
            capture(scenario, "01-intro")
            click("开始测试")
            click("选项 1：更倾向 A")
            await { app.controller.mbti.state.value.answers[1] == 1 }
            click("下一题")
            click("选项 2：略倾向 A")
            await { app.controller.mbti.state.value.answers[2] == 2 }
            capture(scenario, "02-question")

            scenario.recreate()
            await { app.controller.mbti.state.value.visible && app.controller.mbti.state.value.questionIndex == 1 }
            assertEquals(2, app.controller.mbti.state.value.answers[2])
            scenario.moveToState(Lifecycle.State.CREATED)
            await { !app.controller.mbti.state.value.visible }
            assertTrue(app.controller.mbti.state.value.answers.isEmpty())
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { app.controller.mbti.state.value.visible }
            openFrom("我的")
            click("继续本次测试")
            await { app.controller.mbti.state.value.stage == MbtiStage.QUESTIONS }
            assertEquals(2, app.controller.mbti.state.value.answers[2])

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { app.controller.mbti.state.value.questionIndex == 0 }
            // Exercise every production question and actual accessible choice/next controls.
            for (id in 1..93) {
                await { app.controller.mbti.state.value.questionIndex == id - 1 }
                click("选项 1：更倾向 A")
                await { app.controller.mbti.state.value.answers[id] == 1 }
                click(if (id == 93) "查看结果" else "下一题")
            }
            await { app.controller.mbti.state.value.stage == MbtiStage.RESULT }
            val result = assertNotNull(app.controller.mbti.state.value.result)
            assertEquals("ENTJ", result.type)
            assertEquals(listOf(16, 12, 10, 12, 32, 17, 28, 9), result.axes.flatMap { listOf(it.firstScore, it.secondScore) })
            capture(scenario, "03-result")

            click("检查/修改答案")
            click("第 1 题，已答")
            click("选项 5：更倾向 B")
            await { app.controller.mbti.state.value.result == null }
            click("题号目录")
            click("查看结果")
            await { app.controller.mbti.state.value.stage == MbtiStage.RESULT }
            assertEquals(listOf(16, 12, 10, 12, 32, 17, 26, 11),
                app.controller.mbti.state.value.result!!.axes.flatMap { listOf(it.firstScore, it.secondScore) })

            click("返回")
            await { app.controller.mbti.state.value.stage == MbtiStage.INTRO }
            click("查看本次结果")
            await { app.controller.mbti.state.value.stage == MbtiStage.RESULT }
            click("清除本次结果")
            awaitUi { nodes().any { it.text?.toString() == "确认清除" && it.isVisibleToUser } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitUi { nodes().none { it.text?.toString() == "确认清除" && it.isVisibleToUser } }
            assertNotNull(app.controller.mbti.state.value.result)
            click("清除本次结果")
            click("确认清除")
            await { !app.controller.mbti.state.value.hasAttempt }
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
            await { app.controller.mbti.state.value.result?.neutralCount == 93 }
            assertTrue(nodes().any { it.text?.contains("倾向") == true })
            capture(scenario, "04-neutral-result")
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
        setThemePreference("dark")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { app.controller.mbti.state.value.visible }
            instrumentation.runOnMainSync { app.controller.mbti.clear(app.controller.mbti.state.value.generation) }
            openFrom("更多")
            click("开始测试")
            click("题号目录")
            click("第 93 题，未答")
            await { app.controller.mbti.state.value.questionIndex == 92 }
            click("选项 3：两者相当")
            click("题号目录")
            click("关闭目录")
            awaitUi { nodes().none { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            click("题号目录")
            awaitUi { nodes().any { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            click("查看结果")
            await { app.controller.mbti.state.value.questionIndex == 0 && app.controller.mbti.state.value.message != null }
            assertEquals(3, app.controller.mbti.state.value.answers[93])
            capture(scenario, "05-directory-question")
            click("题号目录")
            awaitUi { nodes().any { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            capture(scenario, "06-directory")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitUi { nodes().none { it.text?.toString() == "关闭目录" && it.isVisibleToUser } }
            click("选项 1：更倾向 A")
            await { app.controller.mbti.state.value.answers[1] == 1 }
        } } finally { setThemePreference(oldTheme) }
    }

    @Test fun pendingOperationChangesAloneSuspendAndResumeAnswers() = runBlocking {
        assertTrue(app.packageName.endsWith(".validation"))
        instrumentation.runOnMainSync {
            app.lockStore.persist(false)
            app.session.configureLock(false)
            app.initialize()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            await { app.controller.mbti.state.value.visible && app.repository.state.value != null }
            instrumentation.runOnMainSync { app.controller.mbti.clear(app.controller.mbti.state.value.generation) }
            openFrom("更多")
            click("开始测试")
            click("选项 1：更倾向 A")
            val theme = app.repository.state.value?.preferences?.find { it.key == "theme" }?.value ?: "system"
            val operation = app.environment.id()
            try {
                // First publish a legitimate receipt without acknowledging it. The second request
                // only refreshes pending recovery, with no change to the ledger/UI state.
                app.repository.execute(Command.SetPreference("theme", theme), operation)
                await { app.controller.ui.value.dataset?.contentVersion == app.repository.state.value?.contentVersion }
                val old = app.controller.mbti.state.value.generation
                assertFailsWith<DomainException> {
                    app.repository.execute(Command.SetPreference("theme", theme), app.environment.id())
                }
                await { app.controller.pendingOperation.value != null }
                await { !app.controller.mbti.state.value.visible }
                assertTrue(app.controller.mbti.state.value.answers.isEmpty())
                instrumentation.runOnMainSync { app.controller.mbti.answer(old, 1, 5) }
                app.repository.acknowledgeOperation(operation)
                await { app.controller.mbti.state.value.visible }
                assertEquals(1, app.controller.mbti.state.value.answers[1])
                instrumentation.runOnMainSync { app.controller.mbti.answer(old, 1, 5) }
                assertEquals(1, app.controller.mbti.state.value.answers[1])
            } finally {
                app.repository.acknowledgeOperation(operation)
            }
        }
    }

    private fun openFrom(tab: String) {
        click(tab)
        click("工具箱")
        click("MBTI 测试")
        await { app.controller.navigation.value.page == "MBTI 测试" && app.controller.mbti.state.value.visible }
    }

    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(40)
        }
        error("MBTI UI timed out: ${app.controller.mbti.state.value.stage}, page=${app.controller.navigation.value.page}")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun awaitUi(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        error("Expected UI transition did not complete")
    }

    private fun click(label: String) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            val all = nodes()
            val matches = all.filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
            for (node in matches) {
                if (!node.isVisibleToUser) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    continue
                }
                // Compose omits the click action from an already-selected radio option.
                if (label.startsWith("选项 ") && (node.isChecked || node.isSelected)) return
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                // Only a radio row may use its own bounds when its click action is omitted.
                // Plain text such as a Dialog title must never be treated as a button.
                if (target == null && !label.startsWith("选项 ")) continue
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                    val rect = Rect().also((target ?: node)::getBoundsInScreen)
                    if (rect.isEmpty) continue
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                        event.recycle()
                    }
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(80)
                return
            }
            if (matches.isEmpty()) all.firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Missing action '$label': ${nodes().map { "${it.text ?: it.contentDescription}: visible=${it.isVisibleToUser}, clickable=${it.isClickable}, checked=${it.isChecked}, selected=${it.isSelected}, bounds=${Rect().also(it::getBoundsInScreen)}" }}")
    }

    private fun setThemePreference(value: String) = runBlocking {
        app.initialize()
        await { app.repository.state.value != null }
        val operation = app.environment.id()
        try { app.repository.execute(Command.SetPreference("theme", value), operation) }
        finally { app.repository.acknowledgeOperation(operation) }
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val view = if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                    ?: activity.window.decorView
            } else activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(app.getExternalFilesDir(null), "mbti93-verification").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
