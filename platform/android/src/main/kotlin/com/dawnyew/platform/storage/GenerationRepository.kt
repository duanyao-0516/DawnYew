package com.dawnyew.platform.storage

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.dawnyew.core.*
import com.dawnyew.storage.*
import com.dawnyew.protocol.BackupProtocol
import com.dawnyew.platform.files.JsonStreamCodec
import com.dawnyew.platform.files.PreparedBackup
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** A comparison token includes both edits and whole-dataset switches. Never persisted in UI state. */
data class DatasetToken(val epoch: Long, val generation: String?, val datasetId: String?, val contentVersion: Long?)
data class ProtectionPreview(val state: LedgerState, val baseline: DatasetToken)
class VerifiedCandidate internal constructor(val summary: CandidateSummary, val baseline: DatasetToken, internal val generation: String, internal val owner: String) {
    internal var used = false
}

/** Only a fully validated candidate may become active. Any uncertain publication closes the facade. */
class GenerationRepository internal constructor(
    private val root: File,
    private val staging: File,
    private val environment: DomainEnvironment,
    private val openGeneration: (String) -> LedgerRepository,
    private val files: GenerationFileOps,
    private val authorized: () -> Boolean = { true },
    private val createCandidateDatabase: ((String) -> DawnYewDatabase)? = null,
    private val ownerId: String = UUID.randomUUID().toString(),
    private val candidateValidator: (suspend (String, CandidateSummary) -> Unit)? = null,
    private val upgrades: GenerationUpgradeOperations? = null,
) : LedgerRepository {
    constructor(context: Context, environment: DomainEnvironment, authorized: () -> Boolean = { true }) : this(
        File(context.noBackupFilesDir, "datasets"), File(context.noBackupFilesDir, "staging"), environment,
        { name ->
            val databaseFile = File(context.noBackupFilesDir, "datasets/$name/ledger.db")
            val createIfEmpty = !databaseFile.exists()
            if (!createIfEmpty && databaseFile.length() == 0L) throw IOException("现有数据库为空，未自动创建新账本")
            RoomLedgerRepository(createDatabase(context, databaseFile.absolutePath), environment, createIfEmpty = createIfEmpty)
        },
        GenerationFileOps { directory ->
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }, authorized, { name -> createDatabase(context, File(context.noBackupFilesDir, "datasets/$name/ledger.db").absolutePath) },
        upgrades = GenerationUpgradeOperations(),
    )
    private val pointer = File(root, "active")
    private val protection = File(root, "protection")
    private val journal = File(root, "journal")
    private val pendingFile = File(root, "pending-operation")
    private val pending = MutableStateFlow<PendingOperationRecovery?>(null)
    override val pendingOperation: StateFlow<PendingOperationRecovery?> = pending.asStateFlow()
    private val mutex = Mutex()
    private var delegate: LedgerRepository? = null
    private var activeName: String? = null
    private val mutable = MutableStateFlow<LedgerState?>(null)
    override val state: StateFlow<LedgerState?> = mutable.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    val failureReason: StateFlow<String?> = failure.asStateFlow()
    @Volatile var epoch: Long = 0; private set

    private fun checkName(name: String): String {
        if (!Regex("gen-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(name)) throw IOException("数据代际指针无效")
        return name
    }
    private fun readPointer(file: File): String? = if (file.exists()) checkName(file.readText().trim()) else null
    private fun open(name: String) = openGeneration(checkName(name))
    private fun checkAuthorized() { if (!authorized()) throw DomainException(ErrorCode.LOCKED, "应用已锁定，请重新认证") }
    private fun requireReady() = delegate ?: throw IOException("数据库尚未就绪，请重新验证当前数据或恢复保护副本")
    private suspend fun failClosed(error: Throwable, other: LedgerRepository? = null) {
        val previous = delegate
        delegate = null; activeName = null; mutable.value = null; epoch++
        failure.value = "数据发布或清理未完成，已停止读写。请重试验证活动数据，或预览保护副本后恢复。"
        try { previous?.close() } catch (close: Throwable) { error.addSuppressed(close) }
        if (other !== previous) try { other?.close() } catch (close: Throwable) { error.addSuppressed(close) }
    }
    override suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock {
        if (delegate != null) return@withLock
        check(root.isDirectory || root.mkdirs()) { "无法建立数据目录" }
        var repo: LedgerRepository? = null
        try {
            if (!pointer.exists()) {
                if (root.listFiles().orEmpty().any { it.name.startsWith("gen-") }) throw IOException("发现未完成的数据初始化，需要恢复检查")
                val name = "gen-" + environment.id()
                check(File(root, name).mkdirs())
                repo = open(name); repo.initialize(); repo.close(); repo = null
                files.syncDirectory(File(root, name)); files.atomic(pointer, name)
            }
            var name = readPointer(pointer)!!
            if (!File(root, "$name/ledger.db").isFile || File(root, "$name/ledger.db").length() == 0L) throw IOException("活动数据库不存在，未自动回退旧数据")
            val oldSchema = needsUpgrade(name)
            if (oldSchema != null) {
                // Resolve an interrupted earlier publication before starting a new, separate upgrade.
                recoverJournal(name)
                val upgraded = upgradeGeneration(name, oldSchema)
                name = upgraded.first; repo = upgraded.second
            } else { repo = open(name); repo.initialize() }
            val data = requireNotNull(repo.state.value); validateState(data, environment::normalize)
            // Complete only cleanup for the authoritative pointer. Never switch back to an older generation.
            recoverJournal(name)
            cleanAbandoned(name)
            delegate = repo; repo = null; activeName = name; mutable.value = data; failure.value = null
            refreshPending()
        } catch (error: Throwable) {
            withContext(NonCancellable) { failClosed(error, repo) }
            throw error
        }
    } }

    private fun needsUpgrade(name: String): Int? {
        val operations = upgrades ?: return null
        val file = File(root, "$name/ledger.db")
        if (!file.isFile || file.length() == 0L) throw IOException("现有数据库不存在或为空")
        val version = operations.version(file)
        if (version !in 1..operations.currentVersion) throw IOException("数据库版本不受支持，未修改原数据")
        return version.takeIf { it < operations.currentVersion }
    }

    /** Source schema and receipts stay untouched; only a closed, checkpointed copy reaches Room. */
    private suspend fun copyUpgradeSource(source: String, version: Int): String {
        val original = File(root, "$source/ledger.db")
        ensureSpace(original.length() + File(original.path + "-wal").length())
        requireNotNull(upgrades).checkpoint(original, version)
        files.syncDirectory(original.parentFile!!)
        val candidate = "gen-" + environment.id()
        val directory = File(root, candidate)
        check(directory.mkdirs()) { "无法创建升级候选目录" }
        try {
            val context = currentCoroutineContext()
            files.copySynced(original, File(directory, "ledger.db"), { context.ensureActive() }, ::ensureSpace)
            files.syncDirectory(directory)
            return candidate
        } catch (error: Throwable) {
            try { files.remove(directory) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private suspend fun upgradeGeneration(oldName: String, version: Int): Pair<String, LedgerRepository> {
        val name = copyUpgradeSource(oldName, version)
        var candidate: LedgerRepository? = null
        var renamed = false
        try {
            candidate = open(name); candidate.initialize()
            validateState(requireNotNull(candidate.state.value), environment::normalize)
            candidate.close(); candidate = null
            val operations = requireNotNull(upgrades)
            val databaseFile = File(root, "$name/ledger.db")
            if (operations.version(databaseFile) != operations.currentVersion) throw IOException("候选数据库未完成版本升级")
            operations.checkpoint(databaseFile, operations.currentVersion)
            files.syncDirectory(File(root, name))
            candidate = open(name); candidate.initialize()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                files.atomic(journal, "PREPARED\nUPGRADE\n$oldName\n$name")
                files.atomic(protection, oldName)
                files.atomic(pointer, name) { renamed = true }
                epoch++
                files.atomic(journal, "COMMITTED\nUPGRADE\n$oldName\n$name")
                // Upgrades copy the receipts, so the pending marker remains meaningful in this generation.
                files.remove(journal); files.syncDirectory(root)
            }
            return name to requireNotNull(candidate).also { candidate = null }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try { candidate?.close() } catch (close: Throwable) { error.addSuppressed(close) }
                if (!renamed && runCatching { readPointer(pointer) != name }.getOrDefault(false)) {
                    try { files.remove(File(root, name)) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                }
            }
            throw error
        }
    }

    /** An old protection generation is immutable too; migration for reading happens on a disposable copy. */
    private suspend fun <T> readProtectionGeneration(name: String, read: suspend (String) -> T): T {
        val temporary = needsUpgrade(name)?.let { copyUpgradeSource(name, it) }
        var failure: Throwable? = null
        try { return read(temporary ?: name) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            if (temporary != null) withContext(NonCancellable) {
                try { files.remove(File(root, temporary)) }
                catch (cleanup: Throwable) { if (failure == null) throw cleanup else failure.addSuppressed(cleanup) }
            }
        }
    }

    private fun readPendingId(): String? {
        if (!pendingFile.exists()) return null
        if (pendingFile.length() !in 1..64) throw IOException("待确认操作标识损坏")
        return pendingFile.readText().trim().also(::validateId)
    }
    private suspend fun refreshPending() {
        pending.value = readPendingId()?.let { PendingOperationRecovery(it, requireReady().lookupOperation(it)) }
    }
    private fun clearPending() { files.remove(pendingFile); files.syncDirectory(root); pending.value = null }
    override suspend fun lookupOperation(operationId: String): OperationOutcome? = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); validateId(operationId); requireReady().lookupOperation(operationId)
    } }
    override suspend fun acknowledgeOperation(operationId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); validateId(operationId)
        if (readPendingId() == operationId) {
            try { clearPending() }
            catch (error: Throwable) { runCatching { refreshPending() }; throw error }
        }
    } }
    override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> {
        val requestedEpoch = epoch
        return withContext(Dispatchers.IO) { mutex.withLock {
            checkAuthorized(); validateId(operationId)
            if (requestedEpoch != epoch) conflict("数据集已切换，请重新操作")
            val repo = requireReady()
            val previous = readPendingId()
            if (previous != null && previous != operationId) {
                refreshPending(); conflict("请先确认上次操作的提交结果，再开始新的操作")
            }
            // Only an opaque UUID reaches this marker; no account, amount, note, or draft is persisted.
            var markerPrepared = false
            var commandReturned = false
            try {
                files.atomic(pendingFile, operationId)
                markerPrepared = true
                val before = mutable.value ?: throw IOException("数据库尚未就绪")
                if (command is Command.CommitImport && command.plan.databaseEpoch != epoch && before.importBatches.none { it.submissionId == command.plan.submissionId }) {
                    conflict("导入预览所属数据集已切换，请重新预览")
                }
                if (command == Command.ClearAll) {
                    val mutation = DomainEngine(environment).apply(before, command, operationId, expectedVersion)
                    publish(mutation.state, preserveOld = false)
                    commandReturned = true
                    mutation.resultIds
                } else {
                    val ids = repo.execute(command, operationId, expectedVersion)
                    commandReturned = true
                    mutable.value = requireNotNull(repo.state.value); ids
                }
            } catch (error: Throwable) {
                if (delegate != null) withContext(NonCancellable) {
                    // Domain rejection rolls back inside the repository transaction. Do not turn it into
                    // an uncertain-commit dialog, but never clear an older marker or existing receipt.
                    val rejected = markerPrepared && !commandReturned && previous == null &&
                        error is DomainException && error.code in setOf(ErrorCode.VALIDATION, ErrorCode.CONFLICT, ErrorCode.UNSUPPORTED)
                    try {
                        if (rejected && repo.lookupOperation(operationId) == null && readPendingId() == operationId) {
                            try { clearPending() }
                            catch (cleanup: Throwable) {
                                // A failed directory sync can follow successful unlink: retain a durable
                                // recovery marker where possible instead of declaring cleanup complete.
                                try { files.atomic(pendingFile, operationId) } catch (restore: Throwable) { cleanup.addSuppressed(restore) }
                                throw cleanup
                            }
                        } else refreshPending()
                    } catch (recovery: Throwable) {
                        error.addSuppressed(recovery)
                        pending.value = PendingOperationRecovery(operationId, runCatching { repo.lookupOperation(operationId) }.getOrNull())
                    }
                }
                throw error
            }
        } }
    }
    override suspend fun queryEntryPage(scope: QueryScope, cursor: EntryCursor?, limit: Int): EntryPage = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); requireReady().queryEntryPage(scope, cursor, limit)
    } }
    override suspend fun statistics(scope: QueryScope): StatisticsSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); requireReady().statistics(scope)
    } }
    override suspend fun findCaptureDuplicates(fingerprint: String?, amountMinor: Long?, localDate: String?): CaptureDuplicateSummary = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); requireReady().findCaptureDuplicates(fingerprint, amountMinor, localDate)
    } }
    override suspend fun snapshot(): LedgerState = withContext(Dispatchers.IO) { mutex.withLock { checkAuthorized(); requireReady().snapshot() } }
    suspend fun versionToken(): DatasetToken = withContext(Dispatchers.IO) { mutex.withLock { token() } }
    private suspend fun token(): DatasetToken {
        val data = mutable.value
        // Preserve even an invalid pointer as opaque comparison data during explicit recovery.
        val name = pointer.takeIf { it.exists() }?.readText()?.trim()
        return DatasetToken(epoch, name, data?.datasetId, data?.contentVersion)
    }
    suspend fun previewProtection(): ProtectionPreview = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized()
        if (journal.exists()) {
            val fields = journal.readText().split('\n')
            if (fields.size == 4 && fields[1] == "CLEARING" && pointer.exists() && pointer.readText().trim() == fields[3]) throw IOException("清空后的文件清理尚未完成，请先重试读取；旧副本不再用于恢复")
        }
        val name = readPointer(protection) ?: throw IOException("没有可用的恢复保护副本")
        if (!File(root, "$name/ledger.db").isFile || File(root, "$name/ledger.db").length() == 0L) throw IOException("保护副本文件不存在")
        readProtectionGeneration(name) { readable ->
            val repo = open(readable)
            try { repo.initialize(); val data = requireNotNull(repo.state.value); validateState(data, environment::normalize); ProtectionPreview(data, token()) }
            finally { repo.close() }
        }
    } }
    /** This comparison runs under the same mutex as the eventual pointer publication. */
    suspend fun replace(state: LedgerState, expected: DatasetToken) = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized()
        if (expected != token()) conflict("数据已变化，请重新读取并确认恢复预览")
        publish(state, preserveOld = delegate != null)
    } }
    override suspend fun replace(state: LedgerState) = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); requireReady(); publish(state, preserveOld = true)
    } }

    suspend fun exportBackup(output: OutputStream, createdAt: String, checkCancelled: () -> Unit) = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized()
        val repository = requireReady() as? RoomLedgerRepository ?: throw IOException("此仓库不支持流式导出")
        repository.exportSnapshot {
            JsonStreamCodec.writeStreaming(datasetId, createdAt, counts, output, checkCancelled) { type, emit ->
                visit(type) { batch -> checkCancelled(); BackupProtocol.encodeRecords(type, batch).forEach { emit(it) } }
            }
        }
    } }

    suspend fun exportProtection(output: OutputStream, createdAt: String, checkCancelled: () -> Unit) = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized()
        if (journal.exists()) {
            val fields = journal.readText().split('\n')
            if (fields.size == 4 && fields[1] == "CLEARING" && pointer.exists() && pointer.readText().trim() == fields[3]) throw IOException("清空后的文件清理尚未完成，请先重试读取")
        }
        val name = readPointer(protection) ?: throw IOException("没有可用的恢复保护副本")
        val file = File(root, "$name/ledger.db")
        if (!file.isFile || file.length() == 0L) throw IOException("保护副本文件不存在或为空")
        readProtectionGeneration(name) { readable ->
            val database = (createCandidateDatabase ?: throw IOException("不支持流式保护副本读取"))(readable)
            try {
                database.exportSnapshot {
                    JsonStreamCodec.writeStreaming(datasetId, createdAt, counts, output, checkCancelled) { type, emit ->
                        visit(type) { batch -> checkCancelled(); BackupProtocol.encodeRecords(type, batch).forEach { emit(it) } }
                    }
                }
            } finally { database.close() }
        }
    } }

    /** Writes only an isolated directory; a completed checksum and SQL validation do not activate it. */
    suspend fun prepareRestore(prepared: PreparedBackup, baseline: DatasetToken): VerifiedCandidate = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized()
        if (baseline != token()) conflict("数据已变化，请重新读取备份")
        val factory = createCandidateDatabase ?: throw IOException("此仓库不支持候选数据库构建")
        ensureSpace()
        val name = "gen-" + environment.id(); val directory = File(root, name)
        check(directory.mkdirs()) { "无法创建候选数据库目录" }
        var database: DawnYewDatabase? = null
        try {
            database = factory(name)
            val summary = writeCandidate(database, prepared.metadata.datasetId) {
                for (type in BackupProtocol.entityTypes) prepared.visit(type) { records ->
                    currentCoroutineContext().ensureActive(); ensureSpace(8L * 1024 * 1024); stage(BackupProtocol.decodeRecords(type, records))
                }
            }
            if (summary.counts != prepared.metadata.counts) throw IOException("候选数据库计数与已验证清单不一致")
            database.close(); database = null; files.syncDirectory(directory)
            VerifiedCandidate(summary, baseline, name, ownerId)
        } catch (error: Throwable) {
            withContext(NonCancellable) { database?.close(); files.remove(directory) }
            throw error
        }
    } }
    suspend fun discardCandidate(candidate: VerifiedCandidate) = withContext(Dispatchers.IO) { mutex.withLock {
        if (candidate.owner != ownerId || candidate.used) return@withLock
        // Even an uncertain rename must never turn cleanup into deletion of the active database.
        if (runCatching { readPointer(pointer) == candidate.generation }.getOrDefault(true)) { candidate.used = true; return@withLock }
        files.remove(File(root, candidate.generation)); candidate.used = true
    } }
    suspend fun replaceCandidate(candidate: VerifiedCandidate) = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized()
        if (candidate.owner != ownerId || candidate.used) conflict("恢复候选已失效，请重新读取备份")
        if (candidate.baseline != token()) conflict("数据已变化，请重新读取并确认恢复预览")
        val name = checkName(candidate.generation)
        val databaseFile = File(root, "$name/ledger.db")
        if (!databaseFile.isFile || databaseFile.length() == 0L) throw IOException("候选数据库已丢失或为空，请重新读取备份")
        // Preview may outlive the underlying file. Recheck it before writing any publication metadata.
        if (candidateValidator != null) candidateValidator.invoke(name, candidate.summary)
        else {
            val database = (createCandidateDatabase ?: throw IOException("此仓库不支持候选数据库复核"))(name)
            try { database.verifyCandidate(candidate.summary) } finally { database.close() }
        }
        checkAuthorized()
        val oldName = activeName
        var renamed = false
        var newRepository: LedgerRepository? = null
        currentCoroutineContext().ensureActive()
        try {
            withContext(NonCancellable) {
                files.atomic(journal, "PREPARED\nRESTORE\n${oldName ?: "-"}\n$name")
                if (oldName != null) files.atomic(protection, oldName)
                files.atomic(pointer, name) { renamed = true; candidate.used = true }
                // The parser/staging chunks are already released. Only now load the application's new state.
                delegate?.close(); delegate = null; mutable.value = null; activeName = name; epoch++
                newRepository = open(name); newRepository!!.initialize()
                val data = requireNotNull(newRepository!!.state.value)
                if (data.datasetId != candidate.summary.datasetId) throw IOException("候选数据库标识不一致")
                delegate = newRepository; newRepository = null; mutable.value = data
                files.atomic(journal, "COMMITTED\nRESTORE\n${oldName ?: "-"}\n$name")
                clearPending()
                cleanAbandoned(name); files.remove(journal); files.syncDirectory(root); failure.value = null
            }
        } catch (error: Throwable) {
            if (renamed || runCatching { readPointer(pointer) == name }.getOrDefault(true)) candidate.used = true
            withContext(NonCancellable) { failClosed(error, newRepository) }
            throw error
        }
    } }

    private suspend fun publish(state: LedgerState, preserveOld: Boolean) {
        validateState(state, environment::normalize); ensureSpace(128L * 1024 * 1024)
        val oldName = activeName
        val candidate = "gen-" + environment.id()
        val directory = File(root, candidate)
        check(directory.mkdirs()) { "无法建立候选数据目录" }
        var candidateRepo: LedgerRepository? = null
        var renamed = false
        var journalStarted = false
        val mode = if (preserveOld || oldName == null) "RESTORE" else "CLEARING"
        try {
            candidateRepo = open(candidate); candidateRepo.initialize(); candidateRepo.replace(state)
            val verified = candidateRepo.snapshot(); validateState(verified, environment::normalize)
            candidateRepo.close(); candidateRepo = null
            files.syncDirectory(directory)
            candidateRepo = open(candidate); candidateRepo.initialize()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                journalStarted = true
                files.atomic(journal, "PREPARED\n$mode\n${oldName ?: "-"}\n$candidate")
                if (preserveOld) files.atomic(protection, requireNotNull(oldName))
                files.atomic(pointer, candidate) { renamed = true }
                // Publish the same candidate to this process immediately after its durable pointer.
                val previous = delegate
                delegate = candidateRepo; candidateRepo = null; activeName = candidate; epoch++; mutable.value = verified
                previous?.close()
                files.atomic(journal, "COMMITTED\n$mode\n${oldName ?: "-"}\n$candidate")
                if (mode == "CLEARING") finishClearing(candidate) else {clearPending(); cleanAbandoned(candidate)}
                files.remove(journal); files.syncDirectory(root); failure.value = null
            }
        } catch (error: Throwable) {
            // A provider may fail after moving the pointer. Reread conservatively before considering deletion.
            val pointsToCandidate = runCatching { readPointer(pointer) == candidate }.getOrDefault(true)
            if (renamed || pointsToCandidate || journalStarted) withContext(NonCancellable) { failClosed(error, candidateRepo); candidateRepo = null }
            throw error
        } finally {
            withContext(NonCancellable) {
                candidateRepo?.close()
                if (!renamed && runCatching { readPointer(pointer) != candidate }.getOrDefault(false)) files.remove(directory)
            }
        }
    }
    private fun recoverJournal(name: String) {
        if (!journal.exists()) return
        val fields = journal.readText().split('\n')
        if (fields.size != 4 || fields[0] !in setOf("PREPARED", "COMMITTED") || fields[1] !in setOf("RESTORE", "CLEARING", "UPGRADE")) throw IOException("恢复日志无效，未自动修改数据")
        val old = fields[2].takeIf { it != "-" }?.let(::checkName)
        val candidate = checkName(fields[3])
        if (name != candidate && name != old) throw IOException("恢复日志与活动数据不一致")
        if (name == candidate) { if (fields[1] == "CLEARING") finishClearing(name) else if (fields[1] == "RESTORE") clearPending() }
        files.remove(journal); files.syncDirectory(root)
    }
    private fun finishClearing(active: String) {
        files.remove(protection)
        cleanAbandoned(active, keepProtection = false)
        files.remove(staging)
        files.syncDirectory(root)
    }
    suspend fun clearProtection() = withContext(Dispatchers.IO) { mutex.withLock {
        checkAuthorized(); requireReady()
        try { files.remove(protection); cleanAbandoned(requireNotNull(activeName)); files.syncDirectory(root) }
        catch (error: Throwable) { withContext(NonCancellable) { failClosed(error) }; throw error }
    } }
    fun ensureSpace(nextBytes: Long = 0) {
        val directory = root.takeIf { it.exists() } ?: root.parentFile!!
        if (directory.usableSpace < 128L * 1024 * 1024 + nextBytes) throw IOException("可用空间不足，请至少保留 128 MiB 工作空间")
    }
    override suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { delegate?.close(); delegate = null; activeName = null; mutable.value = null; epoch++; Unit } }
    private fun cleanAbandoned(active: String, keepProtection: Boolean = true) {
        val keep = setOfNotNull(active, if (keepProtection) readPointer(protection) else null)
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("gen-") && it.name !in keep }.forEach(files::remove)
    }
}
