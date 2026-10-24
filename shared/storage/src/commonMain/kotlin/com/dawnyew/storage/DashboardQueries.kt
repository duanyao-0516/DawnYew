package com.dawnyew.storage

import androidx.room.*
import com.dawnyew.core.*

/** Scalar facts are read inside one snapshot and restricted before any payload decoding. */
internal suspend fun DawnYewDatabase.readPanelStatistics(request:PanelRequest):PanelStatistics=useReaderConnection {connection->connection.deferredTransaction {
    val meta=ledgerDao().metadata() ?: throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
    val scope=PanelQueries.readScope(request)
    val filter=activityFilter(scope)
    val records=connection.usePrepared("SELECT a.id,a.ledgerId,a.kind,a.amountMinor,a.occurrence,a.recorded,a.reconciled,a.excludedFromBudget,a.categoryId,a.accountId,a.projectId,a.merchantId,a.deleted"+ACTIVITY_FROM+filter.where+" AND a.family='ENTRY'") {rows->
        filter.bind(rows)
        fun nullable(index:Int)=if(rows.isNull(index)) null else rows.getText(index)
        buildList {while(rows.step()) add(FinancialActivity(ActivityFamily.ENTRY,rows.getText(0),rows.getText(1),ActivityKind.valueOf(rows.getText(2)),rows.getLong(3),rows.getText(4),"",rows.getLong(5)!=0L,rows.getLong(6)!=0L,rows.getLong(7)!=0L,categoryId=nullable(8),accountId=nullable(9),projectId=nullable(10),merchantId=nullable(11),deleted=rows.getLong(12)!=0L))}
    }
    val tags=mutableMapOf<String,MutableSet<String>>()
    for(ids in records.map {it.id}.chunked(500)) connection.usePrepared("SELECT entryId,tagId FROM entry_tags WHERE entryId IN (${ids.joinToString(",") {"?"}})") {rows->
        ids.forEachIndexed {index,id->rows.bindText(index+1,id)}
        while(rows.step()) tags.getOrPut(rows.getText(0)) {mutableSetOf()}.add(rows.getText(1))
    }
    val activities=records.map {it.copy(tagIds=tags[it.id].orEmpty().toSet())}
    val allocations=if(request.panelId!="budget_tracking") emptyList() else {
        val terms=mutableListOf("b.yearMonth=?");val values=mutableListOf<Any>(scope.endDate.take(7))
        if(scope.ledgerIds.isNotEmpty()) {terms+="b.ledgerId IN (${scope.ledgerIds.joinToString(",") {"?"}})";values.addAll(scope.ledgerIds.sorted())}
        if(scope.categoryIds.isNotEmpty()) {terms+="b.categoryId IN (${scope.categoryIds.joinToString(",") {"?"}})";values.addAll(scope.categoryIds.sorted())}
        if(!scope.includeArchived) terms+="l.archived=0"
        val budgetFilter=ActivitySqlFilter(terms.joinToString(" AND "),values)
        connection.usePrepared("SELECT b.id,b.ledgerId,b.yearMonth,b.categoryId,b.amountMinor,b.revision FROM budget_allocations b JOIN ledgers l ON l.id=b.ledgerId WHERE "+budgetFilter.where) {rows->
            budgetFilter.bind(rows);buildList {while(rows.step()) add(BudgetAllocation(rows.getText(0),rows.getText(1),rows.getText(2),rows.getText(3),rows.getLong(4),rows.getLong(5)))}
        }
    }
    suspend fun labels(table:String,ids:Set<String>,category:Boolean=false):Map<String,String> {
        val names=mutableMapOf<String,String>()
        for(chunk in ids.chunked(500)) connection.usePrepared("SELECT id,${if(category) "payload" else "name"} FROM $table WHERE id IN (${chunk.joinToString(",") {"?"}})") {rows->
            chunk.forEachIndexed {index,id->rows.bindText(index+1,id)}
            while(rows.step()) names[rows.getText(0)]=if(category) streamJson.decodeFromString<Category>(rows.getText(1)).name else rows.getText(1)
        }
        return names
    }
    val names=PanelQueryLabels(
        labels("categories",activities.mapNotNull {it.categoryId}.toSet()+allocations.map {it.categoryId},true),
        labels("accounts",activities.mapNotNull {it.accountId}.toSet()),
        labels("dimensions",activities.flatMap {listOfNotNull(it.projectId,it.merchantId)+it.tagIds}.toSet()),
    )
    PanelQueries.aggregate(meta.datasetId,meta.contentVersion,request,activities,names,allocations)
} }

internal suspend fun DawnYewDatabase.readAccountHistory(request:AccountHistoryRequest):AccountHistorySnapshot=useReaderConnection {connection->connection.deferredTransaction {
    AccountHistoryQueries.validate(request)
    val meta=ledgerDao().metadata() ?: throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
    val terms=mutableListOf<String>();val values=mutableListOf<Any>()
    fun selected(field:String,ids:Set<String>) {if(ids.isNotEmpty()) {terms+="$field IN (${ids.joinToString(",") {"?"}})";values.addAll(ids.sorted())}}
    selected("ledgerId",request.ledgerIds);selected("id",request.accountIds)
    if(request.accountIds.isEmpty()) terms+="includedInAssets=1"
    val selection=if(terms.isEmpty()) "1=1" else terms.joinToString(" AND ")
    val sql="""
        WITH selected AS (SELECT id,openingAt,openingBalanceMinor FROM accounts WHERE $selection)
        SELECT substr(openingAt,1,10),openingBalanceMinor FROM selected WHERE openingAt<=?
        UNION ALL
        SELECT substr(e.occurrence,1,10),CASE WHEN e.kind='EXPENSE' THEN -e.amountMinor ELSE e.amountMinor END
        FROM entries e JOIN entry_details d ON d.entryId=e.id JOIN selected a ON a.id=d.accountId
        WHERE e.deletionGroupId IS NULL AND d.recorded=1 AND e.occurrence<=?
        UNION ALL
        SELECT substr(t.occurrence,1,10),CASE WHEN f.id IS NOT NULL THEN -t.amountMinor ELSE t.amountMinor END
        FROM transfers t LEFT JOIN selected f ON f.id=t.fromAccountId LEFT JOIN selected r ON r.id=t.toAccountId
        WHERE t.deleted=0 AND t.recorded=1 AND t.occurrence<=? AND ((f.id IS NULL) != (r.id IS NULL))
    """.trimIndent()
    repeat(3) {values+=request.endDate+"T23:59:59"}
    val binder=ActivitySqlFilter("",values)
    val events=connection.usePrepared(sql) {rows->binder.bind(rows);buildList {while(rows.step()) add(AccountHistoryEvent(rows.getText(0),rows.getLong(1)))}}
    AccountHistoryQueries.aggregate(meta.datasetId,meta.contentVersion,request,events)
} }
