package com.dawnyew.core

import kotlinx.datetime.*

/** Identity is family + id; ordering is occurrence DESC, family.name DESC, id DESC. */
enum class ActivityFamily { ENTRY, TRANSFER }
data class ActivityScope(val ledgerIds:Set<String> = emptySet(), val startDate:String="0001-01-01", val endDate:String="9999-12-31", val kinds:Set<ActivityKind> = emptySet(), val categoryIds:Set<String> = emptySet(), val minAmountMinor:Long?=null, val maxAmountMinor:Long?=null, val accountIds:Set<String> = emptySet(), val projectIds:Set<String> = emptySet(), val merchantIds:Set<String> = emptySet(), val tagIds:Set<String> = emptySet(), val keyword:String="", val recorded:Boolean?=null, val excludedFromBudget:Boolean?=null, val reconciled:Boolean?=null, val includeArchived:Boolean=true, val deleted:Boolean=false, val weekdays:Set<Int> = emptySet(), val accountUnassigned:Boolean=false, val projectUnassigned:Boolean=false, val merchantUnassigned:Boolean=false, val tagUnassigned:Boolean=false)
data class FinancialActivity(val family:ActivityFamily, val id:String, val ledgerId:String, val kind:ActivityKind, val amountMinor:Long, val occurrence:String, val note:String, val recorded:Boolean, val reconciled:Boolean, val excludedFromBudget:Boolean, val categoryId:String?=null, val accountId:String?=null, val fromAccountId:String?=null, val toAccountId:String?=null, val projectId:String?=null, val merchantId:String?=null, val tagIds:Set<String> = emptySet(), val businessKind:TransferBusinessKind?=null, val businessId:String?=null, val deleted:Boolean=false, val revision:Long=1) { val identity get()="${family.name}:$id"; val localDate get()=occurrence.substringBefore('T') }
data class ActivityCursor(val occurrence:String,val family:ActivityFamily,val id:String,val datasetId:String,val contentVersion:Long,val scope:ActivityScope)
data class ActivityPage(val activities:List<FinancialActivity>,val nextCursor:ActivityCursor?,val datasetId:String,val contentVersion:Long)
data class AccountBalance(val account:Account,val balanceMinor:Long)
data class ObligationBalance(val obligation:Obligation,val principalMinor:Long,val settledMinor:Long,val remainingMinor:Long,val pendingPrincipalMinor:Long,val pendingSettlementMinor:Long) { fun isOverdue(localDate:String)=remainingMinor>0 && obligation.dueDate?.let { it<localDate }==true }
data class SavingsProgress(val plan:SavingsPlan,val savedMinor:Long)
data class FinanceSnapshot(val accounts:List<AccountBalance>,val obligations:List<ObligationBalance>,val savings:List<SavingsProgress>,val assetsMinor:Long,val liabilitiesMinor:Long,val netWorthMinor:Long)
fun QueryScope.toActivityScope():ActivityScope = ActivityScope(ledgerIds,startDate,endDate,kinds.map { ActivityKind.valueOf(it.name) }.toSet(),categoryIds,keyword=keyword,includeArchived=includeArchived)

