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

@Composable internal fun ObligationsPage(s: LedgerState, snapshot: FinanceSnapshot?, kind: ObligationKind, selectedLedger: String?, busy: Boolean, now: String, newId: () -> String, dispatch: FinanceDispatch, selectedId: String?, onSelect: (String?) -> Unit, onActivity: (ActivityScope) -> Unit, onOpenActivity: (ActivityFamily, String) -> Unit = { _, _ -> }) {
    var edit by remember { mutableStateOf<Obligation?>(null) }; var form by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<Pair<Obligation, Boolean>?>(null) }; var archived by remember { mutableStateOf(false) }
    val rows = snapshot?.obligations.orEmpty().filter { it.obligation.kind == kind && (archived || !it.obligation.archived) }
    val detail = snapshot?.obligations?.find { it.obligation.id == selectedId && it.obligation.kind == kind }
    if (selectedId == null) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Button(onClick = { edit = null; form = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("新增${obligationName(kind)}") } }
        item { FinanceToggle("显示归档", value = archived) { archived = it } }
        if (snapshot == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (snapshot != null && rows.isEmpty()) item { EmptyState("暂无${obligationName(kind)}记录", "添加单据后可记录本金、部分收付与结清。") }
        items(rows, key = { it.obligation.id }) { balance -> DashboardCard {
            CardHeading(balance.obligation.title, s.contacts.find { it.id == balance.obligation.contactId }?.name, { onSelect(balance.obligation.id) })
            FinanceAmount(balance.remainingMinor, color = if (kind == ObligationKind.BORROW) FinanceColors.expense else MaterialTheme.colorScheme.primary)
            Text(if (balance.remainingMinor == 0L) "已结清" else if (kind == ObligationKind.BORROW) "待还" else "待收", style = MaterialTheme.typography.bodySmall)
            balance.obligation.dueDate?.let { Text("到期 $it" + if (balance.isOverdue(now.take(10))) " · 已逾期" else "", style = MaterialTheme.typography.bodySmall, color = if (balance.isOverdue(now.take(10))) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (balance.pendingPrincipalMinor > 0 || balance.pendingSettlementMinor > 0) Text("未入账：本金 ${Money.format(balance.pendingPrincipalMinor)} / 收付 ${Money.format(balance.pendingSettlementMinor)}", style = MaterialTheme.typography.bodySmall)
        } }
    } else if (detail == null) MissingBusinessDetail(snapshot == null) { onSelect(null) }
    else {
        val value = detail.obligation
        val records = remember(s.contentVersion, value.id) { s.transfers.filter { it.businessId == value.id && it.active && it.businessKind in setOf(TransferBusinessKind.OBLIGATION_PRINCIPAL, TransferBusinessKind.OBLIGATION_SETTLEMENT) }.sortedWith(compareByDescending<AccountTransfer> { it.occurrence }.thenByDescending { it.id }) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DashboardCard {
                Text(if (detail.remainingMinor == 0L) "已结清" else if (kind == ObligationKind.BORROW) "剩余应付" else "剩余应收", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FinanceAmount(detail.remainingMinor, Modifier.weight(1f), large = true, color = if (kind == ObligationKind.BORROW) FinanceColors.expense else MaterialTheme.colorScheme.primary)
                    if (!value.archived) Button(onClick = { action = value to true }, enabled = !busy && detail.remainingMinor > 0L, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) { Text(if (kind == ObligationKind.BORROW) "还款 / 结清" else "收款 / 结清") }
                    else Text("已归档", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ObligationMetric(if (kind == ObligationKind.REIMBURSEMENT) "报销支出" else "${obligationName(kind)}总额", detail.principalMinor, Modifier.weight(1f))
                    ObligationMetric(if (kind == ObligationKind.BORROW) "已还金额" else "已收金额", detail.settledMinor, Modifier.weight(1f))
                }
                if (detail.pendingPrincipalMinor > 0 || detail.pendingSettlementMinor > 0) Text("未入账本金 ${Money.format(detail.pendingPrincipalMinor)} · 未入账收付 ${Money.format(detail.pendingSettlementMinor)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            listOf(false, true).forEach { settlement ->
                val group = records.filter { (it.businessKind == TransferBusinessKind.OBLIGATION_SETTLEMENT) == settlement }
                val title = if (settlement) (if (kind == ObligationKind.BORROW) "还款" else "收款") else if (kind == ObligationKind.REIMBURSEMENT) "报销支出" else obligationName(kind)
                item { DashboardCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!value.archived) TextButton(onClick = { action = value to settlement }, enabled = !busy && (!settlement || detail.remainingMinor > 0L)) { Text(if (settlement) "新增${title}" else if (kind == ObligationKind.REIMBURSEMENT) "追加支出" else "追加本金") }
                    }
                    if (group.isEmpty()) Text("暂无${title}记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    group.forEach { record ->
                        val accountId = if (record.fromAccountId == value.virtualAccountId) record.toAccountId else record.fromAccountId
                        BusinessRecordRow(title, record.occurrence.replace('T', ' ') + " · " + s.accountDisplayName(accountId).orEmpty() + (if (!record.recorded) " · 未入账" else "") + if (record.note.isNotBlank()) " · ${record.note}" else "", record.amountMinor, FinanceColors.transfer) { onOpenActivity(ActivityFamily.TRANSFER, record.id) }
                    }
                } }
            }
            item { TextButton(onClick = { onActivity(ActivityScope(ledgerIds = setOf(value.ledgerId), accountIds = setOf(value.virtualAccountId))) }, modifier = Modifier.fillMaxWidth()) { Text("查看全部资金流水") } }
            item { DashboardCard {
                DetailFact("单据名称", value.title)
                DetailFact("往来人员", s.contacts.find { it.id == value.contactId }?.name ?: "未指定")
                DetailFact("账本", s.ledgers.find { it.id == value.ledgerId }?.name.orEmpty())
                DetailFact("发生时间", value.openingAt.replace('T', ' '))
                DetailFact("到期日期", value.dueDate ?: "未设置")
                if (value.note.isNotBlank()) DetailFact("备注", value.note)
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { edit = value; form = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("编辑单据") }
                OutlinedButton(onClick = { dispatch(FinanceAction.SaveObligation(value.copy(archived = !value.archived), value.revision), newId()) {} }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (value.archived) "取消归档" else "归档") }
            } }
        }
    }
    if (form) ObligationEditor(s, kind, edit, selectedLedger, now, busy, newId, dispatch) { form = false }
    action?.let { (value, settlement) -> ObligationMovementEditor(s, value, settlement, snapshot?.obligations?.find { it.obligation.id == value.id }?.remainingMinor, now, busy, newId, dispatch) { action = null } }
}

