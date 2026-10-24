package com.dawnyew.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FinanceTest {
    private var counter=1000
    private val env=object:DomainEnvironment {
        override fun id()="00000000-0000-4000-8000-${(++counter).toString().padStart(12,'0')}"
        override fun localNow()="2026-10-24T12:00:00"
        override fun utcNow()="2026-10-24T04:00:00Z"
        override fun normalize(value:String)=value.trim()
    }
    private val engine=DomainEngine(env)
    private var s=engine.initialState()
    private val ledger get()=s.ledgers.first().id
    private val expense get()=s.categories.first { it.direction==Direction.EXPENSE }.id
    private val time="2026-10-01T10:00:00"
    private fun act(a:FinanceAction) { s=engine.apply(s,Command.Finance(a),env.id()).state }
    private fun cmd(c:Command) { s=engine.apply(s,c,env.id()).state }
    private fun account(name:String,opening:Long=0):String { val id=env.id();act(FinanceAction.SaveAccount(Account(id,ledger,name,openingBalanceMinor=opening,openingAt="2026-01-01T00:00:00")));return id }
    private fun entry(amount:Long,account:String?,kind:EntryKind=EntryKind.EXPENSE,recorded:Boolean=true,excluded:Boolean=false) {
        cmd(Command.SaveEntry(ledgerId=ledger,kind=kind,amountMinor=amount,categoryId=if(kind==EntryKind.INCOME) s.categories.first { it.direction==Direction.INCOME }.id else expense,occurrence=time,note="",details=EntryDetails("",account,recorded,excluded)))
    }
    private fun obligation(kind:ObligationKind,cash:String,amount:Long):String { val id=env.id();act(FinanceAction.SaveObligation(Obligation(id,ledger,kind,kind.name,env.id(),time),initialPrincipal=ObligationPrincipal(cash,amount,time)));return id }

    @Test fun completeFinancialGoldenExampleKeepsIncomeExpenseAndAssetsSeparate() {
        val cash=account("现金",100000);val bank=account("储蓄")
        entry(1234,cash);entry(10000,cash,EntryKind.INCOME)
        val recurring=RecurringPlan(env.id(),ledger,"周期",ActivityKind.EXPENSE,300,time,RecurringFrequency.MONTHLY,totalOccurrences=1,categoryId=expense,accountId=cash)
        act(FinanceAction.SaveRecurringPlan(recurring))
        val borrow=obligation(ObligationKind.BORROW,cash,1000)
        act(FinanceAction.RecordObligation(borrow,cash,200,time,settlement=true))
        obligation(ObligationKind.LEND,cash,600);obligation(ObligationKind.REIMBURSEMENT,cash,400)
        act(FinanceAction.SaveTransfer(AccountTransfer(env.id(),ledger,cash,bank,2000,time,createdAt=env.utcNow(),updatedAt=env.utcNow())))
        entry(100,cash,recorded=false)
        cmd(Command.SetBudget(ledger,"2026-10",30000))
        val goal=SavingsPlan(env.id(),ledger,"随手攒",SavingsScenario.FLEXIBLE)
        act(FinanceAction.SaveSavingsPlan(goal));act(FinanceAction.SaveSavingsMovement(SavingsMovement(env.id(),goal.id,SavingsMovementKind.DEPOSIT,500,time)))
        val f=FinanceQueries.calculate(s)
        assertEquals(106266L,f.accounts.first { it.account.id==cash }.balanceMinor)
        assertEquals(2000L,f.accounts.first { it.account.id==bank }.balanceMinor)
        assertEquals(1000L,f.accounts.filter { it.account.type==AccountType.RECEIVABLE }.sumOf { it.balanceMinor })
        assertEquals(-800L,f.accounts.single { it.account.type==AccountType.PAYABLE }.balanceMinor)
        assertEquals(108466L,f.netWorthMinor);assertEquals(500L,f.savings.single().savedMinor)
        val stat=Statistics.calculate(s,QueryScope())
        assertEquals(1634L,stat.expense);assertEquals(1634L,stat.budgetUsed)
        assertEquals(28366L,30000-stat.budgetUsed)
        assertEquals(1,s.recurringOccurrences.size)
        validateState(s)
    }

    @Test fun refundsReadCurrentParentBudgetExclusionAndKeepOwnRecordedFlag() {
        entry(10000,null);val parent=s.entries.single()
        cmd(Command.AddRefund(parent.id,2000,"2026-10-02T10:00:00","",details=EntryDetails("",recorded=false)))
        act(FinanceAction.SetEntryDetails(parent.id,EntryDetails(parent.id,excludedFromBudget=true),emptyList(),parent.revision))
        val stats=Statistics.calculate(s,QueryScope())
        assertEquals(10000L,stats.expense);assertEquals(2000L,stats.refunds);assertEquals(0L,stats.budgetUsed)
        assertFalse(s.entryDetails.single { it.entryId!=parent.id }.recorded)
        assertTrue(effectiveBudgetExcluded(s,s.entries.last()))
    }

    @Test fun recurringMonthEndUsesOriginalAnchorAndBatchCreatesUniqueOperations() {
        val plan=RecurringPlan(env.id(),ledger,"月末",ActivityKind.EXPENSE,100,"2026-01-31T10:00:00",RecurringFrequency.MONTHLY,totalOccurrences=4,categoryId=expense)
        assertEquals("2026-02-28T10:00:00",recurringDate(plan,1));assertEquals("2026-03-31T10:00:00",recurringDate(plan,2))
        act(FinanceAction.SaveRecurringPlan(plan));act(FinanceAction.ProcessRecurring())
        assertEquals(4,s.entries.size);assertEquals(4,s.entries.map { it.creationOperationId }.distinct().size)
        assertEquals(2,s.receipts.size)
        val saved=s.recurringPlans.single()
        assertFailsWith<DomainException> { act(FinanceAction.SaveRecurringPlan(saved.copy(frequency=RecurringFrequency.WEEKLY),saved.revision)) }
        cmd(Command.SoftDelete(s.entries.first().id));cmd(Command.Purge(s.entries.first().id))
        act(FinanceAction.ProcessRecurring());assertEquals(3,s.entries.size);assertEquals(4,s.recurringOccurrences.size)
    }

    @Test fun virtualAccountsCannotBeUsedByNormalEntriesOrForgedOwners() {
        val cash=account("现金");obligation(ObligationKind.BORROW,cash,1000)
        val virtual=s.obligations.single().virtualAccountId
        assertFailsWith<DomainException> { entry(100,virtual) }
        assertFailsWith<DomainException> { act(FinanceAction.RecordObligation(s.obligations.single().id,cash,1001,time,settlement=true)) }
        assertFailsWith<DomainException> { validateState(s.copy(obligations=s.obligations + s.obligations.single().copy(id=env.id()))) }
        val original=s.accounts.first { it.id==virtual }
        assertFailsWith<DomainException> { act(FinanceAction.SaveAccount(original.copy(openingBalanceMinor=100),original.revision)) }
    }

    @Test fun wishCompletionHasOneMovementAndCancellationRestoresProgress() {
        val plan=SavingsPlan(env.id(),ledger,"目标",targetMinor=10000);act(FinanceAction.SaveSavingsPlan(plan))
        val deposit=SavingsMovement(env.id(),plan.id,SavingsMovementKind.DEPOSIT,1000,time);act(FinanceAction.SaveSavingsMovement(deposit))
        val wish=SavingsWish(env.id(),plan.id,"愿望",400);act(FinanceAction.SaveSavingsWish(wish))
        act(FinanceAction.FulfillSavingsWish(wish.id,time,expectedRevision=1))
        assertEquals(600L,FinanceQueries.calculate(s).savings.single().savedMinor)
        val saved=s.savingsWishes.single()
        assertFailsWith<DomainException> { act(FinanceAction.FulfillSavingsWish(saved.id,time,expectedRevision=saved.revision)) }
        assertFailsWith<DomainException> { act(FinanceAction.SaveSavingsWish(saved.copy(amountMinor=500),saved.revision)) }
        assertFailsWith<DomainException> { act(FinanceAction.SetSavingsMovementDeleted(deposit.id,true,1)) }
        act(FinanceAction.CancelSavingsWish(saved.id,saved.revision))
        assertEquals(1000L,FinanceQueries.calculate(s).savings.single().savedMinor)
        assertNull(s.savingsWishes.single().fulfilledMovementId)
    }

    @Test fun budgetReallocationIsConservativeAndWholeMonthDeletionCleansChildren() {
        cmd(Command.SetBudget(ledger,"2026-10",10000))
        val a=BudgetAllocation(env.id(),ledger,"2026-10",expense,6000)
        val b=BudgetAllocation(env.id(),ledger,"2026-10",s.categories.filter { it.direction==Direction.EXPENSE }[1].id,4000)
        act(FinanceAction.SaveBudgetAllocation(a));act(FinanceAction.SaveBudgetAllocation(b))
        act(FinanceAction.ReallocateBudget(a.id,b.id,1000,1,1))
        assertEquals(listOf(5000L,5000L),s.budgetAllocations.map { it.amountMinor })
        assertFailsWith<DomainException> { cmd(Command.DeleteCategory(a.categoryId)) }
        assertFailsWith<DomainException> { cmd(Command.SetBudget(ledger,"2026-10",9999)) }
        cmd(Command.SetBudget(ledger,"2026-10",null));assertTrue(s.budgetAllocations.isEmpty());assertTrue(s.budgetReallocations.isEmpty())
    }

    @Test fun activitiesUseFamilyTieBreakerAndCategoryExcludesTransfers() {
        val a=account("A");val b=account("B");entry(100,a)
        val id=s.entries.single().id
        act(FinanceAction.SaveTransfer(AccountTransfer(id,ledger,a,b,100,time,createdAt=env.utcNow(),updatedAt=env.utcNow())))
        val first=activityPageSnapshot(s,ActivityScope(),limit=1)
        assertEquals(ActivityFamily.TRANSFER,first.activities.single().family)
        assertEquals(ActivityFamily.ENTRY,activityPageSnapshot(s,ActivityScope(),first.nextCursor,1).activities.single().family)
        assertEquals(1,activityPageSnapshot(s,ActivityScope(categoryIds=setOf(expense))).activities.size)
    }

    @Test fun pendingObligationPrincipalCannotBeSettled() {
        val cash=account("现金")
        val id=env.id()
        act(FinanceAction.SaveObligation(Obligation(id,ledger,ObligationKind.BORROW,"待入账",env.id(),time),initialPrincipal=ObligationPrincipal(cash,1000,time,recorded=false)))
        val balance=FinanceQueries.calculate(s).obligations.single()
        assertEquals(0L,balance.remainingMinor);assertEquals(1000L,balance.pendingPrincipalMinor)
        assertFailsWith<DomainException> { act(FinanceAction.RecordObligation(id,cash,100,time,settlement=true)) }
        assertFalse(balance.isOverdue("2099-01-01"))
    }

    @Test fun savingsSingleEndpointsHaveExplicitExternalFundDirections() {
        val a=account("A",1000);val b=account("B")
        val plan=SavingsPlan(env.id(),ledger,"自由",SavingsScenario.FLEXIBLE);act(FinanceAction.SaveSavingsPlan(plan))
        act(FinanceAction.SaveSavingsMovement(SavingsMovement(env.id(),plan.id,SavingsMovementKind.DEPOSIT,500,time),TransferEndpoints(a,null)))
        assertEquals(500L,FinanceQueries.calculate(s).netWorthMinor)
        act(FinanceAction.SaveSavingsMovement(SavingsMovement(env.id(),plan.id,SavingsMovementKind.WITHDRAWAL,200,time),TransferEndpoints(null,b)))
        assertEquals(700L,FinanceQueries.calculate(s).netWorthMinor)
        assertEquals(300L,FinanceQueries.calculate(s).savings.single().savedMinor)
        val transfer=s.transfers.first()
        assertFailsWith<DomainException> { act(FinanceAction.SetTransferDeleted(transfer.id,true,transfer.revision)) }
        assertFailsWith<DomainException> { act(FinanceAction.SaveSavingsMovement(SavingsMovement(env.id(),plan.id,SavingsMovementKind.DEPOSIT,100,time),TransferEndpoints(a,a))) }
    }

    @Test fun recurringBoundedBatchSkipAndWeekdayWeekendAnchors() {
        val plan=RecurringPlan(env.id(),ledger,"每日",ActivityKind.EXPENSE,1,"2026-01-01T10:00:00",RecurringFrequency.DAILY,categoryId=expense)
        act(FinanceAction.SaveRecurringPlan(plan));act(FinanceAction.ProcessRecurring())
        assertEquals(101,s.entries.size)
        val saved=s.recurringPlans.single();act(FinanceAction.ExecuteRecurring(saved.id,saved.revision,skip=true))
        assertEquals(102,s.recurringPlans.single().nextIndex);assertEquals(101,s.entries.size)
        assertEquals("2026-01-05T10:00:00",recurringDate(plan.copy(anchor="2026-01-03T10:00:00",frequency=RecurringFrequency.WEEKDAYS),0))
        assertEquals("2026-01-10T10:00:00",recurringDate(plan.copy(anchor="2026-01-03T10:00:00",frequency=RecurringFrequency.WEEKENDS),2))
        assertEquals("2028-02-29T10:00:00",recurringDate(plan.copy(anchor="2024-02-29T10:00:00",frequency=RecurringFrequency.YEARLY),4))
    }

    @Test fun quarterlyExecutionKeepsOriginalMonthEndAndStopsBeforeNextFutureQuarter() {
        val plan=RecurringPlan(env.id(),ledger,"季度月末",ActivityKind.EXPENSE,123,"2026-01-31T10:00:00",RecurringFrequency.QUARTERLY,totalOccurrences=4,categoryId=expense)
        act(FinanceAction.SaveRecurringPlan(plan))
        act(FinanceAction.ProcessRecurring())
        val expected=listOf("2026-01-31T10:00:00","2026-04-30T10:00:00","2026-07-31T10:00:00")
        assertEquals(expected,s.entries.map { it.occurrence })
        assertEquals(expected,s.recurringOccurrences.map { it.scheduledAt })
        assertEquals(listOf(0,1,2),s.recurringOccurrences.map { it.index })
        assertEquals(3,s.recurringPlans.single().nextIndex)
        assertEquals("2026-10-31T10:00:00",recurringNextOccurrence(s.recurringPlans.single()))
        assertEquals(369L,Statistics.calculate(s,QueryScope()).expense)
    }

    @Test fun pausedDuePlanCannotExecuteAndResumeProcessesEachPeriodOnlyOnce() {
        val plan=RecurringPlan(env.id(),ledger,"暂停计划",ActivityKind.EXPENSE,100,time,RecurringFrequency.DAILY,totalOccurrences=2,paused=true,categoryId=expense)
        act(FinanceAction.SaveRecurringPlan(plan))
        act(FinanceAction.ProcessRecurring())
        assertTrue(s.entries.isEmpty());assertTrue(s.recurringOccurrences.isEmpty())
        val paused=s.recurringPlans.single();val before=s
        assertEquals(0,paused.nextIndex)
        assertFailsWith<DomainException> { act(FinanceAction.ExecuteRecurring(paused.id,paused.revision)) }
        assertEquals(before,s)
        act(FinanceAction.SaveRecurringPlan(paused.copy(paused=false),paused.revision))
        act(FinanceAction.ProcessRecurring())
        assertEquals(listOf("2026-10-01T10:00:00","2026-10-02T10:00:00"),s.entries.map { it.occurrence })
        assertEquals(2,s.recurringPlans.single().nextIndex)
        val processed=s.recurringOccurrences;val entries=s.entries
        act(FinanceAction.ProcessRecurring())
        assertEquals(processed,s.recurringOccurrences);assertEquals(entries,s.entries)
    }

    @Test fun futurePeriodCannotBeGeneratedOrSkippedAndRejectionKeepsState() {
        val plan=RecurringPlan(env.id(),ledger,"今天稍后",ActivityKind.EXPENSE,100,"2026-10-24T13:00:00",RecurringFrequency.DAILY,totalOccurrences=1,categoryId=expense)
        act(FinanceAction.SaveRecurringPlan(plan))
        val saved=s.recurringPlans.single();val before=s
        assertTrue(s.entries.isEmpty());assertTrue(s.recurringOccurrences.isEmpty())
        for(skip in listOf(false,true)) {
            assertFailsWith<DomainException> { act(FinanceAction.ExecuteRecurring(saved.id,saved.revision,skip=skip)) }
            assertEquals(before,s)
        }
        act(FinanceAction.ProcessRecurring())
        assertTrue(s.entries.isEmpty());assertTrue(s.recurringOccurrences.isEmpty())
        assertEquals(saved,s.recurringPlans.single())
    }

    @Test fun entryDetailsEditsProtectImportedDigestAndPurgeClearsAssociations() {
        val imported=Entry(env.id(),ledger,EntryKind.EXPENSE,100,expense,time,createdAt=env.utcNow(),updatedAt=env.utcNow(),creationOperationId=env.id())
        val plan=ImportPlan(env.id(),env.id(),s.datasetId,s.contentVersion,0,"fixture","1",Sha256.hex("file".encodeToByteArray()),Sha256.hex("mapping".encodeToByteArray()),rows=listOf(ImportRow(1,imported))).sealed()
        cmd(Command.CommitImport(plan))
        act(FinanceAction.SetEntryDetails(imported.id,EntryDetails(imported.id,recorded=false),emptyList(),1))
        assertEquals(2L,s.entries.single().revision)
        assertFailsWith<DomainException> { cmd(Command.UndoImport(plan.batchId)) }
        cmd(Command.SoftDelete(imported.id));cmd(Command.Purge(imported.id))
        assertTrue(s.entryDetails.isEmpty());assertTrue(s.entryTags.isEmpty());validateState(s)
    }

    @Test fun accountOpeningAndArchiveRulesKeepHistoryButRejectNewLinks() {
        val id=account("现金",1000);entry(100,id)
        val account=s.accounts.single()
        assertFailsWith<DomainException> { act(FinanceAction.SaveAccount(account.copy(openingAt="2025-01-01T00:00:00"),account.revision)) }
        act(FinanceAction.SaveAccount(account.copy(archived=true),account.revision))
        assertEquals(900L,FinanceQueries.calculate(s).netWorthMinor)
        assertFailsWith<DomainException> { entry(100,id) }
        assertFailsWith<DomainException> { act(FinanceAction.DeleteAccount(id,s.accounts.single().revision)) }
        cmd(Command.ClearAll);assertTrue(s.accounts.isEmpty());assertTrue(s.entryDetails.isEmpty())
    }

    @Test fun legacySaveAndRefundReceiptDigestsRemainReplayable() {
        val save:Command=Command.SaveEntry(ledgerId=ledger,kind=EntryKind.EXPENSE,amountMinor=100,categoryId=expense,occurrence=time,note="")
        val refund:Command=Command.AddRefund(env.id(),100,time,"")
        listOf(save,refund).forEach { command ->
            val oldPayload=Json { encodeDefaults=true }.encodeToString(command).replace(",\"details\":null", "").replace(",\"tagIds\":null", "")
            val op=env.id();val original=s.copy(receipts=listOf(OperationReceipt(op,command::class.simpleName!!,Sha256.hex(oldPayload.encodeToByteArray()),listOf(env.id()),env.utcNow())))
            assertEquals(original,engine.apply(original,command,op).state)
        }
    }

    @Test fun financePreferencesHaveSharedValidationAndDeletedDefaultIsCleared() {
        val account=account("默认")
        cmd(Command.SetPreference("amountDisplay","compact"));cmd(Command.SetPreference("continueEntry","true"));cmd(Command.SetPreference("defaultAccountId",account))
        validateEntityBatch(LedgerEntityBatch(preferences=s.preferences));validateState(s)
        listOf("amountDisplay" to "approximate","continueEntry" to "yes","defaultAccountId" to "bad").forEach { (key,value) -> assertFailsWith<DomainException> { cmd(Command.SetPreference(key,value)) } }
        act(FinanceAction.DeleteAccount(account,1))
        assertEquals("none",s.preferences.single { it.key=="defaultAccountId" }.value)
    }

    @Test fun recurringFutureTemplateMayUseAccountOpenedAfterOriginalAnchor() {
        val plan=RecurringPlan(env.id(),ledger,"月计划",ActivityKind.EXPENSE,100,"2026-01-01T10:00:00",RecurringFrequency.MONTHLY,categoryId=expense)
        act(FinanceAction.SaveRecurringPlan(plan));act(FinanceAction.ProcessRecurring())
        val account=Account(env.id(),ledger,"新账户",openingAt="2026-10-24T00:00:00")
        act(FinanceAction.SaveAccount(account))
        val saved=s.recurringPlans.single();act(FinanceAction.SaveRecurringPlan(saved.copy(accountId=account.id),saved.revision))
        assertEquals(account.id,s.recurringPlans.single().accountId)
    }

    @Test fun pendingSettlementsDoNotReserveFundsButRecordingRejectsOverpayment() {
        val cash=account("现金");val id=obligation(ObligationKind.BORROW,cash,1000)
        act(FinanceAction.RecordObligation(id,cash,900,time,settlement=true,recorded=false))
        act(FinanceAction.RecordObligation(id,cash,900,time,settlement=true,recorded=false))
        val transfers=s.transfers.filter { it.businessKind==TransferBusinessKind.OBLIGATION_SETTLEMENT }
        assertEquals(1000L,FinanceQueries.calculate(s).obligations.single().remainingMinor)
        act(FinanceAction.RecordObligation(id,cash,900,time,settlement=true,recorded=true,transferId=transfers[0].id,expectedRevision=transfers[0].revision))
        assertEquals(100L,FinanceQueries.calculate(s).obligations.single().remainingMinor)
        val before=s
        assertFailsWith<DomainException> { act(FinanceAction.RecordObligation(id,cash,900,time,settlement=true,recorded=true,transferId=transfers[1].id,expectedRevision=transfers[1].revision)) }
        assertEquals(before,s)
    }
}
