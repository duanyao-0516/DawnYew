package com.dawnyew.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
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

/** Real accessibility/touch actions against the production UI and an isolated Room generation. */
@RunWith(AndroidJUnit4::class)
class HomeRangeDeviceTest {
    private val env = object : DomainEnvironment {
        override fun id() = UUID.randomUUID().toString()
        override fun localNow() = "2026-10-24T12:00:00"
        override fun utcNow() = "2026-10-24T04:00:00Z"
        override fun normalize(value: String) = normalizeTextNfc(value)
    }

    @Test fun completeMonthChartRendersAndDrillsBothBoundaryDays() = runBlocking {
        withFixture(setOf("monthly_expense")) { f ->
            val description = f.chart().contentDescription.toString()
            val buckets = description.substringAfter("趋势图，").split('；')
            assertEquals(31, buckets.size, description)
            assertEquals("10.1 10.00", buckets.first())
            assertEquals("10.31 31.00", buckets.last())
            f.capture("01-full-month").first.recycle()
            listOf("2026-10-01" to .01f, "2026-10-31" to .99f).forEach { (date, position) ->
                f.tap(f.bounds(f.chart()), position)
                f.settle { f.app.navigation.value.page == "流水" && !f.app.finance.activities.value.loading }
                val query = assertNotNull(f.app.finance.navigation.value.activityScope)
                assertEquals(date, query.startDate); assertEquals(date, query.endDate)
                assertEquals(setOf(ActivityKind.EXPENSE, ActivityKind.INCOME, ActivityKind.REFUND), query.kinds)
                assertEquals(listOf(date), f.app.finance.activities.value.activities.map { it.localDate }.distinct())
                f.click("返回"); f.settle { f.app.navigation.value.page == "首页" }
            }
        }
    }

    @Test fun customRangeUsesRealDateDialogAndDrillsExactInclusiveBounds() = runBlocking {
        withFixture(setOf("monthly_expense")) { f ->
            f.click("首页月份选择"); f.click("自定义日期…")
            // Material's input mode is localized. Match its actual accessible action, not a coordinate.
            val inputMode = f.awaitNode { node ->
                node.contentDescription?.toString()?.let { it.contains("输入") || it.contains("input", true) } == true
            }
            f.clickNode(inputMode)
            f.setDateInput(0, "2026-10-01", "2026-09-15")
            f.setDateInput(1, "2026-10-31", "2026-10-10")
            f.hideImeIfVisible()
            f.click("应用日期")
            f.settle {
                f.app.navigation.value.homeScope.startDate == "2026-09-15" &&
                    f.app.navigation.value.homeScope.endDate == "2026-10-10" &&
                    f.app.homeStatistics.value.let { !it.loading && it.error == null && it.snapshot?.scope?.startDate == "2026-09-15" }
            }
            val chart = f.chart().contentDescription.toString()
            val buckets = chart.substringAfter("趋势图，").split('；')
            assertEquals(26, buckets.size, chart)
            assertEquals("9.15 15.00", buckets.first()); assertEquals("10.10 10.00", buckets.last())
            assertFalse(chart.contains("9.14 ")); assertFalse(chart.contains("10.11 "))
            f.capture("02-custom-range").first.recycle()
            f.click("查看所选范围交易")
            f.settle { f.app.navigation.value.page == "流水" && !f.app.finance.activities.value.loading }
            val query = assertNotNull(f.app.finance.navigation.value.activityScope)
            assertEquals("2026-09-15", query.startDate); assertEquals("2026-10-10", query.endDate)
            assertEquals(setOf(f.firstLedger), query.ledgerIds)
            val expected = f.data.entries.filter { it.active && it.ledgerId == f.firstLedger && it.localDate in "2026-09-15".."2026-10-10" }.map { it.id }.toSet()
            assertEquals(expected, f.app.finance.activities.value.activities.map { it.id }.toSet())
            assertTrue(f.app.finance.activities.value.activities.all { it.family == ActivityFamily.ENTRY })
        }
    }