@Composable private fun ObligationMetric(label: String, amount: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summaryAmount(amount, true), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ObligationEditor(s: LedgerState, kind: ObligationKind, original: Obligation?, selectedLedger: String?, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; val id = remember { original?.id ?: newId() }; val virtualId = remember { original?.virtualAccountId ?: newId() }
    var ledger by remember { mutableStateOf(original?.ledgerId ?: defaultLedger(s, selectedLedger)) }; var title by remember { mutableStateOf(original?.title.orEmpty()) }
    var contact by remember { mutableStateOf(original?.contactId) }; var account by remember { mutableStateOf<String?>(null) }; var amount by remember { mutableStateOf("") }
    var occurrence by remember { mutableStateOf(original?.openingAt ?: now) }; var due by remember { mutableStateOf(original?.dueDate.orEmpty()) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var recorded by remember { mutableStateOf(true) }; var dirty by remember { mutableStateOf(false) }
    var newContact by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original == null) "新增${obligationName(kind)}" else "编辑单据", busy, dirty, onDismiss, onSave = {
        val value = Obligation(id, ledger, kind, title, virtualId, occurrence, contact, due.ifBlank { null }, note, original?.archived ?: false, original?.revision ?: 1)
        val principal = if (original == null) ObligationPrincipal(account ?: invalid("请选择实际资金账户"), Money.parse(amount), occurrence, recorded, note = note) else null
        dispatch(FinanceAction.SaveObligation(value, original?.revision, principal), operationId, onDismiss)
    }) {
        if (original == null) LedgerChoice(s, ledger, busy) { ledger = it; account = null; contact = null; dirty = true }
        FinanceTextField(title, { title = it; dirty = true }, "单据名称", busy)
        FinanceChoice("往来人员", contact, listOf(null to "未指定") + s.contacts.filter { it.ledgerId == ledger && (!it.archived || it.id == contact) }.map { it.id to it.name }, !busy) { contact = it; dirty = true }
        TextButton(onClick = { newContact = true }, enabled = !busy) { Text("新增往来人员") }
        if (original == null) {
            AccountChoice(s, ledger, if (kind == ObligationKind.BORROW) "收款账户" else "付款账户", account, busy, optional = false) { account = it; dirty = true }
            FinanceTextField(amount, { amount = it; dirty = true }, "本金金额（元）", busy, money = true)
            FinanceTextField(occurrence, { occurrence = it; dirty = true }, "发生时间 YYYY-MM-DDTHH:mm:ss", busy)
            FinanceToggle("已入账", "未入账本金不增加可结算金额", recorded, !busy) { recorded = it; dirty = true }
        }
        FinanceTextField(due, { due = it; dirty = true }, "到期日期（可空）YYYY-MM-DD", busy)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
    }
    if (newContact) ContactEditor(s, null, ledger, busy, newId, dispatch, onCreated = { contact = it; dirty = true }) { newContact = false }
}

