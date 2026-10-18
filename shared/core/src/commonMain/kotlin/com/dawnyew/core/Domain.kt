package com.dawnyew.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Money {
    const val MAX = 99_999_999_999L
    fun parse(text: String): Long {
        if (!Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(text)) invalid("金额最多 9 位整数和 2 位小数")
        val parts = text.split('.')
        val value = parts[0].toLong() * 100 + parts.getOrElse(1) { "" }.padEnd(2, '0').toLong()
        requireValid(value)
        return value
    }
    fun requireValid(value: Long) { if (value !in 1..MAX) invalid("金额必须大于 0 且不超过 999,999,999.99 元") }
    fun format(value: Long): String {
        if (value == Long.MIN_VALUE) invalid("金额溢出")
        val abs = if (value < 0) -value else value
        return (if (value < 0) "-" else "") + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }
    fun add(a: Long, b: Long): Long {
        val r = a + b
        if (((a xor r) and (b xor r)) < 0) invalid("汇总金额超出支持范围")
        return r
    }
}

fun codePointCount(value: String): Int {
    var n = 0; var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c.isHighSurrogate()) {
            if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) invalid("文本含无效 Unicode 字符")
            i++
        } else if (c.isLowSurrogate()) invalid("文本含无效 Unicode 字符")
        n++; i++
    }
    return n
}
fun validateOccurrence(value: String) {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}").matches(value)) invalid("日期时间格式无效")
    try { val date = LocalDateTime.parse(value); if (date.year !in 1..9999) invalid("年份超出范围") }
    catch (_: Exception) { invalid("日期时间无效") }
}

class DomainEngine(private val env: DomainEnvironment) {
    private val json = Json { encodeDefaults = true }
    fun previewImport(state: LedgerState, plan: ImportPlan): Mutation = commitImport(state, plan, env)

