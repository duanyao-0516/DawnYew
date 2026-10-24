package com.dawnyew.core

internal fun validateFinanceEntityBatch(b:LedgerEntityBatch,normalize:(String)->String) {
    fun ensure(ok:Boolean,message:String) { if(!ok) invalid(message) }
    fun ids(vararg values:String?)=values.filterNotNull().forEach(::validateId)
    fun name(s:String,max:Int=60) { ensure(s==normalize(s.trim()) && codePointCount(s) in 1..max && s.none { it.code<32 },"名称格式无效") }
    fun note(s:String) { ensure(codePointCount(s)<=500 && !s.contains('\u0000'),"备注最多500个字符") }
    fun revision(r:Long) { ensure(r>0,"版本无效") }
    b.accounts.forEach { a -> ids(a.id,a.ledgerId);name(a.name,30);note(a.note);revision(a.revision);validateOccurrence(a.openingAt);ensure(a.openingBalanceMinor in -Money.MAX..Money.MAX,"初始余额超出范围") }
    b.entryDetails.forEach { d -> ids(d.entryId,d.accountId,d.projectId,d.merchantId) }
    b.entryTags.forEach { ids(it.entryId,it.tagId) }
    b.transfers.forEach { t -> ids(t.id,t.ledgerId,t.fromAccountId,t.toAccountId,t.businessId);Money.requireValid(t.amountMinor);validateOccurrence(t.occurrence);validateUtc(t.createdAt);validateUtc(t.updatedAt);note(t.note);revision(t.revision);ensure(t.fromAccountId!=null || t.toAccountId!=null,"转账至少需要一个账户");ensure(t.fromAccountId==null || t.fromAccountId!=t.toAccountId,"转账两端不能相同") }
    b.dimensions.forEach { ids(it.id,it.ledgerId);name(it.name,20);revision(it.revision) }
    b.contacts.forEach { ids(it.id,it.ledgerId);name(it.name,30);note(it.note);revision(it.revision) }
    b.obligations.forEach { o -> ids(o.id,o.ledgerId,o.virtualAccountId,o.contactId);name(o.title);note(o.note);revision(o.revision);validateOccurrence(o.openingAt);o.dueDate?.let { validateOccurrence(it+"T00:00:00");ensure(it>=o.openingAt.substringBefore('T'),"到期日不能早于单据开始日") } }
    b.recurringPlans.forEach { p ->
        ids(p.id,p.ledgerId,p.categoryId,p.accountId,p.fromAccountId,p.toAccountId,p.projectId,p.merchantId);name(p.name);note(p.note);revision(p.revision);Money.requireValid(p.amountMinor);validateOccurrence(p.anchor)
        ensure(p.nextIndex>=0 && (p.totalOccurrences==null || p.totalOccurrences>0 && p.totalOccurrences>=p.nextIndex),"周期次数无效")
        ensure(p.kind!=ActivityKind.REFUND,"周期不支持退款")
        if(p.kind==ActivityKind.TRANSFER) ensure(p.categoryId==null && p.accountId==null && p.fromAccountId!=null && p.toAccountId!=null && p.fromAccountId!=p.toAccountId && p.projectId==null && p.merchantId==null && !p.excludedFromBudget,"周期转账模板无效")
        else ensure(p.categoryId!=null && p.fromAccountId==null && p.toAccountId==null,"周期账单模板无效")
    }
    b.recurringOccurrences.forEach { o -> ids(o.id,o.planId,o.entryId,o.transferId);validateOccurrence(o.scheduledAt);ensure(o.index>=0 && (o.entryId==null || o.transferId==null),"周期凭证无效");if(o.status==RecurringOccurrenceStatus.SKIPPED) ensure(o.entryId==null && o.transferId==null,"跳过凭证不能有交易") }
    b.savingsPlans.forEach { p -> ids(p.id,p.ledgerId);name(p.name);note(p.note);revision(p.revision);p.targetMinor?.let(Money::requireValid);ensure(p.scenario==SavingsScenario.FLEXIBLE || p.targetMinor!=null,"该存钱场景需要目标金额") }
    b.savingsMovements.forEach { m -> ids(m.id,m.planId,m.transferId);Money.requireValid(m.amountMinor);validateOccurrence(m.occurrence);note(m.note);revision(m.revision) }
    b.savingsWishes.forEach { w -> ids(w.id,w.planId,w.fulfilledMovementId);name(w.name);Money.requireValid(w.amountMinor);revision(w.revision) }
    b.budgetAllocations.forEach { a -> ids(a.id,a.ledgerId,a.categoryId);validateOccurrence(a.yearMonth+"-01T00:00:00");ensure(a.amountMinor in 0..Money.MAX,"分类预算金额无效");revision(a.revision) }
    b.budgetReallocations.forEach { r -> ids(r.id,r.ledgerId,r.fromAllocationId,r.toAllocationId,r.reversalOfId);Money.requireValid(r.amountMinor);validateOccurrence(r.yearMonth+"-01T00:00:00");validateUtc(r.createdAt);ensure(r.fromAllocationId!=r.toAllocationId,"预算转移分类不能相同") }
}

