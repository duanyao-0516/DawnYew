package com.dawnyew.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.MediaStore
import android.content.ContentValues
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.app.capture.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class CaptureDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = context as DawnYewApplication
    private val output get() = File(context.getExternalFilesDir(null), "capture-validation").apply { mkdirs() }
    private fun log(message: String) { File(output, "evidence.txt").appendText(message + "\n") }
    private fun waitFor(timeout: Long = 25_000, message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; instrument.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("$message\n" + nodes().joinToString("\n") { "${it.text} | ${it.contentDescription}" })
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrument.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node != null) { result += node; repeat(node.childCount) { visit(node.getChild(it)) } } }
        visit(instrument.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun click(vararg labels: String) {
        waitFor(message = "Missing ${labels.toList()}") { nodes().any { it.isVisibleToUser && (it.text?.toString() in labels || it.contentDescription?.toString() in labels) } }
        var node: AccessibilityNodeInfo? = nodes().first { it.isVisibleToUser && (it.text?.toString() in labels || it.contentDescription?.toString() in labels) }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
    }
    private fun prepare() {
        assertTrue(context.packageName.endsWith(".validation"))
        context.stopService(Intent(context, ScreenshotWatchService::class.java))
        instrument.runOnMainSync { CaptureRuntime.clear(context); app.pendingCapture = null; app.controller.clearEditor() }
        assertFalse(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
            it.notification.channelId == CaptureNotifications.RESULT_CHANNEL
        }, "The fixture must begin with no stale capture result")
    }

    @Test fun quickSettingsTileCapturesActualScreenAndRequiresFreshConsent() = runBlocking {
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(message = "Main initialization") { app.controller.ui.value.dataset != null && app.session.authorized }
        }
        val before = app.repository.snapshot().entries.size
        val component = "${context.packageName}/com.dawnyew.app.capture.ScreenCaptureTileService"
        shell("cmd statusbar add-tile $component")
        ActivityScenario.launch(CaptureFixtureActivity::class.java).use { fixture ->
            SystemClock.sleep(500)
            shell("cmd statusbar click-tile $component")
            waitFor(message = "Screen capture consent must be visible") { nodes().any { it.text?.toString() in setOf("立即开始", "开始", "Start now", "开始录制", "开始共享") } }
            click("立即开始", "开始", "Start now", "开始录制", "开始共享")
            waitFor(timeout = 45_000, message = "Real captured screen should become editable amount 12.34") { app.controller.editor.value?.amount == "12.34" }
            assertEquals(before, app.repository.snapshot().entries.size, "Recognition must not save without confirmation")
            assertNotNull(app.controller.editor.value!!.capture)
            assertFalse(app.controller.editor.value!!.confirmed)
            log("PASS QS Tile -> system consent -> actual synthetic payment frame -> offline OCR 12.34 -> review editor; zero automatic ledger writes")
            instrument.runOnMainSync { app.controller.clearEditor() }
            fixture.onActivity { it.startActivity(Intent(it, CaptureFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            SystemClock.sleep(400)
            shell("cmd statusbar click-tile $component")
            waitFor(message = "Second screenshot requires new consent") { nodes().any { it.text?.toString() in setOf("立即开始", "开始", "Start now", "开始录制", "开始共享") } }
            click("取消", "Cancel")
            SystemClock.sleep(500)
            assertNull(app.controller.editor.value)
            log("PASS second Tile click requests fresh consent; cancellation creates no draft")
        }
    }

    @Test fun newScreenshotWatcherAndShareUseSameOfflineReview() = runBlocking {
        prepare()
        shell("pm grant ${context.packageName} android.permission.READ_MEDIA_IMAGES")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(message = "Main authorized") { app.session.authorized && app.controller.ui.value.dataset != null }
            val before = app.repository.snapshot().entries.size
            scenario.onActivity { it.capturePlatform.setAutomaticCapture(true) }
            waitFor(message = "Watcher registration") { CaptureRuntime.settings.value.automaticRunning && CaptureRuntime.settings.value.message?.contains("仅监听") == true }
            val resultBaseline = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.map { it.key }.toSet()
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Screenshot_DawnYew_UI_test_${System.currentTimeMillis()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png"); put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Screenshots/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            try {
                val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
                instrument.runOnMainSync { CaptureFixtureActivity.PaymentView(context, "23.45").apply { layout(0, 0, 720, 1280); draw(Canvas(bitmap)) } }
                resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                waitFor(timeout = 45_000, message = "New screenshot review notification") { manager.activeNotifications.any { it.notification.channelId == CaptureNotifications.RESULT_CHANNEL && it.key !in resultBaseline } }
                val result = manager.activeNotifications.first { it.notification.channelId == CaptureNotifications.RESULT_CHANNEL && it.key !in resultBaseline }
                result.notification.contentIntent.send()
                waitFor(message = "Watcher result opens offline editor") { app.controller.editor.value?.amount == "23.45" }
                assertEquals(before, app.repository.snapshot().entries.size)
                log("PASS new MediaStore screenshot -> watcher bounded OCR -> notification -> editable 23.45; zero automatic writes")
                val firstOperation = app.controller.editor.value!!.operationId
                instrument.runOnMainSync { app.controller.clearEditor() }
                context.startService(Intent(context, ScreenshotWatchService::class.java).setAction(ScreenshotWatchService.ACTION_STOP))
                waitFor(message = "Watcher stopped") { !CaptureRuntime.settings.value.automaticRunning }
                context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                waitFor(timeout = 45_000, message = "Shared image opens same review") { app.controller.editor.value?.amount == "23.45" }
                assertNotEquals(firstOperation, app.controller.editor.value!!.operationId)
                assertEquals(before, app.repository.snapshot().entries.size)
                log("PASS image sharing -> same offline review amount; watcher stopped and synthetic image cleanup only")
            } finally {
                context.stopService(Intent(context, ScreenshotWatchService::class.java)); resolver.delete(uri, null, null)
                instrument.runOnMainSync { app.controller.clearEditor() }
            }
        }
    }
}
