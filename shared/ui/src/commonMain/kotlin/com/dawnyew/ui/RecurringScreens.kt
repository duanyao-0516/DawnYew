package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.RecurringUiState

@Composable internal fun RecurringPage(s: LedgerState, selectedLedger: String?, selectedId: String?, onSelect: (String?) -> Unit, busy: Boolean, now: String, newId: () -> String, dispatch: FinanceDispatch, onOpenActivity: (ActivityFamily, String) -> Unit, processing: RecurringUiState, onContinue: () -> Unit) {
    var form by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<RecurringPlan?>(null) }; var archived by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<RecurringPlan?>(null) }
    val plans = s.recurringPlans.filter { (selectedLedger == null || it.ledgerId == selectedLedger) && (archived || !it.archived) }
    val selected = s.recurringPlans.find { it.id == selectedId && (selectedLedger == null || it.ledgerId == selectedLedger) }
    if (selectedId == null) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Button(onClick = { edit = null; form = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("新增周期") } }
        item { FinanceToggle("显示归档", value = archived) { archived = it } }
        if (processing.running || processing.failures.isNotEmpty() || processing.blockedByRecovery || processing.hasMore) item { DashboardCard {
            CardHeading("到期处理", if (processing.running) "正在处理" else "本次已生成 ${processing.generated} 笔")
            if (processing.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (processing.blockedByRecovery) Text("有一笔保存结果待确认，请先处理页面中的保存回执。", color = MaterialTheme.colorScheme.error)
            processing.failures.forEach { failure -> TextButton(onClick = { onSelect(failure.planId) }, modifier = Modifier.fillMaxWidth()) { Text("${failure.name}：${failure.message}", color = MaterialTheme.colorScheme.error) } }
            if (processing.limitReached) Text("本次已处理100期，仍有到期项目。可继续下一批。", style = MaterialTheme.typography.bodySmall)
            if (processing.hasMore && !processing.running) OutlinedButton(onClick = onContinue, enabled = !busy && !processing.blockedByRecovery, modifier = Modifier.fillMaxWidth()) { Text(if (processing.limitReached) "继续处理（最多100期）" else "重试到期项目") }
        } }
        if (plans.isEmpty()) item { EmptyState("暂无周期计划", "每天、每周或每月固定的收支，在到期后记入账本。") }
        items(plans, key = { it.id }) { plan -> DashboardCard {
            CardHeading(plan.name, "${frequencyName(plan.frequency)} · ${activityKindName(plan.kind)}", { onSelect(plan.id) })
            FinanceAmount(plan.amountMinor, color = if (plan.kind == ActivityKind.EXPENSE) FinanceColors.expense else if (plan.kind == ActivityKind.TRANSFER) FinanceColors.transfer else MaterialTheme.colorScheme.primary)
            Text(recurringStatus(plan), style = MaterialTheme.typography.bodySmall)
            if (!plan.archived) runCatching { recurringNextOccurrence(plan) }.getOrNull()?.let { Text("下次 ${it.replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
    } else if (selected == null) MissingBusinessDetail(false) { onSelect(null) }
    else {
        val plan = selected
        val occurrences = remember(s.contentVersion, plan.id) { s.recurringOccurrences.filter { it.planId == plan.id }.sortedByDescending { it.index } }
        val next = runCatching { recurringNextOccurrence(plan) }.getOrNull()
        val canExecute = !plan.paused && !plan.archived && next != null && next <= now
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DashboardCard {
                CardHeading(plan.name, recurringStatus(plan))
                Text("每期${activityKindName(plan.kind)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FinanceAmount(plan.amountMinor, large = true, color = if (plan.kind == ActivityKind.EXPENSE) FinanceColors.expense else MaterialTheme.colorScheme.primary)
                Text("${frequencyName(plan.frequency)} · " + (plan.totalOccurrences?.let { "共 $it 期" } ?: "持续执行"), style = MaterialTheme.typography.bodySmall)
                if (next != null) DetailFact("下一期", next.replace('T', ' '))
                else Text("全部期次已完成", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dispatch(FinanceAction.ExecuteRecurring(plan.id, plan.revision), newId()) {} }, enabled = !busy && canExecute, modifier = Modifier.weight(1f)) { Text("执行当前期") }
                    OutlinedButton(onClick = { dispatch(FinanceAction.ExecuteRecurring(plan.id, plan.revision, skip = true), newId()) {} }, enabled = !busy && canExecute, modifier = Modifier.weight(1f)) { Text("跳过当前期") }
                }
                if (!canExecute && next != null && !plan.paused && !plan.archived) Text("到期后可执行或跳过", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item { DashboardCard {
                DetailFact("账本", s.ledgers.find { it.id == plan.ledgerId }?.name.orEmpty())
                DetailFact("首次日期", plan.anchor.replace('T', ' '))
                if (plan.kind == ActivityKind.TRANSFER) {
                    DetailFact("转出账户", s.accountDisplayName(plan.fromAccountId) ?: "无账户")
                    DetailFact("转入账户", s.accountDisplayName(plan.toAccountId) ?: "无账户")
                } else {
                    DetailFact("分类", s.categories.find { it.id == plan.categoryId }?.name.orEmpty())
                    DetailFact("账户", s.accountDisplayName(plan.accountId) ?: "无账户")
                }
                DetailFact("交易状态", (if (plan.recorded) "已入账" else "未入账") + (if (plan.excludedFromBudget) " · 不计入预算" else ""))
                if (plan.note.isNotBlank()) DetailFact("备注", plan.note)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edit = plan; form = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑计划") }
                    OutlinedButton(onClick = { dispatch(FinanceAction.SaveRecurringPlan(plan.copy(paused = !plan.paused), plan.revision), newId()) {} }, enabled = !busy && !plan.archived && next != null, modifier = Modifier.weight(1f)) { Text(if (plan.paused) "继续计划" else "暂停计划") }
                }
            } }
            item { DashboardCard { CardHeading("执行记录", "已生成 ${occurrences.count { it.status == RecurringOccurrenceStatus.GENERATED }} · 已跳过 ${occurrences.count { it.status == RecurringOccurrenceStatus.SKIPPED }}")
                if (occurrences.isEmpty()) Text("还没有执行记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            items(occurrences, key = { it.id }) { occurrence ->
                val family = if (occurrence.entryId != null) ActivityFamily.ENTRY else ActivityFamily.TRANSFER
                val resultId = occurrence.entryId ?: occurrence.transferId
                DashboardCard { BusinessRecordRow("第 ${occurrence.index + 1} 期", occurrence.scheduledAt.replace('T', ' ') + " · " + if (occurrence.status == RecurringOccurrenceStatus.SKIPPED) "已跳过" else if (resultId == null) "流水已清理" else "已生成流水", onClick = resultId?.let { { onOpenActivity(family, it) } }) }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dispatch(FinanceAction.SaveRecurringPlan(plan.copy(archived = !plan.archived), plan.revision), newId()) {} }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (plan.archived) "取消归档" else "归档计划") }
                if (occurrences.isEmpty()) TextButton(onClick = { delete = plan }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("删除计划", color = MaterialTheme.colorScheme.error) }
            } }
        }
    }
    delete?.let { plan -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除周期计划？") }, text = { Text("计划尚无执行记录，删除后不再处理到期交易。") }, confirmButton = { TextButton(onClick = { dispatch(FinanceAction.DeleteRecurringPlan(plan.id, plan.revision), newId()) { delete = null; onSelect(null) } }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
    if (form) RecurringEditor(s, edit, selectedLedger, now, busy, newId, dispatch) { form = false }
}

private fun recurringStatus(plan: RecurringPlan): String = when { plan.archived -> "已归档"; plan.paused -> "已暂停"; plan.totalOccurrences?.let { plan.nextIndex >= it } == true -> "已完成"; else -> "进行中" }

@Composable private fun RecurringEditor(s: LedgerState, original: RecurringPlan?, selectedLedger: String?, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }
    var ledger by remember { mutableStateOf(original?.ledgerId ?: defaultLedger(s, selectedLedger)) }; var name by remember { mutableStateOf(original?.name.orEmpty()) }; var kind by remember { mutableStateOf(original?.kind ?: ActivityKind.EXPENSE) }
    var amount by remember { mutableStateOf(original?.let { Money.format(it.amountMinor) }.orEmpty()) }; var anchor by remember { mutableStateOf(original?.anchor ?: now) }; var frequency by remember { mutableStateOf(original?.frequency ?: RecurringFrequency.MONTHLY) }; var total by remember { mutableStateOf(original?.totalOccurrences?.toString().orEmpty()) }
    var category by remember { mutableStateOf(original?.categoryId) }; var account by remember { mutableStateOf(original?.accountId) }; var from by remember { mutableStateOf(original?.fromAccountId) }; var to by remember { mutableStateOf(original?.toAccountId) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var recorded by remember { mutableStateOf(original?.recorded ?: true) }; var excluded by remember { mutableStateOf(original?.excludedFromBudget ?: false) }; var dirty by remember { mutableStateOf(false) }
    val frozen = (original?.nextIndex ?: 0) > 0
    FinanceFormDialog(if (original == null) "新增周期" else "编辑周期", busy, dirty, onDismiss, onSave = {
        val transfer = kind == ActivityKind.TRANSFER
        val value = RecurringPlan(id, ledger, name, kind, Money.parse(amount), anchor, frequency, total.ifBlank { null }?.toIntOrNull().also { if (total.isNotBlank() && it == null) invalid("次数应为正整数") }, original?.nextIndex ?: 0, original?.paused ?: false, original?.archived ?: false, if (transfer) null else category, if (transfer) null else account, if (transfer) from else null, if (transfer) to else null, note, recorded, excluded && kind == ActivityKind.EXPENSE, original?.reconciled ?: false, if (transfer) null else original?.projectId, if (transfer) null else original?.merchantId, original?.revision ?: 1)
        dispatch(FinanceAction.SaveRecurringPlan(value, original?.revision), operationId, onDismiss)
    }) {
        if (original == null) LedgerChoice(s, ledger, busy) { ledger = it; category = null; account = null; from = null; to = null; dirty = true }
        FinanceTextField(name, { name = it; dirty = true }, "计划名称", busy)
        FinanceChoice("类型", kind, listOf(ActivityKind.EXPENSE, ActivityKind.INCOME, ActivityKind.TRANSFER).map { it to activityKindName(it) }, !busy) { kind = it; category = null; if (it != ActivityKind.EXPENSE) excluded = false; dirty = true }
        FinanceTextField(amount, { amount = it; dirty = true }, "每期金额（元）", busy, money = true)
        if (kind == ActivityKind.TRANSFER) {
            AccountChoice(s, ledger, "转出账户", from, busy, optional = false) { from = it; dirty = true }
            AccountChoice(s, ledger, "转入账户", to, busy, optional = false) { to = it; dirty = true }
        } else {
            val direction = if (kind == ActivityKind.INCOME) Direction.INCOME else Direction.EXPENSE
            FinanceChoice("分类", category, s.categories.filter { it.ledgerId == ledger && it.direction == direction && (it.enabled || it.id == category) }.map { it.id to it.name }, !busy) { category = it; dirty = true }
            AccountChoice(s, ledger, "账户", account, busy) { account = it; dirty = true }
        }
        FinanceTextField(anchor, { anchor = it; dirty = true }, "首次日期 YYYY-MM-DDTHH:mm:ss", busy || frozen, supporting = if (frozen) "已处理期次后，首次日期与频率不能修改" else "保存时首次已到期，会生成首笔流水")
        FinanceChoice("频率", frequency, RecurringFrequency.entries.map { it to frequencyName(it) }, !busy && !frozen) { frequency = it; dirty = true }
        FinanceTextField(total, { total = it; dirty = true }, "总次数（留空持续执行）", busy)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
        FinanceToggle("已入账", value = recorded, enabled = !busy) { recorded = it; dirty = true }
        if (kind == ActivityKind.EXPENSE) FinanceToggle("不计入预算", value = excluded, enabled = !busy) { excluded = it; dirty = true }
    }
}
