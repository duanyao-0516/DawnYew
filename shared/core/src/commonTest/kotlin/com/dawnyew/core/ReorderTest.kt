package com.dawnyew.core

import kotlin.test.*

class ReorderTest {
    private var sequence=100
    private fun id(n:Int)="00000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private val env=object:DomainEnvironment {
        override fun id()=id(++sequence)
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=value
    }
    private val engine=DomainEngine(env)
    private val ledger=id(1);private val other=id(2)
    private fun fixture()=LedgerState(id(99),ledgers=listOf(Ledger(ledger,"日常",sortOrder=8),Ledger(other,"归档",archived=true,sortOrder=-1)),
        categories=listOf(Category(id(10),ledger,Direction.EXPENSE,"餐饮",sortOrder=4),Category(id(11),ledger,Direction.EXPENSE,"停用",enabled=false,sortOrder=9),Category(id(12),ledger,Direction.INCOME,"工资"),Category(id(13),other,Direction.EXPENSE,"其他")),
        accounts=listOf(Account(id(20),ledger,"现金1",openingAt="2026-10-01T00:00:00",sortOrder=8),Account(id(21),ledger,"隐藏现金",openingAt="2026-10-01T00:00:00",hidden=true,sortOrder=-1),Account(id(22),ledger,"银行卡",type=AccountType.BANK,openingAt="2026-10-01T00:00:00")),
        dimensions=listOf(Dimension(id(30),ledger,DimensionKind.PROJECT,"项目1",sortOrder=7),Dimension(id(31),ledger,DimensionKind.PROJECT,"归档项目",archived=true,sortOrder=9),Dimension(id(32),ledger,DimensionKind.TAG,"标签")))
    private fun apply(state:LedgerState,command:Command,operation:String=env.id())=engine.apply(state,command,operation)
    @Test fun fourReordersAreAtomicScopedAndUpdateAllRevisionsWithOneVersion() {
        val before=fixture()
        val ledgers=apply(before,Command.ReorderLedgers(listOf(other,ledger),before.contentVersion)).state
        assertEquals(listOf(other,ledger),ledgers.ledgers.sortedBy {it.sortOrder}.map {it.id})
        assertEquals(before.categories,ledgers.categories);assertEquals(before.contentVersion+1,ledgers.contentVersion);assertEquals(1,ledgers.receipts.size)
        assertTrue(ledgers.ledgers.all {it.revision==2L})
        val categories=apply(before,Command.ReorderCategories(ledger,Direction.EXPENSE,listOf(id(11),id(10)),before.contentVersion)).state
        assertEquals(listOf(id(11),id(10)),categories.categories.filter {it.ledgerId==ledger && it.direction==Direction.EXPENSE}.sortedBy {it.sortOrder}.map {it.id})
        assertEquals(before.categories.filter {it.direction==Direction.INCOME || it.ledgerId==other},categories.categories.filter {it.direction==Direction.INCOME || it.ledgerId==other})
        assertFalse(categories.categories.first {it.id==id(11)}.enabled)
        val dimensions=apply(before,Command.Finance(FinanceAction.ReorderDimensions(ledger,DimensionKind.PROJECT,listOf(id(31),id(30)),before.contentVersion))).state
        assertEquals(listOf(id(31),id(30)),dimensions.dimensions.filter {it.kind==DimensionKind.PROJECT}.sortedBy {it.sortOrder}.map {it.id})
        assertTrue(dimensions.dimensions.first {it.id==id(31)}.archived)
        val accounts=apply(before,Command.Finance(FinanceAction.ReorderAccounts(ledger,AccountType.CASH,listOf(id(21),id(20)),before.contentVersion))).state
        assertEquals(listOf(id(21),id(20)),accounts.accounts.filter {it.type==AccountType.CASH}.sortedBy {it.sortOrder}.map {it.id})
        assertTrue(accounts.accounts.first {it.id==id(21)}.hidden)
        assertEquals(before.accounts.first {it.type==AccountType.BANK},accounts.accounts.first {it.type==AccountType.BANK})
        assertEquals(FinanceQueries.calculate(before).netWorthMinor,FinanceQueries.calculate(accounts).netWorthMinor)
    }
    @Test fun incompleteDuplicateForeignUnknownAndStaleOrdersCannotCommit() {
        val state=fixture()
        val invalid=listOf(
            Command.ReorderLedgers(listOf(ledger),state.contentVersion),
            Command.ReorderLedgers(listOf(ledger,ledger),state.contentVersion),
            Command.ReorderCategories(ledger,Direction.EXPENSE,listOf(id(10),id(12)),state.contentVersion),
            Command.ReorderCategories(ledger,Direction.EXPENSE,listOf(id(10),id(13)),state.contentVersion),
            Command.Finance(FinanceAction.ReorderAccounts(ledger,AccountType.CASH,listOf(id(20),id(22)),state.contentVersion)),
            Command.Finance(FinanceAction.ReorderDimensions(ledger,DimensionKind.PROJECT,listOf(id(30),id(80)),state.contentVersion)),
            Command.ReorderLedgers(listOf(other,ledger),state.contentVersion-1),
            Command.Finance(FinanceAction.ReorderAccounts(ledger,AccountType.CASH,listOf(id(21),id(20)),state.contentVersion-1)),
            Command.ReorderCategories(other,Direction.EXPENSE,listOf(id(13)),state.contentVersion),
        )
        for(command in invalid) assertFailsWith<DomainException>(command.toString()) {apply(state,command)}
        assertEquals(fixture(),state)
    }
    @Test fun reorderReceiptIsIdempotentAndProtectsVirtualAccountFields() {
        var state=fixture().copy(accounts=fixture().accounts+Account(id(23),ledger,"手工应付",AccountType.PAYABLE,openingAt="2026-10-01T00:00:00"))
        state=apply(state,Command.Finance(FinanceAction.SaveObligation(Obligation(id(40),ledger,ObligationKind.BORROW,"借入",id(41),"2026-10-01T00:00:00"),initialPrincipal=ObligationPrincipal(id(20),1000,"2026-10-01T12:00:00")))).state
        val original=state;val virtual=state.accounts.first {it.id==id(41)};val op=env.id()
        val command=Command.Finance(FinanceAction.ReorderAccounts(ledger,AccountType.PAYABLE,listOf(id(41),id(23)),state.contentVersion))
        val result=apply(state,command,op)
        assertEquals(result.state,apply(result.state,command,op).state)
        assertEquals(virtual.copy(sortOrder=0,revision=virtual.revision+1),result.state.accounts.first {it.id==virtual.id})
        assertEquals(original.obligations,result.state.obligations);assertEquals(original.transfers,result.state.transfers)
        assertEquals(FinanceQueries.calculate(original).netWorthMinor,FinanceQueries.calculate(result.state).netWorthMinor)
        assertFails {apply(result.state,command.copy(action=FinanceAction.ReorderAccounts(ledger,AccountType.PAYABLE,listOf(id(23),id(41)),original.contentVersion)),op)}
    }
}
