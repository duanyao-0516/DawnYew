package com.dawnyew.storage

import androidx.room.*
import androidx.sqlite.SQLiteStatement
import com.dawnyew.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString

/** Reopen and verify immediately before pointer publication; no whole LedgerState is materialized. */
suspend fun DawnYewDatabase.verifyCandidate(expected:CandidateSummary?=null):CandidateSummary = useReaderConnection { connection ->
    connection.deferredTransaction {
        inspectCandidate(connection).also { actual ->
            if(expected!=null && actual!=expected) invalid("候选数据库内容已变化，请重新读取备份")
        }
    }
}

internal suspend fun inspectCandidate(connection:Transactor):CandidateSummary {
    connection.usePrepared("PRAGMA integrity_check") { statement ->
        if(!statement.step() || statement.getText(0)!="ok" || statement.step()) invalid("候选数据库完整性检查失败")
    }
    val datasetId=connection.usePrepared("SELECT singleton,datasetId,contentVersion FROM metadata") { statement ->
        if(!statement.step() || statement.getLong(0)!=1L || statement.getLong(2)!=1L) invalid("恢复候选元信息无效")
        statement.getText(1).also { validateId(it);if(statement.step()) invalid("恢复候选元信息重复") }
    }
    connection.usePrepared("SELECT 1 FROM operation_receipts LIMIT 1") { if(it.step()) invalid("恢复候选不能包含设备临时回执") }
    val hash=Sha256().update(("DawnYew candidate v1\n$datasetId\n").encodeToByteArray())
    val counts=linkedMapOf<String,Long>()
    for((type,table) in entityTables) {
        currentCoroutineContext().ensureActive()
        val count=connection.usePrepared("SELECT COUNT(*),COUNT(DISTINCT position),MIN(position),MAX(position) FROM $table") { statement ->
            check(statement.step());val size=statement.getLong(0)
            if(size>0 && (statement.getLong(1)!=size || statement.getLong(2)!=0L || statement.getLong(3)!=size-1)) invalid("候选记录位置无效")
            size
        }
        counts[type]=count;hash.update(("$type\n$count\n").encodeToByteArray())
        val keys=when(type) { "budgets"->listOf("ledgerId","yearMonth");"importMembers"->listOf("batchId","entryId");"preferences"->listOf("key");else->listOf("id") }
        var cursor:List<String>?=null
        var visited=0L
        while(true) {
            currentCoroutineContext().ensureActive()
            val where=if(cursor==null) "" else if(keys.size==1) " WHERE ${keys[0]}>?" else " WHERE ${keys[0]}>? OR (${keys[0]}=? AND ${keys[1]}>?)"
            val page=connection.usePrepared("SELECT * FROM $table$where ORDER BY ${keys.joinToString(",")} LIMIT 128") { statement ->
                cursor?.let { c->statement.bindText(1,c[0]);if(keys.size==2) {statement.bindText(2,c[0]);statement.bindText(3,c[1])} }
                val columns=(0 until statement.getColumnCount()).associateBy { statement.getColumnName(it) }
                buildList {
                    while(statement.step()) {
                        val payload=statement.getText(columns.getValue("payload"))
                        val expected=recordColumns(type,payload)
                        expected.forEach { (column,value)->verifyColumn(statement,columns.getValue(column),value) }
                        add(keys.map { statement.getText(columns.getValue(it)) } to payload)
                    }
                }
            }
            if(page.isEmpty()) break
            page.forEach { (_,payload)->hash.update(payload.encodeToByteArray());hash.update(byteArrayOf(10)) }
            visited=Money.add(visited,page.size.toLong());cursor=page.last().first
        }
        if(visited!=count) invalid("候选快照计数不一致")
    }
    validateCandidateRelations(connection)
    val active=connection.usePrepared("SELECT COUNT(*) FROM entries WHERE deletionGroupId IS NULL") { check(it.step());it.getLong(0) }
    return CandidateSummary(datasetId,counts,active,counts.getValue("entries")-active,hash.finishHex())
}

