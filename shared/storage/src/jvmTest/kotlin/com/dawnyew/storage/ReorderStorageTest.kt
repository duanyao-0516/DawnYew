package com.dawnyew.storage

import com.dawnyew.core.*
import com.dawnyew.protocol.BackupProtocol
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class ReorderStorageTest {
    private val env=object:DomainEnvironment {
        override fun id()=UUID.randomUUID().toString()
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=normalizeTextNfc(value)
    }
    @Test fun allScopesPersistTheirOrderAndOneReceiptPerDropAfterReopenAndBackup()=runBlocking {
        val dir=Files.createTempDirectory("reorder-").toFile();val path=dir.resolve("ledger.db").absolutePath
        var repo=RoomLedgerRepository(createDatabase(path),env)
        try {
            repo.initialize();repo.execute(Command.CreateLedger("另一个账本"),env.id())
            var state=repo.snapshot();val ledger=state.ledgers.first().id
            val accounts=listOf(Account(env.id(),ledger,"现金A",openingAt="2026-10-01T00:00:00"),Account(env.id(),ledger,"现金B",hidden=true,openingAt="2026-10-01T00:00:00"))
            val dimensions=listOf(Dimension(env.id(),ledger,DimensionKind.PROJECT,"项目A"),Dimension(env.id(),ledger,DimensionKind.PROJECT,"项目B",archived=true))
            repo.replace(state.copy(accounts=accounts,dimensions=dimensions));state=repo.snapshot()
            val beforeVersion=state.contentVersion;val beforeReceipts=state.receipts.size
            val ledgerIds=state.ledgers.map {it.id}.reversed()
            repo.execute(Command.ReorderLedgers(ledgerIds,state.contentVersion),env.id());state=repo.snapshot()
            val categoryIds=state.categories.filter {it.ledgerId==ledger && it.direction==Direction.EXPENSE}.map {it.id}.reversed()
            repo.execute(Command.ReorderCategories(ledger,Direction.EXPENSE,categoryIds,state.contentVersion),env.id());state=repo.snapshot()
            val accountIds=accounts.map {it.id}.reversed()
            repo.execute(Command.Finance(FinanceAction.ReorderAccounts(ledger,AccountType.CASH,accountIds,state.contentVersion)),env.id());state=repo.snapshot()
            val dimensionIds=dimensions.map {it.id}.reversed();val operation=env.id()
            val last=Command.Finance(FinanceAction.ReorderDimensions(ledger,DimensionKind.PROJECT,dimensionIds,state.contentVersion))
            repo.execute(last,operation);repo.execute(last,operation)
            val expected=repo.snapshot()
            assertEquals(beforeVersion+4,expected.contentVersion);assertEquals(beforeReceipts+4,expected.receipts.size)
            repo.close();repo=RoomLedgerRepository(createDatabase(path),env,createIfEmpty=false);repo.initialize()
            val loaded=repo.snapshot();assertEquals(expected,loaded)
            assertEquals(ledgerIds,loaded.ledgers.map {it.id})
            assertEquals(categoryIds,loaded.categories.filter {it.ledgerId==ledger && it.direction==Direction.EXPENSE}.map {it.id})
            assertEquals(accountIds,loaded.accounts.map {it.id});assertEquals(dimensionIds,loaded.dimensions.map {it.id})
            val restored=BackupProtocol.restore(BackupProtocol.create(loaded,env.utcNow()))
            assertEquals(loaded.ledgers.sortedBy {it.id},restored.ledgers.sortedBy {it.id});assertEquals(loaded.categories.sortedBy {it.id},restored.categories.sortedBy {it.id})
            assertEquals(loaded.accounts.sortedBy {it.id},restored.accounts.sortedBy {it.id});assertEquals(loaded.dimensions.sortedBy {it.id},restored.dimensions.sortedBy {it.id})
            assertEquals(ledgerIds,restored.ledgers.sortedBy {it.sortOrder}.map {it.id})
            assertEquals(categoryIds,restored.categories.filter {it.ledgerId==ledger && it.direction==Direction.EXPENSE}.sortedBy {it.sortOrder}.map {it.id})
            assertEquals(accountIds,restored.accounts.sortedBy {it.sortOrder}.map {it.id});assertEquals(dimensionIds,restored.dimensions.sortedBy {it.sortOrder}.map {it.id})
            assertFails {repo.execute(Command.ReorderLedgers(ledgerIds.reversed(),beforeVersion),env.id())}
            assertEquals(loaded,repo.snapshot())
        } finally {repo.close();dir.deleteRecursively()}
    }
}
