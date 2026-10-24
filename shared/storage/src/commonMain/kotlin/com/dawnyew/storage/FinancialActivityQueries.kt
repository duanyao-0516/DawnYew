package com.dawnyew.storage

import androidx.room.*
import androidx.sqlite.SQLiteStatement
import com.dawnyew.core.*

/** One projection/filter contract drives lists, account drilldowns, calendar and totals. */
private const val ACTIVITY_UNION="""
SELECT 'ENTRY' AS family,e.id,e.ledgerId,e.kind,e.amountMinor,e.occurrence,e.noteSearch,
 COALESCE(d.recorded,1) AS recorded,COALESCE(d.reconciled,0) AS reconciled,
 CASE WHEN e.kind='REFUND' THEN COALESCE(pd.excludedFromBudget,0) ELSE COALESCE(d.excludedFromBudget,0) END AS excludedFromBudget,
 e.categoryId,d.accountId,NULL AS fromAccountId,NULL AS toAccountId,d.projectId,d.merchantId,
 (e.deletionGroupId IS NOT NULL) AS deleted,e.payload,d.payload AS detailsPayload
FROM entries e LEFT JOIN entry_details d ON d.entryId=e.id LEFT JOIN entry_details pd ON pd.entryId=e.parentExpenseId
UNION ALL
SELECT 'TRANSFER',t.id,t.ledgerId,'TRANSFER',t.amountMinor,t.occurrence,t.noteSearch,
 t.recorded,t.reconciled,0,NULL,NULL,t.fromAccountId,t.toAccountId,NULL,NULL,t.deleted,t.payload,NULL
FROM transfers t
"""
internal const val ACTIVITY_FROM=" FROM ($ACTIVITY_UNION) a JOIN ledgers l ON l.id=a.ledgerId WHERE "
internal data class ActivitySqlFilter(val where:String,val values:List<Any>) {
    fun bind(statement:SQLiteStatement) { values.forEachIndexed { i,v -> if(v is Long) statement.bindLong(i+1,v) else statement.bindText(i+1,v as String) } }
}
internal fun activityFilter(scope:ActivityScope,cursor:ActivityCursor?=null):ActivitySqlFilter {
    validateActivityScope(scope)
    val terms=mutableListOf("a.deleted=?","a.occurrence>=?","a.occurrence<=?")
    val values=mutableListOf<Any>(if(scope.deleted) 1L else 0L,scope.startDate+"T00:00:00",scope.endDate+"T23:59:59")
    fun selected(field:String,items:Collection<String>) { if(items.isNotEmpty()) {terms+="$field IN (${items.joinToString(",") {"?"}})";values.addAll(items.sorted())} }
    selected("a.ledgerId",scope.ledgerIds);selected("a.kind",scope.kinds.map {it.name});selected("a.categoryId",scope.categoryIds)
    selected("a.projectId",scope.projectIds);selected("a.merchantId",scope.merchantIds)
    if(scope.accountIds.isNotEmpty()) {
        val placeholders=scope.accountIds.joinToString(",") {"?"}
        terms+="(a.accountId IN ($placeholders) OR a.fromAccountId IN ($placeholders) OR a.toAccountId IN ($placeholders))"
        repeat(3) {values.addAll(scope.accountIds.sorted())}
    }
    if(scope.tagIds.isNotEmpty()) {
        terms+="(a.family='ENTRY' AND EXISTS(SELECT 1 FROM entry_tags et WHERE et.entryId=a.id AND et.tagId IN (${scope.tagIds.joinToString(",") {"?"}})))"
        values.addAll(scope.tagIds.sorted())
    }
    if(scope.weekdays.isNotEmpty()) {terms+="((CAST(strftime('%w',a.occurrence) AS INTEGER)+6)%7+1) IN (${scope.weekdays.joinToString(",") {"?"}})";values.addAll(scope.weekdays.sorted().map {it.toLong()})}
    if(scope.accountUnassigned) terms+="(a.family='ENTRY' AND a.accountId IS NULL)"
    if(scope.projectUnassigned) terms+="(a.family='ENTRY' AND a.projectId IS NULL)"
    if(scope.merchantUnassigned) terms+="(a.family='ENTRY' AND a.merchantId IS NULL)"
    if(scope.tagUnassigned) terms+="(a.family='ENTRY' AND NOT EXISTS(SELECT 1 FROM entry_tags et WHERE et.entryId=a.id))"
    scope.minAmountMinor?.let {terms+="a.amountMinor>=?";values+=it};scope.maxAmountMinor?.let {terms+="a.amountMinor<=?";values+=it}
    scope.recorded?.let {terms+="a.recorded=?";values+=if(it) 1L else 0L}
    scope.reconciled?.let {terms+="a.reconciled=?";values+=if(it) 1L else 0L}
    scope.excludedFromBudget?.let {terms+="a.excludedFromBudget=?";values+=if(it) 1L else 0L}
    if(!scope.includeArchived) terms+="l.archived=0"
    if(scope.keyword.isNotEmpty()) {terms+="a.noteSearch LIKE ? ESCAPE '\\'";values+="%"+literalSearchKey(scope.keyword).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"}
    cursor?.let {
        terms+="(a.occurrence<? OR (a.occurrence=? AND (a.family<? OR (a.family=? AND a.id<?))))"
        values.addAll(listOf(it.occurrence,it.occurrence,it.family.name,it.family.name,it.id))
    }
    return ActivitySqlFilter(terms.joinToString(" AND "),values)
}
private fun ActivityScope.frozen()=copy(ledgerIds=ledgerIds.toSet(),kinds=kinds.toSet(),categoryIds=categoryIds.toSet(),accountIds=accountIds.toSet(),projectIds=projectIds.toSet(),merchantIds=merchantIds.toSet(),tagIds=tagIds.toSet(),weekdays=weekdays.toSet())

internal suspend fun DawnYewDatabase.readActivityPage(scope:ActivityScope,cursor:ActivityCursor?,limit:Int):ActivityPage=useReaderConnection { connection -> connection.deferredTransaction {
    val meta=ledgerDao().metadata() ?: throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
    validateActivityCursor(scope,cursor,meta.datasetId,meta.contentVersion,limit)
    val filter=activityFilter(scope,cursor)
    val records=connection.usePrepared("SELECT a.family,a.payload,a.detailsPayload,a.excludedFromBudget"+ACTIVITY_FROM+filter.where+" ORDER BY a.occurrence DESC,a.family DESC,a.id DESC LIMIT ${limit+1}") { rows ->
        filter.bind(rows)
        buildList {
            while(rows.step()) if(rows.getText(0)==ActivityFamily.ENTRY.name) {
                val e=streamJson.decodeFromString<Entry>(rows.getText(1))
                val d=if(rows.isNull(2)) EntryDetails(e.id) else streamJson.decodeFromString<EntryDetails>(rows.getText(2))
                add(FinancialActivity(ActivityFamily.ENTRY,e.id,e.ledgerId,ActivityKind.valueOf(e.kind.name),e.amountMinor,e.occurrence,e.note,d.recorded,d.reconciled,rows.getLong(3)!=0L,
                    categoryId=e.categoryId,accountId=d.accountId,projectId=d.projectId,merchantId=d.merchantId,deleted=!e.active,revision=e.revision))
            } else {
                val t=streamJson.decodeFromString<AccountTransfer>(rows.getText(1))
                add(FinancialActivity(ActivityFamily.TRANSFER,t.id,t.ledgerId,ActivityKind.TRANSFER,t.amountMinor,t.occurrence,t.note,t.recorded,t.reconciled,false,
                    fromAccountId=t.fromAccountId,toAccountId=t.toAccountId,businessKind=t.businessKind,businessId=t.businessId,deleted=t.deleted,revision=t.revision))
            }
        }
    }
    val selected=records.take(limit);val entryIds=selected.filter {it.family==ActivityFamily.ENTRY}.map {it.id}
    val tags=if(entryIds.isEmpty()) emptyMap() else connection.usePrepared("SELECT entryId,tagId FROM entry_tags WHERE entryId IN (${entryIds.joinToString(",") {"?"}})") { rows ->
        entryIds.forEachIndexed {i,id->rows.bindText(i+1,id)}
        val result=mutableMapOf<String,MutableSet<String>>()
        while(rows.step()) result.getOrPut(rows.getText(0)) {mutableSetOf()}.add(rows.getText(1))
        result
    }
    val activities=selected.map {if(it.family==ActivityFamily.ENTRY) it.copy(tagIds=tags[it.id]?.toSet().orEmpty()) else it}
    val next=activities.lastOrNull()?.takeIf {records.size>limit}?.let {ActivityCursor(it.occurrence,it.family,it.id,meta.datasetId,meta.contentVersion,scope.frozen())}
    ActivityPage(activities,next,meta.datasetId,meta.contentVersion)
} }

internal suspend fun DawnYewDatabase.readActivitySummary(scope:ActivityScope):ActivitySummary=useReaderConnection { connection -> connection.deferredTransaction {
    if(ledgerDao().metadata()==null) throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
    val filter=activityFilter(scope)
    val sums=listOf("INCOME","EXPENSE","REFUND","TRANSFER").joinToString(",") {kind->"COALESCE(SUM(CASE WHEN a.kind='$kind' THEN a.amountMinor ELSE 0 END),0)"}
    val totals=connection.usePrepared("SELECT $sums,COUNT(*)"+ACTIVITY_FROM+filter.where) {rows->filter.bind(rows);check(rows.step());(0..4).map(rows::getLong)}
    val days=connection.usePrepared("SELECT substr(a.occurrence,1,10),COUNT(*),$sums"+ACTIVITY_FROM+filter.where+" GROUP BY 1 ORDER BY 1") {rows->
        filter.bind(rows);buildList {while(rows.step()) add(ActivityDaySummary(rows.getText(0),rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),rows.getLong(5)))}
    }
    ActivitySummary(totals[0],totals[1],totals[2],totals[3],totals[4],days)
} }
