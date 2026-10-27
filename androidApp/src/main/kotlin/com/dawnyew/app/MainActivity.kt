package com.dawnyew.app

import android.app.AlertDialog
import android.app.KeyguardManager
import androidx.activity.OnBackPressedCallback
import com.dawnyew.platform.security.SessionCoordinator
import com.dawnyew.platform.storage.VerifiedCandidate
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.platform.crypto.BackupCrypto
import com.dawnyew.platform.files.*
import com.dawnyew.app.capture.*
import com.dawnyew.presentation.*
import com.dawnyew.protocol.*
import com.dawnyew.platform.tabular.DawnYewCsvCodec
import com.dawnyew.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.io.*
import java.util.UUID

class MainActivity:FragmentActivity(),PlatformActions {
    private val app get()=application as DawnYewApplication
    private val controller get()=app.controller
    private val repository get()=app.repository
    private val localFiles by lazy {LocalDocumentAccess(this)}
    private val staging get()=File(noBackupFilesDir,"staging").apply {check(isDirectory || mkdirs()) {"无法创建本机暂存目录"}}
    private var unlocked by mutableStateOf(false)
    private var covered by mutableStateOf(true)
    private var retainContentForPicker by mutableStateOf(false)
    private var lockEnabled by mutableStateOf(false)
    private var capture by mutableStateOf<CaptureDraftUi?>(null)
    private var authentication:BiometricPrompt?=null
    private var authRunning=false
    private var external:PendingExternal?
        get()=app.pendingExternal
        set(value) {app.pendingExternal=value}
    private var fileJob:Job?=null
    private var pendingShare:Uri?=null
    private var pendingShortcut:String?=null
    private var pendingCaptureAction:String?=null
    private var pendingFloatingAction:FloatingCaptureAction?
        get()=app.pendingFloatingAction
        set(value) { app.pendingFloatingAction=value }
    private var pendingRestoreCandidate:VerifiedCandidate?=null
    private var restorePreviewDialog:AlertDialog?=null
    private var csvDialog:AlertDialog?=null
    private val sessionEpoch get()=app.session.epoch
    private var resumed=false
    private val screenshotCapture = AndroidCaptureController(this) {
        resumed && unlocked && !covered && app.session.authorized && !deviceLocked
    }
    override val capturePlatform: CapturePlatform get() = screenshotCapture
    private var authAttempt:SessionCoordinator.Lease?=null
    private var authSuccess:(()->Unit)?=null
    private var authFailure:(()->Unit)?=null
    private var authCompleted=false
    private var backHandler:(()->Unit)?=null
    private val formBackCallback=object:OnBackPressedCallback(false) {override fun handleOnBackPressed() {if(unlocked && !covered)backHandler?.invoke()}}
    private val deviceLocked get()=(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    private var microphonePermissionPending=false
    private val utilityPlatform:AndroidUtilityPlatform by lazy {AndroidUtilityPlatform(this,lifecycleScope,
        canUseTools={resumed && unlocked && !covered && app.session.authorized && !deviceLocked},
        currentPage={controller.navigation.value.page},
        requestMicrophone={if(!microphonePermissionPending) {microphonePermissionPending=true;microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)}})}
    private val microphonePermission:ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        microphonePermissionPending=false
        utilityPlatform.microphonePermissionResult(granted)
        if(granted) controller.notify("麦克风权限已允许，请在噪音测量页点击开始")
    }
    private var documentLauncher:ActivityResultLauncher<Intent>?=null
    private fun registerDocumentLauncher(operation:PendingExternal) {
        releaseDocumentLauncher()
        // A distinct registry key binds late results to their original operation, including after rotation.
        val key="document-${operation.lease.process}-${operation.lease.id}"
        documentLauncher=activityResultRegistry.register(key,ActivityResultContracts.StartActivityForResult()) {result->
            if(external!==operation || operation.resultReceived) return@register
            if(!app.session.externalIsCurrent(operation.lease) || operation.databaseEpoch!=repository.epoch) {
                app.revokeSession();unlocked=app.session.authorized;retainContentForPicker=false
                controller.notify("文件操作授权已过期，请重新发起")
                return@register
            }
            operation.resultReceived=true
            operation.returnedUri=if(result.resultCode==RESULT_OK)result.data?.data else null
            continueReturnedExternal()
        }
    }
    private fun releaseDocumentLauncher() {documentLauncher?.unregister();documentLauncher=null}
    private fun continueReturnedExternal() {
        val operation=external ?: return
        if(!operation.resultReceived || !resumed || authRunning)return
        if(!app.session.externalIsCurrent(operation.lease) || operation.databaseEpoch!=repository.epoch || deviceLocked) {app.revokeSession();unlocked=app.session.authorized;return}
        if(!app.session.resumeExternal(operation.lease)) {app.revokeSession();unlocked=app.session.authorized;return}
        unlocked=app.session.authorized;retainContentForPicker=false;releaseDocumentLauncher()
        controller.resumeVisibleQueries()
        val uri=operation.returnedUri
        if(uri==null) {app.session.finishExternal(operation.lease);app.discardExternal();updateForegroundAccess();return}
        val action:()->Unit={
            if(external!==operation || operation.databaseEpoch!=repository.epoch || !app.session.finishExternal(operation.lease)) app.discardExternal()
            else {
                external=null
                when(operation.type) {
                    "export" -> finishExport(uri,requireNotNull(operation.file))
                    "restore" -> readRestore(uri,operation.password)
                    "image" -> recognize(uri)
                    "csv-import" -> readCsv(uri,requireNotNull(operation.targetLedgerId))
                }
            }
        }
        // A screenshot uses the existing session; export and restore retain their fresh sensitive-operation check.
        if(operation.type=="image") action()
        else sensitive("继续本地文件操作",onCancelled={app.discardExternal()},action=action)
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        // The system owns the branded launch window; normal content uses the app theme.
        setTheme(R.style.Theme_DawnYew)
        super.onCreate(savedInstanceState);enableEdgeToEdge();onBackPressedDispatcher.addCallback(this,formBackCallback)
        lockEnabled=app.lockStore.enabled;unlocked=app.session.authorized
        external?.takeIf {app.session.externalIsCurrent(it.lease)}?.let {retainContentForPicker=true;registerDocumentLauncher(it)}
        lifecycleScope.launch {app.sessionRevocations.collect {
            utilityPlatform.background();capture=null
            unlocked=app.session.authorized
            if(external==null) {retainContentForPicker=false;releaseDocumentLauncher()}
            if(authAttempt?.let {attempt->!app.session.authenticationIsCurrent(attempt)}==true)cancelAuthentication()
            fileJob?.cancel();restorePreviewDialog?.dismiss();restorePreviewDialog=null;csvDialog?.dismiss();csvDialog=null;updateForegroundAccess()
        }}
        updateSecureWindow();acceptShare(intent);acceptShortcut(intent);acceptCapture(intent);acceptFloating(intent)
        lifecycleScope.launch {
            combine(controller.ui,controller.pendingOperation) { _, _ -> Unit }.collect {
                if(resumed) {screenshotCapture.refresh();consumeShare()}
                updateForegroundAccess()
            }
        }
        if(unlocked) app.initialize()
        setContent {
            val startupError by app.startupError.collectAsState()
            Box(Modifier.fillMaxSize()) {
                if(unlocked || retainContentForPicker) {
                    Box(if(covered || !unlocked) Modifier.clearAndSetSemantics {} else Modifier) {
                    if(startupError!=null) DawnYewTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.Center) {Text(startupError!!);Button(onClick={app.initialize()}) {Text("重试读取")};Button(onClick={restoreProtectionBackup()}) {Text("验证并恢复保护副本")}}}}
                    else DawnYewApp(controller,this@MainActivity,lockEnabled,capture,utilities=utilityPlatform,onBackHandlerChanged={handler->backHandler=handler;formBackCallback.isEnabled=handler!=null}) {capture=null}
                    }
                }
                // Material3 Surface blocks touches to siblings behind it while allowing its own unlock button.
                if(covered || !unlocked) DawnYewTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(32.dp).safeDrawingPadding(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Text("DawnYew",style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(16.dp));Text(if(app.lockStore.repairRequired) "安全设置需要通过设备认证修复" else "账本已保护")
                        if(!covered) {Spacer(Modifier.height(24.dp));Button(onClick={unlock()}) {Text("解锁账本")}}
                    }
                }}
            }
        }
    }
    override fun onResume() {
        super.onResume();resumed=true;covered=false
        app.session.foreground(deviceLocked);unlocked=app.session.authorized
        external?.let {
            // Resuming without this launcher's result is an unrelated return, not a picker continuation.
            if(!it.resultReceived || !app.session.externalIsCurrent(it.lease)) {app.revokeSession();unlocked=app.session.authorized;retainContentForPicker=false;releaseDocumentLauncher()}
        }
        if(authAttempt?.let { !app.session.authenticationIsCurrent(it) }==true) cancelAuthentication()
        completeAuthentication()
        if(unlocked) {app.initialize();consumeShare()}
        continueReturnedExternal()
        utilityPlatform.foreground()
        screenshotCapture.refresh()
        updateForegroundAccess()
    }
    override fun onPause() {
        resumed=false;covered=true
        updateForegroundAccess()
        utilityPlatform.background()
        app.session.background(isChangingConfigurations);unlocked=app.session.authorized
        if(!isChangingConfigurations) fileJob?.cancel()
        restorePreviewDialog?.dismiss();restorePreviewDialog=null;csvDialog?.dismiss();csvDialog=null
        super.onPause()
    }
    override fun onDestroy() {
        controller.mbti.suspendAccess()
        controller.recurring.updateAccess(ForegroundAccess(false,sessionEpoch,repository.epoch))
        utilityPlatform.background()
        authAttempt?.let(app.session::cancelAuthentication)
        authAttempt=null;authSuccess=null;authFailure?.invoke();authFailure=null;authCompleted=false
        authentication?.cancelAuthentication();authentication=null;authRunning=false
        releaseDocumentLauncher()
        restorePreviewDialog?.dismiss();pendingRestoreCandidate?.let(::discardRestoreCandidate);pendingRestoreCandidate=null
        // A legitimate picker rotation keeps only its process-owned operation payload.
        if(!isChangingConfigurations) app.discardExternal()
        super.onDestroy()
    }
    override fun onNewIntent(intent:Intent) {
        app.pendingCapture?.takeIf { it.token != intent.getStringExtra(CaptureRuntime.EXTRA_TOKEN) }?.let { CaptureRuntime.discard(this, it.token) }
        app.pendingCapture = null
        super.onNewIntent(intent);utilityPlatform.background();app.revokeSession();cancelAuthentication();fileJob?.cancel();unlocked=app.session.authorized
        retainContentForPicker=false;releaseDocumentLauncher();updateForegroundAccess()
        pendingShare=null;pendingShortcut=null;pendingCaptureAction=null;pendingFloatingAction=null;setIntent(intent);acceptShare(intent);acceptShortcut(intent);acceptCapture(intent);acceptFloating(intent);if(unlocked) consumeShare()
    }
    private fun acceptShare(intent:Intent?) {
        if(intent?.action!=Intent.ACTION_SEND) return
        if(intent.type !in setOf("image/png","image/jpeg") || (intent.clipData?.itemCount ?: 1)!=1) {controller.notify("只支持分享一张 PNG 或 JPEG 截图");return}
        @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        // Keep only an opaque URI while locked: no provider query, thumbnail or byte read.
        pendingShare=uri
        intent.action=null
    }
    private fun consumeShare() {
        if(!resumed || !unlocked || covered || !app.session.authorized || deviceLocked) return
        val uri=pendingShare
        if(uri!=null) {pendingShare=null;recognize(uri)} else if(!consumeFloating() && !consumeCapture()) consumeShortcut()
    }
    private fun acceptFloating(intent: Intent?) {
        pendingFloatingAction = FloatingCaptureNotifications.action(intent) ?: return
        intent?.action = null
    }
    private fun consumeFloating(): Boolean {
        val action = pendingFloatingAction ?: return false
        val data = controller.ui.value.dataset ?: return true
        if (external != null || fileJob?.isActive == true || controller.ui.value.busy || controller.pendingOperation.value != null) return true
        pendingFloatingAction = null
        if (action.kind == "resume") {
            runFileTask { app.floatingCapture.resume(action.token) }
        } else {
            val entry = data.entries.firstOrNull { it.id == action.entryId }
            if (data.datasetId != action.datasetId || entry == null) controller.notify("这笔账单不属于当前数据，请切回原账本数据查看")
            else if (!entry.active) controller.notify("这笔账单已在回收站")
            else if (action.kind == "edit") controller.beginEditor(entry.ledgerId, editId = entry.id)
            else controller.finance.open("账单详情", entry.id)
        }
        return true
    }
    private fun acceptCapture(intent: Intent?) {
        when (intent?.action) {
            CaptureRuntime.ACTION_REVIEW -> {
                val token = intent.getStringExtra(CaptureRuntime.EXTRA_TOKEN)
                if (token != null && runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false))
                    app.pendingCapture = PendingCaptureDelivery(token, sessionEpoch)
                intent.action = null
            }
            AndroidCaptureController.ACTION_SETTINGS -> {
                pendingShortcut = "CAPTURE_SETTINGS"
                // Keep the opaque action token until normal unlock and repository readiness.
                pendingCaptureAction = intent.getStringExtra(AndroidCaptureController.EXTRA_ACTION_TOKEN)
                intent.removeExtra(AndroidCaptureController.EXTRA_ACTION_TOKEN)
                intent.action = null
            }
        }
    }
    /** Consumes only after the new session is authorized and the active generation is known. */
    private fun consumeCapture(): Boolean {
        val pending = app.pendingCapture ?: return false
        if (controller.ui.value.dataset == null || external != null || fileJob?.isActive == true || controller.ui.value.busy || controller.pendingOperation.value != null) return true
        app.pendingCapture = null
        if (pending.sessionEpoch != sessionEpoch) {
            CaptureRuntime.discard(this, pending.token); controller.notify("截图授权已过期，请重新截屏"); return true
        }
        val input = CaptureRuntime.take(this, pending.token, repository.epoch)
        if (input == null) { controller.notify("截图已过期或本地数据已切换，请重新截屏"); return true }
        val epoch = sessionEpoch
        val generation = repository.epoch
        runFileTask(onRejected = { if (input is CapturePayload.Pixels) input.bitmap.recycle() }) {
            val result = when (input) {
                is CapturePayload.Pixels -> CaptureRecognizer.fromBitmap(this@MainActivity, input.bitmap)
                is CapturePayload.Recognized -> input.result
            }
            if (epoch == sessionEpoch && generation == repository.epoch && unlocked && !deviceLocked) acceptRecognition(result)
        }
        return true
    }
    private fun acceptShortcut(intent:Intent?) {
        if(intent?.action!=QuickEntryWidget.ACTION_QUICK_ENTRY) return
        pendingShortcut=intent.getStringExtra(QuickEntryWidget.EXTRA_KIND)?.takeIf {it in setOf("EXPENSE","INCOME","OCR")}
        intent.action=null
    }
    private fun consumeShortcut() {
        val kind=pendingShortcut ?: return
        if(!resumed || !unlocked || covered || !app.session.authorized || deviceLocked || external!=null || controller.ui.value.busy || controller.pendingOperation.value!=null) return
        val data=controller.ui.value.dataset ?: return
        pendingShortcut=null
        if(kind=="CAPTURE_SETTINGS") {
            controller.finance.open("自动")
            val actionToken = pendingCaptureAction
            pendingCaptureAction = null
            if(actionToken != null && CaptureActionRuntime.consume(actionToken, CaptureAction.ENABLE_AUTOMATIC)) screenshotCapture.setAutomaticCapture(true)
            return
        }
        if(kind=="OCR") {chooseScreenshot();return}
        val ledger=controller.navigation.value.homeScope.ledgerIds.singleOrNull()?.takeIf {id->data.ledgers.any {it.id==id && !it.archived}}
            ?: data.ledgers.firstOrNull {!it.archived}?.id
        controller.beginEditor(ledger)
        if(kind=="INCOME") controller.editor.value?.let {controller.changeEditor(it.operationId,EditorChange.Kind(EntryKind.INCOME))}
    }
    override fun requestWidget() {
        if(!resumed || !unlocked || covered || deviceLocked) return
        val manager=android.appwidget.AppWidgetManager.getInstance(this)
        if(manager.isRequestPinAppWidgetSupported) {
            if(!manager.requestPinAppWidget(android.content.ComponentName(this,QuickEntryWidget::class.java),null,null))
                controller.notify("请长按桌面，在小组件中添加 DawnYew 快捷记账")
        } else controller.notify("请长按桌面，在小组件中添加 DawnYew 快捷记账")
    }
    private fun updateForegroundAccess() {
        val ready=resumed && unlocked && !covered && app.session.authorized && !deviceLocked && repository.state.value!=null &&
            external==null && !authRunning && fileJob?.isActive!=true && restorePreviewDialog?.isShowing!=true && csvDialog?.isShowing!=true
        controller.recurring.updateAccess(ForegroundAccess(
            ready=ready,
            sessionEpoch=sessionEpoch,databaseEpoch=repository.epoch))
        controller.mbti.updateAccess(ready && !controller.ui.value.busy && controller.pendingOperation.value==null,sessionEpoch)
    }
    private fun updateSecureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun unlock()=authenticate("解锁 DawnYew") {
        if(app.lockStore.repairRequired) app.lockStore.persist(true)
        lockEnabled=app.lockStore.enabled;app.session.configureLock(lockEnabled);unlocked=true;covered=false;updateSecureWindow();app.initialize();screenshotCapture.refresh();consumeShare()
    }
    private fun authenticate(reason:String,onCancelled:()->Unit={},onSuccess:()->Unit) {
        if(authRunning) {onCancelled();return}
        val authenticators=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(BiometricManager.from(this).canAuthenticate(authenticators)!=BiometricManager.BIOMETRIC_SUCCESS) {
            onCancelled();controller.notify("请先在系统中设置安全锁屏或可用的强生物识别")
            AlertDialog.Builder(this).setTitle("设备认证不可用").setMessage("请先在系统设置中启用 PIN、图案、密码或支持的强生物识别，然后返回重试。不会使用自定义弱口令替代。").setPositiveButton("知道了",null).show();return
        }
        val attempt=app.session.beginAuthentication();unlocked=app.session.authorized;authAttempt=attempt;authSuccess=onSuccess;authFailure=onCancelled;authCompleted=false;authRunning=true;updateForegroundAccess()
        authentication=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                if(authAttempt!=attempt || !app.session.authenticationIsCurrent(attempt))return
                authCompleted=true;completeAuthentication()
            }
            override fun onAuthenticationError(code:Int,message:CharSequence) {
                if(authAttempt!=attempt)return
                cancelAuthentication();controller.notify("认证未完成，请重试")
            }
        })
        authentication!!.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(reason).setSubtitle("仅使用设备本地认证").setAllowedAuthenticators(authenticators).build())
    }
    private fun completeAuthentication() {
        val attempt=authAttempt ?: return
        if(!authCompleted || !resumed)return
        if(!app.session.finishAuthentication(attempt,resumed,deviceLocked)) {cancelAuthentication();return}
        val action=authSuccess;authAttempt=null;authSuccess=null;authFailure=null;authRunning=false;authCompleted=false
        unlocked=true;covered=false;utilityPlatform.foreground();try {action?.invoke()} catch(e:Exception) {app.revokeSession();unlocked=app.session.authorized;controller.notify("认证后的操作未完成，请重试：${e.message.orEmpty().take(100)}")}
        updateForegroundAccess()
    }
    private fun cancelAuthentication() {
        authAttempt?.let(app.session::cancelAuthentication);authAttempt=null;authSuccess=null;authCompleted=false;authRunning=false
        val cancel=authFailure;authFailure=null;authentication?.cancelAuthentication();unlocked=app.session.authorized;cancel?.invoke();updateForegroundAccess()
    }
    private fun sensitive(reason:String,onCancelled:()->Unit={},action:()->Unit) {
        if(!resumed || deviceLocked) {onCancelled();return}
        if(lockEnabled) authenticate(reason,onCancelled,action) else if(unlocked) action() else onCancelled()
    }
    override fun setAppLock(enabled:Boolean) {
        if(enabled==lockEnabled) return
        authenticate(if(enabled) "启用应用锁" else "关闭应用锁") {
            app.lockStore.persist(enabled);lockEnabled=enabled;app.session.configureLock(enabled);unlocked=true;updateSecureWindow();controller.notify(if(enabled) "已启用应用锁，离开应用后立即锁定" else "已关闭应用锁")
        }
    }
    private fun launchExternal(operation:PendingExternal,intent:Intent) {
        if(external!=null) {operation.password.fill('\u0000');operation.file?.delete();controller.notify("已有文件选择操作进行中");return}
        external=operation;retainContentForPicker=true;updateForegroundAccess()
        try {registerDocumentLauncher(operation);requireNotNull(documentLauncher).launch(intent)}
        catch(e:Exception) {app.discardExternal();retainContentForPicker=false;releaseDocumentLauncher();controller.notify("设备没有可用的本地文件选择器")}
    }
    private fun pending(type:String,file:File?=null,password:CharArray=charArrayOf())=PendingExternal(type,app.session.beginExternal(),repository.epoch,file,password)
    private fun runFileTask(onRejected:()->Unit={},block:suspend CoroutineScope.()->Unit) {
        if(fileJob?.isActive==true || controller.ui.value.busy || controller.pendingOperation.value!=null) {onRejected();controller.notify("请等待当前操作完成并确认保存结果");return}
        controller.setBusy(true)
        controller.mbti.suspendAccess()
        fileJob=lifecycleScope.launch {
            try {block()} catch(e:CancellationException) {throw e}
            catch(e:Exception) {controller.notify(if(e is DomainException) e.message else "操作失败：${e.message?.take(160) ?: e::class.simpleName}")}
            finally {fileJob=null;controller.setBusy(false);updateForegroundAccess()}
        }
    }
    override fun exportBackup(encrypted:Boolean,password:CharArray) = sensitive("授权导出备份",onCancelled={password.fill('\u0000')}) {
        if(external!=null) {password.fill('\u0000');controller.notify("已有文件选择操作进行中");return@sensitive}
        runFileTask(onRejected={password.fill('\u0000')}) {
            var prepared:File?=null
            var transferred=false
            try {
                val file=withContext(Dispatchers.IO) {
                    repository.ensureSpace()
                    val plain=File(staging,"export-${UUID.randomUUID()}.json")
                    try {
                        FileOutputStream(plain).use {repository.exportBackup(it,app.environment.utcNow(),checkCancelled={ensureActive()});it.fd.sync()}
                        plain.inputStream().use {JsonStreamCodec.prepare(it,staging,checkCancelled={ensureActive()},ensureSpace=repository::ensureSpace).close()}
                        if(encrypted) {
                            val enc=File(staging,"export-${UUID.randomUUID()}.dwybak")
                            try {plain.inputStream().use {input->FileOutputStream(enc).use {out->BackupCrypto(staging,checkCancelled={ensureActive()},ensureSpace=repository::ensureSpace).encrypt(input,out,password);out.fd.sync()}};enc.also {prepared=it}}
                            catch(e:Exception) {enc.delete();throw e}
                            finally {plain.delete()}
                        } else {password.fill('\u0000');plain.also {prepared=it}}
                    } catch(e:Exception) {plain.delete();password.fill('\u0000');throw e}
                }
                val extension=if(encrypted) "dwybak" else "json"
                launchExternal(pending("export",file),Intent(Intent.ACTION_CREATE_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type=if(encrypted) "application/octet-stream" else "application/json";putExtra(Intent.EXTRA_TITLE,"DawnYew-${app.environment.localNow().take(10)}.$extension");putExtra(Intent.EXTRA_LOCAL_ONLY,true)})
                transferred=true
            } finally {
                password.fill('\u0000')
                if(!transferred) withContext(NonCancellable+Dispatchers.IO) {prepared?.let {if(it.exists() && !it.delete()) controller.notify("导出暂存清理失败，下次启动将重试")}}
            }
        }
    }
    private fun finishExport(uri:Uri,file:File)=runFileTask {
        var verified=false
        try {
            withContext(Dispatchers.IO) {
                localFiles.output(uri).use {out->file.inputStream().use {localFiles.copy(it,out,ensureSpace={ensureActive()})};out.flush()}
                val expected=file.inputStream().use {shaStream(it,checkCancelled={ensureActive()})}
                val actual=localFiles.input(uri).use {shaStream(it,checkCancelled={ensureActive()})}
                if(expected!=actual) throw IOException("写入后校验不一致")
            }
            verified=true
            if(file.extension=="csv") {controller.notify("CSV 已导出，已重新读取并校验文件完整性");return@runFileTask}
            val operationId=app.environment.id()
            repository.execute(Command.SetPreference("lastBackupAt",app.environment.utcNow()),operationId)
            controller.notify("备份已导出，已重新读取并核对文件完整性")
            repository.acknowledgeOperation(operationId)
        } catch(e:Exception) {
            if(verified) {
                controller.notify("备份文件已导出并校验；本机备份状态记录未完成，请确认上次操作结果")
                if(e is CancellationException)throw e
                return@runFileTask
            }
            val removed=withContext(NonCancellable+Dispatchers.IO) {localFiles.deleteCreated(uri)}
            if(!removed) {
                val message="导出未完成，所选位置可能残留文件，请手动删除：$uri"
                if(e is CancellationException) {controller.notify(message);throw e}
                throw IOException(message,e)
            }
            throw e
        }
        finally {file.delete()}
    }
    override fun restoreBackup(password:CharArray)=sensitive("授权读取备份",onCancelled={password.fill('\u0000')}) {
        if(external!=null) {password.fill('\u0000');controller.notify("已有文件选择操作进行中");return@sensitive}
        launchExternal(pending("restore",password=password),Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_LOCAL_ONLY,true)})
    }
    private fun readRestore(uri:Uri,password:CharArray)=runFileTask(onRejected={password.fill('\u0000')}) {
        var decoded:File?=null
        var candidate:VerifiedCandidate?=null
        var delivered=false
        // Bind the preview before any provider read or parsing, so concurrent edits cannot be swallowed.
        try {
            val baseline=repository.versionToken()
            val preparedCandidate=withContext(Dispatchers.IO) {
                repository.ensureSpace()
                localFiles.input(uri).buffered().use {input->
                    input.mark(8);val magic=ByteArray(8);var offset=0
                    while(offset<magic.size) {ensureActive();val n=input.read(magic,offset,magic.size-offset);if(n<0)break;if(n>0)offset+=n};input.reset()
                    val prepared=if(magic.contentEquals("DWYBAK\r\n".toByteArray())) {
                        val file=File(staging,"restore-${UUID.randomUUID()}.json");decoded=file
                        FileOutputStream(file).use {out->BackupCrypto(staging,checkCancelled={ensureActive()},ensureSpace=repository::ensureSpace).decrypt(input,out,password);out.fd.sync()}
                        file.inputStream().use {JsonStreamCodec.prepare(it,staging,checkCancelled={ensureActive()},ensureSpace=repository::ensureSpace)}
                    } else {password.fill('\u0000');JsonStreamCodec.prepare(input,staging,checkCancelled={ensureActive()},ensureSpace=repository::ensureSpace)}
                    prepared.use {repository.prepareRestore(it,baseline).also {value->candidate=value}}
                }
            }
            showRestorePreview(preparedCandidate,false);delivered=true
        } finally {
            password.fill('\u0000');decoded?.delete()
            if(!delivered) candidate?.let {withContext(NonCancellable) {repository.discardCandidate(it)}}
        }
    }
    override fun restoreProtectionBackup()=sensitive("验证本机保护副本") {
        runFileTask {
            val baseline=repository.versionToken()
            val file=File(staging,"protection-${UUID.randomUUID()}.json")
            var candidate:VerifiedCandidate?=null;var delivered=false
            try {
                val preparedCandidate=withContext(Dispatchers.IO) {
                    FileOutputStream(file).use {repository.exportProtection(it,app.environment.utcNow(),checkCancelled={ensureActive()});it.fd.sync()}
                    file.inputStream().use {JsonStreamCodec.prepare(it,staging,checkCancelled={ensureActive()},ensureSpace=repository::ensureSpace)}.use {
                        repository.prepareRestore(it,baseline).also {value->candidate=value}
                    }
                }
                showRestorePreview(preparedCandidate,true);delivered=true
            } finally {
                file.delete()
                if(!delivered) candidate?.let {withContext(NonCancellable) {repository.discardCandidate(it)}}
            }
        }
    }
    private fun discardRestoreCandidate(candidate:VerifiedCandidate) {
        app.applicationScope.launch {
            try {repository.discardCandidate(candidate)} catch(e:Exception) {controller.notify("恢复候选清理失败，下次读取将重试：${e.message.orEmpty().take(80)}")}
        }
    }
    private fun showRestorePreview(candidate:VerifiedCandidate,protection:Boolean) {
        restorePreviewDialog?.dismiss();pendingRestoreCandidate=candidate
        val counts=candidate.summary
        restorePreviewDialog=AlertDialog.Builder(this).setTitle(if(protection) "保护副本恢复预览" else "恢复预览")
            .setMessage("已完成格式、校验和、候选数据库及账务关系检查。\n${counts.counts.getValue("ledgers")} 个账本，${counts.activeEntryCount} 笔有效账单，${counts.deletedEntryCount} 笔回收站账单。\n${counts.counts.getValue("accounts")} 个账户，${counts.counts.getValue("transfers")} 笔资金流水，${counts.counts.getValue("recurringPlans")} 个周期计划，${counts.counts.getValue("savingsPlans")} 个存钱计划。\n确认后将整体替换当前数据；应用锁不变。")
            .setNegativeButton("取消",null).setPositiveButton("确认替换") {_,_->
                pendingRestoreCandidate=null
                sensitive("确认替换本地账本",onCancelled={discardRestoreCandidate(candidate)}) {
                    runFileTask(onRejected={discardRestoreCandidate(candidate)}) {
                        try {repository.replaceCandidate(candidate);app.recovered();controller.resetTransientState();capture=null;controller.notify("恢复完成；已验证的新数据代际现已生效")}
                        finally {
                            controller.mbti.synchronizeDataScope()
                            withContext(NonCancellable) {repository.discardCandidate(candidate)}
                        }
                    }
                }
            }.create().also {dialog->
                dialog.setOnDismissListener {if(pendingRestoreCandidate===candidate) {pendingRestoreCandidate=null;discardRestoreCandidate(candidate)};updateForegroundAccess()}
                dialog.show()
            }
    }
    override fun importCsv(ledgerId:String)=sensitive("授权导入本地 CSV") {
        val data=controller.ui.value.dataset ?: return@sensitive
        if(data.ledgers.none {it.id==ledgerId && !it.archived}) {controller.notify("请选择可写入的实际账本");return@sensitive}
        if(controller.ui.value.busy || external!=null || controller.pendingOperation.value!=null) {controller.notify("请先完成当前操作");return@sensitive}
        val operation=PendingExternal("csv-import",app.session.beginExternal(),repository.epoch,targetLedgerId=ledgerId)
        launchExternal(operation,Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/csv","text/comma-separated-values","application/csv","text/plain","application/octet-stream"));putExtra(Intent.EXTRA_LOCAL_ONLY,true)
        })
    }
    override fun exportCsv(scope:QueryScope)=sensitive("授权导出本地 CSV") {
        if(external!=null) {controller.notify("已有文件选择操作进行中");return@sensitive}
        runFileTask {
            var scratch:File?=null
            var handedToDialog=false
            try {
            val prepared=withContext(Dispatchers.IO) {DawnYewCsv.prepareExport(repository.snapshot(),scope)}
            val file=withContext(Dispatchers.IO) {
                val bytes=DawnYewCsvCodec.encode(prepared,checkCancelled={ensureActive()})
                repository.ensureSpace(bytes.size.toLong())
                File(staging,"export-${UUID.randomUUID()}.csv").also {file->
                    scratch=file
                    try {FileOutputStream(file).use {DawnYewCsvCodec.write(it,bytes,checkCancelled={ensureActive()});it.fd.sync()}}
                    catch(failure:Throwable) {file.delete();throw failure}
                }
            }
            var claimed=false
            val summary=prepared.summary
            csvDialog?.dismiss()
            csvDialog=AlertDialog.Builder(this@MainActivity).setTitle("CSV 导出预览")
                .setMessage("将导出 ${summary.includedCount} 笔普通收支。\n范围内另有 ${summary.excludedRefundCount} 笔退款、${summary.excludedTransferCount} 笔资金流水、${summary.excludedDeletedCount} 笔已删除记录不进入 CSV；${summary.omittedDetailsCount} 笔记录的账户或其他附加属性不包含在 CSV 中。\nCSV 不保存计划和资金关系；完整保留请使用 JSON 备份。")
                .setNegativeButton("取消",null).setPositiveButton("选择保存位置") {_,_->
                    claimed=true
                    sensitive("保存 CSV",onCancelled={file.delete()}) {
                        launchExternal(pending("export",file),Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE);type="text/csv";putExtra(Intent.EXTRA_TITLE,"DawnYew-${app.environment.localNow().take(10)}.csv");putExtra(Intent.EXTRA_LOCAL_ONLY,true)
                        })
                    }
                }.create().also {dialog->dialog.setOnDismissListener {if(csvDialog===dialog)csvDialog=null;if(!claimed)file.delete();updateForegroundAccess()};dialog.show();handedToDialog=true}
            } finally {
                if(!handedToDialog) withContext(NonCancellable+Dispatchers.IO) {
                    scratch?.let {if(it.exists() && !it.delete()) throw IOException("CSV 暂存清理失败，下次启动将重试")}
                }
            }
        }
    }
    private data class CsvPreviewSession(val prepared:PreparedCsvImport,val source:LedgerState,val context:PreviewContext,val sessionEpoch:Long)
    private fun readCsv(uri:Uri,ledgerId:String)=runFileTask {
        val accessEpoch=sessionEpoch
        val databaseEpoch=repository.epoch
        val prepared=withContext(Dispatchers.IO) {
            val source=repository.snapshot()
            val document=localFiles.input(uri).use {DawnYewCsvCodec.read(it,checkCancelled={ensureActive()})}
            CsvPreviewSession(DawnYewCsv.prepareImport(document,ledgerId,source,app.environment),source,
                PreviewContext(app.environment.id(),app.environment.id(),databaseEpoch,DAWNYEW_CSV_ADAPTER_ID,"1"),accessEpoch)
        }
        if(accessEpoch!=sessionEpoch || databaseEpoch!=repository.epoch || !resumed || !unlocked) return@runFileTask
        showCsvPreview(prepared,ImportSelection(prepared.prepared.defaultSelectedRows))
    }
    private fun csvPreviewCurrent(value:CsvPreviewSession):Boolean = resumed && unlocked && !covered && app.session.authorized && !deviceLocked &&
        value.sessionEpoch==sessionEpoch && value.context.databaseEpoch==repository.epoch &&
        controller.ui.value.dataset?.let {it.datasetId==value.source.datasetId && it.contentVersion==value.source.contentVersion}==true
    private fun showCsvPreview(value:CsvPreviewSession,selection:ImportSelection) {
        if(!csvPreviewCurrent(value)) {controller.notify("账本或授权已变化，请重新读取 CSV");return}
        val preview=ImportPreviewer.preview(value.prepared.facts,value.context,value.prepared.parsed,value.prepared.mapping,selection,value.source,app.environment)
        val counts=preview.counts
        val issues=preview.rows.filter {it.message!=null}.take(12).joinToString("\n") {"记录 ${it.rowNumber}：${it.message}"}
        val message="共 ${value.prepared.facts.previewRecordCount} 条记录，已选 ${selection.selectedRows.size} 条。\n新增 ${preview.impact.addedCount}，重复 ${counts.getValue(PreviewDisposition.DUPLICATE)+counts.getValue(PreviewDisposition.DUPLICATE_IN_TRASH)}，冲突 ${counts.getValue(PreviewDisposition.CONFLICT)}，待确认 ${counts.getValue(PreviewDisposition.NEEDS_DECISION)}，格式错误 ${counts.getValue(PreviewDisposition.INVALID)}。\n收入 ¥${Money.format(preview.impact.income)}，支出 ¥${Money.format(preview.impact.expense)}。\n将按文件名称创建 ${preview.plan?.newCategories?.size ?: 0} 个缺失分类。\n"+preview.problems.joinToString("\n")+"\n"+issues
        csvDialog?.dismiss()
        csvDialog=AlertDialog.Builder(this).setTitle("CSV 导入预览").setMessage(message)
            .setNegativeButton("取消",null).setNeutralButton("选择记录") {_,_->showCsvSelection(value,selection,preview)}
            .setPositiveButton("仅导入已选可用记录") {_,_->
                val plan=preview.plan ?: return@setPositiveButton
                sensitive("确认导入本地账本") {
                    if(!csvPreviewCurrent(value)) controller.notify("预览已过期，请重新读取 CSV")
                    else controller.perform(Command.CommitImport(plan),plan.submissionId) {controller.notify("导入完成，可在导入历史核对或撤销本批次")}
                }
            }.create().also {dialog->dialog.setOnDismissListener {if(csvDialog===dialog)csvDialog=null;updateForegroundAccess()};dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=preview.canCommit}
    }
    private fun showCsvSelection(value:CsvPreviewSession,selection:ImportSelection,preview:ImportPreview) {
        if(!csvPreviewCurrent(value)) {controller.notify("预览已过期，请重新读取 CSV");return}
        val candidates=value.prepared.parsed.filterIsInstance<ParsedImportRow.Valid>()
        val rows=preview.rows.associateBy {it.rowNumber}
        val labels=candidates.map {row->val e=row.candidate.entry;"记录 ${row.rowNumber} · ${e.localDate} · ${if(e.kind==EntryKind.INCOME) "收入" else "支出"} ¥${Money.format(e.amountMinor)} · ${rows[row.rowNumber]?.disposition?.let(::csvStatus).orEmpty()}"}.toTypedArray()
        val selected=selection.selectedRows.toMutableSet()
        val checked=BooleanArray(candidates.size) {candidates[it].rowNumber in selected}
        csvDialog=AlertDialog.Builder(this).setTitle("选择记录（格式错误不列入）")
            .setMultiChoiceItems(labels,checked) {_,index,on->if(on)selected+=candidates[index].rowNumber else selected-=candidates[index].rowNumber}
            .setNegativeButton("返回") {_,_->showCsvPreview(value,selection)}
            .setPositiveButton("应用选择") {_,_->showCsvPreview(value,ImportSelection(selected))}
            .setNeutralButton("确认保留已选疑似重复") {_,_->showCsvPreview(value,ImportSelection(selected,selected.toSet()))}
            .create().also {dialog->dialog.setOnDismissListener {if(csvDialog===dialog)csvDialog=null;updateForegroundAccess()};dialog.show()}
    }
    private fun csvStatus(value:PreviewDisposition)=when(value) {
        PreviewDisposition.NEW->"可新增";PreviewDisposition.DUPLICATE->"已存在，将跳过";PreviewDisposition.DUPLICATE_IN_TRASH->"回收站已存在，将跳过"
        PreviewDisposition.CONFLICT->"来源冲突";PreviewDisposition.NEEDS_DECISION->"疑似重复，需确认";PreviewDisposition.EXCLUDED->"已排除";PreviewDisposition.INVALID->"格式错误";PreviewDisposition.SKIPPED->"未选择"
    }

    override fun chooseScreenshot() {
        if(!unlocked || external!=null)return
        launchExternal(pending("image"),Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="image/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/png","image/jpeg"));putExtra(Intent.EXTRA_LOCAL_ONLY,true)})
    }
    private fun recognize(uri:Uri)=runFileTask {
        val operationEpoch=sessionEpoch
        val generation=repository.epoch
        val result=CaptureRecognizer.fromUri(this@MainActivity,uri)
        if(operationEpoch!=sessionEpoch || generation!=repository.epoch || !unlocked || deviceLocked) return@runFileTask
        acceptRecognition(result)
    }
    private fun acceptRecognition(result: CaptureRecognition) {
        if (result.draft == null) AlertDialog.Builder(this).setTitle("这张截图不适合直接生成账单")
            .setMessage(result.explanation + "\n请返回手动记账；本次没有保存任何账单。").setPositiveButton("知道了", null).show()
        else capture = result.draft
    }
    override fun clearAllData()=sensitive("确认清空全部记账数据") {runFileTask {
        val operationId=app.environment.id()
        try {
            repository.execute(Command.ClearAll,operationId)
            controller.resetTransientState();capture=null
            controller.notify("本机记账数据与保护副本已清空，应用锁已保留")
            repository.acknowledgeOperation(operationId)
        } finally {controller.mbti.synchronizeDataScope()}
    }}
    override fun clearProtectionBackup()=sensitive("确认清理保护副本") {runFileTask {repository.clearProtection();controller.notify("保护副本已清理")}}
    private fun shaStream(input:InputStream,checkCancelled:()->Unit={}):String {val digest=Sha256();val buffer=ByteArray(65536);while(true){checkCancelled();val n=input.read(buffer);if(n<0)break;if(n>0)digest.update(buffer.copyOf(n))};return digest.finishHex()}
}