private fun verifyColumn(statement:SQLiteStatement,index:Int,expected:Any?) {
    val matches=when(expected) {
        null->statement.isNull(index)
        is Long->!statement.isNull(index) && statement.getLong(index)==expected
        is String->!statement.isNull(index) && statement.getText(index)==expected
        else->error("unsupported candidate column")
    }
    if(!matches) invalid("候选数据库字段与内容不一致")
}

private inline fun <reified T> readRecord(payload:String,validate:(T)->Unit):T {
    val value=streamJson.decodeFromString<T>(payload)
    validate(value)
    if(streamJson.encodeToString(value)!=payload) invalid("候选记录字段不完整或编码已变化")
    return value
}

private fun recordColumns(type:String,payload:String):Map<String,Any?> = when(type) {
    "ledgers"->readRecord<Ledger>(payload) { validateEntityBatch(LedgerEntityBatch(ledgers=listOf(it))) }.let {
        mapOf("id" to it.id,"normalizedName" to normalizeTextNfc(it.name.trim()),"currency" to it.currency,"archived" to if(it.archived) 1L else 0L)
    }
    "categories"->readRecord<Category>(payload) { validateEntityBatch(LedgerEntityBatch(categories=listOf(it))) }.let {
        mapOf("id" to it.id,"ledgerId" to it.ledgerId,"direction" to it.direction.name,"normalizedName" to normalizeTextNfc(it.name.trim()))
    }
    "entries"->readRecord<Entry>(payload) { validateEntityBatch(LedgerEntityBatch(entries=listOf(it))) }.let {
        mapOf("id" to it.id,"ledgerId" to it.ledgerId,"kind" to it.kind.name,"direction" to if(it.kind==EntryKind.INCOME) "INCOME" else "EXPENSE",
            "amountMinor" to it.amountMinor,"categoryId" to it.categoryId,"parentExpenseId" to it.parentExpenseId,"deletionGroupId" to it.deletionGroupId,
            "creationOperationId" to it.creationOperationId,"occurrence" to it.occurrence,"noteSearch" to literalSearchKey(it.note))
    }
    "deletionGroups"->readRecord<DeletionGroup>(payload) { validateEntityBatch(LedgerEntityBatch(deletionGroups=listOf(it))) }.let { mapOf("id" to it.id) }
    "budgets"->readRecord<MonthlyBudget>(payload) { validateEntityBatch(LedgerEntityBatch(budgets=listOf(it))) }.let {
        mapOf("ledgerId" to it.ledgerId,"yearMonth" to it.yearMonth,"amountMinor" to it.amountMinor)
    }
    "sourceIdentities"->readRecord<SourceIdentity>(payload) { validateEntityBatch(LedgerEntityBatch(sourceIdentities=listOf(it))) }.let {
        mapOf("id" to it.id,"entryId" to it.entryId,"provider" to it.provider,"scope" to it.scope,"eventKind" to it.eventKind,"externalId" to it.externalId)
    }
    "importBatches"->readRecord<ImportBatch>(payload) { validateEntityBatch(LedgerEntityBatch(importBatches=listOf(it))) }.let { mapOf("id" to it.id,"submissionId" to it.submissionId) }
    "importMembers"->readRecord<ImportMember>(payload) { validateEntityBatch(LedgerEntityBatch(importMembers=listOf(it))) }.let {
        mapOf("batchId" to it.batchId,"entryId" to it.entryId,"liveEntryId" to it.entryId.takeIf { _->it.state=="PRESENT" },"memberState" to it.state)
    }
    "preferences"->readRecord<UserPreference>(payload) { validateEntityBatch(LedgerEntityBatch(preferences=listOf(it))) }.let { mapOf("key" to it.key) }
    else->invalid("未知候选实体")
}