    fun initialState(): LedgerState {
        val ledger = Ledger(env.id(), "日常")
        return LedgerState(env.id(), ledgers = listOf(ledger), categories = defaultCategories(ledger.id))
    }
    private fun defaultCategories(id: String): List<Category> =
        listOf("餐饮", "购物", "交通", "居住", "娱乐", "医疗", "教育", "其他支出").mapIndexed { i, n -> Category(env.id(), id, Direction.EXPENSE, n, sortOrder = i) } +
        listOf("工资", "奖金", "其他收入").mapIndexed { i, n -> Category(env.id(), id, Direction.INCOME, n, sortOrder = i) }
    private fun name(raw: String, limit: Int): String {
        val value = env.normalize(raw.trim())
        if (codePointCount(value) !in 1..limit || value.any { it.code < 32 }) invalid("名称需要 1–$limit 个字符，不能含控制字符")
        return value
    }
    private fun note(raw: String): String {
        if (codePointCount(raw) > 500 || raw.any { it == '\u0000' }) invalid("备注最多 500 个字符")
        return raw
    }
    private fun occurrence(raw: String): String {
        validateOccurrence(raw)
        if (raw > env.localNow()) invalid("不能记录未来发生的账单")
        return raw
    }
    fun apply(original: LedgerState, command: Command, operationId: String, expectedVersion: Long? = null): Mutation {
        validateId(operationId)
        val payload = Sha256.hex(json.encodeToString(command).encodeToByteArray())
        original.receipts.firstOrNull { it.operationId == operationId }?.let {
            if (it.payloadDigest != payload) conflict("重复操作标识对应了不同内容")
            return Mutation(original, it.resultIds)
        }
        if (command is Command.CommitImport && original.importBatches.any { it.submissionId == command.plan.submissionId }) return commitImport(original, command.plan, env)
        if (command is Command.GenerateDebugEntries && original.importBatches.any { it.submissionId == operationId }) return generateDebugEntries(original, command, operationId, payload, env)
        if (command is Command.UndoImport && original.importBatches.any { it.id == command.batchId && it.status == "UNDONE" }) return undoImport(original, command.batchId)
        if (expectedVersion != null && expectedVersion != original.contentVersion) conflict("数据已变化，请刷新后重试")
        var state = original
        var resultIds = emptyList<String>()
        val now = env.utcNow()
        fun ledger(id: String): Ledger = state.ledgers.find { it.id == id } ?: invalid("账本不存在")
        fun writable(id: String) { if (ledger(id).archived) invalid("请先取消归档该账本") }
        fun entry(id: String): Entry = state.entries.find { it.id == id } ?: invalid("账单不存在")
        fun category(id: String, ledgerId: String, kind: EntryKind, allowDisabled: Boolean = false): Category {
            val c = state.categories.find { it.id == id } ?: invalid("分类不存在")
            val direction = if (kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
            if (c.ledgerId != ledgerId || c.direction != direction) invalid("分类与账本或收支类型不一致")
            if (!c.enabled && !allowDisabled) invalid("该分类已停用")
            return c
        }
        fun activeEntry(id: String): Entry { val e = entry(id); if (!e.active) invalid("账单已在回收站"); writable(e.ledgerId); return e }
        fun updateEntries(changes: List<Entry>) { val map = changes.associateBy { it.id }; state = state.copy(entries = state.entries.map { map[it.id] ?: it } + changes.filter { c -> state.entries.none { it.id == c.id } }) }
        fun refundSum(parent: String, excluding: String? = null): Long = state.entries.filter { it.active && it.parentExpenseId == parent && it.id != excluding }.fold(0L) { sum, e -> Money.add(sum, e.amountMinor) }
        when (command) {
            is Command.CreateLedger -> {
                val n = name(command.name, 30)
                if (state.ledgers.any { it.name == n }) invalid("账本名称已存在")
                val l = Ledger(env.id(), n, sortOrder = (state.ledgers.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                state = state.copy(ledgers = state.ledgers + l, categories = state.categories + defaultCategories(l.id)); resultIds = listOf(l.id)
            }
            is Command.UpdateLedger -> {
                val l = ledger(command.id); val n = name(command.name, 30)
                if (state.ledgers.any { it.id != l.id && it.name == n }) invalid("账本名称已存在")
                if (command.archived && !l.archived && state.ledgers.count { !it.archived } == 1) invalid("至少保留一个可用账本")
                state = state.copy(ledgers = state.ledgers.map { if (it.id == l.id) it.copy(name=n, archived=command.archived, sortOrder=command.sortOrder, revision=Money.add(it.revision,1)) else it }); resultIds=listOf(l.id)
            }
            is Command.DeleteLedger -> {
                val l = ledger(command.id)
                if (state.entries.any { it.ledgerId == l.id }) invalid("账本含有账单或回收站记录，请使用归档")
                if (!l.archived && state.ledgers.count { !it.archived } == 1) invalid("不能删除最后一个可用账本")
                state=state.copy(ledgers=state.ledgers.filterNot { it.id==l.id }, categories=state.categories.filterNot { it.ledgerId==l.id }, budgets=state.budgets.filterNot { it.ledgerId==l.id })
            }
            is Command.SaveCategory -> {
                writable(command.ledgerId); val n=name(command.name,20)
                val existing=command.id?.let { id -> state.categories.find { it.id==id } ?: invalid("分类不存在") }
                if (existing != null && (existing.ledgerId!=command.ledgerId || existing.direction!=command.direction)) invalid("不能改变分类所属账本或类型")
                if (state.categories.any { it.id!=command.id && it.ledgerId==command.ledgerId && it.direction==command.direction && it.name==n }) invalid("分类名称已存在")
                val c=Category(existing?.id ?: env.id(),command.ledgerId,command.direction,n,command.enabled,command.sortOrder,Money.add(existing?.revision ?: 0,1))
                state=state.copy(categories=state.categories.filterNot { it.id==c.id }+c); resultIds=listOf(c.id)
            }
            is Command.DeleteCategory -> {
                val c=state.categories.find { it.id==command.id } ?: invalid("分类不存在"); writable(c.ledgerId)
                if (state.entries.any { it.categoryId==c.id }) invalid("已有账单引用该分类，可改为停用")
                state=state.copy(categories=state.categories.filterNot { it.id==c.id })
            }
            is Command.SaveEntry -> {
                writable(command.ledgerId); Money.requireValid(command.amountMinor); note(command.note)
                val old=command.id?.let { activeEntry(it) }
                if (old?.occurrence == command.occurrence) validateOccurrence(command.occurrence) else occurrence(command.occurrence)
                if (old != null && old.revision!=command.expectedRevision) conflict("账单已更新，请重新打开")
                if (old?.kind==EntryKind.REFUND) {
                    if (command.kind!=EntryKind.REFUND || command.ledgerId!=old.ledgerId || command.categoryId!=old.categoryId) invalid("退款的账本和分类跟随原支出")
                    val parent=activeEntry(old.parentExpenseId!!)
                    if (command.occurrence<parent.occurrence || Money.add(refundSum(parent.id,old.id),command.amountMinor)>parent.amountMinor) invalid("退款时间或累计金额不符合原支出")
                } else if (command.kind==EntryKind.REFUND) invalid("请从原支出添加退款")
                category(command.categoryId,command.ledgerId,command.kind, old?.categoryId==command.categoryId && old.ledgerId==command.ledgerId)
                val linked=old?.let { state.entries.filter { r -> r.parentExpenseId==it.id } }.orEmpty()
                if (linked.isNotEmpty() && command.kind!=EntryKind.EXPENSE) invalid("有关联退款的支出不能改为收入")
                val active=linked.filter { it.active }
                if (active.fold(0L) { a,e -> Money.add(a,e.amountMinor) }>command.amountMinor || active.any { it.occurrence<command.occurrence }) invalid("修改后金额或时间与退款冲突")
                val e=Entry(old?.id ?: env.id(),command.ledgerId,command.kind,command.amountMinor,command.categoryId,command.occurrence,command.note,old?.parentExpenseId,
                    revision=Money.add(old?.revision ?: 0,1),createdAt=old?.createdAt ?: now,updatedAt=now,originKind=old?.originKind ?: command.originKind,creationOperationId=old?.creationOperationId ?: operationId,
                    captureFingerprint=old?.captureFingerprint ?: command.captureFingerprint,captureEngineVersion=old?.captureEngineVersion ?: command.captureEngineVersion)
                updateEntries(listOf(e)+active.map { it.copy(ledgerId=e.ledgerId,categoryId=e.categoryId,revision=Money.add(it.revision,1),updatedAt=now) }); resultIds=listOf(e.id)
            }
            is Command.AddRefund -> {
                val parent=activeEntry(command.expenseId)
                if (parent.kind!=EntryKind.EXPENSE) invalid("仅支出可以退款")
                Money.requireValid(command.amountMinor); occurrence(command.occurrence); note(command.note)
                if (command.occurrence<parent.occurrence) invalid("退款不能早于原支出")
                if (Money.add(refundSum(parent.id),command.amountMinor)>parent.amountMinor) invalid("累计退款不能超过原支出")
                val r=Entry(env.id(),parent.ledgerId,EntryKind.REFUND,command.amountMinor,parent.categoryId,command.occurrence,command.note,parent.id,createdAt=now,updatedAt=now,creationOperationId=operationId)
                updateEntries(listOf(r));resultIds=listOf(r.id)
            }
            is Command.MoveEntry -> {
                val e=activeEntry(command.id);if(e.kind==EntryKind.REFUND) invalid("退款请随原支出移动")
                writable(command.ledgerId);category(command.categoryId,command.ledgerId,e.kind)
                updateEntries(state.entries.filter { it.id==e.id || (it.active && it.parentExpenseId==e.id) }.map { it.copy(ledgerId=command.ledgerId,categoryId=command.categoryId,revision=Money.add(it.revision,1),updatedAt=now) });resultIds=listOf(e.id)
            }
            is Command.SoftDelete -> {
                val e=activeEntry(command.id);val group=DeletionGroup(env.id(),now,e.id)
                val members=state.entries.filter { it.id==e.id || (e.kind==EntryKind.EXPENSE && it.active && it.parentExpenseId==e.id) }
                updateEntries(members.map { it.copy(deletionGroupId=group.id,revision=Money.add(it.revision,1),updatedAt=now) })
                state=state.copy(deletionGroups=state.deletionGroups+group);resultIds=listOf(group.id)
            }
            is Command.RestoreGroup -> {
                val members=state.entries.filter { it.deletionGroupId==command.groupId };if(members.isEmpty()) invalid("回收站记录不存在")
                val updated=members.map { e ->
                    val parent=if(e.kind==EntryKind.REFUND) entry(e.parentExpenseId!!) else null
                    if(parent!=null && !parent.active && members.none { it.id==parent.id }) invalid("请先恢复原支出")
                    val restored=e.copy(ledgerId=parent?.ledgerId ?: e.ledgerId,categoryId=parent?.categoryId ?: e.categoryId,deletionGroupId=null,revision=Money.add(e.revision,1),updatedAt=now)
                    writable(restored.ledgerId); category(restored.categoryId,restored.ledgerId,restored.kind,true);restored
                }
                updateEntries(updated);validateState(state, env::normalize);resultIds=updated.map { it.id }
            }
            is Command.Purge -> {
                val e=entry(command.id);if(e.active) invalid("请先将账单移入回收站")
                val ids=state.entries.filter { it.id==e.id || it.parentExpenseId==e.id }.map { it.id }.toSet()
                if(state.entries.any { it.id in ids && it.active }) invalid("仍有关联的有效退款")
                state=state.copy(entries=state.entries.filterNot { it.id in ids },sourceIdentities=state.sourceIdentities.filterNot { it.entryId in ids },importMembers=state.importMembers.map { if(it.entryId in ids) it.copy(state="REMOVED") else it });resultIds=ids.toList()
            }
            is Command.SetBudget -> {
                writable(command.ledgerId)
                if(!Regex("[0-9]{4}-[0-9]{2}").matches(command.yearMonth)) invalid("月份格式无效")
                try { LocalDate.parse(command.yearMonth+"-01") } catch(_:Exception) { invalid("月份无效") }
                command.amountMinor?.let { Money.requireValid(it) }
                val old=state.budgets.find { it.ledgerId==command.ledgerId && it.yearMonth==command.yearMonth }
                state=state.copy(budgets=state.budgets.filterNot { it.ledgerId==command.ledgerId && it.yearMonth==command.yearMonth } + listOfNotNull(command.amountMinor?.let { MonthlyBudget(command.ledgerId,command.yearMonth,it,Money.add(old?.revision ?: 0,1)) }))
            }
            is Command.SetPreference -> {
                if(command.key !in setOf("theme","lastLedgerId","lastPeriod","lastBackupAt")) invalid("不支持的偏好设置")
                state=state.copy(preferences=state.preferences.filterNot { it.key==command.key }+UserPreference(command.key,command.value))
            }
            is Command.CommitImport -> { val mutation = commitImport(state, command.plan, env); state = mutation.state; resultIds = mutation.resultIds }
            is Command.GenerateDebugEntries -> { val mutation = generateDebugEntries(state, command, operationId, payload, env); state = mutation.state; resultIds = mutation.resultIds }
            is Command.UndoImport -> { val mutation = undoImport(state, command.batchId); state = mutation.state; resultIds = mutation.resultIds }
            Command.ClearAll -> state=initialState().copy(contentVersion=original.contentVersion)
        }
        val receipt=OperationReceipt(operationId,command::class.simpleName ?: "Command",payload,resultIds,now)
        state=state.copy(contentVersion=Money.add(original.contentVersion,1),receipts=state.receipts+receipt)
        validateState(state, env::normalize)
        return Mutation(state,resultIds)
    }
}
