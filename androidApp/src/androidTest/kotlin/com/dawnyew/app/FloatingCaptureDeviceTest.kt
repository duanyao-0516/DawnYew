package com.dawnyew.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.app.capture.FloatingCaptureAccessibilityService
import com.dawnyew.app.capture.FloatingCaptureNotifications
import com.dawnyew.app.capture.FloatingCapturePreferences
import com.dawnyew.app.capture.FloatingCaptureRuntime
import com.dawnyew.core.Command
import com.dawnyew.core.DomainEngine
import com.dawnyew.core.EntryKind
import com.dawnyew.core.LedgerState
import com.dawnyew.core.OriginKind
import com.dawnyew.platform.storage.GenerationRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Requires the private external fixture from .local/floating-fixture/ on an isolated emulator.
 * No screenshot, OCR result or booking request is injected into production code by this test.
 */
@RunWith(AndroidJUnit4::class)
class FloatingCaptureDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app get() = context as DawnYewApplication
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // The default uiAutomation accessor disables the very accessibility service being tested.
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        }
    }
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val evidence get() = File(context.getExternalFilesDir(null), "floating-capture-validation/evidence.txt").apply { parentFile?.mkdirs() }
    private fun log(message: String) { evidence.appendText(message + "\n") }
    private fun diagnosticFrame(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            try { File(evidence.parentFile, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    private fun shell(command: String): String = automation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText().trim()
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("$message; connected=${FloatingCaptureRuntime.connected.value}; runtime=${FloatingCaptureRuntime.message.value}")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        automation.windows.forEach { visit(it.root) }
        return result
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.isVisibleToUser &&
        (node.text?.toString() == label || node.contentDescription?.toString() == label)
    private fun click(label: String) {
        await("Missing visible control $label") { nodes().any { matches(it, label) } }
        var node = nodes().first { matches(it, label) }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Click failed: $label")
    }
    private fun ballVisible(): Boolean = automation.windows.any { window ->
        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            window.root?.packageName?.toString() == context.packageName &&
            nodes().any { matches(it, BALL) }
    }
    private fun assertExternalForeground(packageName: String) {
        val activities = shell("dumpsys activity activities")
        assertTrue(Regex("(?:topResumedActivity|ResumedActivity)[^\\n]*${Regex.escape(packageName)}/").containsMatchIn(activities),
            "Expected foreground app $packageName")
        assertTrue(ballVisible(), "A real accessibility overlay must remain over $packageName")
    }
    private fun showFixture(sample: String) {
        shell("am start -n com.dawnyew.fixture/.PaymentActivity --es sample $sample")
        await("External fixture $sample must be rendered") {
            nodes().any { it.contentDescription?.toString() == "External screenshot fixture $sample" }
        }
        // Wait for the fixture's new pixels to reach SurfaceFlinger, not only its accessibility tree.
        SystemClock.sleep(6_000)
        assertExternalForeground("com.dawnyew.fixture")
    }
    private fun savedNotification(dataset: String, entry: String) = notifications.activeNotifications
        .firstOrNull { it.tag == "$dataset/$entry" }

    @Test fun realGlobalScreenshotsSaveConfirmDeduplicateAndUndoWithoutForegroundApp() = runBlocking {
        assertTrue(context.packageName.endsWith(".validation"), "Never run against the user's installed app")
        assertFalse(app.lockStore.enabled, "This test covers the default unlocked ledger; it must not bypass an enabled app lock")
        val packages = shell("pm list packages -U com.dawnyew")
        fun uid(packageName: String) = Regex("package:${Regex.escape(packageName)} uid:(\\d+)")
            .find(packages)?.groupValues?.get(1)
        val fixtureUid = assertNotNull(uid("com.dawnyew.fixture"), "Install the private external fixture before running")
        assertTrue(fixtureUid != assertNotNull(uid(context.packageName)), "Fixture must belong to a different UID")
        val preferences = FloatingCapturePreferences(context)
        val oldEnabled = preferences.enabled
        val oldConfirm = preferences.confirmBeforeSave
        val oldRight = preferences.onRight
        val oldFraction = preferences.verticalFraction
        val oldServices = shell("settings get secure enabled_accessibility_services")
        val oldAccessibility = shell("settings get secure accessibility_enabled")
        val component = "${context.packageName}/com.dawnyew.app.capture.FloatingCaptureAccessibilityService"
        var baseline: LedgerState? = null
        var testDataset: String? = null
        evidence.writeText("")
        try {
            main { preferences.enabled = false; app.floatingCapture.cancelPending() }
            // Exercise the actual switch contract before test-only shell authorization.
            val otherServices = oldServices.takeUnless { it == "null" || it.isBlank() }.orEmpty()
                .split(':').filter { it.isNotBlank() && it != component }.joinToString(":")
            if (otherServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $otherServices")
            await("Existing test service must disconnect before the permission flow") { !FloatingCaptureRuntime.connected.value }
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("Authorized app initialization") { app.session.authorized && app.repository.state.value != null }
                scenario.onActivity { it.capturePlatform.setFloatingCapture(true) }
                val disclosure = context.getString(R.string.floating_capture_permission_title)
                await("Switch must explain the missing global accessibility permission") { nodes().any { matches(it, disclosure) } }
                click("前往系统授权")
                await("Permission action must open actual system Settings") {
                    Regex("(?:topResumedActivity|ResumedActivity)[^\\n]*com\\.android\\.settings/")
                        .containsMatchIn(shell("dumpsys activity activities"))
                }
                assertTrue(preferences.enabled, "The switch must preserve enable intent while awaiting system authorization")
                log("PASS real switch detects missing accessibility permission -> disclosure -> system authorization Settings")
            }
            main { preferences.enabled = false; app.floatingCapture.cancelPending() }
            baseline = app.repository.snapshot()
            val fresh = DomainEngine(app.environment).initialState()
            testDataset = fresh.datasetId
            app.repository.replace(fresh)
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            FloatingCaptureNotifications.ensureChannels(context)
            assertTrue(FloatingCaptureNotifications.canNotify(context), "Enable the isolated app's result channel before running")
            val enabledServices = oldServices.takeUnless { it == "null" || it.isBlank() }.orEmpty()
                .split(':').filter { it.isNotBlank() }.toMutableSet().apply { add(component) }.joinToString(":")
            shell("settings put secure enabled_accessibility_services $enabledServices")
            shell("settings put secure accessibility_enabled 1")
            assertEquals(enabledServices, shell("settings get secure enabled_accessibility_services"))
            main { preferences.confirmBeforeSave = false; preferences.onRight = true; preferences.verticalFraction = .5f; preferences.enabled = true }
            await("System must bind the actual accessibility service") { FloatingCaptureRuntime.connected.value && ballVisible() }

            showFixture("1.98")
            diagnosticFrame("before-first")
            click(BALL)
            await("Real screenshot must produce a saved 1.98 expense", 90_000) {
                app.repository.state.value?.entries?.any { it.active && it.amountMinor == 198L && it.originKind == OriginKind.OCR } == true
            }
            val first = app.repository.snapshot().entries.single()
            assertEquals(EntryKind.EXPENSE, first.kind)
            assertEquals(198L, first.amountMinor)
            assertFalse(first.captureFingerprint.isNullOrBlank())
            await("Saved result notification") { savedNotification(fresh.datasetId, first.id) != null && ballVisible() }
            val firstNotification = assertNotNull(savedNotification(fresh.datasetId, first.id))
            assertEquals("已记支出 ¥1.98", firstNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertExternalForeground("com.dawnyew.fixture")
            log("PASS external UID=$fixtureUid -> real screenshot -> offline OCR -> EXPENSE 198 -> committed notification; DawnYew remained background")

            val firstUndo = assertNotNull(firstNotification.notification.actions.firstOrNull { it.title.toString() == "撤销" })
            firstUndo.actionIntent.send()
            await("Notification undo must move the exact entry to trash") { app.repository.state.value?.entries?.singleOrNull()?.active == false }
            assertEquals(first.id, app.repository.snapshot().entries.single().id)
            await("Undo result notification") { savedNotification(fresh.datasetId, first.id)?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "已撤销截图记账" }
            log("PASS actual notification undo PendingIntent -> guarded transaction -> original OCR entry moved to trash")

            // A different notification/system-bar frame is a different image. Restore the exact
            // fullscreen fixture before asserting the production exact-pixel fingerprint contract.
            notifications.cancel(firstNotification.tag, firstNotification.id)
            SystemClock.sleep(6_000)
            val receiptsBeforeDuplicate = app.repository.snapshot().receipts.size
            diagnosticFrame("before-duplicate")
            click(BALL)
            await("Repeated screenshot must finish its deduplicated transaction", 90_000) {
                (app.repository.state.value?.receipts?.size ?: 0) > receiptsBeforeDuplicate && ballVisible()
            }
            log("Repeated screen fingerprints=" + app.repository.snapshot().entries.map { it.captureFingerprint })
            assertEquals(1, app.repository.snapshot().entries.size)
            assertFalse(app.repository.snapshot().entries.single().active, "Same screenshot must not resurrect a trashed entry")
            log("PASS identical actual screen deduplicates against trash without resurrection")

            shell("am start -a android.settings.SETTINGS")
            await("Global ball on system Settings") { nodes().any { it.packageName?.toString() == "com.android.settings" } && ballVisible() }
            assertExternalForeground("com.android.settings")
            log("PASS real accessibility overlay remains visible over system Settings")

            main { preferences.confirmBeforeSave = true }
            showFixture("35.50")
            click(BALL)
            await("One global editable confirmation must show OCR 35.50", 90_000) {
                nodes().any { matches(it, "确认这笔记账") } && nodes().any { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == "35.50" }
            }
            assertEquals(1, app.repository.snapshot().entries.size, "Confirmation mode must not commit before confirmation")
            val amount = nodes().first { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == "35.50" }
            assertTrue(amount.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "36.50") }))
            click("确认记账")
            await("Edited amount must commit only after global confirmation") {
                app.repository.state.value?.entries?.any { it.active && it.amountMinor == 3650L } == true
            }
            val second = app.repository.snapshot().entries.single { it.active }
            assertEquals(EntryKind.EXPENSE, second.kind)
            assertEquals(OriginKind.OCR, second.originKind)
            await("Edited capture result notification") { savedNotification(fresh.datasetId, second.id) != null && ballVisible() }
            assertExternalForeground("com.dawnyew.fixture")
            log("PASS actual second screenshot -> OCR 3550 -> global editable confirmation -> edited 3650 committed exactly once")

            // A fresh storage facade reads disk independently of the existing in-memory state flow.
            val reopened = GenerationRepository(context, app.environment) { app.session.authorized }
            try {
                reopened.initialize()
                assertEquals(second, reopened.snapshot().entries.single { it.id == second.id })
            } finally { reopened.close() }
            log("PASS saved OCR entry survives independent on-disk repository reopen")

            val staleUndo = assertNotNull(savedNotification(fresh.datasetId, second.id)?.notification?.actions?.firstOrNull { it.title.toString() == "撤销" })
            val editOperation = UUID.randomUUID().toString()
            app.repository.execute(Command.SaveEntry(second.id, second.ledgerId, second.kind, 3750L, second.categoryId,
                second.occurrence, second.note, second.revision, second.originKind, second.captureFingerprint, second.captureEngineVersion), editOperation)
            app.repository.acknowledgeOperation(editOperation)
            notifications.cancel(1912)
            staleUndo.actionIntent.send()
            await("Stale notification undo must report failure") {
                notifications.activeNotifications.any {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "截图记账未完成" &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("账单已修改") == true
                }
            }
            val edited = app.repository.snapshot().entries.single { it.id == second.id }
            assertTrue(edited.active, "Old notification must not delete a subsequently edited entry")
            assertEquals(3750L, edited.amountMinor)
            assertEquals(second.revision + 1, edited.revision)
            log("PASS stale actual notification undo rejects revised entry and preserves the edit")

            main { preferences.enabled = false; app.floatingCapture.cancelPending(); FloatingCaptureAccessibilityService.dismissPending() }
            await("Disabling must remove global windows") { !ballVisible() && nodes().none { matches(it, "确认这笔记账") } }
            log("PASS feature disabled removes global overlay")
        } finally {
            main { preferences.enabled = false; app.floatingCapture.cancelPending(); FloatingCaptureAccessibilityService.dismissPending() }
            baseline?.let { app.repository.replace(it) }
            testDataset?.let { dataset -> notifications.activeNotifications.filter { it.tag?.startsWith("$dataset/") == true }.forEach { notifications.cancel(it.tag, it.id) } }
            notifications.cancel(1912)
            if (oldServices == "null" || oldServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            if (oldAccessibility == "null" || oldAccessibility.isBlank()) shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $oldAccessibility")
            main {
                preferences.confirmBeforeSave = oldConfirm; preferences.onRight = oldRight; preferences.verticalFraction = oldFraction
                preferences.enabled = oldEnabled; FloatingCaptureRuntime.refresh(context)
            }
        }
    }

    private companion object { const val BALL = "截屏记账，拖动调整位置" }
}
