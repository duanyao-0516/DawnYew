package com.dawnyew.core

import kotlinx.datetime.*

enum class PanelUnit { MONEY, COUNT }
data class PanelRequest(val panelId:String,val scope:ActivityScope)
data class PanelRow(val id:String,val label:String,val value:Long,val income:Long=0,val expense:Long=0,val refunds:Long=0,val count:Long=0,val comparison:Long?=null,val drill:ActivityScope?=null,val entryId:String?=null)
data class PanelStatistics(val datasetId:String,val contentVersion:Long,val request:PanelRequest,val rows:List<PanelRow>,val unit:PanelUnit=PanelUnit.MONEY)
data class PanelQueryLabels(val categories:Map<String,String> = emptyMap(),val accounts:Map<String,String> = emptyMap(),val dimensions:Map<String,String> = emptyMap())

/** Pure reference calculation shared with the bounded SQL projection. No UI scans ledger entities. */
object PanelQueries {
    private val extra=setOf("budget_tracking","category_change","category_radar","income_expense","monthly_expense")
    private val ordinary=setOf(ActivityKind.EXPENSE,ActivityKind.INCOME,ActivityKind.REFUND)
    fun readScope(request:PanelRequest):ActivityScope {
        if(request.panelId !in PanelCatalog.definitions(PanelSurface.STATISTICS).map {it.id} && request.panelId !in extra) invalid("未知统计面板")
        validateActivityScope(request.scope)
        val scope=request.scope
        val kinds=if(request.panelId in setOf("budget_tracking","category_change")) setOf(ActivityKind.EXPENSE,ActivityKind.REFUND) else scope.kinds.ifEmpty {ordinary}.intersect(ordinary)
        if(kinds.isEmpty()) invalid("统计面板不包含转账")
        var result=scope.copy(kinds=kinds)
        if(request.panelId=="budget_tracking" || request.panelId=="category_change") {
            if(scope.startDate.take(7)!=scope.endDate.take(7)) invalid("该面板需要单月范围")
            if(request.panelId=="budget_tracking") result=result.copy(excludedFromBudget=false)
            else {
                val first=LocalDate.parse(scope.startDate.take(7)+"-01")
                if(first.year==1 && first.monthNumber==1) invalid("无法比较公元元年之前的月份")
                result=result.copy(startDate=first.minus(1,DateTimeUnit.MONTH).toString())
            }
        }
        return result
    }
    fun calculate(state:LedgerState,request:PanelRequest):PanelStatistics {
        val scope=readScope(request)
        val ledgers=state.ledgers.filter {scope.includeArchived || !it.archived}.map {it.id}.toSet()
        val allocations=state.budgetAllocations.filter {it.yearMonth==scope.endDate.take(7) && it.ledgerId in ledgers && (scope.ledgerIds.isEmpty() || it.ledgerId in scope.ledgerIds)}
        return aggregate(state.datasetId,state.contentVersion,request,activitySnapshot(state,scope),PanelQueryLabels(state.categories.associate {it.id to it.name},state.accounts.associate {it.id to it.name},state.dimensions.associate {it.id to it.name}),allocations)
    }
    fun aggregate(datasetId:String,contentVersion:Long,request:PanelRequest,activities:List<FinancialActivity>,labels:PanelQueryLabels,allocations:List<BudgetAllocation> = emptyList()):PanelStatistics {
        val read=readScope(request)
        val scope=read.copy(startDate=request.scope.startDate)
        val items=activities.filter {it.localDate>=scope.startDate && it.localDate<=scope.endDate && it.kind in read.kinds}
        val selectedKind=request.scope.kinds.singleOrNull()?.takeIf {it in ordinary} ?: ActivityKind.EXPENSE
        val valueScope=scope.copy(kinds=setOf(selectedKind))
        fun sum(list:List<FinancialActivity>,kind:ActivityKind)=list.filter {it.kind==kind}.fold(0L) {total,item->Money.add(total,item.amountMinor)}
        // Cards also display totals across groups. Reject an unrepresentable total in the query,
        // where the controller can render an error, rather than throwing while composing a card.
        ordinary.forEach {kind->sum(items,kind)}
        if(request.panelId=="category_change") ordinary.forEach {kind->sum(activities.filter {it.localDate<scope.startDate.take(7)+"-01"},kind)}
        fun row(key:String,label:String,list:List<FinancialActivity>,drill:ActivityScope?=valueScope):PanelRow {
            val income=sum(list,ActivityKind.INCOME);val expense=sum(list,ActivityKind.EXPENSE);val refunds=sum(list,ActivityKind.REFUND)
            val value=when(selectedKind) {ActivityKind.INCOME->income;ActivityKind.REFUND->refunds;else->expense}
            return PanelRow(key,label,value,income,expense,refunds,list.size.toLong(),drill=drill)
        }
        fun bounded(drill:ActivityScope,start:String,end:String)=drill.copy(startDate=maxOf(scope.startDate,start),endDate=minOf(scope.endDate,end))
        fun monthBounds(month:String):Pair<String,String> {
            val first=LocalDate.parse("$month-01")
            val last=if(month=="9999-12") "9999-12-31" else first.plus(1,DateTimeUnit.MONTH).minus(1,DateTimeUnit.DAY).toString()
            return first.toString() to last
        }
        fun months():List<String> {
            val first=LocalDate.parse(scope.startDate.take(7)+"-01");val end=scope.endDate.take(7)
            if(first.daysUntil(LocalDate.parse(scope.endDate))>3660) return items.map {it.localDate.take(7)}.distinct().sorted()
            return buildList {var date=first;while(date.toString().take(7)<=end) {add(date.toString().take(7));if(date.year==9999 && date.monthNumber==12) break;date=date.plus(1,DateTimeUnit.MONTH)}}
        }
        fun categoryRows():List<PanelRow> = items.groupBy {it.categoryId}.map { (id,list)->row(id ?: "none",labels.categories[id] ?: "未分类",list,valueScope.copy(categoryIds=setOfNotNull(id))) }.sortedWith(compareByDescending<PanelRow> {it.value}.thenBy {it.id})
        val rows=when(request.panelId) {
            "category","category_radar"->categoryRows()
            "average"->categoryRows().map {base->
                val selected=items.filter {it.categoryId==base.id && it.kind==selectedKind}
                val count=selected.size.toLong()
                val rounded=if(count==0L) 0L else base.value/count+if(base.value%count >= (count+1)/2) 1 else 0
                base.copy(value=rounded,count=count)
            }
            "daily","income_expense","monthly_expense"->{
                val groups=items.groupBy {it.localDate};val start=LocalDate.parse(scope.startDate);val end=LocalDate.parse(scope.endDate)
                val keys=if(start.daysUntil(end)<=366) (0..start.daysUntil(end)).map {start.plus(it,DateTimeUnit.DAY).toString()} else groups.keys.sorted()
                keys.map {date->row(date,date.takeLast(5),groups[date].orEmpty(),valueScope.copy(startDate=date,endDate=date))}
            }
            "monthly","category_monthly","count","maximum"->{
                if(request.panelId=="category_monthly" && scope.categoryIds.isEmpty()) emptyList() else {
                    val groups=items.groupBy {it.localDate.take(7)}
                    months().map {month->
                        val list=groups[month].orEmpty();val bounds=monthBounds(month)
                        val base=row(month,month,list,bounded(valueScope,bounds.first,bounds.second))
                        when(request.panelId) {
                            "count"->base.copy(value=list.size.toLong(),drill=bounded(scope,bounds.first,bounds.second))
                            "maximum"->{val highest=list.filter {it.kind==selectedKind}.sortedWith(compareByDescending<FinancialActivity> {it.amountMinor}.thenBy {it.occurrence}.thenBy {it.id}).firstOrNull();base.copy(value=highest?.amountMinor ?: 0,entryId=highest?.id,drill=null)}
                            else->base
                        }
                    }
                }
            }
            "yearly"->items.groupBy {it.localDate.take(4)}.entries.sortedBy {it.key}.map { (year,list)->row(year,year+"年",list,bounded(valueScope,"$year-01-01","$year-12-31")) }
            "quarterly"->{
                val keys=months().map {it.take(4)+"-"+((it.takeLast(2).toInt()-1)/3+1)}.distinct()
                val groups=items.groupBy {it.localDate.take(4)+"-"+((it.localDate.substring(5,7).toInt()-1)/3+1)}
                keys.map {key->val year=key.take(4);val q=key.takeLast(1).toInt();val start="$year-${((q-1)*3+1).toString().padStart(2,'0')}-01";val end=monthBounds("$year-${(q*3).toString().padStart(2,'0')}").second;row(key,"${year}年 Q$q",groups[key].orEmpty(),bounded(valueScope,start,end))}
            }
            "weekday"->{val groups=items.groupBy {LocalDate.parse(it.localDate).dayOfWeek.isoDayNumber};(1..7).map {day->row(day.toString(),listOf("周一","周二","周三","周四","周五","周六","周日")[day-1],groups[day].orEmpty(),if(scope.weekdays.isEmpty() || day in scope.weekdays) valueScope.copy(weekdays=setOf(day)) else null)} }
            "weekend"->listOf(false,true).map {weekend->val days=if(weekend) setOf(6,7) else (1..5).toSet();row(if(weekend) "weekend" else "weekday",if(weekend) "周末" else "工作日",items.filter {LocalDate.parse(it.localDate).dayOfWeek.isoDayNumber in days},if(scope.weekdays.isNotEmpty() && days.intersect(scope.weekdays).isEmpty()) null else valueScope.copy(weekdays=if(scope.weekdays.isEmpty()) days else days.intersect(scope.weekdays)))}
            "account","project","merchant","tag"->{
                val grouped=mutableMapOf<String?,MutableList<FinancialActivity>>()
                items.forEach {item->val ids=when(request.panelId) {"account"->listOf(item.accountId);"project"->listOf(item.projectId);"merchant"->listOf(item.merchantId);else->item.tagIds.filter {scope.tagIds.isEmpty() || it in scope.tagIds}.map {it as String?}.ifEmpty {listOf(null)}};ids.forEach {id->grouped.getOrPut(id) {mutableListOf()}.add(item)}}
                grouped.map { (id,list)->
                    val drill=when(request.panelId) {
                        "account"->valueScope.copy(accountIds=setOfNotNull(id),accountUnassigned=id==null)
                        "project"->valueScope.copy(projectIds=setOfNotNull(id),projectUnassigned=id==null)
                        "merchant"->valueScope.copy(merchantIds=setOfNotNull(id),merchantUnassigned=id==null)
                        else->valueScope.copy(tagIds=setOfNotNull(id),tagUnassigned=id==null)
                    }
                    val label=if(id==null) when(request.panelId) {"account"->"无账户";"project"->"无项目";"merchant"->"无商家";else->"无标签"} else (if(request.panelId=="account") labels.accounts[id] else labels.dimensions[id]) ?: "已删除项目"
                    row(id ?: "none",label,list,drill)
                }.sortedWith(compareByDescending<PanelRow> {it.value}.thenBy {it.id})
            }
            "amount_distribution"->{
                val bounds=listOf(0L to 999L,1000L to 4999L,5000L to 9999L,10000L to 49999L,50000L to Money.MAX)
                val titles=listOf("10元以下","10至50元","50至100元","100至500元","500元及以上")
                bounds.mapIndexedNotNull {index,(min,max)->val lower=maxOf(min,scope.minAmountMinor ?: 0);val upper=minOf(max,scope.maxAmountMinor ?: Money.MAX);if(lower>upper) null else row(index.toString(),titles[index],items.filter {it.amountMinor in lower..upper},valueScope.copy(minAmountMinor=lower,maxAmountMinor=upper))}
            }
            "category_change"->{
                val previous=activities.filter {it.localDate<scope.startDate.take(7)+"-01"}.groupBy {it.categoryId};val current=items.groupBy {it.categoryId}
                (current.keys+previous.keys).map {id->val list=current[id].orEmpty();val old=previous[id].orEmpty();val base=row(id ?: "none",labels.categories[id] ?: "未分类",list,scope.copy(categoryIds=setOfNotNull(id)));base.copy(value=Money.add(base.expense,-base.refunds),comparison=Money.add(sum(old,ActivityKind.EXPENSE),-sum(old,ActivityKind.REFUND)))}.sortedWith(compareByDescending<PanelRow> {it.value}.thenBy {it.id})
            }
            "budget_tracking"->allocations.filter {scope.categoryIds.isEmpty() || it.categoryId in scope.categoryIds}.groupBy {it.categoryId}.map { (id,limits)->
                val base=row(id,labels.categories[id] ?: "未分类",items.filter {it.categoryId==id},scope.copy(categoryIds=setOf(id)))
                base.copy(value=Money.add(base.expense,-base.refunds).coerceAtLeast(0),comparison=limits.fold(0L) {total,allocation->Money.add(total,allocation.amountMinor)})
            }.sortedBy {it.id}
            else->invalid("未知统计面板")
        }
        return PanelStatistics(datasetId,contentVersion,request,rows,if(request.panelId=="count") PanelUnit.COUNT else PanelUnit.MONEY)
    }
}
