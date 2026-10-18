package com.dawnyew.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawnyew.core.*
import com.dawnyew.platform.AndroidEnvironment
import com.dawnyew.platform.crypto.BackupCrypto
import com.dawnyew.platform.files.JsonStreamCodec
import com.dawnyew.platform.ocr.AndroidOfflineOcrEngine
import com.dawnyew.platform.storage.GenerationRepository
import com.dawnyew.protocol.BackupProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.*
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class OfflineIntegrationTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private suspend fun GenerationRepository.commitForTest(command:Command,env:DomainEnvironment) {
        val operation=env.id();execute(command,operation);acknowledgeOperation(operation)
    }
    private fun temporaryContext():Pair<Context,File> {
        val directory=File(context.cacheDir,"instrumentation-${UUID.randomUUID()}").apply {mkdirs()}
        return object:ContextWrapper(context) {override fun getNoBackupFilesDir()=directory} to directory
    }
    @Test fun manifestCannotUseNetworkAndExcludesAutomaticBackup() {
        @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.ACCESS_NETWORK_STATE"))
        assertEquals(0,context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
    @Test fun realNativeEncryptedBackupAuthenticatesBeforeOutput() {
        val (_,directory)=temporaryContext()
        try {
            val plaintext=ByteArray(65536) {(it%251).toByte()}
            val encrypted=ByteArrayOutputStream()
            BackupCrypto(directory).encrypt(plaintext.inputStream(),encrypted,"DawnYew-device-test-123".toCharArray())
            val output=ByteArrayOutputStream()
            BackupCrypto(directory).decrypt(encrypted.toByteArray().inputStream(),output,"DawnYew-device-test-123".toCharArray())
            assertContentEquals(plaintext,output.toByteArray())
            val damaged=encrypted.toByteArray().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
            val rejected=ByteArrayOutputStream()
            assertFails {BackupCrypto(directory).decrypt(damaged.inputStream(),rejected,"DawnYew-device-test-123".toCharArray())}
            assertEquals(0,rejected.size())
        } finally {directory.deleteRecursively()}
    }
    @Test fun databaseGenerationRoundtripAndPersistentOperationReplay()=runBlocking {
        val (isolated,directory)=temporaryContext();val env=AndroidEnvironment();var repo=GenerationRepository(isolated,env)
        try {
            repo.initialize();val initial=repo.snapshot();val operation=env.id()
            val command=Command.SaveEntry(ledgerId=initial.ledgers.first().id,kind=EntryKind.EXPENSE,amountMinor=3250,categoryId=initial.categories.first {it.direction==Direction.EXPENSE}.id,occurrence=env.localNow(),note="isolated-device-test")
            repo.execute(command,operation);repo.close();repo=GenerationRepository(isolated,env);repo.initialize();repo.execute(command,operation)
            assertEquals(1,repo.snapshot().entries.size)
            assertEquals(operation,repo.pendingOperation.value?.operationId)
            assertNotNull(repo.pendingOperation.value?.outcome)
            repo.acknowledgeOperation(operation);assertNull(repo.pendingOperation.value)
            val json=ByteArrayOutputStream();JsonStreamCodec.write(BackupProtocol.create(repo.snapshot(),env.utcNow()),json)
            val restored=BackupProtocol.restore(JsonStreamCodec.read(json.toByteArray().inputStream()),env::normalize)
            repo.commitForTest(Command.CreateLedger("temporary-device-test"),env)
            repo.replace(restored);repo.close();repo=GenerationRepository(isolated,env);repo.initialize()
            assertEquals(1,repo.snapshot().ledgers.size);assertEquals(3250L,repo.snapshot().entries.single().amountMinor)
            assertTrue(File(directory,"datasets/protection").exists())
            repo.commitForTest(Command.ClearAll,env);assertTrue(repo.snapshot().entries.isEmpty());assertFalse(File(directory,"datasets/protection").exists())
        } finally {repo.close();directory.deleteRecursively()}
    }
    @Test fun offlineOcrLoadsBundledModelsAndRecognizesSyntheticAmount()=runBlocking {
        val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=48f}
        canvas.drawText("支付成功",220f,230f,paint);paint.textSize=82f;canvas.drawText("12.34",235f,400f,paint)
        paint.textSize=42f;canvas.drawText("示例咖啡店",180f,570f,paint);canvas.drawText("支付时间",60f,730f,paint);canvas.drawText("2026-10-18 12:00:00",60f,820f,paint)
        val engine=AndroidOfflineOcrEngine.getInstance(context)
        try {val result=engine.recognize(bitmap);assertTrue(result.blocks.any {it.text.contains("12.34")},"Synthetic amount absent: "+result.blocks.map {it.text});assertTrue(result.engineVersion.isNotBlank())}
        finally {bitmap.recycle();engine.release()}
    }

    @Test fun streamingCandidateIsIsolatedAndRejectsStalePreview()=runBlocking {
        val (isolated,directory)=temporaryContext();val env=AndroidEnvironment();val repo=GenerationRepository(isolated,env)
        try {
            repo.initialize();val original=repo.snapshot()
            val json=ByteArrayOutputStream();repo.exportBackup(json,env.utcNow()) {}
            repo.commitForTest(Command.CreateLedger("before-preview"),env)
            val baseline=repo.versionToken();val before=repo.snapshot()
            val candidate=JsonStreamCodec.prepare(json.toByteArray().inputStream(),File(directory,"parse-stage")).use {repo.prepareRestore(it,baseline)}
            assertEquals(before,repo.snapshot(),"Preparing a candidate must not change the active dataset")
            try {repo.replaceCandidate(candidate)} finally {repo.discardCandidate(candidate)}
            assertEquals(original.ledgers,repo.snapshot().ledgers)
            val nextBaseline=repo.versionToken()
            val stale=JsonStreamCodec.prepare(json.toByteArray().inputStream(),File(directory,"parse-stage")).use {repo.prepareRestore(it,nextBaseline)}
            repo.commitForTest(Command.CreateLedger("after-preview"),env);val edited=repo.snapshot()
            try {
                val error=assertFailsWith<DomainException> {repo.replaceCandidate(stale)}
                assertEquals(ErrorCode.CONFLICT,error.code)
                assertEquals(edited,repo.snapshot())
            } finally {repo.discardCandidate(stale)}
            val protection=ByteArrayOutputStream();repo.exportProtection(protection,env.utcNow()) {}
            JsonStreamCodec.prepare(protection.toByteArray().inputStream(),File(directory,"protection-stage")).use {assertEquals(2L,it.metadata.counts.getValue("ledgers"))}
        } finally {repo.close();directory.deleteRecursively()}
    }

    @Test fun tenThousandEntryQueriesUseRealDeviceDatabase()=runBlocking {
        val (isolated,directory)=temporaryContext();val env=AndroidEnvironment();val repo=GenerationRepository(isolated,env)
        try {
            repo.initialize();val initial=repo.snapshot();val ledger=initial.ledgers.first();val category=initial.categories.first {it.direction==Direction.EXPENSE}
            val now=env.localNow();val utc=env.utcNow()
            val entries=(0 until 10_000).map {index->Entry(env.id(),ledger.id,EntryKind.EXPENSE,100,category.id,now,"synthetic-$index",createdAt=utc,updatedAt=utc,creationOperationId=env.id())}
            repo.replace(initial.copy(entries=entries))
            val range=monthRange(now.take(7));val query=QueryScope(startDate=range.first,endDate=range.second)
            val pageTimes=mutableListOf<Long>();val statsTimes=mutableListOf<Long>()
            repeat(20) {
                pageTimes+=measureTimeMillis {val page=repo.queryEntryPage(query,null,50);assertEquals(50,page.entries.size);assertNotNull(page.nextCursor)}
                statsTimes+=measureTimeMillis {val stats=repo.statistics(query);assertEquals(10_000L,stats.entryCount);assertEquals(1_000_000L,stats.expense)}
            }
            fun p95(values:List<Long>)=values.sorted()[18]
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {putString("DawnYewBenchmark","10000 synthetic entries; 20 runs; page50 p95=${p95(pageTimes)}ms; statistics p95=${p95(statsTimes)}ms; device=${android.os.Build.MODEL}; API=${android.os.Build.VERSION.SDK_INT}")})
        } finally {repo.close();directory.deleteRecursively()}
    }
}
