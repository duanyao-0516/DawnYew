package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.AccountHistoryUiState
import com.dawnyew.presentation.ActivityPageUi

private sealed interface AccountOrderRow {
    val key: String
    data class Heading(val type: AccountType, val total: Long) : AccountOrderRow { override val key get() = "account-heading-" + type.name }
    data class Balance(val value: AccountBalance) : AccountOrderRow { override val key get() = value.account.id }
}

@Composable internal fun AccountsPage(s: LedgerState, snapshot: FinanceSnapshot?, selectedLedger: String?, busy: Boolean, now: String, newId: () -> String, dispatch: FinanceDispatch, selectedId: String?, onSelect: (String?) -> Unit, onActivity: (ActivityScope) -> Unit, onOpen: (String) -> Unit, history: AccountHistoryUiState? = null, onLoadHistory: (AccountHistoryRequest) -> Unit = {}, accountActivities: ActivityPageUi? = null, onLoadAccountActivities: (ActivityScope) -> Unit = {}, onOpenActivity: (FinancialActivity) -> Unit = {}) {
    var hidden by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Account?>(null) }
    var form by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Account?>(null) }
    val rows = snapshot?.accounts.orEmpty()
    val detail = rows.find { it.account.id == selectedId }
    if (selectedId == null) {
        val historyRange = remember(now.take(10)) { dateRangePreset(DateRangePreset.LAST_30_DAYS, now.take(10)) }
        val historyRequest = remember(selectedLedger, historyRange) { AccountHistoryRequest(selectedLedger?.let { setOf(it) }.orEmpty(), emptySet(), historyRange.startDate, historyRange.endDate) }
        LaunchedEffect(historyRequest, s.datasetId, s.contentVersion) { onLoadHistory(historyRequest) }
        val matchingHistory = history?.takeIf { it.request == historyRequest }
        val heroHistory = matchingHistory?.snapshot?.takeIf { it.request == historyRequest && it.datasetId == s.datasetId && it.contentVersion == s.contentVersion && !matchingHistory.loading && matchingHistory.error == null }
        val historyPoints = heroHistory?.points?.map { FinanceChartPoint(it.date, it.date.substring(5).replace('-', '.'), it.balanceMinor) }
        val ledgerOrder = s.ledgers.sortedWith(compareBy<Ledger> { it.sortOrder }.thenBy { it.id }).mapIndexed { index, ledger -> ledger.id to index }.toMap()
        val orderRows = buildList<AccountOrderRow> {
            AccountType.entries.forEach { type ->
                val group = rows.filter { it.account.type == type && (showHidden || !it.account.hidden) }.sortedWith(compareBy<AccountBalance> { ledgerOrder[it.account.ledgerId] ?: Int.MAX_VALUE }.thenBy { it.account.sortOrder }.thenBy { it.account.id })
                if (group.isNotEmpty()) {
                    add(AccountOrderRow.Heading(type, group.fold(0L) { sum, value -> Money.add(sum, value.balanceMinor) }))
                    group.forEach { add(AccountOrderRow.Balance(it)) }
                }
            }
        }
        val balanceRows = orderRows.filterIsInstance<AccountOrderRow.Balance>()
        val byId = balanceRows.associateBy { it.value.account.id }
        LongPressReorderableList(orderRows, key = { it.key }, label = { row -> when (row) { is AccountOrderRow.Heading -> accountTypeName(row.type); is AccountOrderRow.Balance -> s.accountDisplayName(row.value.account.id).orEmpty() } }, busy = busy || snapshot == null,
            sourceVersion = Triple(s.datasetId, s.contentVersion, showHidden),
            reorderGroup = { row -> (row as? AccountOrderRow.Balance)?.value?.account?.takeIf { account -> s.ledgers.any { it.id == account.ledgerId && !it.archived } }?.let { it.ledgerId + "/" + it.type.name } },
            onCommit = { keys ->
                val reordered = keys.mapNotNull { byId[it]?.value?.account }
                val changed = balanceRows.map { it.value.account }.groupBy { it.ledgerId to it.type }.entries.firstOrNull { (group, members) -> members.map { it.id } != reordered.filter { it.ledgerId == group.first && it.type == group.second }.map { it.id } }
                changed?.let { (group, _) ->
                    val complete = s.accounts.filter { it.ledgerId == group.first && it.type == group.second }.sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.id }).map { it.id }
                    val visible = reordered.filter { it.ledgerId == group.first && it.type == group.second }.map { it.id }
                    dispatch(FinanceAction.ReorderAccounts(group.first, group.second, mergeVisibleOrder(complete, visible), s.contentVersion), newId()) {}
                }
            },
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), spacing = 4.dp,
            header = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("资产总览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = { edit = null; form = true }, enabled = !busy) { AppGlyph("新增"); Text("添加账户") } }
                AssetHero(snapshot?.netWorthMinor, snapshot?.assetsMinor, snapshot?.liabilitiesMinor, hidden, { hidden = !hidden }, historyPoints = historyPoints, historyLoading = heroHistory == null && matchingHistory?.error == null, historyError = matchingHistory?.error) { onOpen("资产趋势") }
                if (snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (rows.isEmpty() && snapshot != null) EmptyState("添加第一个账户", "记录现金、储蓄或信用账户，查看每一笔资金的来去。")
            } },
            footer = { Column {
                if (rows.any { it.account.hidden }) TextButton(onClick = { showHidden = !showHidden }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (showHidden) "收起隐藏账户" else "${rows.count { it.account.hidden }} 个隐藏账户  ›") }
                Text("长按账户拖动，可调整同一账本、同一类型内的顺序", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
        ) { item -> when (item) {
            is AccountOrderRow.Heading -> Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(accountTypeName(item.type), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(if (hidden) "••••" else Money.format(item.total), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            is AccountOrderRow.Balance -> {
                val row = item.value
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    TextButton(onClick = { onSelect(row.account.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        AppGlyph(accountTypeName(row.account.type)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.accountDisplayName(row.account.id).orEmpty() + if (row.account.archived) " · 已归档" else "", color = MaterialTheme.colorScheme.onSurface)
                            if (selectedLedger == null) Text(s.ledgers.find { it.id == row.account.ledgerId }?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (hidden) "••••" else Money.format(row.balanceMinor), color = if (row.balanceMinor < 0) FinanceColors.expense else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        } }
    } else if (detail == null) MissingBusinessDetail(snapshot == null) { onSelect(null) }
    else {
        val account = detail.account
        val virtual = s.obligations.any { it.virtualAccountId == account.id }
        val activityScope = remember(account.id, account.ledgerId) { ActivityScope(ledgerIds = setOf(account.ledgerId), accountIds = setOf(account.id)) }
        LaunchedEffect(activityScope, s.datasetId, s.contentVersion) { onLoadAccountActivities(activityScope) }
        val recent = accountActivities?.takeIf { it.scope == activityScope && it.contentScope == activityScope && it.datasetId == s.datasetId && it.contentVersion == s.contentVersion && !it.loading && it.error == null }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DashboardCard {
                Text("当前余额", style = MaterialTheme.typography.titleMedium)
                FinanceAmount(detail.balanceMinor, large = true, color = MaterialTheme.colorScheme.primary)
            } }
            item { Text("当前余额 = 初始余额 + 起始时间后已入账交易之和", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { AccountHistoryCard(s, setOf(account.ledgerId), setOf(account.id), now, history, onLoadHistory) }
            item { DashboardCard {
                CardHeading("最近交易", onClick = { onActivity(activityScope) })
                when {
                    accountActivities?.error != null && accountActivities.scope == activityScope -> { Text(accountActivities.error.orEmpty(), color = MaterialTheme.colorScheme.error); TextButton(onClick = { onLoadAccountActivities(activityScope) }) { Text("重新加载") } }
                    recent == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    recent.activities.isEmpty() -> Text("此账户尚无交易", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> recent.activities.take(5).forEach { activity -> ActivityRow(s, activity, onClick = { onOpenActivity(activity) }) }
                }
                TextButton(onClick = { onActivity(activityScope) }) { Text("查看全部账户流水") }
            } }
            item { DashboardCard {
                DetailFact("账户名称", s.accountDisplayName(account.id).orEmpty())
                DetailFact("账户类型", accountTypeName(account.type))
                DetailFact("账本", s.ledgers.find { it.id == account.ledgerId }?.name.orEmpty())
                DetailFact("初始余额", Money.format(account.openingBalanceMinor))
                DetailFact("起始时间", account.openingAt.replace('T', ' '))
                if (account.note.isNotBlank()) DetailFact("备注", account.note)
                if (virtual) Text("由关联的往来单据管理资金", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    FinanceToggle("计入资产", value = account.includedInAssets, enabled = !busy) { dispatch(FinanceAction.SaveAccount(account.copy(includedInAssets = it), account.revision), newId()) {} }
                    FinanceToggle("隐藏账户", "仍可用于记账", account.hidden, !busy) { dispatch(FinanceAction.SaveAccount(account.copy(hidden = it), account.revision), newId()) {} }
                }
            } }
            if (!virtual) item { DashboardCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edit = account; form = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑账户") }
                    OutlinedButton(onClick = { dispatch(FinanceAction.SaveAccount(account.copy(archived = !account.archived), account.revision), newId()) {} }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (account.archived) "取消归档" else "归档") }
                }
                TextButton(onClick = { delete = account }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除账户", color = MaterialTheme.colorScheme.error) }
            } }
        }
    }
    delete?.let { account -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除账户？") }, text = { Text("仅能删除没有资金流水或业务引用的账户。历史账户可选择归档。") }, confirmButton = { TextButton(onClick = { dispatch(FinanceAction.DeleteAccount(account.id, account.revision), newId()) { delete = null; onSelect(null) } }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
    if (form) AccountEditor(s, edit, selectedLedger, now, busy, newId, dispatch) { form = false }
}

@Composable internal fun AssetHistoryPage(s: LedgerState, ledgerIds: Set<String>, now: String, history: AccountHistoryUiState, onLoadHistory: (AccountHistoryRequest) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { AccountHistoryCard(s, ledgerIds, emptySet(), now, history, onLoadHistory, showValues = true) }
        item { Text("净资产由计入资产的账户每日余额合计。未入账和已删除的交易不计入；账户间转账按资金流向计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun AccountHistoryCard(s: LedgerState, ledgerIds: Set<String>, accountIds: Set<String>, now: String, history: AccountHistoryUiState?, onLoadHistory: (AccountHistoryRequest) -> Unit, showValues: Boolean = false) {
    var month by rememberSaveable(ledgerIds, accountIds) { mutableStateOf(now.take(7)) }
    var valuesVisible by rememberSaveable(ledgerIds, accountIds) { mutableStateOf(showValues) }
    val range = monthRange(month).let { DateRange(it.first, it.second) }
    val request = AccountHistoryRequest(ledgerIds, accountIds, range.startDate, range.endDate)
    LaunchedEffect(request, s.datasetId, s.contentVersion) { onLoadHistory(request) }
    val snapshot = history?.snapshot?.takeIf { history.request == request && it.request == request && it.datasetId == s.datasetId && it.contentVersion == s.contentVersion && !history.loading && history.error == null }
    DashboardCard {
        CardHeading(if (accountIds.isEmpty()) "净资产趋势" else "余额趋势")
        MonthSelector(month) { month = it.startDate.take(7) }
        when {
            history?.error != null && history.request == request -> { Text(history.error.orEmpty(), color = MaterialTheme.colorScheme.error); TextButton(onClick = { onLoadHistory(request) }) { Text("重新加载") } }
            snapshot == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            snapshot.points.isEmpty() -> Text("此范围没有余额记录", style = MaterialTheme.typography.bodySmall)
            else -> {
                FinanceAreaChart(snapshot.points.map { FinanceChartPoint(it.date, it.date.substring(5).replace('-', '.'), it.balanceMinor) }, color = MaterialTheme.colorScheme.primary)
                DetailFact("期末余额", Money.format(snapshot.points.last().balanceMinor))
                TextButton(onClick = { valuesVisible = !valuesVisible }) { Text(if (valuesVisible) "收起每日余额" else "查看每日余额") }
                if (valuesVisible) snapshot.points.asReversed().forEach { DetailFact(it.date, Money.format(it.balanceMinor)) }
            }
        }
    }
}

@Composable private fun AccountEditor(s: LedgerState, original: Account?, selectedLedger: String?, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; val id = remember { original?.id ?: newId() }
    var ledger by remember { mutableStateOf(original?.ledgerId ?: defaultLedger(s, selectedLedger)) }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }; var type by remember { mutableStateOf(original?.type ?: AccountType.CASH) }
    var amount by remember { mutableStateOf(original?.let { Money.format(it.openingBalanceMinor) } ?: "0.00") }
    var openingAt by remember { mutableStateOf(original?.openingAt ?: now.take(10) + "T00:00:00") }
    var note by remember { mutableStateOf(original?.note.orEmpty()) }; var hidden by remember { mutableStateOf(original?.hidden ?: false) }; var included by remember { mutableStateOf(original?.includedInAssets ?: true) }; var dirty by remember { mutableStateOf(false) }
    val referenced = original != null && (s.entryDetails.any { it.accountId == original.id } || s.transfers.any { it.fromAccountId == original.id || it.toAccountId == original.id } || s.recurringPlans.any { it.accountId == original.id || it.fromAccountId == original.id || it.toAccountId == original.id })
    FinanceFormDialog(if (original == null) "新增账户" else "编辑账户", busy, dirty, onDismiss, onSave = {
        dispatch(FinanceAction.SaveAccount(Account(id, ledger, name, type, signedMoney(amount), openingAt, original?.archived ?: false, hidden, included, note, original?.sortOrder ?: 0, original?.revision ?: 1), original?.revision), operationId, onDismiss)
    }) {
        if (original == null) LedgerChoice(s, ledger, busy) { ledger = it; dirty = true }
        FinanceTextField(name, { name = it; dirty = true }, "账户名称", busy)
        FinanceChoice("账户类型", type, AccountType.entries.map { it to accountTypeName(it) }, !busy) { type = it; dirty = true }
        FinanceTextField(amount, { amount = it; dirty = true }, "初始余额（负数表示负债）", busy, money = true, supporting = "修改初始余额会重新计算账户的全部后续余额")
        FinanceTextField(openingAt, { openingAt = it; dirty = true }, "余额起始 YYYY-MM-DDTHH:mm:ss", busy || referenced, supporting = if (referenced) "账户已有引用，起始时间不可修改" else "绑定的交易不能早于余额起始时间")
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
        FinanceToggle("计入资产", value = included, enabled = !busy) { included = it; dirty = true }
        FinanceToggle("隐藏账户", "仅从默认账户列表隐藏，仍可记账", hidden, !busy) { hidden = it; dirty = true }
    }
}
