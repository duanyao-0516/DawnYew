package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.*

@Composable internal fun FileAndWidgetPage(page: String, s: LedgerState, selectedLedger: String?, initialScope: QueryScope, actions: PlatformActions, command: CommandDispatch, newId: () -> String, onConfirm: (Confirmation) -> Unit, onOpen: (String) -> Unit) {
    var ledger by remember { mutableStateOf(defaultLedger(s, selectedLedger)) }; var start by remember { mutableStateOf(initialScope.startDate) }; var end by remember { mutableStateOf(initialScope.endDate) }; var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == "组件") {
            item { SettingsSection {
                SettingsText("快捷记账", "桌面组件提供支出、收入和截图识别入口，点击后打开应用并继续录入。")
                SettingsDivider()
                SettingItem("添加到桌面", onClick = actions::requestWidget)
            } }
        } else {
            item { SettingsSection {
                SettingsText("DawnYew CSV", "只交换有效的普通支出与收入，不包含退款、转账、账户、计划、回收站和其它关联资料。完整迁移请使用本地备份。")
                SettingsDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerChoice(s, ledger, false) { ledger = it }
                    if (page == "导出") {
                        FinanceTextField(start, { start = it }, "开始日期 YYYY-MM-DD", false)
                        FinanceTextField(end, { end = it }, "结束日期 YYYY-MM-DD", false)
                        Text("最多10,000条普通收支。创建文件前会核对范围与排除项。", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { try { val query = QueryScope(setOf(ledger), start, end); validateQueryScope(query); actions.exportCsv(query) } catch (e: DomainException) { error = e.message } }, enabled = ledger.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("预览并导出 CSV") }
                    } else {
                        Text("请选择 DawnYew CSV v1 文件，先预览并核对映射与重复记录，再确认导入。", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { actions.importCsv(ledger) }, enabled = ledger.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("选择本地 CSV") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                SettingsDivider()
                SettingItem("完整备份", "JSON / 加密备份，保留全部关联资料", onClick = { onOpen("备份") })
            } }
            if (page == "导入") item { SettingsSection("本地导入批次") {
                val batches = s.importBatches.sortedByDescending { it.createdAt }
                if (batches.isEmpty()) SettingItem("暂无导入批次")
                batches.forEachIndexed { index, batch ->
                    if (index > 0) SettingsDivider()
                    SettingItem(batch.createdAt, "${batch.count}笔 · ${if (batch.status == "UNDONE") "已撤销" else "已导入"}", trailing = if (batch.status == "UNDONE") null else ({
                        TextButton(onClick = { onConfirm(Confirmation("撤销本批导入？", "仅在记录未被编辑时允许撤销，系统会重新核对整批数据。") { command(Command.UndoImport(batch.id), newId()) {} }) }) { Text("撤销本批") }
                    }))
                }
            } }
        }
    }
}

@Composable internal fun FinanceRecyclePage(s: LedgerState, page: ActivityPageUi, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, command: CommandDispatch, onMore: () -> Unit, onRetry: () -> Unit, onConfirm: (Confirmation) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        page.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("重试") } } }
        if (page.loaded && page.activities.isEmpty() && page.error == null) item { EmptyState("回收站为空", "删除的账单和转账会在这里保留。") }
        items(page.activities, key = { it.identity }) { activity -> DashboardCard {
            Text("${activity.localDate} · ${activityKindName(activity.kind)}", style = MaterialTheme.typography.labelLarge)
            FinanceAmount(activity.amountMinor)
            if (activity.note.isNotBlank()) Text(activity.note)
            if (activity.family == ActivityFamily.ENTRY) {
                val entry = s.entries.find { it.id == activity.id }
                entry?.deletionGroupId?.let { group -> TextButton(onClick = { command(Command.RestoreGroup(group), newId()) {} }, enabled = !busy) { Text("恢复删除组（原支出及关联退款）") } }
                if (entry != null) TextButton(onClick = { onConfirm(Confirmation("永久删除这笔账单？", "此操作无法撤销，关联退款也会按删除组规则处理。") { command(Command.Purge(entry.id), newId()) {} }) }, enabled = !busy) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            } else {
                val transfer = s.transfers.find { it.id == activity.id }
                transfer?.let { value ->
                    val movement = s.savingsMovements.find { it.id == value.businessId }
                    val restore = when (value.businessKind) {
                        TransferBusinessKind.MANUAL -> FinanceAction.SetTransferDeleted(value.id, false, value.revision)
                        TransferBusinessKind.OBLIGATION_PRINCIPAL, TransferBusinessKind.OBLIGATION_SETTLEMENT -> FinanceAction.SetObligationTransferDeleted(value.id, false, value.revision)
                        TransferBusinessKind.SAVINGS -> movement?.takeIf { it.kind != SavingsMovementKind.WISH }?.let { FinanceAction.SetSavingsMovementDeleted(it.id, false, it.revision) }
                    }
                    if (restore != null) TextButton(onClick = { dispatch(restore, newId()) {} }, enabled = !busy) { Text("恢复关联记录") }
                    if (value.businessKind == TransferBusinessKind.MANUAL) TextButton(onClick = { onConfirm(Confirmation("永久删除转账？", "转出和转入将一并清理，已执行周期凭证保留。") { dispatch(FinanceAction.PurgeTransfer(value.id, value.revision), newId()) {} }) }, enabled = !busy) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
                    else Text("关联业务记录通过来源计划管理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        if (page.nextCursor != null) item { TextButton(onClick = onMore, enabled = !page.loading && page.error == null, modifier = Modifier.fillMaxWidth()) { Text("加载下一页") } }
    }
}
