package com.dawnyew.storage

import androidx.room.*
import androidx.sqlite.SQLiteStatement
import com.dawnyew.core.*
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

internal data class EntryFilter(val where:String,val arguments:List<String>) {
    fun bind(statement:SQLiteStatement) { arguments.forEachIndexed { i,value -> statement.bindText(i+1,value) } }
}
internal fun entryFilter(scope:QueryScope,cursor:EntryCursor?=null):EntryFilter {
    validateQueryScope(scope)
    val predicates=mutableListOf("e.deletionGroupId IS NULL", "e.occurrence >= ?", "e.occurrence <= ?")
    val args=mutableListOf(scope.startDate+"T00:00:00",scope.endDate+"T23:59:59")
    fun set(field:String,values:Collection<String>) { if(values.isNotEmpty()) { predicates += "$field IN (${values.joinToString(",") { "?" }})";args += values.sorted() } }
    set("e.ledgerId",scope.ledgerIds);set("e.kind",scope.kinds.map { it.name });set("e.categoryId",scope.categoryIds)
    if(!scope.includeArchived) predicates += "l.archived = 0"
    if(scope.keyword.isNotEmpty()) {
        predicates += "e.noteSearch LIKE ? ESCAPE '\\'"
        args += "%"+literalSearchKey(scope.keyword).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
    }
    cursor?.let { predicates += "(e.occurrence < ? OR (e.occurrence = ? AND e.id < ?))";args += listOf(it.occurrence,it.occurrence,it.id) }
    return EntryFilter(predicates.joinToString(" AND "),args)
}
private const val FROM=" FROM entries e JOIN ledgers l ON l.id=e.ledgerId LEFT JOIN entry_details d ON d.entryId=e.id LEFT JOIN entry_details pd ON pd.entryId=e.parentExpenseId WHERE "
private val queryJson=Json { encodeDefaults=true }

internal suspend fun DawnYewDatabase.readEntryPage(scope:QueryScope,cursor:EntryCursor?,limit:Int):EntryPage = useReaderConnection { connection ->
    connection.deferredTransaction {
        val dao=ledgerDao();val meta=dao.metadata() ?: throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
        validateCursor(scope,cursor,meta.datasetId,meta.contentVersion,limit)
        val filter=entryFilter(scope,cursor)
        val rows=dao.queryEntryRows(RoomRawQuery("SELECT e.*"+FROM+filter.where+" ORDER BY e.occurrence DESC,e.id DESC LIMIT ${limit+1}",filter::bind))
        val entries=rows.take(limit).map { queryJson.decodeFromString<Entry>(it.payload) }
        val next=entries.lastOrNull()?.takeIf { rows.size>limit }?.let { EntryCursor(it.occurrence,it.id,meta.datasetId,meta.contentVersion,scope.copy(ledgerIds=scope.ledgerIds.toSet(),kinds=scope.kinds.toSet(),categoryIds=scope.categoryIds.toSet())) }
        EntryPage(entries,next,meta.datasetId,meta.contentVersion)
    }
}

