package com.dawnyew.core

/** Bound to a single dataset, committed version, and exact query; stale cursors require a fresh first page. */
data class EntryCursor(val occurrence:String,val id:String,val datasetId:String,val contentVersion:Long,val scope:QueryScope)
data class EntryPage(val entries:List<Entry>,val nextCursor:EntryCursor?,val datasetId:String,val contentVersion:Long)

fun literalSearchKey(value:String):String = value.map { it.uppercaseChar().lowercaseChar() }.joinToString("")
fun validateQueryScope(scope:QueryScope) {
    validateOccurrence(scope.startDate+"T00:00:00");validateOccurrence(scope.endDate+"T00:00:00")
    if(scope.startDate>scope.endDate) invalid("开始日期不能晚于结束日期")
    codePointCount(scope.keyword)
}
fun validateCursor(scope:QueryScope,cursor:EntryCursor?,datasetId:String,contentVersion:Long,limit:Int) {
    validateQueryScope(scope)
    if(limit !in 1..200) invalid("分页数量必须在1到200之间")
    if(cursor!=null) {
        validateOccurrence(cursor.occurrence);validateId(cursor.id)
        if(cursor.datasetId!=datasetId || cursor.contentVersion!=contentVersion || cursor.scope!=scope) conflict("分页数据或筛选已变化，请重新加载")
    }
}
/** Default adapter for in-memory test repositories. Production storage overrides this with SQL keyset queries. */
fun pageSnapshot(state:LedgerState,scope:QueryScope,cursor:EntryCursor?,limit:Int):EntryPage {
    validateCursor(scope,cursor,state.datasetId,state.contentVersion,limit)
    val filtered=Statistics.calculate(state,scope).entries.filter { cursor==null || it.occurrence<cursor.occurrence || it.occurrence==cursor.occurrence && it.id<cursor.id }
    val entries=filtered.take(limit)
    val next=entries.lastOrNull()?.takeIf { filtered.size>limit }?.let { EntryCursor(it.occurrence,it.id,state.datasetId,state.contentVersion,scope) }
    return EntryPage(entries,next,state.datasetId,state.contentVersion)
}

data class CaptureDuplicateSummary(val hasSameImage:Boolean,val hasSameDayAmount:Boolean,val hasDeletedMatch:Boolean)
