package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.dawnyew.core.*
import com.dawnyew.presentation.ActivityPageUi
import com.dawnyew.presentation.ActivitySummaryUi

@Composable internal fun ActivityRow(s: LedgerState, activity: FinancialActivity, enabled: Boolean = true, horizontalPadding: Dp = 0.dp, onClick: () -> Unit) {
    val color = when (activity.kind) { ActivityKind.EXPENSE -> FinanceColors.expense; ActivityKind.TRANSFER -> FinanceColors.transfer; else -> MaterialTheme.colorScheme.primary }
    val title = if (activity.family == ActivityFamily.ENTRY) s.categories.find { it.id == activity.categoryId }?.name ?: activityKindName(activity.kind) else when (activity.businessKind) {
        TransferBusinessKind.OBLIGATION_PRINCIPAL -> s.obligations.find { it.id == activity.businessId }?.let { obligationName(it.kind) } ?: "往来本金"
        TransferBusinessKind.OBLIGATION_SETTLEMENT -> s.obligations.find { it.id == activity.businessId }?.let { if (it.kind == ObligationKind.BORROW) "还款" else if (it.kind == ObligationKind.REIMBURSEMENT) "报销收款" else "收款" } ?: "往来收付"
        TransferBusinessKind.SAVINGS -> "存钱资金转账"
        else -> "转账"
    }
    val account = if (activity.family == ActivityFamily.TRANSFER) "${s.accountDisplayName(activity.fromAccountId) ?: "外部资金"} → ${s.accountDisplayName(activity.toAccountId) ?: "外部资金"}" else s.accountDisplayName(activity.accountId) ?: "无账户"
    val amount = (if (activity.kind == ActivityKind.EXPENSE) "−" else if (activity.kind == ActivityKind.TRANSFER) "" else "+") + Money.format(activity.amountMinor)
    val metadata = activity.occurrence.substringAfter('T').take(5) + if (activity.note.isNotBlank()) " · ${activity.note}" else ""
    val accountStatus = (if (!activity.recorded) "未入账 · " else "") + (if (activity.reconciled) "已对账 · " else "") + account
    val largeText = LocalDensity.current.fontScale > 1.4f
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 61.dp).padding(vertical = 10.dp, horizontal = horizontalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (largeText) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(amount, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(amount, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(metadata, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(shape = RoundedCornerShape(5.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                            Text(accountStatus, Modifier.widthIn(max = 140.dp).padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun ActivitiesPage(s: LedgerState, page: ActivityPageUi, summaryPage: ActivitySummaryUi, scope: ActivityScope, onMore: () -> Unit, onRetry: () -> Unit, onOpen: (FinancialActivity) -> Unit, onFilter: (ActivityScope) -> Unit, onCapture: () -> Unit) {
    val interactive = !page.loading && page.error == null && page.contentScope == scope && page.scope == scope && page.datasetId == s.datasetId && page.contentVersion == s.contentVersion
    val summary = summaryPage.summary?.takeIf { !summaryPage.loading && summaryPage.error == null && summaryPage.scope == scope && summaryPage.datasetId == s.datasetId && summaryPage.contentVersion == s.contentVersion }
    val daySummaries = remember(summary) { summary?.days?.associateBy { it.date }.orEmpty() }
    val visibleActivities = page.activities.takeIf { page.contentScope == scope && page.datasetId == s.datasetId }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ActivityQueryControls(s, scope, onFilter, onCapture) }
        item { Text(if (scope.startDate == "0001-01-01" && scope.endDate == "9999-12-31") "全部日期" else "${scope.startDate} 至 ${scope.endDate}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { DashboardCard {
            Text(summary?.let { "${it.activityCount}笔交易" } ?: "正在读取范围合计", style = MaterialTheme.typography.bodyMedium)
            val balance = summary?.let { runCatching { Money.add(Money.add(it.income, it.refunds), -it.expense) }.getOrNull() }
            val totals = listOf(Triple("支出", summary?.expense, FinanceColors.expense), Triple("收入", summary?.income, MaterialTheme.colorScheme.primary), Triple("结余", balance, MaterialTheme.colorScheme.onSurface))
            if (LocalDensity.current.fontScale > 1.4f) {
                totals.forEach { (label, value, color) -> ActivityTotal(label, value, color) }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                totals.forEach { (label, value, color) -> ActivityTotal(label, value, color, Modifier.weight(1f)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("退款 ${summary?.refunds?.let(Money::format) ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("转账 ${summary?.transferAmount?.let(Money::format) ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("结余含退款；转账不计入收支", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summaryPage.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            summaryPage.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("重试汇总") } }
        } }
        if (page.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        page.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("重试") } } }
        if (page.loaded && visibleActivities.isEmpty() && interactive) item { EmptyState("当前范围没有流水", "调整筛选，或点击记一笔添加新的收支。") }
        val grouped = visibleActivities.groupBy { it.localDate }
        grouped.forEach { (date, activities) ->
            item("day-$date") { Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(date, style = MaterialTheme.typography.titleSmall)
                daySummaries[date]?.let { day ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("收入 ¥${Money.format(day.income)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text("支出 ¥${Money.format(day.expense)}", style = MaterialTheme.typography.bodySmall, color = FinanceColors.expense)
                        if (day.refunds > 0) Text("退款 ¥${Money.format(day.refunds)}", style = MaterialTheme.typography.bodySmall)
                        if (day.transferAmount > 0) Text("转账 ¥${Money.format(day.transferAmount)}", style = MaterialTheme.typography.bodySmall, color = FinanceColors.transfer)
                    }
                }
            } }
            items(activities, key = { it.identity }) { activity -> ActivityRow(s, activity, interactive, horizontalPadding = 16.dp) { onOpen(activity) } }
        }
        if (page.nextCursor != null) item { TextButton(onClick = onMore, enabled = interactive, modifier = Modifier.fillMaxWidth()) { Text("加载下一页") } }
    }
}

@Composable private fun ActivityTotal(label: String, amount: Long?, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount?.let(Money::format) ?: "—", color = color, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

@Composable internal fun TransferEditor(s: LedgerState, original: AccountTransfer?, selectedLedger: String?, initialAmount: String, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; val id = remember { original?.id ?: newId() }; var ledger by remember { mutableStateOf(original?.ledgerId ?: selectedLedger?.takeIf { id -> s.ledgers.any { it.id == id && !it.archived } }.orEmpty()) }; var from by remember { mutableStateOf(original?.fromAccountId) }; var to by remember { mutableStateOf(original?.toAccountId) }; var amount by remember { mutableStateOf(original?.let { Money.format(it.amountMinor) } ?: initialAmount) }; var occurrence by remember { mutableStateOf(original?.occurrence ?: now) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var recorded by remember { mutableStateOf(original?.recorded ?: true) }; var reconciled by remember { mutableStateOf(original?.reconciled ?: false) }; var dirty by remember { mutableStateOf(initialAmount.isNotBlank()) }
    FinanceFormDialog(if (original == null) "转账" else "编辑转账", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.SaveTransfer(AccountTransfer(id, ledger, from, to, Money.parse(amount), occurrence, note, recorded, reconciled, original?.deleted ?: false, original?.revision ?: 1, original?.createdAt ?: now, now), original?.revision), operationId, onDismiss) }) {
        if (original == null) LedgerChoice(s, ledger, busy) { ledger = it; from = null; to = null; dirty = true }
        FinanceTextField(amount, { amount = it; dirty = true }, "转账金额（元）", busy, money = true)
        AccountChoice(s, ledger, "转出账户", from, busy, optional = false) { from = it; dirty = true }
        AccountChoice(s, ledger, "转入账户", to, busy, optional = false) { to = it; dirty = true }
        FinanceTextField(occurrence, { occurrence = it; dirty = true }, "发生时间 YYYY-MM-DDTHH:mm:ss", busy)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
        FinanceToggle("已入账", value = recorded, enabled = !busy) { recorded = it; dirty = true }
        FinanceToggle("已对账", value = reconciled, enabled = !busy) { reconciled = it; dirty = true }
        Text("转账不会计入普通收入、支出或预算。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun TransferDetail(s: LedgerState, transfer: AccountTransfer, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onEdit: () -> Unit, onSource: (String, String) -> Unit, onDone: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("转账", color = FinanceColors.transfer)
        FinanceAmount(transfer.amountMinor, color = FinanceColors.transfer, large = true)
        Text("${s.accountDisplayName(transfer.fromAccountId) ?: "外部资金"} → ${s.accountDisplayName(transfer.toAccountId) ?: "外部资金"}")
        Text(transfer.occurrence.replace('T', ' ')); Text(if (transfer.recorded) "已入账" else "未入账"); Text(if (transfer.reconciled) "已对账" else "未对账")
        if (transfer.note.isNotBlank()) Text(transfer.note)
        val movement = s.savingsMovements.find { it.id == transfer.businessId }
        val editable = transfer.businessKind != TransferBusinessKind.SAVINGS || movement?.kind?.let { it != SavingsMovementKind.WISH } == true
        if (editable) {
            Button(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("编辑记录") }
            TextButton(onClick = { deleting = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("移入回收站", color = MaterialTheme.colorScheme.error) }
        }
        if (transfer.businessKind != TransferBusinessKind.MANUAL) {
            Text(if (editable) "修改会同步关联单据或存钱进度。" else "愿望资金记录请通过来源计划取消完成。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { transfer.businessId?.let { businessId ->
                val obligation = s.obligations.find { it.id == businessId }
                if (obligation != null) onSource(obligationName(obligation.kind), obligation.id)
                else if (movement != null) onSource("存钱", movement.planId)
            } }, enabled = transfer.businessId != null) { Text("查看来源") }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("移入回收站？") }, text = { Text("转出和转入会一起撤销，可从回收站恢复。") }, confirmButton = { TextButton(onClick = { val action = when (transfer.businessKind) {
                    TransferBusinessKind.MANUAL -> FinanceAction.SetTransferDeleted(transfer.id, true, transfer.revision)
                    TransferBusinessKind.OBLIGATION_PRINCIPAL, TransferBusinessKind.OBLIGATION_SETTLEMENT -> FinanceAction.SetObligationTransferDeleted(transfer.id, true, transfer.revision)
                    TransferBusinessKind.SAVINGS -> s.savingsMovements.find { it.id == transfer.businessId }?.let { FinanceAction.SetSavingsMovementDeleted(it.id, true, it.revision) }
                }
                if (action != null) dispatch(action, newId()) { deleting = false; onDone() } }, enabled = !busy) { Text("确认") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}