internal suspend fun DawnYewDatabase.readStatistics(scope:QueryScope):StatisticsSnapshot = useReaderConnection { connection ->
    connection.deferredTransaction {
        val dao=ledgerDao();val meta=dao.metadata() ?: throw DomainException(ErrorCode.CORRUPT_DATA,"数据库缺少元信息")
        val filter=entryFilter(scope)
        val sums="COALESCE(SUM(CASE WHEN e.kind='INCOME' THEN e.amountMinor ELSE 0 END),0),COALESCE(SUM(CASE WHEN e.kind='EXPENSE' THEN e.amountMinor ELSE 0 END),0),COALESCE(SUM(CASE WHEN e.kind='REFUND' THEN e.amountMinor ELSE 0 END),0)"
        val budgetSums="COALESCE(SUM(CASE WHEN e.kind='EXPENSE' AND COALESCE(d.excludedFromBudget,0)=0 THEN e.amountMinor ELSE 0 END),0),COALESCE(SUM(CASE WHEN e.kind='REFUND' AND COALESCE(pd.excludedFromBudget,0)=0 THEN e.amountMinor ELSE 0 END),0)"
        val totals=connection.usePrepared("SELECT $sums,COUNT(*),$budgetSums"+FROM+filter.where) { s -> filter.bind(s);check(s.step());(0..5).map(s::getLong) }
        val categoryLabels=dao.categoryRows().associate { it.id to queryJson.decodeFromString<Category>(it.payload).name }
        val ledgerLabels=dao.ledgerRows().associate { it.id to queryJson.decodeFromString<Ledger>(it.payload).name }
        val categoryBreakdown=connection.usePrepared("SELECT e.categoryId,$sums"+FROM+filter.where+" AND e.kind IN ('EXPENSE','REFUND') GROUP BY e.categoryId ORDER BY 3 DESC,e.categoryId") { s ->
            filter.bind(s);buildList { while(s.step()) { val id=s.getText(0);add(FinancialAmountGroup(id,categoryLabels.getValue(id),s.getLong(2),s.getLong(3))) } }
        }
        val ledgerBreakdown=connection.usePrepared("SELECT e.ledgerId,$sums"+FROM+filter.where+" GROUP BY e.ledgerId ORDER BY 3-4 DESC,e.ledgerId") { s ->
            filter.bind(s);buildList { while(s.step()) { val id=s.getText(0);add(FinancialAmountGroup(id,ledgerLabels.getValue(id),s.getLong(2),s.getLong(3))) } }
        }.sortedByDescending { it.netExpense }
        val categories=categoryBreakdown.filter { it.expense>0 }.map { AmountGroup(it.id,it.label,it.expense) }
        val ledgers=ledgerBreakdown.map { AmountGroup(it.id,it.label,it.netExpense) }
        val start=LocalDate.parse(scope.startDate);val end=LocalDate.parse(scope.endDate);val daily=start.daysUntil(end)<=90
        val observed=connection.usePrepared("SELECT substr(e.occurrence,1,${if(daily) 10 else 7}),$sums"+FROM+filter.where+" GROUP BY 1 ORDER BY 1") { s ->
            filter.bind(s);buildMap { while(s.step()) { val period=s.getText(0);put(period,TrendPoint(period,s.getLong(1),s.getLong(2),s.getLong(3))) } }
        }
        val periods=if(start.year==1 || end.year==9999) observed.keys.sorted() else buildList {
            var date=if(daily) start else LocalDate(start.year,start.month,1)
            while(date<=end) { add(if(daily) date.toString() else date.toString().take(7));date=date.plus(1,if(daily) DateTimeUnit.DAY else DateTimeUnit.MONTH) }
        }
        StatisticsSnapshot(meta.contentVersion,scope,totals[0],totals[1],totals[2],emptyList(),categories,ledgers,periods.map { observed[it] ?: TrendPoint(it,0,0,0) },meta.datasetId,totals[3],categoryBreakdown,ledgerBreakdown,budgetExpense=totals[4],budgetRefunds=totals[5])
    }
}

internal suspend fun DawnYewDatabase.readCaptureDuplicates(fingerprint:String?,amountMinor:Long?,localDate:String?):CaptureDuplicateSummary {
    fingerprint?.let(::validateDigest)
    val hasDay=amountMinor!=null && localDate!=null
    if(hasDay) { Money.requireValid(amountMinor);validateOccurrence(localDate+"T00:00:00") }
    if(fingerprint==null && !hasDay) return CaptureDuplicateSummary(false,false,false)
    return useReaderConnection { connection -> connection.deferredTransaction {
        suspend fun exists(deleted:Boolean,image:Boolean,day:Boolean):Boolean {
            val clauses=mutableListOf<String>();val args=mutableListOf<String>()
            if(image && fingerprint!=null) { clauses += "json_extract(payload,'$.captureFingerprint')=?";args += fingerprint }
            if(day && hasDay) { clauses += "(kind='EXPENSE' AND amountMinor=? AND occurrence>=? AND occurrence<=?)";args += listOf(amountMinor.toString(),localDate+"T00:00:00",localDate+"T23:59:59") }
            if(clauses.isEmpty()) return false
            return connection.usePrepared("SELECT 1 FROM entries WHERE ("+clauses.joinToString(" OR ")+")"+(if(deleted) " AND deletionGroupId IS NOT NULL" else "")+" LIMIT 1") { statement -> args.forEachIndexed { i,v -> statement.bindText(i+1,v) };statement.step() }
        }
        CaptureDuplicateSummary(exists(false,true,false),exists(false,false,true),exists(true,true,true))
    } }
}


internal suspend fun DawnYewDatabase.readOperationOutcome(operationId:String):OperationOutcome? {
    validateId(operationId)
    return useReaderConnection { connection -> connection.deferredTransaction {
        val receipt=connection.usePrepared("SELECT payload FROM operation_receipts WHERE operationId=? LIMIT 1") { s -> s.bindText(1,operationId);if(s.step()) queryJson.decodeFromString<OperationReceipt>(s.getText(0)) else null }
        if(receipt!=null) OperationOutcome(operationId,receipt.commandType,receipt.resultIds)
        else {
            val batch = connection.usePrepared("SELECT payload FROM import_batches WHERE submissionId=? LIMIT 1") { s ->
                s.bindText(1, operationId)
                if (s.step()) queryJson.decodeFromString<ImportBatch>(s.getText(0)) else null
            }
            if (batch?.adapterId == DEBUG_BATCH_ADAPTER_ID) OperationOutcome(operationId, "GenerateDebugEntries", listOf(batch.id))
            else connection.usePrepared("SELECT id,kind FROM entries WHERE creationOperationId=? LIMIT 1") { s -> s.bindText(1,operationId);if(s.step()) OperationOutcome(operationId,if(s.getText(1)=="REFUND") "AddRefund" else "SaveEntry",listOf(s.getText(0))) else null }
        }
    } }
}
