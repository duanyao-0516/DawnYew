package com.dawnyew.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.*
import com.dawnyew.platform.storage.GenerationRepository
import com.dawnyew.presentation.*
import com.dawnyew.ui.DawnYewApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.test.*

/** Production routes and controller, isolated durable data; no synthetic busy or preference state. */
@RunWith(AndroidJUnit4::class)
class ConvergenceDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrument: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val env = object : DomainEnvironment {
        override fun id() = UUID.randomUUID().toString()
        override fun localNow() = "2026-10-24T12:00:00"
        override fun utcNow() = "2026-10-24T04:00:00Z"
        override fun normalize(value: String) = normalizeTextNfc(value)
    }

    private fun isolated(): Pair<Context, File> {
        assertTrue(context.packageName.endsWith(".validation"), "Use the isolated validation package")
        val directory = File(context.cacheDir, "convergence-${env.id()}").apply { mkdirs() }
        return object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory } to directory
    }

    private fun output(name: String) = File(context.getExternalFilesDir(null), "convergence/$name").apply { deleteRecursively(); mkdirs() }

    private fun sample(): LedgerState {
        val engine = DomainEngine(env)
        val initial = engine.initialState()
        return engine.apply(initial, Command.SaveEntry(ledgerId = initial.ledgers.single().id, kind = EntryKind.EXPENSE,
            amountMinor = 1234, categoryId = initial.categories.first { it.direction == Direction.EXPENSE }.id,
            occurrence = "2026-10-24T10:00:00", note = "UI测试·收敛验证"), env.id()).state
            .copy(preferences = listOf(UserPreference("theme", "light")))
    }

    /** Delay only the real preference transaction, allowing deterministic inspection of its write gate. */
    private class DelayedPanelRepository(private val delegate: LedgerRepository) : LedgerRepository by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var panelWrites = 0
            private set
        override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> {
            if (command is Command.SetPreference && command.key == PanelCatalog.preferenceKey(PanelSurface.HOME)) {
                panelWrites++
                entered.complete(Unit)
                release.await()
            }
            return delegate.execute(command, operationId, expectedVersion)
        }
    }

    @Test fun panelPreferenceWriteDoesNotRecolorOtherRowsOnAnyFrame() = runBlocking {
        val (isolated, directory) = isolated()
        val database = GenerationRepository(isolated, env)
        val delayed = DelayedPanelRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val output = output("panel-frames")
        var frames: UnchangedRowFrames? = null
        try {
            database.initialize(); database.replace(sample())
            val app = AppController(delayed, env, scope)
            val driver = Driver(instrument)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var host: MainActivity
                scenario.onActivity { activity -> host = activity; activity.setContent { DawnYewApp(app, Actions, false, utilities = Tools) } }
                driver.settle { host.hasWindowFocus() && app.ui.value.dataset != null }
                instrument.runOnMainSync { app.finance.open("主页面板") }
                driver.settle { app.navigation.value.page == "主页面板" }
                val definitions = PanelCatalog.definitions(PanelSurface.HOME)
                val original = PanelCatalog.resolve(database.snapshot().preferences, PanelSurface.HOME)
                val target = definitions.first()
                // Include both checked and unchecked non-target switches in the pixel comparison.
                val unaffected = definitions.drop(1).take(3)
                val rectangles = unaffected.associate { it.title to driver.bounds("拖动排序${it.title}") }
                instrument.runOnMainSync {
                    assertTrue(host.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    frames = UnchangedRowFrames(host.window.decorView, rectangles, output) { app.ui.value.busy }
                    frames!!.start()
                }
                driver.click("开关${target.title}")
                withTimeout(10_000) { delayed.entered.await() }
                driver.settle { app.ui.value.busy }
                assertEquals(original, PanelCatalog.resolve(database.snapshot().preferences, PanelSurface.HOME), "A pending write must not pretend to be committed")
                unaffected.forEach { definition ->
                    val toggle = driver.node("开关${definition.title}")
                    assertFalse(toggle.isEnabled, "Unchanged switches still obey the shared write gate")
                    assertEquals(definition.id in original.enabled, toggle.isChecked)
                }
                // The frame callback runs on the UI thread while this instrumentation thread waits.
                SystemClock.sleep(650)
                delayed.release.complete(Unit)
                driver.settle {
                    !app.ui.value.busy && app.pendingOperation.value == null && app.ui.value.dataset?.let {
                        target.id !in PanelCatalog.resolve(it.preferences, PanelSurface.HOME).enabled
                    } == true
                }
                SystemClock.sleep(350)
                instrument.runOnMainSync { frames!!.stop() }
                val committed = PanelCatalog.resolve(database.snapshot().preferences, PanelSurface.HOME)
                assertEquals(original.copy(enabled = original.enabled - target.id), committed)
                assertEquals(1, delayed.panelWrites, "Only the targeted production toggle may write")
                unaffected.forEach { definition ->
                    assertEquals(rectangles.getValue(definition.title), driver.bounds("拖动排序${definition.title}"), "Unchanged row moved")
                }
                frames!!.verify()
            }
        } finally {
            instrument.runOnMainSync { frames?.stop() }
            delayed.release.complete(Unit)
            scope.cancel(); database.close(); directory.deleteRecursively()
        }
    }

    @Test fun settingsReturnsToActuallyScrolledPositionIn450DpViewport() = runBlocking {
        val (isolated, directory) = isolated()
        val database = GenerationRepository(isolated, env)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val output = output("settings-scroll")
        try {
            database.initialize(); database.replace(sample())
            val app = AppController(database, env, scope)
            val driver = Driver(instrument)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var measuredHeight = 0
                scenario.onActivity { activity -> activity.setContent {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(450.dp).onGloballyPositioned { measuredHeight = it.size.height }) {
                            DawnYewApp(app, Actions, false, utilities = Tools)
                        }
                    }
                } }
                driver.settle { app.ui.value.dataset != null && measuredHeight > 0 }
                assertTrue(abs(measuredHeight - 450 * context.resources.displayMetrics.density) <= 2, "The fixture must really constrain the app viewport")
                instrument.runOnMainSync { app.finance.selectTab("我的"); app.finance.open("设置") }
                driver.settle { app.navigation.value.page == "设置" }
                val firstRow = driver.bounds("暗夜模式")
                assertNull(driver.find("备份"), "Backup must begin outside the visible viewport so this proves a real scroll")
                var successfulScrolls = 0
                repeat(8) {
                    if (driver.find("备份") == null) {
                        val scrolling = driver.nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }
                            ?: fail("Settings has no real scroll container")
                        assertTrue(scrolling.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                        successfulScrolls++
                        instrument.waitForIdleSync(); SystemClock.sleep(300)
                    }
                }
                assertTrue(successfulScrolls > 0, "This regression must not pass without scrolling")
                val changedFirstRow = driver.find("暗夜模式")?.let { Rect().also(it::getBoundsInScreen) }
                assertTrue(changedFirstRow == null || changedFirstRow.top < firstRow.top - 20, "Scroll did not move the initial content")
                val before = driver.bounds("备份")
                driver.click("备份"); driver.settle { app.navigation.value.page == "备份" }
                driver.click("返回"); driver.settle { app.navigation.value.page == "设置" }
                val after = driver.bounds("备份")
                assertTrue(abs(before.top - after.top) <= 3 && abs(before.bottom - after.bottom) <= 3,
                    "Settings returned at a different scroll position: $before -> $after")
                File(output, "result.txt").writeText("PASS viewport=${measuredHeight}px scrolls=$successfulScrolls first=$firstRow afterScroll=$changedFirstRow backup=$before returned=$after\n")
            }
        } finally { scope.cancel(); database.close(); directory.deleteRecursively() }
    }

    @Test fun searchFilterPendingCancelAndResetOnlyChangeResultsAfterApply() = runBlocking {
        val (isolated, directory) = isolated()
        val database = GenerationRepository(isolated, env)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val output = output("search-filter")
        try {
            database.initialize(); database.replace(sample())
            val app = AppController(database, env, scope)
            val driver = Driver(instrument)
            val original = ActivityScope(ledgerIds = setOf(database.snapshot().ledgers.single().id), startDate = "2026-10-01", endDate = "2026-10-31")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { DawnYewApp(app, Actions, false, utilities = Tools) } }
                driver.settle { app.ui.value.dataset != null }
                instrument.runOnMainSync { app.finance.showActivities(original) }
                driver.settle { !app.finance.activities.value.loading && app.finance.activities.value.contentScope == original }
                assertEquals(1, app.finance.activities.value.activities.size)
                driver.click("类型"); driver.click("收入")
                assertEquals(original, app.finance.navigation.value.activityScope, "Pending filter leaked into the applied route")
                driver.click("取消")
                driver.click("类型"); driver.click("应用")
                driver.settle { !app.finance.activities.value.loading && app.finance.activities.value.contentScope == original }
                assertEquals(original, app.finance.navigation.value.activityScope, "Cancelled income filter was retained on reopening")
                driver.click("类型"); driver.click("收入"); driver.click("应用")
                val income = original.copy(kinds = setOf(ActivityKind.INCOME))
                driver.settle { !app.finance.activities.value.loading && app.finance.activities.value.contentScope == income }
                assertEquals(income, app.finance.navigation.value.activityScope)
                assertTrue(app.finance.activities.value.activities.isEmpty(), "Income filter still displays the expense fixture")
                driver.click("更多"); driver.click("清空")
                assertEquals(income, app.finance.navigation.value.activityScope, "Pending reset changed results before application")
                driver.click("取消")
                assertEquals(income, app.finance.navigation.value.activityScope)
                driver.click("清空"); driver.click("应用")
                val cleared = ActivityScope()
                driver.settle { !app.finance.activities.value.loading && app.finance.activities.value.contentScope == cleared }
                assertEquals(cleared, app.finance.navigation.value.activityScope)
                assertEquals(1, app.finance.activities.value.activities.size)
                File(output, "result.txt").writeText("PASS pending income filter does not apply; cancel resets draft; apply changes real results; pending reset is reversible; reset applies only after confirmation\n")
            }
        } finally { scope.cancel(); database.close(); directory.deleteRecursively() }
    }

    /** Captures every rendered frame, retaining only a failing crop instead of dozens of full screenshots. */
    private class UnchangedRowFrames(
        private val view: View, screenRectangles: Map<String, Rect>, private val output: File, private val busy: () -> Boolean,
    ) : Choreographer.FrameCallback {
        private val rectangles = screenRectangles.mapValues { (_, screen) ->
            val origin = IntArray(2); view.getLocationOnScreen(origin)
            Rect(screen).apply { offset(-origin[0], -origin[1]) }.also {
                require(it.width() > 0 && it.height() > 0 && Rect(0, 0, view.width, view.height).contains(it))
            }
        }
        private val baseline = rectangles.mapValues { (_, bounds) -> pixels(bounds) }
        private val changes = mutableListOf<String>()
        private var running = false
        private var count = 0
        private var busyCount = 0
        private var previousFrame = 0L
        private var maxGapMillis = 0L
        fun start() { running = true; Choreographer.getInstance().postFrameCallback(this) }
        fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            count++
            if (busy()) busyCount++
            if (previousFrame != 0L) maxGapMillis = maxOf(maxGapMillis, (frameTimeNanos - previousFrame) / 1_000_000)
            previousFrame = frameTimeNanos
            rectangles.forEach { (name, bounds) ->
                val actual = pixels(bounds)
                val expected = baseline.getValue(name)
                val differing = actual.indices.count { actual[it] != expected[it] }
                if (differing > 0) {
                    if (changes.isEmpty()) {
                        val image = Bitmap.createBitmap(actual, bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
                        File(output, "first-changed-row.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                    }
                    changes += "frame=$count busy=${busy()} row=$name changedPixels=$differing"
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
        private fun pixels(bounds: Rect): IntArray {
            val image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image); canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat()); view.draw(canvas)
            return IntArray(bounds.width() * bounds.height()).also {
                image.getPixels(it, 0, bounds.width(), 0, 0, bounds.width(), bounds.height()); image.recycle()
            }
        }
        fun verify() {
            File(output, "frames.txt").writeText("frames=$count busyFrames=$busyCount maxFrameGapMs=$maxGapMillis\n" + changes.joinToString("\n"))
            assertTrue(count >= 12 && busyCount >= 6, "Insufficient rendered write-gate frames: $count total, $busyCount busy")
            assertTrue(changes.isEmpty(), "Non-target rows changed during another toggle: ${changes.take(8)}")
        }
    }

    private class Driver(private val instrument: Instrumentation) {
        fun settle(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrument.waitForIdleSync(); instrument.runOnMainSync { ready = predicate() }
                if (!ready) SystemClock.sleep(25)
            }
            assertTrue(ready, "UI state did not settle")
            instrument.waitForIdleSync(); SystemClock.sleep(180)
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrument.uiAutomation.clearCache()
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
            instrument.uiAutomation.rootInActiveWindow?.let(::visit)
            return result
        }
        fun find(label: String) = nodes().firstOrNull { node -> node.isVisibleToUser &&
            (node.text?.toString()?.split('\n').orEmpty() + node.contentDescription?.toString()?.split('\n').orEmpty()).contains(label) }
        fun node(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrument.waitForIdleSync()
                find(label)?.let { return it }
                SystemClock.sleep(50)
            }
            fail("Visible node missing: $label; available=${nodes().mapNotNull { it.text ?: it.contentDescription }}")
        }
        fun bounds(label: String) = Rect().also(node(label)::getBoundsInScreen)
        fun click(label: String) {
            var target: AccessibilityNodeInfo? = node(label)
            while (target != null && target.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) target = target.parent
            val clickable = target ?: fail("Production click action missing: $label")
            if (!clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                // Some OEM bridges reject Compose ACTION_CLICK. Exercise its actual touch target instead.
                val bounds = Rect().also(clickable::getBoundsInScreen)
                assertTrue(bounds.width() > 0 && bounds.height() > 0)
                val down = SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                    val event = MotionEvent.obtain(down, down + index * 60L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(instrument.uiAutomation.injectInputEvent(event, true)); event.recycle()
                }
            }
            instrument.waitForIdleSync()
            SystemClock.sleep(150)
        }
    }

    private object Actions : PlatformActions {
        override val capturePlatform = PreviewCapturePlatform
        override fun exportBackup(encrypted: Boolean, password: CharArray): Unit = error("Outside this regression")
        override fun restoreBackup(password: CharArray): Unit = error("Outside this regression")
        override fun chooseScreenshot(): Unit = error("Outside this regression")
        override fun setAppLock(enabled: Boolean): Unit = error("Outside this regression")
        override fun clearAllData(): Unit = error("Outside this regression")
        override fun restoreProtectionBackup(): Unit = error("Outside this regression")
        override fun clearProtectionBackup(): Unit = error("Outside this regression")
        override fun importCsv(ledgerId: String): Unit = error("Outside this regression")
        override fun exportCsv(scope: QueryScope): Unit = error("Outside this regression")
        override fun requestWidget(): Unit = error("Outside this regression")
    }
    private object Tools : UtilityPlatform {
        override val rulerScale = MutableStateFlow(RulerScale(40f))
        override val noise = MutableStateFlow(NoiseUi())
        override fun setRulerFullscreen(enabled: Boolean) = Unit
        override fun saveRulerScale(pixelsPerCm: Float): Unit = error("Outside this regression")
        override fun resetRulerScale(): Unit = error("Outside this regression")
        override fun startNoise(): Unit = error("Outside this regression")
        override fun stopNoise() = Unit
        override fun calibrateNoise(referenceDb: Double): Unit = error("Outside this regression")
        override fun resetNoiseCalibration(): Unit = error("Outside this regression")
    }
}
