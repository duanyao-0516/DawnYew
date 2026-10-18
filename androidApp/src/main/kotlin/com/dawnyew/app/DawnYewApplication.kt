package com.dawnyew.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dawnyew.platform.AndroidEnvironment
import com.dawnyew.platform.security.AppLockStore
import com.dawnyew.platform.security.SessionCoordinator
import com.dawnyew.platform.storage.GenerationRepository
import com.dawnyew.presentation.AppController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** A picker continuation is process-only and contains no Activity or view references. */
data class PendingExternal(val type: String, val lease: SessionCoordinator.Lease, val databaseEpoch: Long, val file: File? = null,
    val password: CharArray = charArrayOf(), var returnedUri: Uri? = null)

class DawnYewApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var environment: AndroidEnvironment; private set
    lateinit var lockStore: AppLockStore; private set
    lateinit var repository: GenerationRepository; private set
    lateinit var controller: AppController; private set
    lateinit var session: SessionCoordinator; private set
    private var externalExpiry: Job? = null
    var pendingExternal: PendingExternal? = null
        set(value) {
            externalExpiry?.cancel();field = value
            if (value != null) externalExpiry = applicationScope.launch {
                delay((value.lease.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 1)
                if (pendingExternal === value) revokeSession()
            }
        }
    val sessionRevocations = MutableStateFlow(0L)
    val startupError = MutableStateFlow<String?>(null)
    private var initializing = false
    private var startupStagingNeedsCleanup = false
    override fun onCreate() {
        super.onCreate(); environment = AndroidEnvironment(); lockStore = AppLockStore(this)
        session = SessionCoordinator(SystemClock::elapsedRealtime).also { it.configureLock(lockStore.enabled) }
        // Secrets, provider leases and continuations are deliberately absent from saved state.
        val staging = File(noBackupFilesDir, "staging")
        if (staging.exists() && !staging.deleteRecursively()) {startupStagingNeedsCleanup = true;startupError.value = "上次操作的暂存文件未能清理，请重试后继续。"}
        repository = GenerationRepository(this, environment) { session.authorized }; controller = AppController(repository, environment, applicationScope)
        applicationScope.launch { repository.failureReason.collect { if (it != null) startupError.value = it } }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) revokeSession() }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun discardExternal() {
        pendingExternal?.let { operation ->
            session.cancelExternal(operation.lease);operation.password.fill('\u0000')
            if (operation.file?.exists() == true && !operation.file.delete()) controller.notify("暂存文件清理失败，将在下次启动时重试")
            if (operation.type == "export" && operation.returnedUri != null) applicationScope.launch {
                val removed = withContext(Dispatchers.IO) { com.dawnyew.platform.files.LocalDocumentAccess(this@DawnYewApplication).deleteCreated(operation.returnedUri!!) }
                if (!removed) controller.notify("已取消导出；所选位置可能残留空文件，请手动检查：${operation.returnedUri}")
            }
        }
        pendingExternal = null
    }
    fun revokeSession() { session.revoke(); discardExternal();sessionRevocations.value++ }
    fun initialize() {
        if (initializing || (repository.state.value != null && !startupStagingNeedsCleanup)) return
        initializing = true
        applicationScope.launch {
            try {
                if (startupStagingNeedsCleanup) {
                    withContext(Dispatchers.IO) { val staging = File(noBackupFilesDir, "staging"); if (staging.exists() && !staging.deleteRecursively()) throw java.io.IOException("暂存文件仍无法清理") }
                    startupStagingNeedsCleanup = false
                }
                repository.initialize(); startupError.value = null
            }
            catch (e: Exception) { startupError.value = "本地数据暂时无法打开。为保护数据，未创建空账本覆盖。${e.message.orEmpty()}" }
            finally { initializing = false }
        }
    }
    fun recovered() { if (!startupStagingNeedsCleanup) startupError.value = null }
}
