package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*

internal val LocalFinanceEditorPresence = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

internal typealias FinanceDispatch = (FinanceAction, String, () -> Unit) -> Unit

@Composable internal fun FinanceFormDialog(
    title: String, busy: Boolean, dirty: Boolean, onDismiss: () -> Unit,
    onSave: () -> Unit, saveLabel: String = "保存", content: @Composable ColumnScope.() -> Unit,
) {
    val editingPresence = LocalFinanceEditorPresence.current
    DisposableEffect(editingPresence) { editingPresence(true); onDispose { editingPresence(false) } }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun dismiss() { if (!busy) { if (dirty) discard = true else onDismiss() } }
    AlertDialog(onDismissRequest = ::dismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { try { error = null; onSave() } catch (e: DomainException) { error = e.message } catch (e: IllegalArgumentException) { error = e.message ?: "请检查输入" } }) { Text(if (busy) "正在保存…" else saveLabel) } },
        dismissButton = { TextButton(onClick = ::dismiss, enabled = !busy) { Text("取消") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") }, text = { Text("当前输入只保留在本次页面，离开后会清除。") }, confirmButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text("放弃") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable internal fun FinanceTextField(value: String, onChange: (String) -> Unit, label: String, busy: Boolean, money: Boolean = false, supporting: String? = null) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (money) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = { if (supporting != null) Text(supporting) })
}

@Composable internal fun <T> FinanceChoice(label: String, value: T, options: List<Pair<T, String>>, enabled: Boolean = true, onChange: (T) -> Unit) {
    var selecting by remember { mutableStateOf(false) }
    GroupedFormRow(label, options.find { it.first == value }?.second ?: "请选择", enabled) { selecting = true }
    if (selecting) ChoiceDialog(label, options, value, { onChange(it); selecting = false }, { selecting = false })
}

internal fun LedgerState.spendableAccounts(ledgerId: String, selected: String? = null): List<Account> {
    val virtual = obligations.map { it.virtualAccountId }.toSet()
    return accounts.filter { it.ledgerId == ledgerId && it.id !in virtual && (!it.archived || it.id == selected) }.sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.name })
}

/** Virtual account identifiers remain stable; their user-facing name belongs to the obligation. */
internal fun LedgerState.accountDisplayName(id: String?): String? = accounts.find { it.id == id }?.let { account ->
    obligations.find { it.virtualAccountId == account.id }?.title ?: account.name
}

@Composable internal fun AccountChoice(s: LedgerState, ledgerId: String, label: String, selected: String?, busy: Boolean, optional: Boolean = true, emptyLabel: String = "无账户", onChange: (String?) -> Unit) {
    val options: List<Pair<String?, String>> = (if (optional) listOf(null to emptyLabel) else emptyList()) + s.spendableAccounts(ledgerId, selected).map { it.id to (it.name + if (it.archived) " · 已归档" else "") }
    FinanceChoice(label, selected, options, !busy, onChange)
}

@Composable internal fun LedgerChoice(s: LedgerState, selected: String, busy: Boolean, onChange: (String) -> Unit) {
    FinanceChoice("账本", selected, s.ledgers.filter { !it.archived || it.id == selected }.map { it.id to it.name }, !busy, onChange)
}

internal fun accountTypeName(type: AccountType): String = when (type.name) {
    "CASH" -> "现金"; "BANK" -> "储蓄卡"; "CREDIT" -> "信用卡"; "E_WALLET" -> "网络支付"; "INVESTMENT" -> "投资"; "RECEIVABLE" -> "应收账户"; "PAYABLE" -> "应付账户"; else -> "充值卡"
}
internal fun obligationName(kind: ObligationKind): String = when (kind) { ObligationKind.BORROW -> "借入"; ObligationKind.LEND -> "借出"; ObligationKind.REIMBURSEMENT -> "报销" }
internal fun activityKindName(kind: ActivityKind): String = when (kind) { ActivityKind.EXPENSE -> "支出"; ActivityKind.INCOME -> "收入"; ActivityKind.REFUND -> "退款"; ActivityKind.TRANSFER -> "转账" }
internal fun frequencyName(frequency: RecurringFrequency): String = when (frequency) { RecurringFrequency.DAILY -> "每天"; RecurringFrequency.WEEKLY -> "每周"; RecurringFrequency.BIWEEKLY -> "每两周"; RecurringFrequency.MONTHLY -> "每月"; RecurringFrequency.YEARLY -> "每年"; RecurringFrequency.QUARTERLY -> "每季"; RecurringFrequency.WEEKDAYS -> "工作日"; RecurringFrequency.WEEKENDS -> "周末" }
internal fun signedMoney(text: String): Long = if (text.trim().startsWith("-")) -Money.parse(text.trim().removePrefix("-")) else if (text.trim() in listOf("0", "0.0", "0.00", "")) 0 else Money.parse(text)
internal fun defaultLedger(s: LedgerState, selected: String?): String = selected?.takeIf { id -> s.ledgers.any { it.id == id && !it.archived } } ?: s.ledgers.firstOrNull { !it.archived }?.id ?: ""
