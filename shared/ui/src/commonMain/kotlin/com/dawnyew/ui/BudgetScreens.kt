package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.StatisticsPageUi

internal typealias CommandDispatch = (Command, String, () -> Unit) -> Unit

@Composable internal fun BudgetPage(s: LedgerState, selectedLedger: String?, selectedMonth: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, command: CommandDispatch, statistics: StatisticsPageUi, onQuery: (QueryScope) -> Unit, onActivity: (ActivityScope) -> Unit) {
    var ledger by remember { mutableStateOf(defaultLedger(s, selectedLedger)) }; var month by remember { mutableStateOf(selectedMonth) }
    var totalDialog by remember { mutableStateOf(false) }; var allocationDialog by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<BudgetAllocation?>(null) }; var transfer by remember { mutableStateOf(false) }; var deleteTotal by remember { mutableStateOf(false) }
    val range = runCatching { monthRange(month) }.getOrNull()
    val query = range?.let { QueryScope(setOf(ledger), it.first, it.second) }
    LaunchedEffect(query, s.datasetId, s.contentVersion) { if (query != null && ledger.isNotBlank()) onQuery(query) }
    val snapshot = statistics.snapshot?.takeIf { !statistics.loading && statistics.error == null && it.scope == query && statistics.scope == query }
    val budget = s.budgets.find { it.ledgerId == ledger && it.yearMonth == month }
    val allocations = s.budgetAllocations.filter { it.ledgerId == ledger && it.yearMonth == month }
    val history = s.budgetReallocations.filter { it.ledgerId == ledger && it.yearMonth == month }.sortedByDescending { it.createdAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DashboardCard {
            LedgerChoice(s, ledger, busy) { ledger = it }
            FinanceTextField(month, { month = it }, "月份 YYYY-MM", busy)
            if (range == null) Text("请输入有效月份", color = MaterialTheme.colorScheme.error)
        } }
        item { DashboardCard {
            CardHeading("月度总预算", month) { totalDialog = true }
            if (statistics.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            statistics.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { if (query != null) onQuery(query) }) { Text("重试") } }
            if (budget == null) Text("为这个月设置支出额度") else {
                val used = snapshot?.budgetUsed
                if (used == null) Text("正在读取预算消费…") else {
                    FinanceRing((used.toFloat() / budget.amountMinor).coerceIn(0f, 1f), if (used > budget.amountMinor) "超出" else "剩余", Money.format(kotlin.math.abs(budget.amountMinor - used)), "预算 ${Money.format(budget.amountMinor)}")
                    Text("计预算净支出 ${Money.format(used)}")
                }
                TextButton(onClick = { if (query != null) onActivity(query.toActivityScope().copy(kinds = setOf(ActivityKind.EXPENSE, ActivityKind.REFUND), excludedFromBudget = false)) }, enabled = snapshot != null) { Text("查看预算消费") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { totalDialog = true }, enabled = !busy) { Text("修改总预算") }
                    TextButton(onClick = { deleteTotal = true }, enabled = !busy) { Text("删除整月预算") }
                }
            }
            if (budget == null) Button(onClick = { totalDialog = true }, enabled = !busy && range != null) { Text("设置预算") }
        } }
        if (budget != null) {
            item { Row { Text("分类子预算", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = { edit = null; allocationDialog = true }, enabled = !busy) { Text("新增") } } }
            items(allocations, key = { it.id }) { allocation -> DashboardCard {
                CardHeading(s.categories.find { it.id == allocation.categoryId }?.name ?: "分类", onClick = { edit = allocation; allocationDialog = true })
                FinanceAmount(allocation.amountMinor)
                TextButton(onClick = { if (query != null) onActivity(query.toActivityScope().copy(categoryIds = setOf(allocation.categoryId), excludedFromBudget = false)) }, enabled = query != null) { Text("查看消费流水") }
                TextButton(onClick = { dispatch(FinanceAction.DeleteBudgetAllocation(allocation.id, allocation.revision), newId()) {} }, enabled = !busy && history.none { it.fromAllocationId == allocation.id || it.toAllocationId == allocation.id }) { Text("删除子预算") }
            } }
            item { OutlinedButton(onClick = { transfer = true }, enabled = !busy && allocations.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("同月预算额度转移") } }
            if (history.isNotEmpty()) item { Text("额度转移记录", style = MaterialTheme.typography.titleLarge) }
            items(history, key = { it.id }) { item -> DashboardCard {
                val from = allocations.find { it.id == item.fromAllocationId }; val to = allocations.find { it.id == item.toAllocationId }
                Text("${s.categories.find { it.id == from?.categoryId }?.name ?: "分类"} → ${s.categories.find { it.id == to?.categoryId }?.name ?: "分类"}")
                Text("${Money.format(item.amountMinor)} · ${item.createdAt.replace('T', ' ')}", style = MaterialTheme.typography.bodySmall)
                if (item.reversalOfId != null) Text("反向转移", style = MaterialTheme.typography.bodySmall)
                else TextButton(onClick = { if (from != null && to != null) dispatch(FinanceAction.ReverseBudgetReallocation(item.id, to.revision, from.revision), newId()) {} }, enabled = !busy && from != null && to != null && history.none { it.reversalOfId == item.id }) { Text("反向转移撤回") }
            } }
        }
    }
    if (totalDialog) BudgetTotalEditor(ledger, month, budget?.amountMinor, busy, newId, command) { totalDialog = false }
    if (allocationDialog) BudgetAllocationEditor(s, ledger, month, edit, busy, newId, dispatch) { allocationDialog = false }
    if (transfer) BudgetTransferEditor(s, allocations, busy, newId, dispatch) { transfer = false }
    if (deleteTotal) AlertDialog(onDismissRequest = { deleteTotal = false }, title = { Text("删除 $month 的全部预算？") }, text = { Text("会同时删除该月总预算、分类额度和额度转移历史。账单流水保留。") }, confirmButton = { TextButton(onClick = { command(Command.SetBudget(ledger, month, null), newId()) { deleteTotal = false } }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteTotal = false }) { Text("取消") } })
}

