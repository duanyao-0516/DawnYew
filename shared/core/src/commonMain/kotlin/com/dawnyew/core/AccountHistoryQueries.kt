package com.dawnyew.core

import kotlinx.datetime.*

data class AccountHistoryRequest(val ledgerIds:Set<String> = emptySet(),val accountIds:Set<String> = emptySet(),val startDate:String,val endDate:String)
data class AccountHistoryPoint(val date:String,val balanceMinor:Long)
data class AccountHistorySnapshot(val datasetId:String,val contentVersion:Long,val request:AccountHistoryRequest,val points:List<AccountHistoryPoint>)
data class AccountHistoryEvent(val date:String,val deltaMinor:Long)

object AccountHistoryQueries {
    fun validate(request:AccountHistoryRequest) {
        validateActivityScope(ActivityScope(ledgerIds=request.ledgerIds,accountIds=request.accountIds,startDate=request.startDate,endDate=request.endDate))
        if(LocalDate.parse(request.startDate).daysUntil(LocalDate.parse(request.endDate))>365) invalid("资产趋势一次最多查看366天")
    }
    fun calculate(state:LedgerState,request:AccountHistoryRequest):AccountHistorySnapshot {
        validate(request)
        val accounts=state.accounts.filter {(request.ledgerIds.isEmpty() || it.ledgerId in request.ledgerIds) && (if(request.accountIds.isEmpty()) it.includedInAssets else it.id in request.accountIds)}
        val ids=accounts.map {it.id}.toSet()
        val details=state.entryDetails.associateBy {it.entryId}
        val events=buildList {
            accounts.filter {it.openingAt.substringBefore('T')<=request.endDate}.forEach {add(AccountHistoryEvent(it.openingAt.substringBefore('T'),it.openingBalanceMinor))}
            state.entries.filter {it.active && it.localDate<=request.endDate}.forEach {entry->val detail=details[entry.id];if(detail?.accountId in ids && detail?.recorded!=false) add(AccountHistoryEvent(entry.localDate,if(entry.kind==EntryKind.EXPENSE) -entry.amountMinor else entry.amountMinor))}
            state.transfers.filter {it.active && it.recorded && it.localDate<=request.endDate}.forEach {transfer->
                // Internal moves cancel before summation, avoiding a transient artificial overflow.
                if((transfer.fromAccountId in ids)!=(transfer.toAccountId in ids)) add(AccountHistoryEvent(transfer.localDate,if(transfer.fromAccountId in ids) -transfer.amountMinor else transfer.amountMinor))
            }
        }
        return aggregate(state.datasetId,state.contentVersion,request,events)
    }
    // Add opposite signs first so a representable sum is independent of database row order.
    private fun sumSigned(values:List<Long>):Long {
        val positive=values.filter {it>=0}.iterator();val negative=values.filter {it<0}.iterator()
        var total=0L
        while(positive.hasNext() || negative.hasNext()) {
            val next=if(total<=0 && positive.hasNext() || !negative.hasNext()) positive.next() else negative.next()
            total=Money.add(total,next)
        }
        return total
    }
    fun aggregate(datasetId:String,contentVersion:Long,request:AccountHistoryRequest,events:List<AccountHistoryEvent>):AccountHistorySnapshot {
        validate(request)
        val grouped=events.filter {it.date<=request.endDate}.groupBy {it.date}
        var balance=sumSigned(grouped.filterKeys {it<request.startDate}.values.flatten().map {it.deltaMinor})
        val start=LocalDate.parse(request.startDate);val length=start.daysUntil(LocalDate.parse(request.endDate))
        val points=(0..length).map {offset->val date=start.plus(offset,DateTimeUnit.DAY).toString();balance=sumSigned(listOf(balance)+grouped[date].orEmpty().map {it.deltaMinor});if(balance==Long.MIN_VALUE) invalid("余额超出可显示范围");AccountHistoryPoint(date,balance)}
        return AccountHistorySnapshot(datasetId,contentVersion,request,points)
    }
}