    @Test fun calendarShowsNetAmountsAndKeepsEmptyPositiveNegativeZeroDatesAligned() = runBlocking {
        withFixture(setOf("calendar")) { f ->
            f.settle { !f.app.finance.calendar.value.loading && f.app.finance.calendar.value.summary != null }
            val labels = listOf("日历2026-10-01，净收支-10.00", "日历2026-10-02，净收支+37.00",
                "日历2026-10-03，净收支0.00", "日历2026-10-04，无收支")
            val cells = labels.map { label -> f.awaitNode { it.contentDescription?.toString() == label } }
            cells.forEach { assertFalse(it.text?.contains("笔") == true, "Calendar must display money, not transaction counts: ${it.text}") }
            val rectangles = cells.map(f::bounds)
            assertEquals(1, rectangles.map { it.top }.distinct().size, "Fixture dates must occupy the same calendar week")
            val (bitmap, origin) = f.capture("03-calendar-net-and-baseline")
            try {
                // Read the rendered black date glyphs in the upper portion of each real cell.
                // Gray zero amounts and colored nonzero amounts are excluded; an empty day centering its
                // date while populated days move upward therefore fails this comparison.
                val bottoms = rectangles.map { cell ->
                    val left = cell.left - origin[0]; val top = cell.top - origin[1]
                    val darkRows = (0 until (cell.height() * .62f).toInt()).filter { y ->
                        ((cell.width() * .30f).toInt() until (cell.width() * .70f).toInt()).any { x ->
                            val pixel = bitmap.getPixel(left + x, top + y)
                            val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                            channels.max() < 100 && channels.max() - channels.min() < 16
                        }
                    }
                    assertTrue(darkRows.isNotEmpty(), "No rendered date glyph found at $cell")
                    darkRows.last()
                }
                assertTrue(bottoms.max() - bottoms.min() <= 2, "Calendar date baselines differ in screen pixels: $bottoms")
            } finally { bitmap.recycle() }
            f.click(labels[1])
            f.settle { f.app.navigation.value.page == "流水" && !f.app.finance.activities.value.loading }
            assertEquals("2026-10-02", f.app.finance.navigation.value.activityScope?.startDate)
            assertEquals("2026-10-02", f.app.finance.navigation.value.activityScope?.endDate)
        }
    }

    @Test fun homeAccountTrackingEntersAccountTabAndLedgerPickerChangesActualAccounts() = runBlocking {
        withFixture(setOf("account_tracking")) { f ->
            f.awaitNode { it.text?.toString()?.split('\n')?.contains("UI测试·首账本现金") == true }
            f.click("账户追踪")
            f.settle { f.app.navigation.value.page == "账户" && !f.app.finance.snapshot.value.loading }
            assertEquals("账户", f.app.navigation.value.tab)
            assertNull(f.app.finance.navigation.value.sourceLedgerIds)
            f.click("UI测试·首账本 ▾")
            f.click("UI测试·次账本")
            f.settle {
                f.app.navigation.value.scope.ledgerIds == setOf(f.secondLedger) &&
                    f.app.finance.snapshot.value.let { !it.loading && it.error == null && it.ledgerIds == setOf(f.secondLedger) && it.snapshot != null }
            }
            assertNull(f.app.finance.navigation.value.sourceLedgerIds)
            assertEquals(setOf(f.secondLedger), f.app.finance.snapshot.value.snapshot!!.accounts.map { it.account.ledgerId }.toSet())
            f.awaitNode { it.text?.toString()?.split('\n')?.contains("UI测试·次账本现金") == true }
            assertFalse(f.nodes().any { it.text?.toString()?.split('\n')?.contains("UI测试·首账本现金") == true })
            f.capture("04-account-ledger-changed").first.recycle()
        }
    }

