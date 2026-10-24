package com.dawnyew.core

/** Atomic association mutation shared by manual writes and recurring generated entries. */
internal fun updateEntryAssociations(state:LedgerState,entryId:String,details:EntryDetails?,tagIds:List<String>?,preserveIfNull:Boolean=true):LedgerState {
    val e=state.entries.firstOrNull { it.id==entryId } ?: invalid("账单不存在")
    val old=state.entryDetails.firstOrNull { it.entryId==entryId }
    val d=details?.copy(entryId=entryId) ?: if(preserveIfNull) old else null
    if(d?.accountId!=null && d.accountId!=old?.accountId) {
        val account=state.accounts.firstOrNull { it.id==d.accountId } ?: invalid("账户不存在")
        if(account.archived) invalid("账户已归档")
    }
    fun activeDimension(id:String?,oldId:String?) { if(id!=null && id!=oldId && state.dimensions.firstOrNull { it.id==id }?.archived==true) invalid("维度已归档") }
    activeDimension(d?.projectId,old?.projectId);activeDimension(d?.merchantId,old?.merchantId)
    val tags=tagIds ?: state.entryTags.filter { it.entryId==entryId }.map { it.tagId }
    val oldTags=state.entryTags.filter { it.entryId==entryId }.map { it.tagId }.toSet()
    tags.forEach { activeDimension(it,if(it in oldTags) it else null) }
    if(tags.size!=tags.distinct().size) invalid("标签不能重复")
    if(e.kind==EntryKind.REFUND && d?.excludedFromBudget==true) invalid("退款预算资格由原支出决定")
    return state.copy(entryDetails=state.entryDetails.filterNot { it.entryId==entryId }+listOfNotNull(d),entryTags=state.entryTags.filterNot { it.entryId==entryId }+tags.map { EntryTag(entryId,it) })
}