@Composable private fun BudgetTotalEditor(ledger: String, month: String, current: Long?, busy: Boolean, newId: () -> String, command: CommandDispatch, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; var amount by remember { mutableStateOf(current?.let(Money::format).orEmpty()) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog("月度总预算", busy, dirty, onDismiss, onSave = { command(Command.SetBudget(ledger, month, Money.parse(amount)), operationId, onDismiss) }) {
        Text(month)
        FinanceTextField(amount, { amount = it; dirty = true }, "预算额度（元）", busy, money = true, supporting = "总预算不能低于该月所有分类子预算的合计")
    }
}

@Composable private fun BudgetAllocationEditor(s: LedgerState, ledger: String, month: String, original: BudgetAllocation?, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }; var category by remember { mutableStateOf(original?.categoryId) }; var amount by remember { mutableStateOf(original?.let { Money.format(it.amountMinor) }.orEmpty()) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original == null) "新增子预算" else "编辑子预算", busy, dirty, onDismiss, onSave = { if (amount.isBlank()) invalid("请输入预算额度"); val minor = signedMoney(amount); if (minor < 0) invalid("额度不能为负"); dispatch(FinanceAction.SaveBudgetAllocation(BudgetAllocation(id, ledger, month, category ?: invalid("请选择分类"), minor, original?.revision ?: 1), original?.revision), operationId, onDismiss) }) {
        FinanceChoice("支出分类", category, s.categories.filter { it.ledgerId == ledger && it.direction == Direction.EXPENSE && (it.enabled || it.id == category) }.map { it.id to it.name }, !busy && original == null) { category = it; dirty = true }
        FinanceTextField(amount, { amount = it; dirty = true }, "当前额度（元）", busy, money = true)
    }
}

@Composable private fun BudgetTransferEditor(s: LedgerState, allocations: List<BudgetAllocation>, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; var from by remember { mutableStateOf(allocations.first().id) }; var to by remember { mutableStateOf(allocations.last().id) }; var amount by remember { mutableStateOf("") }; var dirty by remember { mutableStateOf(false) }
    val options = allocations.map { it.id to "${s.categories.find { c -> c.id == it.categoryId }?.name} · ${Money.format(it.amountMinor)}" }
    FinanceFormDialog("同月额度转移", busy, dirty, onDismiss, onSave = { val source = allocations.first { it.id == from }; val target = allocations.first { it.id == to }; dispatch(FinanceAction.ReallocateBudget(from, to, Money.parse(amount), source.revision, target.revision), operationId, onDismiss) }) {
        FinanceChoice("转出分类", from, options, !busy) { from = it; dirty = true }
        FinanceChoice("转入分类", to, options, !busy) { to = it; dirty = true }
        FinanceTextField(amount, { amount = it; dirty = true }, "转移额度（元）", busy, money = true)
        Text("只调整同一月份的分类额度，总预算不变。", style = MaterialTheme.typography.bodySmall)
    }
}