    private suspend fun withFixture(panels: Set<String>, block: suspend (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.packageName.endsWith(".validation"), "Run only in the isolated validation package")
        val dir = File(context.cacheDir, "home-range-test-${env.id()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = dir }
        val repo = GenerationRepository(isolated, env)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val data = sample(panels)
            repo.initialize(); repo.replace(data)
            val app = AppController(repo, env, scope)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var host: MainActivity? = null
                scenario.onActivity { host = it; it.setContent { DawnYewApp(app, Actions, false, utilities = Tools) } }
                val fixture = Fixture(context, assertNotNull(host), app, data)
                fixture.settle { host?.hasWindowFocus() == true && app.homeStatistics.value.let { !it.loading && it.error == null && it.snapshot != null } && !app.finance.snapshot.value.loading && app.finance.snapshot.value.snapshot != null }
                block(fixture)
            }
        } finally { scope.cancel(); repo.close(); dir.deleteRecursively() }
    }

    private fun sample(panels: Set<String>): LedgerState {
        val engine = DomainEngine(env); var data = engine.initialState()
        fun command(value: Command) { data = engine.apply(data, value, env.id()).state }
        val first = data.ledgers.single().id
        command(Command.UpdateLedger(first, "UI测试·首账本", false, data.ledgers.single().sortOrder))
        command(Command.CreateLedger("UI测试·次账本"))
        val second = data.ledgers.single { it.id != first }.id
        val cash = env.id(); val bank = env.id()
        listOf(Account(cash, first, "UI测试·首账本现金", openingBalanceMinor = 2000000, openingAt = "2026-01-01T00:00:00"),
            Account(bank, first, "UI测试·首账本银行", AccountType.BANK, openingAt = "2026-01-01T00:00:00"),
            Account(env.id(), second, "UI测试·次账本现金", openingBalanceMinor = 70000, openingAt = "2026-01-01T00:00:00")).forEach { command(Command.Finance(FinanceAction.SaveAccount(it))) }
        fun entry(date: String, amount: Long, kind: EntryKind = EntryKind.EXPENSE): String {
            val operation = env.id()
            val category = data.categories.first { it.ledgerId == first && it.direction == if (kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE }.id
            data = engine.apply(data, Command.SaveEntry(ledgerId = first, kind = kind, amountMinor = amount, categoryId = category,
                occurrence = "${date}T12:00:00", note = "UI测试·日期边界", details = EntryDetails("", cash)), operation).state
            return data.entries.single { it.creationOperationId == operation }.id
        }
        entry("2026-09-14", 9900); entry("2026-09-15", 1500)
        val expense = entry("2026-10-01", 1000)
        entry("2026-10-02", 4000, EntryKind.INCOME); entry("2026-10-02", 500)
        command(Command.AddRefund(expense, 200, "2026-10-02T12:00:00", "UI测试·退款"))
        entry("2026-10-03", 1000); entry("2026-10-03", 1000, EntryKind.INCOME)
        entry("2026-10-10", 1000); entry("2026-10-11", 1100); entry("2026-10-31", 3100); entry("2026-11-01", 9900)
        command(Command.Finance(FinanceAction.SaveTransfer(AccountTransfer(env.id(), first, cash, bank, 999900,
            "2026-10-02T12:00:00", createdAt = env.utcNow(), updatedAt = env.utcNow()))))
        val layout = PanelCatalog.defaultLayout(PanelSurface.HOME).copy(enabled = panels)
        return data.copy(preferences = listOf(UserPreference("theme", "light"), UserPreference("lastLedgerId", first),
            UserPreference("lastPeriod", "2026-10"), UserPreference(PanelCatalog.preferenceKey(PanelSurface.HOME), PanelCatalog.encode(PanelSurface.HOME, layout))))
    }

    private class Fixture(val context: Context, val host: MainActivity, val app: AppController, val data: LedgerState) {
        private val instrument = InstrumentationRegistry.getInstrumentation()
        val firstLedger = data.ledgers.single { it.name == "UI测试·首账本" }.id
        val secondLedger = data.ledgers.single { it.name == "UI测试·次账本" }.id
        fun settle(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000; var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrument.waitForIdleSync(); instrument.runOnMainSync { ready = predicate() }
                if (!ready) SystemClock.sleep(40)
            }
            assertTrue(ready, "UI did not settle: ${app.navigation.value}; ${dump()}")
            instrument.waitForIdleSync(); SystemClock.sleep(200)
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrument.uiAutomation.clearCache()
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
            instrument.uiAutomation.rootInActiveWindow?.let(::visit)
            return result
        }
        private fun dump() = nodes().joinToString("\n") { "text=${it.text}; desc=${it.contentDescription}; actions=${it.actionList.map { action -> action.id }}" }
        fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrument.waitForIdleSync(); nodes().firstOrNull(predicate)?.let { return it }; SystemClock.sleep(80)
            }
            fail("Missing UI node: ${dump()}")
        }
        fun chart() = awaitNode { it.contentDescription?.toString()?.startsWith("趋势图，") == true }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
        fun click(label: String) = clickNode(awaitNode { (it.text?.toString()?.split('\n').orEmpty() + it.contentDescription?.toString()?.split('\n').orEmpty()).contains(label) })
        fun clickNode(node: AccessibilityNodeInfo) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) tap(bounds(target ?: node))
            instrument.waitForIdleSync(); SystemClock.sleep(200)
        }
        fun tap(bounds: Rect, horizontal: Float = .5f) {
            assertTrue(bounds.width() > 0 && bounds.height() > 0, "Empty touch bounds: $bounds")
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(down, down + index * 60L, action, bounds.left + bounds.width() * horizontal, bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrument.uiAutomation.injectInputEvent(event, true)); event.recycle()
            }
            instrument.waitForIdleSync(); SystemClock.sleep(200)
        }
        fun setDateInput(index: Int, initial: String, value: String) {
            val date = initial.split('-'); val replacement = value.split('-')
            val initialFormats = setOf(date.joinToString(""), date[1] + date[2] + date[0], date[2] + date[1] + date[0])
            awaitNode { (it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) && it.text?.toString()?.filter(Char::isDigit) in initialFormats }
            fun fields() = nodes().filter { it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
                .sortedWith(compareBy<AccessibilityNodeInfo> { bounds(it).top }.thenBy { bounds(it).left })
            val field = fields().also { assertEquals(2, it.size, dump()) }[index]
            val digits = field.text.toString().filter(Char::isDigit)
            val order = when (digits) {
                date.joinToString("") -> listOf(0, 1, 2)
                date[1] + date[2] + date[0] -> listOf(1, 2, 0)
                date[2] + date[1] + date[0] -> listOf(2, 1, 0)
                else -> fail("Unsupported material date input format: ${field.text}; ${dump()}")
            }
            val next = order.joinToString("") { replacement[it] }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
            }), "Date input rejected text: ${dump()}")
            awaitNode { (it.isEditable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) && it.text?.toString()?.filter(Char::isDigit) == next }
        }
        fun hideImeIfVisible() {
            var shown = false
            instrument.runOnMainSync {
                shown = android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.isShown && it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            }
            if (shown) { instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrument.waitForIdleSync(); SystemClock.sleep(200) }
        }
        fun capture(name: String): Pair<Bitmap, IntArray> {
            var captured: Pair<Bitmap, IntArray>? = null
            instrument.runOnMainSync {
                assertTrue(host.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val view = host.window.decorView; val origin = IntArray(2); view.getLocationOnScreen(origin)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
                val output = File(context.getExternalFilesDir(null), "home-range-verification").apply { mkdirs() }
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                captured = bitmap to origin
            }
            return assertNotNull(captured)
        }
    }

    private object Actions : PlatformActions {
        override val capturePlatform = PreviewCapturePlatform
        override fun exportBackup(encrypted: Boolean, password: CharArray): Unit = error("Not part of range regression")
        override fun restoreBackup(password: CharArray): Unit = error("Not part of range regression")
        override fun chooseScreenshot(): Unit = error("Not part of range regression")
        override fun setAppLock(enabled: Boolean): Unit = error("Not part of range regression")
        override fun clearAllData(): Unit = error("Not part of range regression")
        override fun restoreProtectionBackup(): Unit = error("Not part of range regression")
        override fun clearProtectionBackup(): Unit = error("Not part of range regression")
        override fun importCsv(ledgerId: String): Unit = error("Not part of range regression")
        override fun exportCsv(scope: QueryScope): Unit = error("Not part of range regression")
        override fun requestWidget(): Unit = error("Not part of range regression")
    }
    private object Tools : UtilityPlatform {
        override val rulerScale = MutableStateFlow(RulerScale(40f)); override val noise = MutableStateFlow(NoiseUi())
        override fun setRulerFullscreen(enabled: Boolean) = Unit
        override fun saveRulerScale(pixelsPerCm: Float): Unit = error("Not part of range regression")
        override fun resetRulerScale(): Unit = error("Not part of range regression")
        override fun startNoise(): Unit = error("Not part of range regression")
        override fun stopNoise() = Unit
        override fun calibrateNoise(referenceDb: Double): Unit = error("Not part of range regression")
        override fun resetNoiseCalibration(): Unit = error("Not part of range regression")
    }
}