internal fun mutateFinance(original:LedgerState,action:FinanceAction,env:DomainEnvironment):Mutation = FinanceMutator(original,env).apply(action)
private class FinanceMutator(var s:LedgerState,val env:DomainEnvironment) {
    private val now get()=env.utcNow()
    private fun revision(old:Long?,expected:Long?):Long { if(old!=expected) conflict("数据已更新，请重新打开");return Money.add(old ?: 0,1) }
    private fun writable(ledgerId:String) { val ledger=s.ledgers.firstOrNull { it.id==ledgerId } ?: invalid("账本不存在");if(ledger.archived) invalid("请先取消归档该账本") }
    private fun name(value:String)=env.normalize(value.trim())
    private fun occurrence(value:String,old:String?=null) { validateOccurrence(value);if(value!=old && value>env.localNow()) invalid("不能记录未来发生的交易") }
    private fun account(id:String?,ledgerId:String,date:String,oldIds:Set<String> = emptySet()) {
        if(id==null) return
        val a=s.accounts.firstOrNull { it.id==id } ?: invalid("账户不存在")
        if(a.ledgerId!=ledgerId || date<a.openingAt) invalid("账户归属或期初日期不一致")
        if(a.archived && id !in oldIds) invalid("账户已归档")
        if(s.obligations.any { it.virtualAccountId==id }) invalid("不能直接使用单据专属账户")
    }
    private fun hasAccountReferences(id:String)=s.entryDetails.any { it.accountId==id } || s.transfers.any { it.fromAccountId==id || it.toAccountId==id } || s.recurringPlans.any { it.accountId==id || it.fromAccountId==id || it.toAccountId==id } || s.obligations.any { it.virtualAccountId==id }
    private fun transfer(id:String)=s.transfers.firstOrNull { it.id==id } ?: invalid("转账不存在")
    private fun putTransfer(value:AccountTransfer) { s=s.copy(transfers=s.transfers.filterNot { it.id==value.id }+value) }
    private fun plainTransfer(t:AccountTransfer) { if(t.businessKind!=TransferBusinessKind.MANUAL) invalid("请从来源业务修改资金记录") }
    private fun savingsPlan(id:String,requireActive:Boolean=true):SavingsPlan { val p=s.savingsPlans.firstOrNull { it.id==id } ?: invalid("存钱计划不存在");writable(p.ledgerId);if(requireActive && p.archived) invalid("存钱计划已归档");return p }
    private fun savingsMovement(id:String)=s.savingsMovements.firstOrNull { it.id==id } ?: invalid("存钱记录不存在")
    private fun wish(id:String)=s.savingsWishes.firstOrNull { it.id==id } ?: invalid("愿望不存在")
    private fun obligation(id:String,requireActive:Boolean=true):Obligation { val o=s.obligations.firstOrNull { it.id==id } ?: invalid("单据不存在");writable(o.ledgerId);if(requireActive && o.archived) invalid("单据已归档");return o }
    private fun category(id:String?,ledgerId:String,kind:ActivityKind,old:String?=null) {
        val c=s.categories.firstOrNull { it.id==id } ?: invalid("分类不存在")
        if(c.ledgerId!=ledgerId || c.direction!=(if(kind==ActivityKind.INCOME) Direction.INCOME else Direction.EXPENSE)) invalid("分类归属或收支类型不一致")
        if(!c.enabled && c.id!=old) invalid("分类已停用")
    }
    private fun dimension(id:String?,ledgerId:String,kind:DimensionKind,old:String?=null) { if(id!=null) { val d=s.dimensions.firstOrNull { it.id==id } ?: invalid("维度不存在");if(d.ledgerId!=ledgerId || d.kind!=kind || d.archived && id!=old) invalid("维度归属或状态无效") } }
    private fun recordObligation(o:Obligation,accountId:String,amount:Long,date:String,settlement:Boolean,recorded:Boolean,reconciled:Boolean,note:String,id:String?=null,expected:Long?=null):String {
        val old=id?.let(::transfer)
        if(old!=null && (old.businessId!=o.id || old.businessKind !in setOf(TransferBusinessKind.OBLIGATION_PRINCIPAL,TransferBusinessKind.OBLIGATION_SETTLEMENT) || old.deleted)) invalid("该转账不属于当前有效单据")
        val rev=revision(old?.revision,expected);Money.requireValid(amount);occurrence(date,old?.occurrence)
        account(accountId,o.ledgerId,date,listOfNotNull(old?.fromAccountId,old?.toAccountId).toSet())
        val virtualFrom=(o.kind==ObligationKind.BORROW)!=settlement
        val t=AccountTransfer(id ?: env.id(),o.ledgerId,if(virtualFrom) o.virtualAccountId else accountId,if(virtualFrom) accountId else o.virtualAccountId,amount,date,note,recorded,reconciled,revision=rev,createdAt=old?.createdAt ?: now,updatedAt=now,businessKind=if(settlement) TransferBusinessKind.OBLIGATION_SETTLEMENT else TransferBusinessKind.OBLIGATION_PRINCIPAL,businessId=o.id)
        putTransfer(t);return t.id
    }
    private fun saveMovement(value:SavingsMovement,endpoints:TransferEndpoints,expected:Long?,allowWish:Boolean=false):String {
        val p=savingsPlan(value.planId);val old=s.savingsMovements.firstOrNull { it.id==value.id }
        if(old!=null && (old.planId!=value.planId || old.deleted)) invalid("不能修改计划归属或回收站记录")
        if(!allowWish && (value.kind==SavingsMovementKind.WISH || old?.kind==SavingsMovementKind.WISH)) invalid("请从愿望管理完成记录")
        if(value.deleted) invalid("请使用删除命令")
        if(value.transferId!=null && value.transferId!=old?.transferId) invalid("存钱转账引用由业务管理")
        val rev=revision(old?.revision,expected);Money.requireValid(value.amountMinor);occurrence(value.occurrence,old?.occurrence)
        val prior=old?.transferId?.let(::transfer)
        val oldAccounts=listOfNotNull(prior?.fromAccountId,prior?.toAccountId).toSet()
        account(endpoints.fromAccountId,p.ledgerId,value.occurrence,oldAccounts);account(endpoints.toAccountId,p.ledgerId,value.occurrence,oldAccounts)
        if(endpoints.fromAccountId!=null && endpoints.fromAccountId==endpoints.toAccountId) invalid("转账账户不能相同")
        val linked=if(endpoints.fromAccountId==null && endpoints.toAccountId==null) null else AccountTransfer(prior?.id ?: env.id(),p.ledgerId,endpoints.fromAccountId,endpoints.toAccountId,value.amountMinor,value.occurrence,value.note,endpoints.recorded,endpoints.reconciled,revision=Money.add(prior?.revision ?: 0,1),createdAt=prior?.createdAt ?: now,updatedAt=now,businessKind=TransferBusinessKind.SAVINGS,businessId=value.id)
        s=s.copy(transfers=s.transfers.filterNot { it.id==prior?.id }+listOfNotNull(linked),savingsMovements=s.savingsMovements.filterNot { it.id==value.id }+value.copy(transferId=linked?.id,revision=rev))
        return value.id
    }
    private fun setMovementDeleted(m:SavingsMovement,deleted:Boolean,expected:Long) {
        savingsPlan(m.planId,!deleted);if(m.kind==SavingsMovementKind.WISH) invalid("请通过取消愿望完成处理")
        val rev=revision(m.revision,expected)
        s=s.copy(savingsMovements=s.savingsMovements.map { if(it.id==m.id) it.copy(deleted=deleted,revision=rev) else it })
        m.transferId?.let { id -> val t=transfer(id);putTransfer(t.copy(deleted=deleted,revision=Money.add(t.revision,1),updatedAt=now)) }
    }
    private fun recurringPlan(id:String)=s.recurringPlans.firstOrNull { it.id==id } ?: invalid("周期计划不存在")
    private fun recurringDue(p:RecurringPlan):Boolean = !p.paused && !p.archived && (p.totalOccurrences==null || p.nextIndex<p.totalOccurrences) && recurringDate(p,p.nextIndex)<=env.localNow()
    private fun executeRecurring(p:RecurringPlan,skip:Boolean):String {
        writable(p.ledgerId)
        if(!recurringDue(p)) invalid("该计划没有已到期的待处理期次")
        val date=recurringDate(p,p.nextIndex)
        var entryId:String?=null;var transferId:String?=null
        if(!skip) {
            if(p.kind==ActivityKind.TRANSFER) {
                account(p.fromAccountId,p.ledgerId,date);account(p.toAccountId,p.ledgerId,date)
                val t=AccountTransfer(env.id(),p.ledgerId,p.fromAccountId,p.toAccountId,p.amountMinor,date,p.note,p.recorded,p.reconciled,createdAt=now,updatedAt=now)
                putTransfer(t);transferId=t.id
            } else {
                category(p.categoryId,p.ledgerId,p.kind);account(p.accountId,p.ledgerId,date);dimension(p.projectId,p.ledgerId,DimensionKind.PROJECT);dimension(p.merchantId,p.ledgerId,DimensionKind.MERCHANT)
                val e=Entry(env.id(),p.ledgerId,EntryKind.valueOf(p.kind.name),p.amountMinor,p.categoryId ?: invalid("周期分类不存在"),date,p.note,createdAt=now,updatedAt=now,creationOperationId=env.id())
                s=s.copy(entries=s.entries+e);s=updateEntryAssociations(s,e.id,EntryDetails(e.id,p.accountId,p.recorded,p.excludedFromBudget,p.reconciled,p.projectId,p.merchantId),emptyList());entryId=e.id
            }
        }
        if(p.nextIndex==Int.MAX_VALUE) invalid("周期次数超出范围")
        val receipt=RecurringOccurrence(env.id(),p.id,p.nextIndex,date,if(skip) RecurringOccurrenceStatus.SKIPPED else RecurringOccurrenceStatus.GENERATED,entryId,transferId)
        s=s.copy(recurringOccurrences=s.recurringOccurrences+receipt,recurringPlans=s.recurringPlans.map { if(it.id==p.id) it.copy(nextIndex=p.nextIndex+1,revision=Money.add(p.revision,1)) else it })
        return entryId ?: transferId ?: receipt.id
    }
    private fun allocation(id:String)=s.budgetAllocations.firstOrNull { it.id==id } ?: invalid("分类预算不存在")
    private fun reallocate(from:BudgetAllocation,to:BudgetAllocation,amount:Long,fromRevision:Long,toRevision:Long,reversalOf:String?=null):String {
        writable(from.ledgerId);Money.requireValid(amount)
        val fromRev=revision(from.revision,fromRevision);val toRev=revision(to.revision,toRevision)
        if(from.id==to.id || from.ledgerId!=to.ledgerId || from.yearMonth!=to.yearMonth || from.amountMinor<amount) invalid("预算转移范围或余额无效")
        val history=BudgetReallocation(env.id(),from.ledgerId,from.yearMonth,from.id,to.id,amount,now,reversalOf)
        s=s.copy(budgetAllocations=s.budgetAllocations.map { when(it.id) { from.id -> it.copy(amountMinor=it.amountMinor-amount,revision=fromRev);to.id -> it.copy(amountMinor=Money.add(it.amountMinor,amount),revision=toRev);else -> it } },budgetReallocations=s.budgetReallocations+history)
        return history.id
    }
    fun apply(action:FinanceAction):Mutation {
        var result=emptyList<String>()
        when(action) {
            is FinanceAction.ReorderAccounts -> {
                writable(action.ledgerId)
                val members=s.accounts.filter {it.ledgerId==action.ledgerId && it.type==action.accountType}
                // Only display metadata changes, including for business-owned virtual accounts.
                s=s.copy(accounts=reorderEntities(s.accounts,members,action.orderedIds,action.expectedVersion,s.contentVersion,Account::id) {item,index->item.copy(sortOrder=index,revision=Money.add(item.revision,1))})
                result=action.orderedIds.toList()
            }
            is FinanceAction.ReorderDimensions -> {
                writable(action.ledgerId)
                val members=s.dimensions.filter {it.ledgerId==action.ledgerId && it.kind==action.kind}
                s=s.copy(dimensions=reorderEntities(s.dimensions,members,action.orderedIds,action.expectedVersion,s.contentVersion,Dimension::id) {item,index->item.copy(sortOrder=index,revision=Money.add(item.revision,1))})
                result=action.orderedIds.toList()
            }
            is FinanceAction.SaveAccount -> {
                val a=action.value;writable(a.ledgerId);val old=s.accounts.firstOrNull { it.id==a.id };val rev=revision(old?.revision,action.expectedRevision)
                if(old!=null && (old.ledgerId!=a.ledgerId || old.openingAt!=a.openingAt && hasAccountReferences(a.id))) invalid("有引用的账户不能改变账本或期初日期")
                if(s.obligations.any { it.virtualAccountId==a.id } && old!=null && (old.type!=a.type || old.openingBalanceMinor!=a.openingBalanceMinor || old.openingAt!=a.openingAt || old.includedInAssets!=a.includedInAssets)) invalid("不能修改单据专属账户资金设置")
                if(old==null || old.openingAt!=a.openingAt) occurrence(a.openingAt)
                s=s.copy(accounts=s.accounts.filterNot { it.id==a.id }+a.copy(name=name(a.name),revision=rev));result=listOf(a.id)
            }
            is FinanceAction.DeleteAccount -> { val a=s.accounts.firstOrNull { it.id==action.id } ?: invalid("账户不存在");writable(a.ledgerId);revision(a.revision,action.expectedRevision);if(hasAccountReferences(a.id)) invalid("账户已有引用，请使用归档");s=s.copy(accounts=s.accounts.filterNot { it.id==a.id },preferences=s.preferences.map { if(it.key=="defaultAccountId" && it.value==a.id) it.copy(value="none") else it }) }
            is FinanceAction.SaveDimension -> { val d=action.value;writable(d.ledgerId);val old=s.dimensions.firstOrNull { it.id==d.id };val rev=revision(old?.revision,action.expectedRevision);if(old!=null && (old.ledgerId!=d.ledgerId || old.kind!=d.kind)) invalid("不能修改维度归属或类型");s=s.copy(dimensions=s.dimensions.filterNot { it.id==d.id }+d.copy(name=name(d.name),revision=rev));result=listOf(d.id) }
            is FinanceAction.DeleteDimension -> {
                val d=s.dimensions.firstOrNull { it.id==action.id } ?: invalid("维度不存在");writable(d.ledgerId);revision(d.revision,action.expectedRevision)
                if(s.entryDetails.any { it.projectId==d.id || it.merchantId==d.id } || s.entryTags.any { it.tagId==d.id } || s.recurringPlans.any { it.projectId==d.id || it.merchantId==d.id }) invalid("维度已有引用，请使用归档")
                s=s.copy(dimensions=s.dimensions.filterNot { it.id==d.id })
            }
            is FinanceAction.SaveContact -> { val c=action.value;writable(c.ledgerId);val old=s.contacts.firstOrNull { it.id==c.id };val rev=revision(old?.revision,action.expectedRevision);if(old!=null && old.ledgerId!=c.ledgerId) invalid("不能修改联系人所属账本");s=s.copy(contacts=s.contacts.filterNot { it.id==c.id }+c.copy(name=name(c.name),revision=rev));result=listOf(c.id) }
            is FinanceAction.DeleteContact -> { val c=s.contacts.firstOrNull { it.id==action.id } ?: invalid("联系人不存在");writable(c.ledgerId);revision(c.revision,action.expectedRevision);if(s.obligations.any { it.contactId==c.id }) invalid("联系人已有单据，请使用归档");s=s.copy(contacts=s.contacts.filterNot { it.id==c.id }) }
            is FinanceAction.SetEntryDetails -> {
                val e=s.entries.firstOrNull { it.id==action.entryId } ?: invalid("账单不存在");writable(e.ledgerId);if(!e.active) invalid("账单已删除");val rev=revision(e.revision,action.expectedRevision)
                s=updateEntryAssociations(s,e.id,action.details,action.tagIds,false);s=s.copy(entries=s.entries.map { if(it.id==e.id) it.copy(revision=rev,updatedAt=now) else it });result=listOf(e.id)
            }
            is FinanceAction.SaveTransfer -> {
                val t=action.value;writable(t.ledgerId);plainTransfer(t);val old=s.transfers.firstOrNull { it.id==t.id };old?.let(::plainTransfer);val rev=revision(old?.revision,action.expectedRevision)
                if(old?.deleted==true || t.deleted) invalid("请通过回收站操作删除记录")
                if(old!=null && old.ledgerId!=t.ledgerId) invalid("转账不能改变所属账本")
                occurrence(t.occurrence,old?.occurrence);val oldIds=listOfNotNull(old?.fromAccountId,old?.toAccountId).toSet();account(t.fromAccountId,t.ledgerId,t.occurrence,oldIds);account(t.toAccountId,t.ledgerId,t.occurrence,oldIds)
                putTransfer(t.copy(revision=rev,createdAt=old?.createdAt ?: now,updatedAt=now));result=listOf(t.id)
            }
            is FinanceAction.SetTransferDeleted -> { val t=transfer(action.id);plainTransfer(t);writable(t.ledgerId);val rev=revision(t.revision,action.expectedRevision);putTransfer(t.copy(deleted=action.deleted,revision=rev,updatedAt=now));result=listOf(t.id) }
            is FinanceAction.PurgeTransfer -> {
                val t=transfer(action.id);plainTransfer(t);writable(t.ledgerId);revision(t.revision,action.expectedRevision);if(!t.deleted) invalid("请先移入回收站")
                s=s.copy(transfers=s.transfers.filterNot { it.id==t.id },recurringOccurrences=s.recurringOccurrences.map { if(it.transferId==t.id) it.copy(transferId=null) else it });result=listOf(t.id)
            }
            is FinanceAction.SaveObligation -> {
                val o=action.value;writable(o.ledgerId);val old=s.obligations.firstOrNull { it.id==o.id };val rev=revision(old?.revision,action.expectedRevision)
                if(old!=null && (old.ledgerId!=o.ledgerId || old.kind!=o.kind || old.virtualAccountId!=o.virtualAccountId || old.openingAt!=o.openingAt)) invalid("单据类型、账户与开始日期不能修改")
                if(old!=null && action.initialPrincipal!=null) invalid("请通过单据记录本金")
                o.contactId?.let { id -> val c=s.contacts.firstOrNull { it.id==id } ?: invalid("联系人不存在");if(c.ledgerId!=o.ledgerId || c.archived && old?.contactId!=id) invalid("联系人归属或状态无效") }
                val saved=o.copy(title=name(o.title),revision=rev)
                if(old==null) {
                    if(o.archived) invalid("新单据不能已归档")
                    val initial=action.initialPrincipal ?: invalid("新单据需要首笔本金")
                    if(s.accounts.any { it.id==o.virtualAccountId }) invalid("专属账户标识已存在")
                    occurrence(o.openingAt)
                    val type=if(o.kind==ObligationKind.BORROW) AccountType.PAYABLE else AccountType.RECEIVABLE
                    val a=Account(o.virtualAccountId,o.ledgerId,(if(type==AccountType.PAYABLE) "应付·" else "应收·")+o.id.takeLast(20),type,openingAt=o.openingAt)
                    s=s.copy(accounts=s.accounts+a,obligations=s.obligations+saved)
                    recordObligation(saved,initial.accountId,initial.amountMinor,initial.occurrence,false,initial.recorded,initial.reconciled,initial.note)
                } else s=s.copy(obligations=s.obligations.filterNot { it.id==o.id }+saved)
                result=listOf(o.id)
            }
            is FinanceAction.DeleteObligation -> { val o=obligation(action.id,false);revision(o.revision,action.expectedRevision);if(s.transfers.any { it.businessId==o.id || it.fromAccountId==o.virtualAccountId || it.toAccountId==o.virtualAccountId }) invalid("单据已有资金记录，请使用归档");s=s.copy(obligations=s.obligations.filterNot { it.id==o.id },accounts=s.accounts.filterNot { it.id==o.virtualAccountId }) }
            is FinanceAction.RecordObligation -> { val o=obligation(action.obligationId);result=listOf(recordObligation(o,action.accountId,action.amountMinor,action.occurrence,action.settlement,action.recorded,action.reconciled,action.note,action.transferId,action.expectedRevision)) }
            is FinanceAction.SetObligationTransferDeleted -> { val t=transfer(action.id);if(t.businessKind !in setOf(TransferBusinessKind.OBLIGATION_PRINCIPAL,TransferBusinessKind.OBLIGATION_SETTLEMENT)) invalid("不是单据资金记录");obligation(t.businessId ?: invalid("单据不存在"),!action.deleted);val rev=revision(t.revision,action.expectedRevision);putTransfer(t.copy(deleted=action.deleted,revision=rev,updatedAt=now));result=listOf(t.id) }
            is FinanceAction.SaveRecurringPlan -> {
                val p=action.value;writable(p.ledgerId);val old=s.recurringPlans.firstOrNull { it.id==p.id };val rev=revision(old?.revision,action.expectedRevision)
                if(old!=null && (old.ledgerId!=p.ledgerId || old.nextIndex!=p.nextIndex || old.nextIndex>0 && (old.anchor!=p.anchor || old.frequency!=p.frequency))) invalid("已处理周期的账本、锚点、频率与次数索引不可修改")
                if(old==null && p.nextIndex!=0) invalid("新周期索引必须从0开始")
                if(p.kind!=ActivityKind.TRANSFER) category(p.categoryId,p.ledgerId,p.kind,old?.categoryId)
                val nextDate=recurringNextOccurrence(p) ?: "9999-12-31T23:59:59"
                val oldAccounts=listOfNotNull(old?.accountId,old?.fromAccountId,old?.toAccountId).toSet();account(p.accountId,p.ledgerId,nextDate,oldAccounts);account(p.fromAccountId,p.ledgerId,nextDate,oldAccounts);account(p.toAccountId,p.ledgerId,nextDate,oldAccounts)
                dimension(p.projectId,p.ledgerId,DimensionKind.PROJECT,old?.projectId);dimension(p.merchantId,p.ledgerId,DimensionKind.MERCHANT,old?.merchantId)
                val saved=p.copy(name=name(p.name),revision=rev);s=s.copy(recurringPlans=s.recurringPlans.filterNot { it.id==p.id }+saved)
                validateFinanceState(s,env::normalize)
                if(old==null && recurringDue(saved)) executeRecurring(saved,false)
                result=listOf(p.id)
            }
            is FinanceAction.DeleteRecurringPlan -> { val p=recurringPlan(action.id);writable(p.ledgerId);revision(p.revision,action.expectedRevision);if(p.nextIndex>0 || s.recurringOccurrences.any { it.planId==p.id }) invalid("周期已有历史，请使用归档");s=s.copy(recurringPlans=s.recurringPlans.filterNot { it.id==p.id }) }
            is FinanceAction.ExecuteRecurring -> { val p=recurringPlan(action.planId);revision(p.revision,action.expectedRevision);result=listOf(executeRecurring(p,action.skip)) }
            is FinanceAction.ProcessRecurring -> {
                if(action.limit !in 1..100) invalid("每批处理1到100期")
                val ids=mutableListOf<String>()
                repeat(action.limit) { val p=s.recurringPlans.filter(::recurringDue).minWithOrNull(compareBy<RecurringPlan> { recurringDate(it,it.nextIndex) }.thenBy { it.id });if(p!=null) ids+=executeRecurring(p,false) }
                result=ids
            }
            is FinanceAction.SaveSavingsPlan -> { val p=action.value;writable(p.ledgerId);val old=s.savingsPlans.firstOrNull { it.id==p.id };val rev=revision(old?.revision,action.expectedRevision);if(old!=null && old.ledgerId!=p.ledgerId) invalid("不能修改存钱计划账本");s=s.copy(savingsPlans=s.savingsPlans.filterNot { it.id==p.id }+p.copy(name=name(p.name),revision=rev));result=listOf(p.id) }
            is FinanceAction.DeleteSavingsPlan -> { val p=savingsPlan(action.id,false);revision(p.revision,action.expectedRevision);if(s.savingsMovements.any { it.planId==p.id } || s.savingsWishes.any { it.planId==p.id }) invalid("存钱计划已有记录，请使用归档");s=s.copy(savingsPlans=s.savingsPlans.filterNot { it.id==p.id }) }
            is FinanceAction.SaveSavingsMovement -> result=listOf(saveMovement(action.value,action.endpoints,action.expectedRevision))
            is FinanceAction.SetSavingsMovementDeleted -> { setMovementDeleted(savingsMovement(action.id),action.deleted,action.expectedRevision);result=listOf(action.id) }
            is FinanceAction.SaveSavingsWish -> {
                val w=action.value;savingsPlan(w.planId);val old=s.savingsWishes.firstOrNull { it.id==w.id };val rev=revision(old?.revision,action.expectedRevision)
                if(old?.fulfilledMovementId!=null) invalid("请先取消愿望完成再编辑")
                if(w.fulfilledMovementId!=null || old!=null && old.planId!=w.planId) invalid("愿望完成引用与计划不可修改")
                s=s.copy(savingsWishes=s.savingsWishes.filterNot { it.id==w.id }+w.copy(name=name(w.name),revision=rev));result=listOf(w.id)
            }
            is FinanceAction.DeleteSavingsWish -> { val w=wish(action.id);savingsPlan(w.planId,false);revision(w.revision,action.expectedRevision);if(w.fulfilledMovementId!=null) invalid("请先取消愿望完成");s=s.copy(savingsWishes=s.savingsWishes.filterNot { it.id==w.id }) }
            is FinanceAction.FulfillSavingsWish -> {
                val w=wish(action.id);savingsPlan(w.planId);val rev=revision(w.revision,action.expectedRevision);if(w.fulfilledMovementId!=null) invalid("愿望已经完成")
                val m=SavingsMovement(env.id(),w.planId,SavingsMovementKind.WISH,w.amountMinor,action.occurrence,w.name)
                saveMovement(m,action.endpoints,null,true);s=s.copy(savingsWishes=s.savingsWishes.map { if(it.id==w.id) it.copy(fulfilledMovementId=m.id,revision=rev) else it });result=listOf(w.id,m.id)
            }
            is FinanceAction.CancelSavingsWish -> {
                val w=wish(action.id);savingsPlan(w.planId,false);val rev=revision(w.revision,action.expectedRevision);val m=savingsMovement(w.fulfilledMovementId ?: invalid("愿望尚未完成"))
                s=s.copy(savingsWishes=s.savingsWishes.map { if(it.id==w.id) it.copy(fulfilledMovementId=null,revision=rev) else it },savingsMovements=s.savingsMovements.filterNot { it.id==m.id },transfers=s.transfers.filterNot { it.id==m.transferId });result=listOf(w.id)
            }
            is FinanceAction.SaveBudgetAllocation -> { val a=action.value;writable(a.ledgerId);val old=s.budgetAllocations.firstOrNull { it.id==a.id };val rev=revision(old?.revision,action.expectedRevision);if(old!=null && (old.ledgerId!=a.ledgerId || old.yearMonth!=a.yearMonth || old.categoryId!=a.categoryId)) invalid("分类预算归属不可修改");category(a.categoryId,a.ledgerId,ActivityKind.EXPENSE,old?.categoryId);s=s.copy(budgetAllocations=s.budgetAllocations.filterNot { it.id==a.id }+a.copy(revision=rev));result=listOf(a.id) }
            is FinanceAction.DeleteBudgetAllocation -> { val a=allocation(action.id);writable(a.ledgerId);revision(a.revision,action.expectedRevision);if(s.budgetReallocations.any { it.fromAllocationId==a.id || it.toAllocationId==a.id }) invalid("该分类预算有转移历史，请删除整月预算");s=s.copy(budgetAllocations=s.budgetAllocations.filterNot { it.id==a.id }) }
            is FinanceAction.ReallocateBudget -> result=listOf(reallocate(allocation(action.fromAllocationId),allocation(action.toAllocationId),action.amountMinor,action.fromRevision,action.toRevision))
            is FinanceAction.ReverseBudgetReallocation -> { val r=s.budgetReallocations.firstOrNull { it.id==action.id } ?: invalid("预算转移不存在");if(r.reversalOfId!=null || s.budgetReallocations.any { it.reversalOfId==r.id }) invalid("该预算转移已经撤回");result=listOf(reallocate(allocation(r.toAllocationId),allocation(r.fromAllocationId),r.amountMinor,action.fromRevision,action.toRevision,r.id)) }
        }
        return Mutation(s,result)
    }
}
