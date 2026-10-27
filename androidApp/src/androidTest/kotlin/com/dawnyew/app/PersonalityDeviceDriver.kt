package com.dawnyew.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.Command
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.assertTrue

/** Shared device interaction only; tests retain their own behavioral assertions. */
internal class PersonalityDeviceDriver(private val screenshotDirectory: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = ApplicationProvider.getApplicationContext<DawnYewApplication>()
    init { check(app.packageName.endsWith(".validation")) { "Use an isolated validation package" } }

    fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(40)
        }
        error("Personality UI timed out: MBTI=${app.controller.mbti.state.value.stage}, SBTI=${app.controller.sbti.state.value.stage}, page=${app.controller.navigation.value.page}")
    }

    fun nodes(): List<AccessibilityNodeInfo> {
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

    fun awaitUi(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        error("Expected UI transition did not complete")
    }

    fun click(label: String) {
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

    /** Reveal non-interactive content without tapping it, including at large font scales. */
    fun reveal(label: String) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            val all = nodes()
            val target = all.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (target?.isVisibleToUser == true) return
            if (target != null) target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            else all.firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Missing visible content '$label'")
    }

    fun setThemePreference(value: String) = runBlocking {
        app.initialize()
        await { app.repository.state.value != null }
        val operation = app.environment.id()
        try { app.repository.execute(Command.SetPreference("theme", value), operation) }
        finally { app.repository.acknowledgeOperation(operation) }
    }

    fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val view = if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                    ?: activity.window.decorView
            } else activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(app.getExternalFilesDir(null), screenshotDirectory).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