data class ActivityDaySummary(val date:String,val count:Long,val income:Long,val expense:Long,val refunds:Long,val transferAmount:Long)
data class ActivitySummary(val income:Long,val expense:Long,val refunds:Long,val transferAmount:Long,val activityCount:Long,val days:List<ActivityDaySummary>)
fun effectiveBudgetExcluded(state:LedgerState,entry:Entry):Boolean {
    val owner=if(entry.kind==EntryKind.REFUND) entry.parentExpenseId else entry.id
    return state.entryDetails.firstOrNull { it.entryId==owner }?.excludedFromBudget ?: false
}
fun validateActivityScope(scope:ActivityScope) {
    validateQueryScope(QueryScope(scope.ledgerIds,scope.startDate,scope.endDate,keyword=scope.keyword))
    (scope.ledgerIds+scope.categoryIds+scope.accountIds+scope.projectIds+scope.merchantIds+scope.tagIds).forEach(::validateId)
    if(scope.weekdays.any { it !in 1..7 }) invalid("星期筛选无效")
    if(scope.accountUnassigned && scope.accountIds.isNotEmpty() || scope.projectUnassigned && scope.projectIds.isNotEmpty() || scope.merchantUnassigned && scope.merchantIds.isNotEmpty() || scope.tagUnassigned && scope.tagIds.isNotEmpty()) invalid("未分配与指定维度不能同时筛选")
    scope.minAmountMinor?.let { if(it<0 || it>Money.MAX) invalid("金额下界无效") }
    scope.maxAmountMinor?.let { if(it<0 || it>Money.MAX) invalid("金额上界无效") }
    if(scope.minAmountMinor!=null && scope.maxAmountMinor!=null && scope.minAmountMinor>scope.maxAmountMinor) invalid("金额范围无效")
}
fun validateActivityCursor(scope:ActivityScope,cursor:ActivityCursor?,datasetId:String,contentVersion:Long,limit:Int) {
    validateActivityScope(scope)
    if(limit !in 1..200) invalid("分页数量必须在1到200之间")
    cursor?.let {
        validateId(it.id);validateOccurrence(it.occurrence)
        if(it.datasetId!=datasetId || it.contentVersion!=contentVersion || it.scope!=scope) conflict("分页数据或筛选已变化，请重新加载")
    }
}
internal fun activitySnapshot(state:LedgerState,scope:ActivityScope):List<FinancialActivity> {
    validateActivityScope(scope)
    val details=state.entryDetails.associateBy { it.entryId }
    val tags=state.entryTags.groupBy { it.entryId }
    val ledgers=state.ledgers.filter { scope.includeArchived || !it.archived }.map { it.id }.toSet()
    val all=state.entries.map { e ->
        val d=details[e.id] ?: EntryDetails(e.id)
        val excluded=details[if(e.kind==EntryKind.REFUND) e.parentExpenseId else e.id]?.excludedFromBudget ?: false
        FinancialActivity(ActivityFamily.ENTRY,e.id,e.ledgerId,ActivityKind.valueOf(e.kind.name),e.amountMinor,e.occurrence,e.note,d.recorded,d.reconciled,excluded,e.categoryId,d.accountId,projectId=d.projectId,merchantId=d.merchantId,tagIds=tags[e.id].orEmpty().map { it.tagId }.toSet(),deleted=!e.active,revision=e.revision)
    }+state.transfers.map { t -> FinancialActivity(ActivityFamily.TRANSFER,t.id,t.ledgerId,ActivityKind.TRANSFER,t.amountMinor,t.occurrence,t.note,t.recorded,t.reconciled,false,fromAccountId=t.fromAccountId,toAccountId=t.toAccountId,businessKind=t.businessKind,businessId=t.businessId,deleted=t.deleted,revision=t.revision) }
    val keyword=literalSearchKey(scope.keyword)
    return all.filter { a ->
        a.deleted==scope.deleted && a.ledgerId in ledgers && (scope.ledgerIds.isEmpty() || a.ledgerId in scope.ledgerIds) && a.localDate>=scope.startDate && a.localDate<=scope.endDate &&
        (scope.kinds.isEmpty() || a.kind in scope.kinds) && (scope.categoryIds.isEmpty() || a.categoryId in scope.categoryIds) &&
        (scope.minAmountMinor==null || a.amountMinor>=scope.minAmountMinor) && (scope.maxAmountMinor==null || a.amountMinor<=scope.maxAmountMinor) &&
        (scope.accountIds.isEmpty() || listOfNotNull(a.accountId,a.fromAccountId,a.toAccountId).any { it in scope.accountIds }) &&
        (scope.projectIds.isEmpty() || a.projectId in scope.projectIds) && (scope.merchantIds.isEmpty() || a.merchantId in scope.merchantIds) &&
        (scope.tagIds.isEmpty() || a.tagIds.any { it in scope.tagIds }) &&
        (scope.weekdays.isEmpty() || LocalDate.parse(a.localDate).dayOfWeek.isoDayNumber in scope.weekdays) &&
        (!scope.accountUnassigned || a.family==ActivityFamily.ENTRY && a.accountId==null) &&
        (!scope.projectUnassigned || a.family==ActivityFamily.ENTRY && a.projectId==null) &&
        (!scope.merchantUnassigned || a.family==ActivityFamily.ENTRY && a.merchantId==null) &&
        (!scope.tagUnassigned || a.family==ActivityFamily.ENTRY && a.tagIds.isEmpty()) &&
        (keyword.isEmpty() || literalSearchKey(a.note).contains(keyword)) &&
        (scope.recorded==null || a.recorded==scope.recorded) && (scope.reconciled==null || a.reconciled==scope.reconciled) &&
        (scope.excludedFromBudget==null || a.excludedFromBudget==scope.excludedFromBudget)
    }.sortedWith(compareByDescending<FinancialActivity> { it.occurrence }.thenByDescending { it.family.name }.thenByDescending { it.id })
}
fun activityPageSnapshot(state:LedgerState,scope:ActivityScope,cursor:ActivityCursor?=null,limit:Int=50):ActivityPage {
    validateActivityCursor(scope,cursor,state.datasetId,state.contentVersion,limit)
    val filtered=activitySnapshot(state,scope).filter { a -> cursor==null || a.occurrence<cursor.occurrence || a.occurrence==cursor.occurrence && (a.family.name<cursor.family.name || a.family==cursor.family && a.id<cursor.id) }
    val page=filtered.take(limit)
    val next=page.lastOrNull()?.takeIf { filtered.size>limit }?.let { ActivityCursor(it.occurrence,it.family,it.id,state.datasetId,state.contentVersion,scope.copy(ledgerIds=scope.ledgerIds.toSet(),kinds=scope.kinds.toSet(),categoryIds=scope.categoryIds.toSet(),accountIds=scope.accountIds.toSet(),projectIds=scope.projectIds.toSet(),merchantIds=scope.merchantIds.toSet(),tagIds=scope.tagIds.toSet(),weekdays=scope.weekdays.toSet())) }
    return ActivityPage(page,next,state.datasetId,state.contentVersion)
}
fun activitySummarySnapshot(state:LedgerState,scope:ActivityScope):ActivitySummary {
    val activities=activitySnapshot(state,scope)
    fun sum(items:List<FinancialActivity>,kind:ActivityKind)=items.filter { it.kind==kind }.fold(0L) { total,a -> Money.add(total,a.amountMinor) }
    return ActivitySummary(sum(activities,ActivityKind.INCOME),sum(activities,ActivityKind.EXPENSE),sum(activities,ActivityKind.REFUND),sum(activities,ActivityKind.TRANSFER),activities.size.toLong(),activities.groupBy { it.localDate }.entries.sortedBy { it.key }.map { (date,items) -> ActivityDaySummary(date,items.size.toLong(),sum(items,ActivityKind.INCOME),sum(items,ActivityKind.EXPENSE),sum(items,ActivityKind.REFUND),sum(items,ActivityKind.TRANSFER)) })
}
/** Null means the finite plan is exhausted; pause/archive do not alter its calendar identity. */
fun recurringNextOccurrence(plan:RecurringPlan):String? = if(plan.totalOccurrences!=null && plan.nextIndex>=plan.totalOccurrences) null else recurringDate(plan,plan.nextIndex)
fun recurringDate(plan:RecurringPlan,index:Int):String {
    validateOccurrence(plan.anchor)
    if(index<0 || index>3_652_058) invalid("周期索引超出日历范围")
    val anchor=LocalDate.parse(plan.anchor.substringBefore('T'))
    val date=try { when(plan.frequency) {
        RecurringFrequency.DAILY -> anchor.plus(index,DateTimeUnit.DAY)
        RecurringFrequency.WEEKLY -> anchor.plus(index*7,DateTimeUnit.DAY)
        RecurringFrequency.BIWEEKLY -> anchor.plus(index*14,DateTimeUnit.DAY)
        RecurringFrequency.MONTHLY -> anchor.plus(index,DateTimeUnit.MONTH)
        RecurringFrequency.QUARTERLY -> anchor.plus(index*3,DateTimeUnit.MONTH)
        RecurringFrequency.YEARLY -> anchor.plus(index,DateTimeUnit.YEAR)
        RecurringFrequency.WEEKDAYS,RecurringFrequency.WEEKENDS -> {
            val weekend=plan.frequency==RecurringFrequency.WEEKENDS
            fun matches(d:LocalDate)=(d.dayOfWeek==DayOfWeek.SATURDAY || d.dayOfWeek==DayOfWeek.SUNDAY)==weekend
            var first=anchor
            while(!matches(first)) first=first.plus(1,DateTimeUnit.DAY)
            val weekCount=if(weekend) 2 else 5
            var current=first.plus((index/weekCount)*7,DateTimeUnit.DAY)
            repeat(index%weekCount) { do { current=current.plus(1,DateTimeUnit.DAY) } while(!matches(current)) }
            current
        }
    } } catch(_:Exception) { invalid("周期日期超出范围") }
    if(date.year !in 1..9999) invalid("周期日期超出范围")
    return date.toString()+plan.anchor.substring(10)
}
object FinanceQueries {
    fun calculate(state:LedgerState,ledgerIds:Set<String> = emptySet()):FinanceSnapshot {
        val balances=state.accounts.associate { it.id to it.openingBalanceMinor }.toMutableMap()
        val details=state.entryDetails.associateBy { it.entryId }
        fun delta(id:String?,amount:Long) { if(id!=null) balances[id]=Money.add(balances[id] ?: invalid("资金账户不存在"),amount) }
        state.entries.filter { it.active }.forEach { e -> val d=details[e.id];if(d?.recorded!=false) delta(d?.accountId,if(e.kind==EntryKind.EXPENSE) -e.amountMinor else e.amountMinor) }
        state.transfers.filter { it.active && it.recorded }.forEach { delta(it.fromAccountId,-it.amountMinor);delta(it.toAccountId,it.amountMinor) }
        val accounts=state.accounts.filter { ledgerIds.isEmpty() || it.ledgerId in ledgerIds }.sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.id }).map { AccountBalance(it,balances.getValue(it.id)) }
        val included=accounts.filter { it.account.includedInAssets }
        val assets=included.filter { it.balanceMinor>0 }.fold(0L) { sum,a -> Money.add(sum,a.balanceMinor) }
        val liabilities=included.filter { it.balanceMinor<0 }.fold(0L) { sum,a -> if(a.balanceMinor==Long.MIN_VALUE) invalid("金额溢出");Money.add(sum,-a.balanceMinor) }
        val grouped=state.transfers.filter { it.active }.groupBy { it.businessId }
        val obligations=state.obligations.filter { ledgerIds.isEmpty() || it.ledgerId in ledgerIds }.map { o ->
            val transfers=grouped[o.id].orEmpty()
            fun sum(kind:TransferBusinessKind,recorded:Boolean)=transfers.filter { it.businessKind==kind && it.recorded==recorded }.fold(0L) { sum,t -> Money.add(sum,t.amountMinor) }
            val principal=sum(TransferBusinessKind.OBLIGATION_PRINCIPAL,true);val settled=sum(TransferBusinessKind.OBLIGATION_SETTLEMENT,true)
            ObligationBalance(o,principal,settled,Money.add(principal,-settled),sum(TransferBusinessKind.OBLIGATION_PRINCIPAL,false),sum(TransferBusinessKind.OBLIGATION_SETTLEMENT,false))
        }
        val movements=state.savingsMovements.filter { it.active }.groupBy { it.planId }
        val savings=state.savingsPlans.filter { ledgerIds.isEmpty() || it.ledgerId in ledgerIds }.map { p -> SavingsProgress(p,movements[p.id].orEmpty().fold(0L) { total,m -> Money.add(total,if(m.kind==SavingsMovementKind.DEPOSIT) m.amountMinor else -m.amountMinor) }) }
        return FinanceSnapshot(accounts,obligations,savings,assets,liabilities,Money.add(assets,-liabilities))
    }
}
