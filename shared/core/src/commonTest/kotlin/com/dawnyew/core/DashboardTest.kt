package com.dawnyew.core

import kotlin.test.*

class DashboardTest {
    private fun id(n:Int)="00000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private val ledger=id(1)
    private val category=id(2)
    private val account=id(3)
    private fun entry(n:Int,amount:Long,date:String,kind:EntryKind=EntryKind.EXPENSE,parent:String?=null)=Entry(id(n),ledger,kind,amount,category,date+"T12:00:00",parentExpenseId=parent,createdAt="2026-10-24T04:00:00Z",updatedAt="2026-10-24T04:00:00Z",creationOperationId=id(n+100))
    private fun state()=LedgerState(id(900),ledgers=listOf(Ledger(ledger,"日常")),categories=listOf(Category(category,ledger,Direction.EXPENSE,"餐饮")),accounts=listOf(Account(account,ledger,"现金",openingBalanceMinor=10000,openingAt="2026-10-02T00:00:00")))
    private val month=ActivityScope(startDate="2026-10-01",endDate="2026-10-31")

    @Test fun layoutRoundTripPreservesAllOffOrderAndRejectsUnknownDuplicateOrIncompleteIds() {
        for(surface in PanelSurface.entries) {
            val default=PanelCatalog.defaultLayout(surface)
            val changed=PanelLayout(default.order.reversed(),emptySet())
            val wire=PanelCatalog.encode(surface,changed)
            assertEquals(changed,PanelCatalog.decode(surface,wire))
            assertEquals(default,PanelCatalog.resolve(emptyList(),surface))
            validatePreference(UserPreference(PanelCatalog.preferenceKey(surface),wire))
            assertFails {PanelCatalog.decode(surface,wire+"|bogus=1")}
            assertFails {PanelCatalog.decode(surface,wire+"|${changed.order.first()}=1")}
            assertFails {PanelCatalog.decode(surface,wire.substringBeforeLast('|'))}
        }
        assertEquals(10,PanelCatalog.definitions(PanelSurface.HOME).size)
        assertEquals(16,PanelCatalog.definitions(PanelSurface.STATISTICS).size)
    }
    @Test fun unassignedAndWeekdayFiltersAreExactAndInvalidCombinationsRejected() {
        val monday=entry(10,100,"2026-10-05");val sunday=entry(11,200,"2026-10-04")
        val s=state().copy(entries=listOf(monday,sunday),entryDetails=listOf(EntryDetails(sunday.id,account)))
        assertEquals(listOf(monday.id),activityPageSnapshot(s,month.copy(weekdays=setOf(1),accountUnassigned=true)).activities.map {it.id})
        assertFails {validateActivityScope(month.copy(weekdays=setOf(0)))}
        assertFails {validateActivityScope(month.copy(accountIds=setOf(account),accountUnassigned=true))}
    }
    @Test fun everyPanelUsesRealRowsAndDimensionDrillsMatchDisplayedTotals() {
        val project=id(50);val tag1=id(51);val tag2=id(52)
        val a=entry(10,101,"2026-10-05");val b=entry(11,299,"2026-10-04");val c=entry(12,900,"2026-09-04")
        val s=state().copy(entries=listOf(a,b,c),entryDetails=listOf(EntryDetails(b.id,account,projectId=project)),dimensions=listOf(Dimension(project,ledger,DimensionKind.PROJECT,"项目"),Dimension(tag1,ledger,DimensionKind.TAG,"标签一"),Dimension(tag2,ledger,DimensionKind.TAG,"标签二")),entryTags=listOf(EntryTag(b.id,tag1),EntryTag(b.id,tag2)))
        for(definition in PanelCatalog.definitions(PanelSurface.STATISTICS)) {
            val request=PanelRequest(definition.id,if(definition.id=="category_monthly") month.copy(categoryIds=setOf(category)) else month)
            val result=PanelQueries.calculate(s,request)
            assertEquals(request,result.request)
            assertTrue(result.rows.isNotEmpty(),definition.id)
        }
        val projectRows=PanelQueries.calculate(s,PanelRequest("project",month)).rows
        assertEquals(400,projectRows.sumOf {it.value})
        projectRows.forEach {row->assertEquals(row.value,activitySummarySnapshot(s,assertNotNull(row.drill)).expense)}
        val tags=PanelQueries.calculate(s,PanelRequest("tag",month)).rows
        assertEquals(699,tags.sumOf {it.value}) // overlapping tags plus unassigned
        assertEquals(200,PanelQueries.calculate(s,PanelRequest("average",month)).rows.single().value)
        assertEquals(299,PanelQueries.calculate(s,PanelRequest("maximum",month)).rows.single {it.value>0}.value)
    }
    @Test fun budgetRefundUsesParentExclusionAndCategoryComparisonCanBeNegative() {
        val expense=entry(10,1000,"2026-09-05");val refund=entry(11,600,"2026-10-05",EntryKind.REFUND,expense.id)
        val s=state().copy(entries=listOf(expense,refund),entryDetails=listOf(EntryDetails(expense.id,excludedFromBudget=true)),budgetAllocations=listOf(BudgetAllocation(id(60),ledger,"2026-10",category,2000)))
        val change=PanelQueries.calculate(s,PanelRequest("category_change",month)).rows.single()
        assertEquals(-600,change.value);assertEquals(1000,change.comparison)
        assertEquals(0,PanelQueries.calculate(s,PanelRequest("budget_tracking",month)).rows.single().value)
        val included=s.copy(entryDetails=emptyList())
        val budget=PanelQueries.calculate(included,PanelRequest("budget_tracking",month)).rows.single()
        assertEquals(0,budget.value);assertEquals(600,budget.refunds)
        assertEquals(Statistics.calculate(included,QueryScope(startDate=month.startDate,endDate=month.endDate)).budgetUsed,budget.value)
    }
    @Test fun historyUsesOpeningDateRecordedEntriesAndBothTransferEndpoints() {
        val future=id(4);val expense=entry(10,200,"2026-10-03");val pending=entry(11,500,"2026-10-03")
        val s=state().copy(accounts=state().accounts+Account(future,ledger,"银行卡",openingBalanceMinor=3000,openingAt="2026-10-04T00:00:00"),entries=listOf(expense,pending),entryDetails=listOf(EntryDetails(expense.id,account),EntryDetails(pending.id,account,recorded=false)),transfers=listOf(AccountTransfer(id(20),ledger,account,future,1000,"2026-10-04T10:00:00",createdAt="2026-10-24T04:00:00Z",updatedAt="2026-10-24T04:00:00Z")))
        val request=AccountHistoryRequest(startDate="2026-10-01",endDate="2026-10-04")
        assertEquals(listOf(0L,10000L,9800L,12800L),AccountHistoryQueries.calculate(s,request).points.map {it.balanceMinor})
        assertEquals(8800,AccountHistoryQueries.calculate(s,request.copy(accountIds=setOf(account))).points.last().balanceMinor)
        assertFails {AccountHistoryQueries.calculate(s,request.copy(endDate="2027-12-01"))}
    }
    @Test fun drillNeverBroadensWeekdayOrTagSourceAndSignedHistoryIsOrderIndependent() {
        val a=entry(10,100,"2026-10-05");val b=entry(11,200,"2026-10-04");val tagA=id(51);val tagB=id(52)
        val s=state().copy(entries=listOf(a,b),entryTags=listOf(EntryTag(a.id,tagA),EntryTag(a.id,tagB),EntryTag(b.id,tagB)))
        val weekend=PanelQueries.calculate(s,PanelRequest("weekend",month.copy(weekdays=setOf(1)))).rows
        assertNull(weekend.single {it.id=="weekend"}.drill)
        assertEquals(setOf(1),weekend.single {it.id=="weekday"}.drill?.weekdays)
        val tags=PanelQueries.calculate(s,PanelRequest("tag",month.copy(tagIds=setOf(tagA)))).rows
        assertEquals(listOf(tagA),tags.map {it.id})
        assertEquals(100,activitySummarySnapshot(s,assertNotNull(tags.single().drill)).expense)
        val request=AccountHistoryRequest(startDate="2026-10-05",endDate="2026-10-05")
        val events=listOf(AccountHistoryEvent(request.startDate,Long.MAX_VALUE),AccountHistoryEvent(request.startDate,10),AccountHistoryEvent(request.startDate,-Long.MAX_VALUE))
        assertEquals(10,AccountHistoryQueries.aggregate(s.datasetId,1,request,events).points.single().balanceMinor)
        assertEquals(10,AccountHistoryQueries.aggregate(s.datasetId,1,request,events.reversed()).points.single().balanceMinor)
    }

}
