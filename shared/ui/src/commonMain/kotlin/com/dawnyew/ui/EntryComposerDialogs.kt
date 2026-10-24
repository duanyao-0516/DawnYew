package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.EditorChange
import com.dawnyew.presentation.EditorDraft

/** Only one auxiliary field editor is open; amount and note always remain on the main page. */
@Composable internal fun EntryComposerDialogs(
    s: LedgerState, draft: EditorDraft, busy: Boolean, ui: EntryComposerState,
    categories: List<Category>, onChange: (EditorChange) -> Unit,
    newId: () -> String, onSaveDimension: (Dimension, String, () -> Unit) -> Unit,
) {
    when (val target = ui.dialog) {
        null -> Unit
        EntryComposerDialog.Ledger -> ChoiceDialog("选择账本", s.ledgers.filter { !it.archived }.sortedWith(compareBy<Ledger> { it.sortOrder }.thenBy { it.id }).map { it.id to it.name }, draft.ledgerId,
            { id -> if (!busy) { onChange(EditorChange.Ledger(id)); ui.closeDialog() } }, ui::closeDialog)
        EntryComposerDialog.Category -> ChoiceDialog("全部分类", categories.map { it.id to it.name }, draft.categoryId,
            { id -> if (!busy && id != null) { onChange(EditorChange.Category(id)); ui.closeDialog() } }, ui::closeDialog)
        EntryComposerDialog.Account -> draft.ledgerId?.let { ledger ->
            val accounts: List<Pair<String?, String>> = listOf(null to "无账户") + s.spendableAccounts(ledger, draft.accountId).map { it.id to (it.name + if (it.archived) " · 已归档" else "") }
            ChoiceDialog("选择账户", accounts, draft.accountId, { id -> if (!busy) { onChange(EditorChange.Account(id)); ui.closeDialog() } }, ui::closeDialog)
        }
        EntryComposerDialog.Tags -> {
            val tags = s.dimensions.filter { it.kind == DimensionKind.TAG && it.ledgerId == draft.ledgerId && (!it.archived || it.id in draft.tagIds) }
                .sortedWith(compareBy<Dimension> { it.sortOrder }.thenBy { it.id })
            AlertDialog(onDismissRequest = ui::closeDialog, title = { Text("选择标签") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (tags.isEmpty()) Text("还没有标签，可先新增。", style = MaterialTheme.typography.bodyMedium)
                    tags.forEach { tag -> Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tag.id in draft.tagIds, { onChange(EditorChange.Tags(if (it) draft.tagIds + tag.id else draft.tagIds - tag.id)) }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "标签${tag.name}" })
                        Text(tag.name, Modifier.weight(1f))
                    } }
                }
            }, confirmButton = { TextButton(onClick = ui::closeDialog) { Text("完成") } }, dismissButton = {
                TextButton(onClick = { ui.openDialog(EntryComposerDialog.NewDimension(DimensionKind.TAG)) }, enabled = !busy && draft.ledgerId != null) { Text("新增标签") }
            })
        }
        EntryComposerDialog.Date, EntryComposerDialog.Time -> {
            val date = target == EntryComposerDialog.Date
            AlertDialog(onDismissRequest = ui::closeDialog, title = { Text(if (date) "记账日期" else "记账时间") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (date) FinanceTextField(draft.date, { ui.dialogError = null; onChange(EditorChange.Date(it)) }, "日期 YYYY-MM-DD", busy)
                    else FinanceTextField(draft.time, { ui.dialogError = null; onChange(EditorChange.Time(it)) }, "时间 HH:mm:ss", busy)
                    ui.dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { TextButton(onClick = {
                try {
                    validateOccurrence(draft.date + "T" + if (draft.time.length == 5) draft.time + ":00" else draft.time)
                    ui.closeDialog()
                } catch (error: DomainException) { ui.dialogError = error.message }
            }, enabled = !busy) { Text("完成") } })
        }
        EntryComposerDialog.More -> AlertDialog(onDismissRequest = ui::closeDialog, title = { Text("更多选项") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(DimensionKind.PROJECT to draft.projectId, DimensionKind.MERCHANT to draft.merchantId).forEach { (kind, id) ->
                    EntryComposerRow(dimensionName(kind), s.dimensions.find { it.id == id }?.name ?: "未选择", !busy && draft.ledgerId != null) { ui.openDialog(EntryComposerDialog.DimensionPicker(kind)) }
                }
                EntryComposerToggle("已入账", draft.recorded, !busy) { onChange(EditorChange.Recorded(it)) }
                Text("未入账只保留流水，暂不影响账户余额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (draft.kind == EntryKind.EXPENSE) EntryComposerToggle("不计入预算", draft.excludedFromBudget, !busy) { onChange(EditorChange.ExcludedFromBudget(it)) }
                else if (draft.kind == EntryKind.REFUND) Text("预算资格跟随原支出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EntryComposerToggle("已对账", draft.reconciled, !busy) { onChange(EditorChange.Reconciled(it)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DimensionKind.entries.forEach { kind -> TextButton(onClick = { ui.openDialog(EntryComposerDialog.NewDimension(kind)) }, enabled = !busy && draft.ledgerId != null) { Text("新增${dimensionName(kind)}") } }
                }
            }
        }, confirmButton = { TextButton(onClick = ui::closeDialog) { Text("完成") } })
        is EntryComposerDialog.DimensionPicker -> {
            val kind = target.kind
            val selected = if (kind == DimensionKind.PROJECT) draft.projectId else draft.merchantId
            val values = listOf(null to "无${dimensionName(kind)}") + s.dimensions.filter { it.kind == kind && it.ledgerId == draft.ledgerId && (!it.archived || it.id == selected) }
                .sortedWith(compareBy<Dimension> { it.sortOrder }.thenBy { it.id }).map { it.id to (it.name + if (it.archived) " · 已归档" else "") }
            ChoiceDialog(dimensionName(kind), values, selected, { id -> if (!busy) {
                onChange(if (kind == DimensionKind.PROJECT) EditorChange.Project(id) else EditorChange.Merchant(id)); ui.closeDialog()
            } }, ui::closeDialog)
        }
        is EntryComposerDialog.NewDimension -> draft.ledgerId?.let { ledger ->
            DimensionEditor(s, target.kind, null, ledger, busy, newId, { action, id, done ->
                if (action is FinanceAction.SaveDimension) onSaveDimension(action.value, id, done) else invalid("不支持此维度操作")
            }, {}, ui::closeDialog)
        }
    }
}