@Composable internal fun ObligationMovementEditor(s: LedgerState, value: Obligation, settlement: Boolean, remaining: Long?, now: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, original: AccountTransfer? = null, onDismiss: () -> Unit) {
    val operationId = remember { newId() }; var account by remember { mutableStateOf(original?.let { if (it.fromAccountId == value.virtualAccountId) it.toAccountId else it.fromAccountId }) }; var amount by remember { mutableStateOf(original?.let { Money.format(it.amountMinor) }.orEmpty()) }; var occurrence by remember { mutableStateOf(original?.occurrence ?: now) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var recorded by remember { mutableStateOf(original?.recorded ?: true) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original != null) "编辑往来流水" else if (settlement) "追加收付" else "追加本金", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.RecordObligation(value.id, account ?: invalid("请选择实际资金账户"), Money.parse(amount), occurrence, settlement, recorded, original?.reconciled ?: false, note = note, transferId = original?.id, expectedRevision = original?.revision), operationId, onDismiss) }) {
        Text(value.title)
        AccountChoice(s, value.ledgerId, "资金账户", account, busy, optional = false) { account = it; dirty = true }
        FinanceTextField(amount, { amount = it; dirty = true }, "金额（元）", busy, money = true)
        if (settlement && original == null && remaining != null) TextButton(onClick = { amount = Money.format(remaining); dirty = true }, enabled = !busy) { Text("全部结清 ${Money.format(remaining)}") }
        FinanceTextField(occurrence, { occurrence = it; dirty = true }, "发生时间 YYYY-MM-DDTHH:mm:ss", busy)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
        FinanceToggle("已入账", value = recorded, enabled = !busy) { recorded = it; dirty = true }
    }
}

@Composable internal fun ContactsPage(s: LedgerState, selectedLedger: String?, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onActivity: (ActivityScope) -> Unit) {
    var form by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<Contact?>(null) }; var showArchived by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = { edit = null; form = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("新增往来人员") } }
        item { FinanceToggle("显示归档", value = showArchived) { showArchived = it } }
        items(s.contacts.filter { (selectedLedger == null || it.ledgerId == selectedLedger) && (showArchived || !it.archived) }, key = { it.id }) { contact ->
            DashboardCard {
                CardHeading(contact.name, contact.note.ifBlank { null })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { edit = contact; form = true }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = { val accounts = s.obligations.filter { it.contactId == contact.id }.map { it.virtualAccountId }.toSet(); if (accounts.isNotEmpty()) onActivity(ActivityScope(accountIds = accounts)) }, enabled = s.obligations.any { it.contactId == contact.id }) { Text("往来流水") }
                    TextButton(onClick = { dispatch(FinanceAction.SaveContact(contact.copy(archived = !contact.archived), contact.revision), newId()) {} }, enabled = !busy) { Text(if (contact.archived) "取消归档" else "归档") }
                    TextButton(onClick = { dispatch(FinanceAction.DeleteContact(contact.id, contact.revision), newId()) {} }, enabled = !busy) { Text("删除") }
                }
            }
        }
    }
    if (form) ContactEditor(s, edit, selectedLedger, busy, newId, dispatch, {}) { form = false }
}

@Composable internal fun ContactEditor(s: LedgerState, original: Contact?, selectedLedger: String?, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }; var ledger by remember { mutableStateOf(original?.ledgerId ?: defaultLedger(s, selectedLedger)) }; var name by remember { mutableStateOf(original?.name.orEmpty()) }; var note by remember { mutableStateOf(original?.note.orEmpty()) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original == null) "新增往来人员" else "编辑往来人员", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.SaveContact(Contact(id, ledger, name, note, original?.archived ?: false, original?.revision ?: 1), original?.revision), operationId) { onCreated(id); onDismiss() } }) {
        if (original == null) LedgerChoice(s, ledger, busy) { ledger = it; dirty = true }
        FinanceTextField(name, { name = it; dirty = true }, "姓名", busy)
        FinanceTextField(note, { note = it; dirty = true }, "备注", busy)
    }
}
