package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*

internal fun savingsScenarioName(scenario: SavingsScenario): String = when (scenario.name) { "CHALLENGE" -> "存钱挑战"; "DREAM" -> "梦想计划"; "GOAL" -> "目标随心"; else -> "随手攒钱" }

@Composable internal fun SavingsPage(s: LedgerState, snapshot: FinanceSnapshot?, selectedLedger: String?, selectedId: String?, onSelect: (String?) -> Unit, busy: Boolean, now: String, newId: () -> String, dispatch: FinanceDispatch, onOpenActivity: (ActivityFamily, String) -> Unit) {
    var form by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<SavingsPlan?>(null) }; var chosenScenario by remember { mutableStateOf(SavingsScenario.entries.first()) }; var showArchived by remember { mutableStateOf(false) }
    var movement by remember { mutableStateOf<Triple<SavingsPlan, SavingsMovementKind, SavingsMovement?>?>(null) }; var wish by remember { mutableStateOf<Pair<SavingsPlan, SavingsWish?>?>(null) }; var fulfill by remember { mutableStateOf<Pair<SavingsPlan, SavingsWish>?>(null) }
    var delete by remember { mutableStateOf<SavingsPlan?>(null) }; var showDeleted by remember(selectedId) { mutableStateOf(false) }
    val rows = snapshot?.savings.orEmpty().filter { showArchived || !it.plan.archived }
    val detail = snapshot?.savings?.find { it.plan.id == selectedId }
    if (selectedId == null) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { FeatureGrid(SavingsScenario.entries.map { savingsScenarioName(it) to "新建计划" }) { title -> chosenScenario = SavingsScenario.entries.first { savingsScenarioName(it) == title }; edit = null; form = true } }
        item { FinanceToggle("显示归档", value = showArchived) { showArchived = it } }
        if (snapshot == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (snapshot != null && rows.isEmpty()) item { EmptyState("开始一个存钱计划", "为目标手动存入与取出，也可以关联真实账户转账。") }
        items(rows, key = { it.plan.id }) { progress -> DashboardCard {
            CardHeading(progress.plan.name, savingsScenarioName(progress.plan.scenario), { onSelect(progress.plan.id) })
            FinanceAmount(progress.savedMinor, color = MaterialTheme.colorScheme.primary)
            progress.plan.targetMinor?.let { target ->
                LinearProgressIndicator(progress = { (progress.savedMinor.toDouble() / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("目标 ${Money.format(target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text("自由积累", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    } else if (detail == null) MissingBusinessDetail(snapshot == null) { onSelect(null) }
    else {
        val plan = detail.plan
        val allMovements = remember(s.contentVersion, plan.id) { s.savingsMovements.filter { it.planId == plan.id }.sortedWith(compareByDescending<SavingsMovement> { it.occurrence }.thenByDescending { it.id }) }
        val movements = allMovements.filter { showDeleted || it.active }
        val wishes = s.savingsWishes.filter { it.planId == plan.id }
        val remaining = plan.targetMinor?.let { if (detail.savedMinor >= it) 0L else Money.add(it, -detail.savedMinor) }
        val progressLabel = plan.targetMinor?.let { target ->
            if (detail.savedMinor >= target) "已达成" else {
                val tenths = (detail.savedMinor.toDouble() / target * 1000).toLong()
                "${tenths / 10}" + (if (tenths % 10 == 0L) "" else ".${tenths % 10}") + "%"
            }
        } ?: "自由积累"
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DashboardCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已存余额", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(savingsScenarioName(plan.scenario), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.Bottom) {
                        FinanceAmount(detail.savedMinor, large = true, color = MaterialTheme.colorScheme.primary)
                        Text(plan.targetMinor?.let { "目标 ${summaryAmount(it, true)}" } ?: "无固定目标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!plan.archived) Button(onClick = { movement = Triple(plan, SavingsMovementKind.DEPOSIT, null) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) { Text("存入") }
                    else Text("已归档", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SavingsMetric("剩余目标", remaining?.let { summaryAmount(it, true) } ?: "未设置", Modifier.weight(1f))
                    SavingsMetric("当前进度", progressLabel, Modifier.weight(1f))
                    SavingsMetric("已存次数", allMovements.count { it.active && it.kind == SavingsMovementKind.DEPOSIT }.toString(), Modifier.weight(1f))
                }
            } }
            item { Text("存取记录", style = MaterialTheme.typography.titleMedium) }
            if (allMovements.any { it.deleted }) item { FinanceToggle("显示已删除记录", value = showDeleted) { showDeleted = it } }
            if (movements.isEmpty()) item { Text("还没有存取记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(movements, key = { "movement-" + it.id }) { item -> DashboardCard {
                val title = if (item.kind == SavingsMovementKind.DEPOSIT) "存入" else if (item.kind == SavingsMovementKind.WISH) "愿望支取" else "取出"
                BusinessRecordRow(title, item.occurrence.replace('T', ' ') + (if (item.deleted) " · 已删除" else "") + if (item.note.isNotBlank()) " · ${item.note}" else "", item.amountMinor, if (item.kind == SavingsMovementKind.DEPOSIT) MaterialTheme.colorScheme.primary else FinanceColors.expense)
                val linked = s.transfers.find { it.id == item.transferId }
                if (linked != null) Text("${s.accountDisplayName(linked.fromAccountId) ?: "外部资金"} → ${s.accountDisplayName(linked.toAccountId) ?: "外部资金"}" + if (!linked.recorded) " · 未入账" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("仅记录计划进度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    linked?.let { transfer -> TextButton(onClick = { onOpenActivity(ActivityFamily.TRANSFER, transfer.id) }) { Text("资金流水") } }
                    if (item.kind != SavingsMovementKind.WISH && !plan.archived) {
                        if (!item.deleted) TextButton(onClick = { movement = Triple(plan, item.kind, item) }, enabled = !busy) { Text("编辑") }
                        TextButton(onClick = { dispatch(FinanceAction.SetSavingsMovementDeleted(item.id, !item.deleted, item.revision), newId()) {} }, enabled = !busy) { Text(if (item.deleted) "恢复记录" else "删除记录", color = if (item.deleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    }
                }
            } }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("愿望", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!plan.archived) TextButton(onClick = { wish = plan to null }, enabled = !busy) { Text("新增愿望") }
            } }
            if (wishes.isEmpty()) item { Text("添加一个愿望，完成时从计划中支取对应金额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(wishes, key = { "wish-" + it.id }) { item -> DashboardCard {
                BusinessRecordRow(item.name, if (item.fulfilledMovementId != null) "已完成" else "待完成", item.amountMinor, MaterialTheme.colorScheme.primary)
                if (!plan.archived) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.fulfilledMovementId == null) {
                        TextButton(onClick = { fulfill = plan to item }, enabled = !busy) { Text("完成愿望") }
                        TextButton(onClick = { wish = plan to item }, enabled = !busy) { Text("编辑") }
                        TextButton(onClick = { dispatch(FinanceAction.DeleteSavingsWish(item.id, item.revision), newId()) {} }, enabled = !busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    } else TextButton(onClick = { dispatch(FinanceAction.CancelSavingsWish(item.id, item.revision), newId()) {} }, enabled = !busy) { Text("取消完成") }
                }
            } }
            if (!plan.archived) item { DashboardCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("手动取出", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { movement = Triple(plan, SavingsMovementKind.WITHDRAWAL, null) }, enabled = !busy && detail.savedMinor > 0L) { Text("取出") }
                }
            } }
            item { DashboardCard {
                DetailFact("计划名称", plan.name)
                DetailFact("计划状态", if (plan.archived) "已归档" else "进行中")
                DetailFact("账本", s.ledgers.find { it.id == plan.ledgerId }?.name.orEmpty())
                if (plan.note.isNotBlank()) DetailFact("备注", plan.note)
                Text("存入与取出由你手动记录，仅关联账户时影响资金余额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { edit = plan; form = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑计划") }
                    OutlinedButton(onClick = { dispatch(FinanceAction.SaveSavingsPlan(plan.copy(archived = !plan.archived), plan.revision), newId()) {} }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (plan.archived) "取消归档" else if (plan.targetMinor?.let { detail.savedMinor >= it } == true) "完成并归档" else "归档") }
                }
                if (allMovements.isEmpty() && wishes.isEmpty()) TextButton(onClick = { delete = plan }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除计划", color = MaterialTheme.colorScheme.error) }
            } }
        }
    }
    delete?.let { plan -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除存钱计划？") }, text = { Text("仅删除尚无记录和愿望的计划。") }, confirmButton = { TextButton(onClick = { dispatch(FinanceAction.DeleteSavingsPlan(plan.id, plan.revision), newId()) { delete = null; onSelect(null) } }, enabled = !busy) { Text("删除") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
    if (form) SavingsPlanEditor(s, edit, chosenScenario, selectedLedger, busy, newId, dispatch) { form = false }
    movement?.let { (plan, kind, original) -> SavingsMovementEditor(s, plan, kind, original, now, busy, newId, dispatch) { movement = null } }
    wish?.let { (plan, original) -> SavingsWishEditor(plan, original, busy, newId, dispatch) { wish = null } }
    fulfill?.let { (plan, item) -> SavingsFulfillEditor(s, plan, item, now, busy, newId, dispatch) { fulfill = null } }
}

@Composable private fun SavingsMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SavingsPlanEditor(s: LedgerState, original: SavingsPlan?, scenario: SavingsScenario, selectedLedger: String?, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }; var ledger by remember { mutableStateOf(original?.ledgerId ?: defaultLedger(s, selectedLedger)) }; var name by remember { mutableStateOf(original?.name.orEmpty()) }; var type by remember { mutableStateOf(original?.scenario ?: scenario) }; var target by remember { mutableStateOf(original?.targetMinor?.let(Money::format).orEmpty()) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original == null) "新增存钱计划" else "编辑存钱计划", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.SaveSavingsPlan(SavingsPlan(id, ledger, name, type, if (target.isBlank() && type.name == "FLEXIBLE") null else Money.parse(target), note, original?.archived ?: false, original?.revision ?: 1), original?.revision), operationId, onDismiss) }) {
        if (original == null) LedgerChoice(s, ledger, busy) { ledger = it; dirty = true }
        FinanceChoice("场景", type, SavingsScenario.entries.map { it to savingsScenarioName(it) }, !busy) { type = it; dirty = true }
        FinanceTextField(name, { name = it; dirty = true }, "计划名称", busy)
        FinanceTextField(target, { target = it; dirty = true }, if (type.name == "FLEXIBLE") "目标金额（可空）" else "目标金额（必填）", busy, money = true)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
        Text("四种场景均通过手动记录累积进度。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun SavingsEndpointFields(s: LedgerState, ledger: String, from: String?, to: String?, amount: String, busy: Boolean, onFrom: (String?) -> Unit, onTo: (String?) -> Unit) {
    AccountChoice(s, ledger, "付款账户（转出）", from, busy, emptyLabel = if (to == null) "不关联账户" else "外部资金", onChange = onFrom)
    AccountChoice(s, ledger, "收款账户（转入）", to, busy, emptyLabel = if (from == null) "不关联账户" else "外部资金", onChange = onTo)
    val fromIncluded = s.accounts.any { it.id == from && it.includedInAssets }; val toIncluded = s.accounts.any { it.id == to && it.includedInAssets }
    val amountLabel = runCatching { Money.format(Money.parse(amount)) }.getOrElse { "对应金额" }
    Text(when {
        from == null && to == null -> "仅改变计划进度，不改变账户余额与净资产。"
        fromIncluded && !toIncluded -> "付款账户扣款，净资产减少 $amountLabel。" + if (to == null) "收款方为外部资金。" else "收款账户不计入资产。"
        !fromIncluded && toIncluded -> "收款账户入款，净资产增加 $amountLabel。" + if (from == null) "付款方为外部资金。" else "付款账户不计入资产。"
        else -> "付款账户扣款，收款账户入款，净资产不变。"
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun SavingsMovementEditor(s: LedgerState, plan: SavingsPlan, kind: SavingsMovementKind, original: SavingsMovement?, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val linked = s.transfers.find { it.id == original?.transferId }; val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }; var amount by remember { mutableStateOf(original?.let { Money.format(it.amountMinor) }.orEmpty()) }; var occurrence by remember { mutableStateOf(original?.occurrence ?: now) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var from by remember { mutableStateOf(linked?.fromAccountId) }; var to by remember { mutableStateOf(linked?.toAccountId) }; var recorded by remember { mutableStateOf(linked?.recorded ?: true) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (kind == SavingsMovementKind.DEPOSIT) "存入" else "取出", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.SaveSavingsMovement(SavingsMovement(id, plan.id, kind, Money.parse(amount), occurrence, note, original?.transferId, original?.deleted ?: false, original?.revision ?: 1), TransferEndpoints(from, to, recorded, linked?.reconciled ?: false), original?.revision), operationId, onDismiss) }) {
        FinanceTextField(amount, { amount = it; dirty = true }, "金额（元）", busy, money = true)
        FinanceTextField(occurrence, { occurrence = it; dirty = true }, "发生时间 YYYY-MM-DDTHH:mm:ss", busy)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
        SavingsEndpointFields(s, plan.ledgerId, from, to, amount, busy, { from = it; dirty = true }, { to = it; dirty = true })
        if (from != null || to != null) FinanceToggle("资金已入账", value = recorded, enabled = !busy) { recorded = it; dirty = true }
    }
}

@Composable private fun SavingsWishEditor(plan: SavingsPlan, original: SavingsWish?, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }; var name by remember { mutableStateOf(original?.name.orEmpty()) }; var amount by remember { mutableStateOf(original?.let { Money.format(it.amountMinor) }.orEmpty()) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original == null) "新增愿望" else "编辑愿望", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.SaveSavingsWish(SavingsWish(id, plan.id, name, Money.parse(amount), original?.fulfilledMovementId, original?.revision ?: 1), original?.revision), operationId, onDismiss) }) {
        FinanceTextField(name, { name = it; dirty = true }, "愿望名称", busy)
        FinanceTextField(amount, { amount = it; dirty = true }, "愿望金额（元）", busy, money = true)
    }
}

@Composable private fun SavingsFulfillEditor(s: LedgerState, plan: SavingsPlan, wish: SavingsWish, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; var from by remember { mutableStateOf<String?>(null) }; var to by remember { mutableStateOf<String?>(null) }; var occurrence by remember { mutableStateOf(now) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog("完成愿望", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.FulfillSavingsWish(wish.id, occurrence, TransferEndpoints(from, to), wish.revision), operationId, onDismiss) }, saveLabel = "完成") {
        Text("${wish.name} · ${Money.format(wish.amountMinor)}")
        Text("完成后从计划进度中支取此金额，可在愿望列表取消完成。", style = MaterialTheme.typography.bodySmall)
        FinanceTextField(occurrence, { occurrence = it; dirty = true }, "发生时间 YYYY-MM-DDTHH:mm:ss", busy)
        SavingsEndpointFields(s, plan.ledgerId, from, to, Money.format(wish.amountMinor), busy, { from = it; dirty = true }, { to = it; dirty = true })
    }
}
