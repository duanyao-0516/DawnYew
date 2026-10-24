package com.dawnyew.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
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
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class HomeScopeDeviceTest {
    private val env = object : DomainEnvironment {
        override fun id() = UUID.randomUUID().toString()
        override fun localNow() = "2026-10-24T12:00:00"
        override fun utcNow() = "2026-10-24T04:00:00Z"
        override fun normalize(value: String) = normalizeTextNfc(value)
    }

    @Test fun radarDrillUsesMonthChangedAfterFirstRender() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.packageName.endsWith(".validation"), "Run only in the isolated validation package")
        val dir = File(context.cacheDir, "home-scope-test-${env.id()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = dir }
        val repo = GenerationRepository(isolated, env)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val instrument = InstrumentationRegistry.getInstrumentation()
        try {
            val engine = DomainEngine(env)
            var data = engine.initialState()
            val ledger = data.ledgers.single().id
            val category = data.categories.first { it.direction == Direction.EXPENSE }.id
            listOf("2026-09-12T10:00:00", "2026-10-12T10:00:00").forEachIndexed { index, date ->
                data = engine.apply(data, Command.SaveEntry(ledgerId = ledger, kind = EntryKind.EXPENSE,
                    amountMinor = (index + 1) * 100L, categoryId = category, occurrence = date, note = "UI测试·月份范围"), env.id()).state
            }
            val layout = PanelCatalog.defaultLayout(PanelSurface.HOME).copy(enabled = setOf("category_radar"))
            data = data.copy(preferences = listOf(UserPreference("theme", "light"),
                UserPreference(PanelCatalog.preferenceKey(PanelSurface.HOME), PanelCatalog.encode(PanelSurface.HOME, layout))))
            repo.initialize(); repo.replace(data)
            val app = AppController(repo, env, scope)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var activity: MainActivity? = null
                scenario.onActivity { host -> activity = host; host.setContent { DawnYewApp(app, Actions, false, utilities = Tools) } }
                fun settle(predicate: () -> Boolean) {
                    val end = SystemClock.elapsedRealtime() + 15_000
                    var ready = false
                    while (!ready && SystemClock.elapsedRealtime() < end) {
                        instrument.waitForIdleSync(); instrument.runOnMainSync { ready = predicate() }
                        if (!ready) SystemClock.sleep(30)
                    }
                    assertTrue(ready, "Home scope did not settle: page=${app.navigation.value.page}, panel=${app.dashboard.panels.value["category_radar"]}, source=${app.finance.navigation.value.sourceStatisticsScope}")
                    instrument.waitForIdleSync(); SystemClock.sleep(180)
                }
                // Initial rendering must complete so this catches a click closure retaining October.
                settle { activity?.hasWindowFocus() == true && app.dashboard.panels.value["category_radar"]?.let {
                    !it.loading && it.error == null && it.snapshot != null && it.request?.scope?.startDate == "2026-10-01"
                } == true }
                click(instrument, "首页月份选择")
                val month = awaitMonthField(instrument, "2026-10")
                assertTrue(month.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "2026-09")
                }), "Month field rejected ACTION_SET_TEXT: ${nodeDump(instrument)}")
                awaitMonthField(instrument, "2026-09")
                click(instrument, "确定")
                settle { app.dashboard.panels.value["category_radar"]?.let {
                    !it.loading && it.error == null && it.snapshot != null && it.request?.scope?.startDate == "2026-09-01" && it.request?.scope?.endDate == "2026-09-30"
                } == true }
                click(instrument, "分类倾向")
                settle { app.navigation.value.page == "分类统计" }
                instrument.runOnMainSync {
                    val source = assertNotNull(app.finance.navigation.value.sourceStatisticsScope)
                    assertEquals("2026-09-01", source.startDate)
                    assertEquals("2026-09-30", source.endDate)
                }
            }
        } finally { scope.cancel(); repo.close(); dir.deleteRecursively() }
    }

    private fun nodes(instrument: Instrumentation): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrument.uiAutomation.clearCache()
        val root = instrument.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        visit(root)
        return result
    }

    private fun awaitMonthField(instrument: Instrumentation, expectedText: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrument.waitForIdleSync()
            nodes(instrument).firstOrNull { node ->
                (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) &&
                    node.text?.toString()?.contains(expectedText) == true
            }?.let { return it }
            SystemClock.sleep(100)
        }
        fail("Month field did not expose $expectedText with editable/SET_TEXT semantics: ${nodeDump(instrument)}")
    }

    private fun nodeDump(instrument: Instrumentation): String = nodes(instrument).joinToString("\n") {
        "text=${it.text}; description=${it.contentDescription}; editable=${it.isEditable}; actions=${it.actionList.map { action -> action.id }}"
    }

    private fun click(instrument: Instrumentation, label: String) {
        repeat(20) {
            instrument.waitForIdleSync()
            val matching = nodes(instrument).filter { node ->
                (node.text?.toString()?.split('\n').orEmpty() + node.contentDescription?.toString()?.split('\n').orEmpty()).contains(label)
            }
            for (node in matching) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                val bounds = Rect().also { (target ?: node).getBoundsInScreen(it) }
                if (bounds.width() <= 0 || bounds.height() <= 0) continue
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
                    val down = SystemClock.uptimeMillis()
                    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                        val event = MotionEvent.obtain(down, down + index * 60L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        assertTrue(instrument.uiAutomation.injectInputEvent(event, true)); event.recycle()
                    }
                }
                instrument.waitForIdleSync(); SystemClock.sleep(180)
                return
            }
            SystemClock.sleep(100)
        }
        fail("Missing clickable node: $label")
    }

    private object Actions : PlatformActions {
        override val capturePlatform = PreviewCapturePlatform
        override fun exportBackup(encrypted: Boolean, password: CharArray): Unit = error("Not part of scope regression")
        override fun restoreBackup(password: CharArray): Unit = error("Not part of scope regression")
        override fun chooseScreenshot(): Unit = error("Not part of scope regression")
        override fun setAppLock(enabled: Boolean): Unit = error("Not part of scope regression")
        override fun clearAllData(): Unit = error("Not part of scope regression")
        override fun restoreProtectionBackup(): Unit = error("Not part of scope regression")
        override fun clearProtectionBackup(): Unit = error("Not part of scope regression")
        override fun importCsv(ledgerId: String): Unit = error("Not part of scope regression")
        override fun exportCsv(scope: QueryScope): Unit = error("Not part of scope regression")
        override fun requestWidget(): Unit = error("Not part of scope regression")
    }
    private object Tools : UtilityPlatform {
        override val rulerScale = MutableStateFlow(RulerScale(40f))
        override val noise = MutableStateFlow(NoiseUi())
        override fun setRulerFullscreen(enabled: Boolean) = Unit
        override fun saveRulerScale(pixelsPerCm: Float): Unit = error("Not part of scope regression")
        override fun resetRulerScale(): Unit = error("Not part of scope regression")
        override fun startNoise(): Unit = error("Not part of scope regression")
        override fun stopNoise() = Unit
        override fun calibrateNoise(referenceDb: Double): Unit = error("Not part of scope regression")
        override fun resetNoiseCalibration(): Unit = error("Not part of scope regression")
    }
}