internal fun validateFinanceState(s:LedgerState,normalize:(String)->String) {
    validateFinanceEntityBatch(LedgerEntityBatch(accounts=s.accounts,entryDetails=s.entryDetails,entryTags=s.entryTags,transfers=s.transfers,dimensions=s.dimensions,contacts=s.contacts,obligations=s.obligations,recurringPlans=s.recurringPlans,recurringOccurrences=s.recurringOccurrences,savingsPlans=s.savingsPlans,savingsMovements=s.savingsMovements,savingsWishes=s.savingsWishes,budgetAllocations=s.budgetAllocations,budgetReallocations=s.budgetReallocations),normalize)
    fun ensure(ok:Boolean,message:String) { if(!ok) invalid(message) }
    fun unique(keys:List<String>,name:String) { ensure(keys.distinct().size==keys.size,"$name 重复") }
    unique(s.accounts.map { it.id },"账户");unique(s.accounts.map { "${it.ledgerId}/${it.name}" },"账户名称")
    unique(s.entryDetails.map { it.entryId },"账单扩展");unique(s.entryTags.map { "${it.entryId}/${it.tagId}" },"账单标签")
    unique(s.transfers.map { it.id },"转账");unique(s.dimensions.map { it.id },"维度");unique(s.dimensions.map { "${it.ledgerId}/${it.kind}/${it.name}" },"维度名称")
    unique(s.contacts.map { it.id },"联系人");unique(s.contacts.map { "${it.ledgerId}/${it.name}" },"联系人名称")
    unique(s.obligations.map { it.id },"单据");unique(s.obligations.map { it.virtualAccountId },"单据专属账户")
    unique(s.recurringPlans.map { it.id },"周期计划");unique(s.recurringOccurrences.map { it.id },"周期凭证");unique(s.recurringOccurrences.map { "${it.planId}/${it.index}" },"周期期次")
    unique(s.recurringOccurrences.mapNotNull { it.entryId },"周期账单结果");unique(s.recurringOccurrences.mapNotNull { it.transferId },"周期转账结果")
    unique(s.savingsPlans.map { it.id },"存钱计划");unique(s.savingsMovements.map { it.id },"存钱记录");unique(s.savingsMovements.mapNotNull { it.transferId },"存钱转账")
    unique(s.savingsWishes.map { it.id },"存钱愿望");unique(s.savingsWishes.mapNotNull { it.fulfilledMovementId },"愿望完成事实")
    unique(s.budgetAllocations.map { it.id },"分类预算");unique(s.budgetAllocations.map { "${it.ledgerId}/${it.yearMonth}/${it.categoryId}" },"分类预算额度")
    unique(s.budgetReallocations.map { it.id },"预算转移");unique(s.budgetReallocations.mapNotNull { it.reversalOfId },"预算转移撤回")
    val ledgers=s.ledgers.associateBy { it.id };val accounts=s.accounts.associateBy { it.id };val entries=s.entries.associateBy { it.id };val dimensions=s.dimensions.associateBy { it.id }
    val contacts=s.contacts.associateBy { it.id };val obligations=s.obligations.associateBy { it.id };val virtualOwners=s.obligations.associateBy { it.virtualAccountId }
    val transfers=s.transfers.associateBy { it.id };val savings=s.savingsPlans.associateBy { it.id };val movements=s.savingsMovements.associateBy { it.id };val recurring=s.recurringPlans.associateBy { it.id }
    fun ledger(id:String) { ensure(id in ledgers,"账本引用不存在") }
    fun account(id:String?,ledgerId:String,occurrence:String,allowVirtual:Boolean=false) {
        if(id==null) return
        val a=accounts[id] ?: invalid("资金账户引用不存在")
        ensure(a.ledgerId==ledgerId && occurrence>=a.openingAt,"账户账本或期初日期不一致")
        ensure(allowVirtual || id !in virtualOwners,"不能直接使用单据专属账户")
    }
    fun dimension(id:String?,ledgerId:String,kind:DimensionKind) { if(id!=null) { val d=dimensions[id] ?: invalid("维度引用不存在");ensure(d.ledgerId==ledgerId && d.kind==kind,"维度归属或类型不一致") } }
    s.accounts.forEach { ledger(it.ledgerId) };s.dimensions.forEach { ledger(it.ledgerId) };s.contacts.forEach { ledger(it.ledgerId) }
    s.entryDetails.forEach { d -> val e=entries[d.entryId] ?: invalid("扩展账单不存在");account(d.accountId,e.ledgerId,e.occurrence);dimension(d.projectId,e.ledgerId,DimensionKind.PROJECT);dimension(d.merchantId,e.ledgerId,DimensionKind.MERCHANT);ensure(e.kind!=EntryKind.REFUND || !d.excludedFromBudget,"退款预算资格由原支出决定") }
    s.entryTags.forEach { t -> val e=entries[t.entryId] ?: invalid("标签账单不存在");dimension(t.tagId,e.ledgerId,DimensionKind.TAG) }
    s.obligations.forEach { o ->
        ledger(o.ledgerId);o.contactId?.let { ensure(contacts[it]?.ledgerId==o.ledgerId,"单据联系人归属无效") }
        val a=accounts[o.virtualAccountId] ?: invalid("单据专属账户不存在")
        ensure(a.ledgerId==o.ledgerId && a.openingBalanceMinor==0L && a.openingAt==o.openingAt && a.includedInAssets && a.type==if(o.kind==ObligationKind.BORROW) AccountType.PAYABLE else AccountType.RECEIVABLE,"单据专属账户字段无效")
    }
    s.transfers.forEach { t ->
        ledger(t.ledgerId);account(t.fromAccountId,t.ledgerId,t.occurrence,true);account(t.toAccountId,t.ledgerId,t.occurrence,true)
        when(t.businessKind) {
            TransferBusinessKind.MANUAL -> { ensure(t.fromAccountId!=null && t.toAccountId!=null && t.businessId==null,"普通转账需要完整双端");ensure(t.fromAccountId !in virtualOwners && t.toAccountId !in virtualOwners,"普通转账不能使用单据账户") }
            TransferBusinessKind.SAVINGS -> {
                val m=movements[t.businessId] ?: invalid("存钱转账来源不存在");val p=savings[m.planId] ?: invalid("存钱计划不存在")
                ensure(m.transferId==t.id && p.ledgerId==t.ledgerId && m.amountMinor==t.amountMinor && m.occurrence==t.occurrence && m.deleted==t.deleted,"存钱转账与进度不一致")
                ensure(t.fromAccountId !in virtualOwners && t.toAccountId !in virtualOwners,"存钱不能使用单据账户")
            }
            TransferBusinessKind.OBLIGATION_PRINCIPAL,TransferBusinessKind.OBLIGATION_SETTLEMENT -> {
                val o=obligations[t.businessId] ?: invalid("借还转账来源不存在")
                val virtualFrom=(o.kind==ObligationKind.BORROW)==(t.businessKind==TransferBusinessKind.OBLIGATION_PRINCIPAL)
                ensure(t.ledgerId==o.ledgerId && t.fromAccountId!=null && t.toAccountId!=null && (if(virtualFrom) t.fromAccountId else t.toAccountId)==o.virtualAccountId,"借还资金方向无效")
                ensure((if(virtualFrom) t.toAccountId else t.fromAccountId) !in virtualOwners,"借还对方不能是专属账户")
            }
        }
    }
    s.recurringPlans.forEach { p ->
        ledger(p.ledgerId)
        val nextDate=recurringNextOccurrence(p) ?: "9999-12-31T23:59:59"
        if(p.kind==ActivityKind.TRANSFER) { account(p.fromAccountId,p.ledgerId,nextDate);account(p.toAccountId,p.ledgerId,nextDate) }
        else { val c=s.categories.firstOrNull { it.id==p.categoryId } ?: invalid("周期分类不存在");ensure(c.ledgerId==p.ledgerId && c.direction==if(p.kind==ActivityKind.INCOME) Direction.INCOME else Direction.EXPENSE,"周期分类不一致");account(p.accountId,p.ledgerId,nextDate);dimension(p.projectId,p.ledgerId,DimensionKind.PROJECT);dimension(p.merchantId,p.ledgerId,DimensionKind.MERCHANT) }
    }
    val occurrences=s.recurringOccurrences.groupBy { it.planId }
    s.recurringPlans.forEach { p -> val list=occurrences[p.id].orEmpty();ensure(list.size==p.nextIndex && list.map { it.index }.sorted()==(0 until p.nextIndex).toList(),"周期凭证与已处理次数不一致") }
    s.recurringOccurrences.forEach { o ->
        val p=recurring[o.planId] ?: invalid("周期凭证计划不存在");ensure(o.index<p.nextIndex && o.scheduledAt==recurringDate(p,o.index),"周期凭证时间或索引不一致")
        o.entryId?.let { ensure(entries[it]?.ledgerId==p.ledgerId,"周期账单结果不存在或账本不一致") }
        o.transferId?.let { ensure(transfers[it]?.ledgerId==p.ledgerId,"周期转账结果不存在或账本不一致") }
    }
    s.savingsPlans.forEach { ledger(it.ledgerId) }
    val wishByMovement=s.savingsWishes.filter { it.fulfilledMovementId!=null }.associateBy { it.fulfilledMovementId }
    s.savingsMovements.forEach { m ->
        ensure(m.planId in savings,"存钱记录计划不存在")
        m.transferId?.let { ensure(transfers[it]?.businessKind==TransferBusinessKind.SAVINGS && transfers[it]?.businessId==m.id,"存钱资金引用不一致") }
        if(m.kind==SavingsMovementKind.WISH) ensure(m.active && wishByMovement[m.id]?.planId==m.planId,"愿望支出必须由唯一完成愿望引用")
        else ensure(m.id !in wishByMovement,"普通进度不能充当愿望完成")
    }
    s.savingsWishes.forEach { w ->
        ensure(w.planId in savings,"愿望计划不存在")
        w.fulfilledMovementId?.let { val m=movements[it] ?: invalid("愿望完成记录不存在");ensure(m.planId==w.planId && m.active && m.kind==SavingsMovementKind.WISH && m.amountMinor==w.amountMinor,"愿望完成与进度不一致") }
    }
    val allocations=s.budgetAllocations.associateBy { it.id };val budgets=s.budgets.associateBy { "${it.ledgerId}/${it.yearMonth}" }
    s.budgetAllocations.forEach { a ->
        val c=s.categories.firstOrNull { it.id==a.categoryId } ?: invalid("预算分类不存在")
        ensure(c.ledgerId==a.ledgerId && c.direction==Direction.EXPENSE && "${a.ledgerId}/${a.yearMonth}" in budgets,"分类预算父项无效")
    }
    s.budgetAllocations.groupBy { "${it.ledgerId}/${it.yearMonth}" }.forEach { (key,list) -> ensure(list.fold(0L) { sum,a -> Money.add(sum,a.amountMinor) }<=budgets.getValue(key).amountMinor,"分类预算合计超过总预算") }
    val reallocations=s.budgetReallocations.associateBy { it.id }
    s.budgetReallocations.forEach { r ->
        val from=allocations[r.fromAllocationId] ?: invalid("预算转移来源不存在");val to=allocations[r.toAllocationId] ?: invalid("预算转移目标不存在")
        ensure(from.ledgerId==r.ledgerId && to.ledgerId==r.ledgerId && from.yearMonth==r.yearMonth && to.yearMonth==r.yearMonth,"预算转移必须同账本同月份")
        r.reversalOfId?.let { id -> val old=reallocations[id] ?: invalid("预算撤回来源不存在");ensure(old.id!=r.id && old.fromAllocationId==r.toAllocationId && old.toAllocationId==r.fromAllocationId && old.amountMinor==r.amountMinor && old.reversalOfId==null,"预算撤回关系不一致") }
    }
    val finance=FinanceQueries.calculate(s)
    ensure(finance.obligations.all { it.remainingMinor>=0 },"结算金额超过已入账本金")
    ensure(finance.savings.all { it.savedMinor>=0 },"存钱余额不能为负")
}
