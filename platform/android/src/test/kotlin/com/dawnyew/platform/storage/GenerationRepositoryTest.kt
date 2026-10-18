package com.dawnyew.platform.storage

import com.dawnyew.core.*
import com.dawnyew.storage.CandidateSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class GenerationRepositoryTest {
    private class Environment : DomainEnvironment {
        override fun id() = UUID.randomUUID().toString()
        override fun utcNow() = "2026-10-18T00:00:00Z"
        override fun localNow() = "2026-10-18T08:00:00"
        override fun normalize(value: String) = value
    }
    private class Faults : GenerationFileOps() {
        var fail: ((String, File) -> Boolean)? = null
        var lastMoved: File? = null
        var copied: ((File, File) -> Unit)? = null
        private fun trip(operation: String, file: File) { if (fail?.invoke(operation, file) == true) { fail = null; throw IOException("injected $operation ${file.name}") } }
        override fun writeSynced(file: File, text: String) { trip(if (text.startsWith("COMMITTED")) "commit" else "write", file); super.writeSynced(file, text) }
        override fun replaceAtomic(source: File, target: File) { trip("rename", target); super.replaceAtomic(source, target); lastMoved = target; trip("afterRename", target) }
        override fun syncDirectory(directory: File) { trip("sync", lastMoved ?: directory); lastMoved = null; super.syncDirectory(directory) }
        override fun remove(file: File) { trip("remove", file); super.remove(file) }
        override fun copySynced(source: File, target: File, checkCancelled: () -> Unit, ensureSpace: (Long) -> Unit) {
            trip("copy", target); super.copySynced(source, target, checkCancelled, ensureSpace); copied?.invoke(source, target)
        }
    }
    private class Fixture(upgrading: Boolean = false) {
        val env = Environment()
        val directory = Files.createTempDirectory("dawnyew-generations-").toFile()
        val root = File(directory, "datasets")
        val staging = File(directory, "staging")
        val files = Faults()
        val states = mutableMapOf<String, LedgerState>()
        val corrupt = mutableSetOf<String>()
        val versions = mutableMapOf<String, Int>()
        var failMigration = false
        var afterCommitFailure: Throwable? = null
        var failLookup = false
        var authorized = true
        val owner = UUID.randomUUID().toString()
        val repository = GenerationRepository(root, staging, env, { name ->
            object : LedgerRepository {
                override val state = MutableStateFlow<LedgerState?>(null)
                var closed = false
                override suspend fun initialize() {
                    check(name !in corrupt) { "corrupt generation" }
                    if ((versions[name] ?: 3) < 3) {
                        if (failMigration) throw IOException("injected migration failure")
                        versions[name] = 3
                        File(root, "$name/ledger.db").writeText("synthetic schema 3")
                    }
                    File(root, "$name/ledger.db").apply { parentFile!!.mkdirs(); if (!exists()) writeText("synthetic") }
                    state.value = states.getOrPut(name) { DomainEngine(env).initialState() }
                }
                override suspend fun execute(command: Command, operationId: String, expectedVersion: Long?): List<String> {
                    check(!closed)
                    val mutation = DomainEngine(env).apply(snapshot(), command, operationId, expectedVersion)
                    replace(mutation.state)
                    afterCommitFailure?.let { afterCommitFailure = null; throw it }
                    return mutation.resultIds
                }
                override suspend fun lookupOperation(operationId: String): OperationOutcome? {
                    if (failLookup) throw IOException("injected receipt lookup failure")
                    return super<LedgerRepository>.lookupOperation(operationId)
                }
                override suspend fun snapshot(): LedgerState { check(!closed); return states.getValue(name) }
                override suspend fun replace(state: LedgerState) { check(!closed); states[name] = state; this.state.value = state }
                override suspend fun close() { closed = true; state.value = null }
            }
        }, files, { authorized }, ownerId = owner, candidateValidator = { name, summary ->
            check(name !in corrupt) { "corrupt generation" }
            val value = states.getValue(name)
            validateState(value, env::normalize)
            check(value.datasetId == summary.datasetId)
        }, upgrades = if (!upgrading) null else object : GenerationUpgradeOperations() {
            override val currentVersion = 3
            override fun version(database: File) = versions[database.parentFile!!.name] ?: 3
            override fun checkpoint(database: File, expectedVersion: Int) {
                check(version(database) == expectedVersion)
                check(database.parentFile!!.name !in corrupt)
            }
        })
        init {
            files.copied = { source, target ->
                val old = source.parentFile!!.name; val next = target.parentFile!!.name
                states[next] = states.getValue(old); versions[next] = versions[old] ?: 3
            }
        }
        suspend fun markAsOldVersion(version: Int = 1): String {
            val old = active(); repository.close(); versions[old] = version
            File(root, "$old/ledger.db").writeText("synthetic schema $version")
            return old
        }
        suspend fun verifiedCandidate(state: LedgerState): VerifiedCandidate {
            val name = "gen-" + env.id(); states[name] = state
            File(root, "$name/ledger.db").apply { parentFile!!.mkdirs(); writeText("validated synthetic candidate") }
            return VerifiedCandidate(CandidateSummary(state.datasetId, emptyMap(), 0, 0), repository.versionToken(), name, owner)
        }
        fun active() = File(root, "active").readText()
        suspend fun close() { repository.close(); directory.deleteRecursively() }
    }
    @Test fun directorySyncFailureAfterPointerRenameNeverDeletesActiveCandidate() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val before = f.repository.snapshot()
            val candidate = before.copy(datasetId = f.env.id())
            f.files.fail = { op, file -> op == "sync" && file.name == "active" }
            assertFailsWith<IOException> { f.repository.replace(candidate) }
            assertTrue(File(f.root, "${f.active()}/ledger.db").exists())
            assertNull(f.repository.state.value)
            assertFails { f.repository.execute(Command.CreateLedger("forbidden"), f.env.id()) }
            f.repository.initialize(); assertEquals(candidate, f.repository.snapshot())
        } finally { f.close() }
    }
    @Test fun uncertainRenameExceptionAlsoKeepsActivatedCandidate() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val replacement = f.repository.snapshot().copy(datasetId = f.env.id())
            f.files.fail = { op, file -> op == "afterRename" && file.name == "active" }
            assertFailsWith<IOException> { f.repository.replace(replacement) }
            assertTrue(File(f.root, "${f.active()}/ledger.db").exists())
            f.repository.initialize(); assertEquals(replacement, f.repository.snapshot())
        } finally { f.close() }
    }
    @Test fun committedJournalFailureCannotLeaveOldWritableDelegate() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val old = f.active(); val replacement = f.repository.snapshot().copy(datasetId = f.env.id())
            f.files.fail = { op, _ -> op == "commit" }
            assertFailsWith<IOException> { f.repository.replace(replacement) }
            assertNotEquals(old, f.active()); assertNull(f.repository.state.value)
            assertFails { f.repository.execute(Command.CreateLedger("must not be lost"), f.env.id()) }
            f.repository.initialize(); assertEquals(replacement, f.repository.snapshot())
            assertEquals(old, File(f.root, "protection").readText())
        } finally { f.close() }
    }
    @Test fun prePublicationFailureKeepsOriginalAndCanReinitialize() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val before = f.repository.snapshot(); val old = f.active()
            f.files.fail = { op, file -> op == "rename" && file.name == "active" }
            assertFailsWith<IOException> { f.repository.replace(before.copy(datasetId = f.env.id())) }
            assertEquals(old, f.active()); f.repository.initialize(); assertEquals(before, f.repository.snapshot())
        } finally { f.close() }
    }
    @Test fun stalePreviewRejectsOrdinaryEditUnderPublicationMutex() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val before = f.repository.snapshot(); val token = f.repository.versionToken()
            f.repository.execute(Command.CreateLedger("edited after preview"), f.env.id())
            assertFailsWith<DomainException> { f.repository.replace(before, token) }
            assertTrue(f.repository.snapshot().ledgers.any { it.name == "edited after preview" })
        } finally { f.close() }
    }
    @Test fun corruptedActiveCanExplicitlyPreviewAndRestoreProtectionIntoNewGeneration() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val protectedState = f.repository.snapshot(); f.repository.replace(protectedState.copy(datasetId = f.env.id()))
            val broken = f.active(); f.repository.close(); f.corrupt += broken
            assertFails { f.repository.initialize() }
            val preview = f.repository.previewProtection(); assertEquals(protectedState, preview.state)
            assertEquals(broken, f.active()) // Reading/previewing cannot silently roll back.
            f.repository.replace(preview.state, preview.baseline)
            assertNotEquals(broken, f.active()); assertEquals(protectedState, f.repository.snapshot())
        } finally { f.close() }
    }
    @Test fun clearCleanupFailureIsRetriedFromJournalWithoutResurrectingProtection() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); f.repository.execute(Command.CreateLedger("private old data"), f.env.id())
            f.repository.replace(f.repository.snapshot())
            val old = f.active(); val protect = File(f.root, "protection").readText()
            f.staging.mkdirs(); File(f.staging, "private.json").writeText("synthetic")
            f.files.fail = { op, file -> op == "remove" && file.name == "protection" }
            assertFailsWith<IOException> { f.repository.execute(Command.ClearAll, f.env.id()) }
            assertNull(f.repository.state.value); assertTrue(File(f.root, "journal").exists())
            assertFailsWith<IOException> { f.repository.previewProtection() }
            f.repository.initialize()
            assertFalse(File(f.root, old).exists()); assertFalse(File(f.root, protect).exists())
            assertFalse(File(f.root, "protection").exists()); assertFalse(f.staging.exists())
            assertFalse(f.repository.snapshot().ledgers.any { it.name == "private old data" })
        } finally { f.close() }
    }
    @Test fun staleImportEpochRejectsNewCommitButAllowsVerifiedSubmissionRetry() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val state = f.repository.snapshot()
            val entry = Entry(f.env.id(), state.ledgers.first().id, EntryKind.EXPENSE, 100,
                state.categories.first { it.direction == Direction.EXPENSE }.id, f.env.localNow(),
                createdAt = f.env.utcNow(), updatedAt = f.env.utcNow(), originKind = OriginKind.IMPORT, creationOperationId = f.env.id())
            val plan = ImportPlan(f.env.id(), f.env.id(), state.datasetId, state.contentVersion, f.repository.epoch,
                "fixture", "1", Sha256.hex("file".toByteArray()), Sha256.hex("mapping".toByteArray()), rows = listOf(ImportRow(1, entry))).sealed()
            val stale = plan.copy(databaseEpoch = f.repository.epoch + 1).sealed()
            val rejectedId = f.env.id()
            assertEquals(ErrorCode.CONFLICT, assertFailsWith<DomainException> { f.repository.execute(Command.CommitImport(stale), rejectedId) }.code)
            f.repository.acknowledgeOperation(rejectedId)
            assertEquals(listOf(plan.batchId), f.repository.execute(Command.CommitImport(plan), f.env.id()))
            f.repository.replace(f.repository.snapshot())
            val committed = f.repository.snapshot()
            val retryId = f.env.id()
            assertEquals(listOf(plan.batchId), f.repository.execute(Command.CommitImport(plan), retryId))
            f.repository.acknowledgeOperation(retryId)
            assertEquals(committed, f.repository.snapshot())
            assertFailsWith<DomainException> { f.repository.execute(Command.CommitImport(plan.copy(fileDigest = Sha256.hex("changed".toByteArray())).sealed()), f.env.id()) }; Unit
        } finally { f.close() }
    }
    @Test fun revokedAuthorizationIsCheckedInsideCommandAndRestoreMutex() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val original = f.repository.snapshot(); val token = f.repository.versionToken()
            f.authorized = false
            assertEquals(ErrorCode.LOCKED, assertFailsWith<DomainException> { f.repository.execute(Command.CreateLedger("forbidden"), f.env.id()) }.code)
            assertFailsWith<DomainException> { f.repository.replace(original, token) }
            assertFailsWith<DomainException> { f.repository.snapshot() }
            f.authorized = true; assertEquals(original, f.repository.snapshot())
        } finally { f.close() }
    }

    @Test fun streamedCandidatePublicationSurvivesPostRenameAndCommittedJournalFailures() = runBlocking {
        for (fault in listOf("sync", "commit", "afterRename")) {
            val f = Fixture()
            try {
                f.repository.initialize(); val state = f.repository.snapshot().copy(datasetId = f.env.id())
                val candidate = f.verifiedCandidate(state)
                f.files.fail = { operation, file -> operation == fault && (fault == "commit" || file.name == "active") }
                assertFailsWith<IOException> { f.repository.replaceCandidate(candidate) }
                assertNull(f.repository.state.value)
                f.repository.discardCandidate(candidate)
                assertTrue(File(f.root, "${f.active()}/ledger.db").exists())
                f.repository.initialize(); assertEquals(state, f.repository.snapshot())
            } finally { f.close() }
        }
    }
    @Test fun streamedCandidateCannotCrossOrdinaryEditAndDiscardCannotDeleteActive() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val initial = f.repository.snapshot()
            val candidate = f.verifiedCandidate(initial.copy(datasetId = f.env.id()))
            f.repository.execute(Command.CreateLedger("changed during preview"), f.env.id())
            assertFailsWith<DomainException> { f.repository.replaceCandidate(candidate) }
            f.repository.discardCandidate(candidate); assertFalse(File(f.root, candidate.generation).exists())
            val next = f.verifiedCandidate(initial.copy(datasetId = f.env.id()))
            f.repository.replaceCandidate(next); f.repository.discardCandidate(next)
            assertTrue(File(f.root, "${f.active()}/ledger.db").exists())
            assertFailsWith<DomainException> { f.repository.replaceCandidate(next) }; Unit
        } finally { f.close() }
    }

    @Test fun candidateDamagedAfterPreviewCannotPublishOrInvalidateCurrentGeneration() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val before = f.repository.snapshot(); val token = f.repository.versionToken()
            val candidate = f.verifiedCandidate(before.copy(datasetId = f.env.id()))
            f.corrupt += candidate.generation
            assertFails { f.repository.replaceCandidate(candidate) }
            assertEquals(token, f.repository.versionToken())
            assertEquals(before, f.repository.snapshot())
            assertFalse(File(f.root, "journal").exists()); assertFalse(File(f.root, "protection").exists())
            f.repository.discardCandidate(candidate); assertFalse(File(f.root, candidate.generation).exists())
            f.repository.execute(Command.CreateLedger("original remains writable"), f.env.id()); Unit
        } finally { f.close() }
    }

    @Test fun commitBeforeUiAcknowledgementSurvivesRestartAndPreventsDuplicateNewOperation() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val operation = f.env.id(); val command = Command.CreateLedger("receipt-window")
            val ids = f.repository.execute(command, operation)
            assertNull(f.repository.pendingOperation.value) // Normal success does not flash a recovery dialog.
            assertEquals(operation, File(f.root, "pending-operation").readText())
            f.repository.close(); f.repository.initialize()
            assertEquals(ids, f.repository.pendingOperation.value?.outcome?.resultIds)
            assertFailsWith<DomainException> { f.repository.execute(Command.CreateLedger("must wait"), f.env.id()) }
            assertEquals(ids, f.repository.execute(command, operation))
            assertEquals(1, f.repository.snapshot().ledgers.count { it.name == "receipt-window" })
            f.repository.acknowledgeOperation(operation)
            assertNull(f.repository.pendingOperation.value); assertFalse(File(f.root, "pending-operation").exists())
            f.repository.execute(Command.CreateLedger("after acknowledgement"), f.env.id()); Unit
        } finally { f.close() }
    }
    @Test fun failureAfterPendingMarkerRenameBeforeDatabaseCommitRecoversAsUncommitted() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val before = f.repository.snapshot(); val operation = f.env.id()
            f.files.fail = { kind, file -> kind == "afterRename" && file.name == "pending-operation" }
            assertFailsWith<IOException> { f.repository.execute(Command.CreateLedger("must not exist"), operation) }
            assertEquals(before, f.repository.snapshot()); assertEquals(operation, File(f.root, "pending-operation").readText())
            f.repository.close(); f.repository.initialize()
            assertEquals(PendingOperationRecovery(operation, null), f.repository.pendingOperation.value)
            f.repository.acknowledgeOperation(operation); assertNull(f.repository.pendingOperation.value)
        } finally { f.close() }
    }
    @Test fun acknowledgementFailureRetainsReceiptAndRestoreClearsOnlyAfterPublication() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val operation = f.env.id()
            f.repository.execute(Command.CreateLedger("saved"), operation)
            f.files.fail = { kind, file -> kind == "remove" && file.name == "pending-operation" }
            assertFailsWith<IOException> { f.repository.acknowledgeOperation(operation) }
            assertNotNull(f.repository.pendingOperation.value?.outcome)
            val replacement = f.repository.snapshot().copy(datasetId = f.env.id())
            f.files.fail = { kind, _ -> kind == "commit" }
            assertFailsWith<IOException> { f.repository.replace(replacement) }
            assertTrue(File(f.root, "pending-operation").exists())
            f.repository.initialize(); assertFalse(File(f.root, "pending-operation").exists()); assertNull(f.repository.pendingOperation.value)
            val clearId = f.env.id(); f.repository.execute(Command.ClearAll, clearId)
            f.repository.close(); f.repository.initialize()
            assertEquals(clearId, f.repository.pendingOperation.value?.operationId); assertNotNull(f.repository.pendingOperation.value?.outcome)
            f.repository.acknowledgeOperation(clearId)
        } finally { f.close() }
    }

    @Test fun startupUpgradeCopiesBeforeMigrationAndKeepsOldProtectionAndPendingReceipt() = runBlocking {
        val f = Fixture(upgrading = true)
        try {
            f.repository.initialize(); val operation = f.env.id()
            val ids = f.repository.execute(Command.CreateLedger("pre-upgrade receipt"), operation)
            val before = f.repository.snapshot(); val old = f.markAsOldVersion()
            f.repository.initialize()
            assertNotEquals(old, f.active()); assertEquals(3, f.versions[f.active()])
            assertEquals(1, f.versions[old]); assertEquals("synthetic schema 1", File(f.root, "$old/ledger.db").readText())
            assertEquals(old, File(f.root, "protection").readText()); assertEquals(before, f.repository.snapshot())
            assertEquals(ids, f.repository.pendingOperation.value?.outcome?.resultIds)
            assertEquals(operation, File(f.root, "pending-operation").readText())
            val directories = f.root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
            assertEquals(before, f.repository.previewProtection().state)
            assertEquals(directories, f.root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet())
            assertEquals(1, f.versions[old]) // Protection preview also migrates only its disposable copy.
            f.repository.acknowledgeOperation(operation)
        } finally { f.close() }
    }

    @Test fun copyOrMigrationFailurePreservesOldSchemaPointerAndPendingOperation() = runBlocking {
        for (point in listOf("copy", "migration", "rename")) {
            val f = Fixture(upgrading = true)
            try {
                f.repository.initialize(); val operation = f.env.id()
                f.repository.execute(Command.CreateLedger("before failed upgrade"), operation)
                val old = f.markAsOldVersion(2)
                f.failMigration = point == "migration"
                if (point != "migration") f.files.fail = { kind, file -> kind == point && (point == "copy" || file.name == "active") }
                assertFailsWith<IOException> { f.repository.initialize() }
                assertEquals(old, f.active()); assertEquals(2, f.versions[old])
                assertEquals("synthetic schema 2", File(f.root, "$old/ledger.db").readText())
                assertEquals(operation, File(f.root, "pending-operation").readText())
                assertEquals(listOf(old), f.root.listFiles()!!.filter { it.isDirectory }.map { it.name })
                f.failMigration = false; f.repository.initialize()
                assertNotNull(f.repository.pendingOperation.value?.outcome)
            } finally { f.close() }
        }
    }

    @Test fun postPublicationUpgradeFaultsRecoverNewSchemaWithoutClearingCommitReceipt() = runBlocking {
        for (point in listOf("sync", "afterRename", "commit")) {
            val f = Fixture(upgrading = true)
            try {
                f.repository.initialize(); val operation = f.env.id()
                f.repository.execute(Command.CreateLedger("committed before upgrade"), operation)
                val before = f.repository.snapshot(); val old = f.markAsOldVersion()
                f.files.fail = { kind, file -> kind == point && (point == "commit" || file.name == "active") }
                assertFailsWith<IOException> { f.repository.initialize() }
                assertNotEquals(old, f.active()); assertEquals(1, f.versions[old])
                assertNull(f.repository.state.value)
                f.repository.initialize(); assertEquals(before, f.repository.snapshot())
                assertEquals(operation, f.repository.pendingOperation.value?.operationId)
                assertNotNull(f.repository.pendingOperation.value?.outcome)
                assertEquals(old, File(f.root, "protection").readText())
            } finally { f.close() }
        }
    }

    @Test fun futureSchemaIsRejectedBeforeRoomOrCopyCanChangeIt() = runBlocking {
        val f = Fixture(upgrading = true)
        try {
            f.repository.initialize(); val old = f.markAsOldVersion(99)
            assertFailsWith<IOException> { f.repository.initialize() }
            assertEquals(old, f.active()); assertEquals("synthetic schema 99", File(f.root, "$old/ledger.db").readText())
            assertFalse(File(f.root, "protection").exists()); assertFalse(File(f.root, "journal").exists())
        } finally { f.close() }
    }

    @Test fun referencedCategoryRejectionClearsOnlyItsNewMarkerAndKeepsOriginalBusinessError() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val state = f.repository.snapshot()
            val category = state.categories.first { it.direction == Direction.EXPENSE }
            val save = f.env.id()
            f.repository.execute(Command.SaveEntry(ledgerId = category.ledgerId, kind = EntryKind.EXPENSE,
                amountMinor = 100, categoryId = category.id, occurrence = f.env.localNow(), note = "synthetic"), save)
            f.repository.acknowledgeOperation(save)
            val before = f.repository.snapshot(); val rejected = f.env.id()
            val error = assertFailsWith<DomainException> { f.repository.execute(Command.DeleteCategory(category.id), rejected) }
            assertEquals("已有账单引用该分类，可改为停用", error.message)
            assertEquals(before, f.repository.snapshot()); assertNull(f.repository.lookupOperation(rejected))
            assertNull(f.repository.pendingOperation.value); assertFalse(File(f.root, "pending-operation").exists())
            f.repository.close(); f.repository.initialize(); assertNull(f.repository.pendingOperation.value)
            f.repository.execute(Command.CreateLedger("after rejected delete"), f.env.id()); Unit
        } finally { f.close() }
    }

    @Test fun rejectedRetryCannotEraseAnExistingCommittedMarker() = runBlocking {
        val f = Fixture()
        try {
            f.repository.initialize(); val operation = f.env.id()
            val ids = f.repository.execute(Command.CreateLedger("first payload"), operation)
            assertFailsWith<DomainException> { f.repository.execute(Command.CreateLedger("changed payload"), operation) }
            assertEquals(operation, File(f.root, "pending-operation").readText())
            assertEquals(ids, f.repository.pendingOperation.value?.outcome?.resultIds)
        } finally { f.close() }
    }

    @Test fun failureAfterCommitKeepsReceiptEvenWhenTheThrownErrorIsDomainValidation() = runBlocking {
        for (failure in listOf(IOException("post-commit I/O"), DomainException(ErrorCode.VALIDATION, "post-commit callback"))) {
            val f = Fixture()
            try {
                f.repository.initialize(); val operation = f.env.id()
                f.afterCommitFailure = failure
                val thrown = assertFails { f.repository.execute(Command.CreateLedger("committed result"), operation) }
                assertEquals(failure::class, thrown::class); assertEquals(failure.message, thrown.message)
                assertEquals(operation, File(f.root, "pending-operation").readText())
                assertNotNull(f.repository.pendingOperation.value?.outcome)
                f.repository.close(); f.repository.initialize()
                assertEquals(1, f.repository.snapshot().ledgers.count { it.name == "committed result" })
                assertNotNull(f.repository.pendingOperation.value?.outcome)
            } finally { f.close() }
        }
    }

    @Test fun rejectedCommandRetainsRecoveryWhenReceiptLookupOrMarkerCleanupFails() = runBlocking {
        for (point in listOf("lookup", "cleanup")) {
            val f = Fixture()
            try {
                f.repository.initialize(); val existingName = f.repository.snapshot().ledgers.first().name
                val operation = f.env.id()
                if (point == "lookup") f.failLookup = true
                else f.files.fail = { kind, file -> kind == "remove" && file.name == "pending-operation" }
                assertFailsWith<DomainException> { f.repository.execute(Command.CreateLedger(existingName), operation) }
                assertEquals(operation, File(f.root, "pending-operation").readText())
                assertEquals(operation, f.repository.pendingOperation.value?.operationId)
                f.failLookup = false; f.repository.acknowledgeOperation(operation)
            } finally { f.close() }
        }
    }

}
