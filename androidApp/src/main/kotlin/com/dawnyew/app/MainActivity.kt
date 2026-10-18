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
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.platform.crypto.BackupCrypto
import com.dawnyew.platform.files.*
import com.dawnyew.platform.ocr.AndroidOfflineOcrEngine
import com.dawnyew.platform.ocr.PaymentScreenshotParser
import com.dawnyew.presentation.*
import com.dawnyew.ui.*
import kotlinx.coroutines.*
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
    private var lockEnabled by mutableStateOf(false)
    private var capture by mutableStateOf<CaptureDraftUi?>(null)
    private var authentication:BiometricPrompt?=null
    private var authRunning=false
    private var external:PendingExternal?
        get()=app.pendingExternal
        set(value) {app.pendingExternal=value}
    private var fileJob:Job?=null
    private var pendingShare:Uri?=null
    private var pendingRestoreCandidate:VerifiedCandidate?=null
    private var restorePreviewDialog:AlertDialog?=null
    private val sessionEpoch get()=app.session.epoch
    private var resumed=false
    private var authAttempt:SessionCoordinator.Lease?=null
    private var authSuccess:(()->Unit)?=null
    private var authFailure:(()->Unit)?=null
    private var authCompleted=false
    private var backHandler:(()->Unit)?=null
    private val formBackCallback=object:OnBackPressedCallback(false) {override fun handleOnBackPressed() {backHandler?.invoke()}}
    private val deviceLocked get()=(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
    private val documentLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
        val operation=external ?: return@registerForActivityResult
        val uri=result.data?.data
        if(result.resultCode!=RESULT_OK || uri==null || !app.session.externalIsCurrent(operation.lease) || operation.databaseEpoch!=repository.epoch) {
            app.session.finishExternal(operation.lease);app.discardExternal()
            if(uri!=null) controller.notify("文件操作授权已过期，请重新发起")
            return@registerForActivityResult
        }
        operation.returnedUri=uri
        continueReturnedExternal()
    }
    private fun continueReturnedExternal() {
        val operation=external ?: return
        val uri=operation.returnedUri ?: return
        if(!resumed || authRunning)return
        if(!app.session.externalIsCurrent(operation.lease) || operation.databaseEpoch!=repository.epoch || deviceLocked) {app.revokeSession();unlocked=app.session.authorized;return}
        // The picker can obscure Home/lock transitions. Its result never reuses an old protected session.
        sensitive("继续本地文件操作",onCancelled={app.session.finishExternal(operation.lease);app.discardExternal()}) {
            if(external!==operation || operation.databaseEpoch!=repository.epoch || !app.session.finishExternal(operation.lease)) {app.discardExternal();return@sensitive}
            external=null
            when(operation.type) {
                "export" -> finishExport(uri,requireNotNull(operation.file))
                "restore" -> readRestore(uri,operation.password)
                "image" -> recognize(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();onBackPressedDispatcher.addCallback(this,formBackCallback)
        lockEnabled=app.lockStore.enabled;unlocked=app.session.authorized
        lifecycleScope.launch {app.sessionRevocations.collect {
            unlocked=app.session.authorized
            if(authAttempt?.let {attempt->!app.session.authenticationIsCurrent(attempt)}==true)cancelAuthentication()
            fileJob?.cancel();restorePreviewDialog?.dismiss();restorePreviewDialog=null
        }}
        updateSecureWindow();acceptShare(intent)
        if(unlocked) app.initialize()
        setContent {
            val startupError by app.startupError.collectAsState()
            Box(Modifier.fillMaxSize()) {
                if(unlocked && !covered) {
                    if(startupError!=null) DawnYewTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.Center) {Text(startupError!!);Button(onClick={app.initialize()}) {Text("重试读取")};Button(onClick={restoreProtectionBackup()}) {Text("验证并恢复保护副本")}}}}
                    else DawnYewApp(controller,this@MainActivity,lockEnabled,capture,onBackHandlerChanged={handler->backHandler=handler;formBackCallback.isEnabled=handler!=null}) {capture=null}
                } else DawnYewTheme {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
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
        external?.let {if(!app.session.externalIsCurrent(it.lease))app.discardExternal()}
        if(authAttempt?.let { !app.session.authenticationIsCurrent(it) }==true) cancelAuthentication()
        completeAuthentication()
        if(unlocked) {app.initialize();consumeShare()}
        continueReturnedExternal()
    }
    override fun onPause() {
        resumed=false;covered=true
        app.session.background(isChangingConfigurations);unlocked=app.session.authorized
        if(!isChangingConfigurations) fileJob?.cancel()
        restorePreviewDialog?.dismiss();restorePreviewDialog=null
        super.onPause()
    }
    override fun onDestroy() {
        authAttempt?.let(app.session::cancelAuthentication)
        authAttempt=null;authSuccess=null;authFailure?.invoke();authFailure=null;authCompleted=false
        authentication?.cancelAuthentication();authentication=null;authRunning=false
        restorePreviewDialog?.dismiss();pendingRestoreCandidate?.let(::discardRestoreCandidate);pendingRestoreCandidate=null
        // A legitimate picker rotation keeps only its process-owned operation payload.
        if(!isChangingConfigurations) {external?.let {app.session.finishExternal(it.lease)};app.discardExternal()}
        super.onDestroy()
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);app.revokeSession();cancelAuthentication();fileJob?.cancel();unlocked=app.session.authorized
        pendingShare=null;setIntent(intent);acceptShare(intent);if(unlocked) consumeShare()
    }
    private fun acceptShare(intent:Intent?) {
        if(intent?.action!=Intent.ACTION_SEND) return
        if(intent.type !in setOf("image/png","image/jpeg") || (intent.clipData?.itemCount ?: 1)!=1) {controller.notify("只支持分享一张 PNG 或 JPEG 截图");return}
        @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        // Keep only an opaque URI while locked: no provider query, thumbnail or byte read.
        pendingShare=uri
        intent.action=null
    }
    private fun consumeShare() {val uri=pendingShare ?: return;pendingShare=null;recognize(uri)}
    private fun updateSecureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun unlock()=authenticate("解锁 DawnYew") {
        if(app.lockStore.repairRequired) app.lockStore.persist(true)
        lockEnabled=app.lockStore.enabled;app.session.configureLock(lockEnabled);unlocked=true;covered=false;updateSecureWindow();app.initialize();consumeShare()
    }
    private fun authenticate(reason:String,onCancelled:()->Unit={},onSuccess:()->Unit) {
        if(authRunning) {onCancelled();return}
        val authenticators=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(BiometricManager.from(this).canAuthenticate(authenticators)!=BiometricManager.BIOMETRIC_SUCCESS) {
            onCancelled();controller.notify("请先在系统中设置安全锁屏或可用的强生物识别")
            AlertDialog.Builder(this).setTitle("设备认证不可用").setMessage("请先在系统设置中启用 PIN、图案、密码或支持的强生物识别，然后返回重试。不会使用自定义弱口令替代。").setPositiveButton("知道了",null).show();return
        }
        val attempt=app.session.beginAuthentication();unlocked=app.session.authorized;authAttempt=attempt;authSuccess=onSuccess;authFailure=onCancelled;authCompleted=false;authRunning=true
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
        unlocked=true;covered=false;try {action?.invoke()} catch(e:Exception) {app.revokeSession();unlocked=app.session.authorized;controller.notify("认证后的操作未完成，请重试：${e.message.orEmpty().take(100)}")}
    }
    private fun cancelAuthentication() {
        authAttempt?.let(app.session::cancelAuthentication);authAttempt=null;authSuccess=null;authCompleted=false;authRunning=false
        val cancel=authFailure;authFailure=null;authentication?.cancelAuthentication();unlocked=app.session.authorized;cancel?.invoke()
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
        external=operation
        try {documentLauncher.launch(intent)} catch(e:Exception) {app.session.finishExternal(operation.lease);external=null;operation.file?.delete();operation.password.fill('\u0000');controller.notify("设备没有可用的本地文件选择器")}
    }
    private fun pending(type:String,file:File?=null,password:CharArray=charArrayOf())=PendingExternal(type,app.session.beginExternal(),repository.epoch,file,password)
    private fun runFileTask(onRejected:()->Unit={},block:suspend CoroutineScope.()->Unit) {
        if(fileJob?.isActive==true) {onRejected();controller.notify("请等待当前文件任务完成");return}
        controller.setBusy(true)
        fileJob=lifecycleScope.launch {
            try {block()} catch(e:CancellationException) {throw e}
            catch(e:Exception) {controller.notify(if(e is DomainException) e.message else "操作失败：${e.message?.take(160) ?: e::class.simpleName}")}
            finally {controller.setBusy(false)}
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
            .setMessage("已完成格式、校验和、候选数据库及账务关系检查。\n${counts.counts.getValue("ledgers")} 个账本，${counts.activeEntryCount} 笔有效账单，${counts.deletedEntryCount} 笔回收站记录。\n确认后将整体替换当前数据；应用锁不变。")
            .setNegativeButton("取消",null).setPositiveButton("确认替换") {_,_->
                pendingRestoreCandidate=null
                sensitive("确认替换本地账本",onCancelled={discardRestoreCandidate(candidate)}) {
                    runFileTask(onRejected={discardRestoreCandidate(candidate)}) {
                        try {repository.replaceCandidate(candidate);app.recovered();controller.resetTransientState();capture=null;controller.notify("恢复完成；已验证的新数据代际现已生效")}
                        finally {withContext(NonCancellable) {repository.discardCandidate(candidate)}}
                    }
                }
            }.create().also {dialog->
                dialog.setOnDismissListener {if(pendingRestoreCandidate===candidate) {pendingRestoreCandidate=null;discardRestoreCandidate(candidate)}}
                dialog.show()
            }
    }
    override fun chooseScreenshot() {
        if(!unlocked || external!=null)return
        launchExternal(pending("image"),Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="image/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/png","image/jpeg"));putExtra(Intent.EXTRA_LOCAL_ONLY,true)})
    }
    private fun recognize(uri:Uri)=runFileTask {
        val operationEpoch=sessionEpoch
        val result=withContext(Dispatchers.IO) {
            val image=ImageInputService(this@MainActivity,localFiles,checkCancelled={ensureActive()}).decode(uri)
            try {ensureActive();val ocr=AndroidOfflineOcrEngine.getInstance(this@MainActivity).recognize(image.bitmap);Triple(PaymentScreenshotParser.parse(ocr),ocr.engineVersion,image.fingerprint)} finally {image.bitmap.recycle()}
        }
        if(operationEpoch!=sessionEpoch || !unlocked) return@runFileTask
        val candidate=result.first
        if(candidate.disposition.name=="UNSUPPORTED") {
            AlertDialog.Builder(this@MainActivity).setTitle("这张截图不适合直接生成账单").setMessage(candidate.messages.joinToString("\n")+"\n请返回手动记账；本次没有保存任何账单。").setPositiveButton("知道了",null).show()
        } else {
            val occurred=if(candidate.localDate!=null && candidate.localTime!=null) "${candidate.localDate}T${candidate.localTime}" else ""
            capture=CaptureDraftUi(candidate.amountMinor?.let {Money.format(it)}.orEmpty(),occurred,candidate.merchant.orEmpty(),candidate.messages+listOfNotNull(if(occurred.isEmpty()) "未识别出完整时间，暂填当前时间，请核对后修改。" else null,if(candidate.amountCandidates.size>1) "候选金额："+candidate.amountCandidates.joinToString {Money.format(it)} else null),engineVersion=result.second,fingerprint=result.third)
        }
    }
    override fun clearAllData()=sensitive("确认清空全部记账数据") {runFileTask {val operationId=app.environment.id();repository.execute(Command.ClearAll,operationId);controller.resetTransientState();capture=null;controller.notify("本机记账数据与保护副本已清空，应用锁已保留");repository.acknowledgeOperation(operationId)}}
    override fun clearProtectionBackup()=sensitive("确认清理保护副本") {runFileTask {repository.clearProtection();controller.notify("保护副本已清理")}}
    private fun shaStream(input:InputStream,checkCancelled:()->Unit={}):String {val digest=Sha256();val buffer=ByteArray(65536);while(true){checkCancelled();val n=input.read(buffer);if(n<0)break;if(n>0)digest.update(buffer.copyOf(n))};return digest.finishHex()}
}
