package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.*
import com.dawnyew.presentation.AppController

@Composable internal fun LedgerManagement(s: LedgerState, controller: AppController, onConfirm: (Confirmation) -> Unit) {
    var show by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<Ledger?>(null) }
    val ui by controller.ui.collectAsState(); val pending by controller.pendingOperation.collectAsState()
    val busy = ui.busy || pending != null
    val ledgers = s.ledgers.sortedWith(compareBy<Ledger> { it.sortOrder }.thenBy { it.id })
    LongPressReorderableList(ledgers, key = { it.id }, label = { it.name }, busy = busy,
        sourceVersion = s.datasetId to s.contentVersion,
        onCommit = { ids -> controller.perform(Command.ReorderLedgers(ids, s.contentVersion)) },
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), spacing = 16.dp,
        header = { Button(onClick = { edit = null; show = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("新建账本") } },
        footer = { Text("长按账本拖动调整顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) { ledger ->
        var menu by remember(ledger.id) { mutableStateOf(false) }
        ReorderSettingRow(
            title = ledger.name,
            description = "${s.entries.count { it.ledgerId == ledger.id && it.active }} 笔有效账单" + if (ledger.archived) " · 已归档" else "",
            enabled = !busy,
            trailing = {
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(22.dp).semantics { contentDescription = "管理账本${ledger.name}" }) { Text("⋮", fontSize = 18.sp) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(enabled = !busy, text = { Text("重命名") }, onClick = { menu = false; edit = ledger; show = true })
                        DropdownMenuItem(enabled = !busy, text = { Text(if (ledger.archived) "取消归档" else "归档") }, onClick = { menu = false; onConfirm(Confirmation(if (ledger.archived) "取消归档账本？" else "归档账本？", if (ledger.archived) "取消归档后可继续记账。" else "历史数据保留，归档后不能新增或修改其中的账单。") { controller.perform(Command.UpdateLedger(ledger.id, ledger.name, !ledger.archived, ledger.sortOrder)) }) })
                        DropdownMenuItem(enabled = !busy, text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onConfirm(Confirmation("删除空账本？", "会同时删除该账本的预算与无引用分类。有账单的账本请使用归档。") { controller.perform(Command.DeleteLedger(ledger.id)) }) })
                    }
                }
            },
        )
    }
    if (show) NameDialog(if (edit == null) "新建账本" else "重命名账本", edit?.name.orEmpty(), { show = false }) { name -> val ledger = edit; controller.perform(if (ledger == null) Command.CreateLedger(name) else Command.UpdateLedger(ledger.id, name, ledger.archived, ledger.sortOrder)) { show = false } }
}

@Composable internal fun CategoryManagement(s: LedgerState, defaultLedger: String?, controller: AppController, onConfirm: (Confirmation) -> Unit, onActivity: (ActivityScope) -> Unit) {
    var ledger by rememberSaveable { mutableStateOf(defaultLedger ?: s.ledgers.filterNot { it.archived }.minWith(compareBy<Ledger> { it.sortOrder }.thenBy { it.id }).id) }
    var direction by rememberSaveable { mutableStateOf(Direction.EXPENSE) }
    var picker by remember { mutableStateOf(false) }; var show by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<Category?>(null) }
    val ui by controller.ui.collectAsState(); val pending by controller.pendingOperation.collectAsState()
    val busy = ui.busy || pending != null
    val writable = s.ledgers.any { it.id == ledger && !it.archived }
    val categories = s.categories.filter { it.ledgerId == ledger && it.direction == direction }.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
    key(ledger, direction) {
        LongPressReorderableList(categories, key = { it.id }, label = { it.name }, busy = busy || !writable,
            sourceVersion = s.datasetId to s.contentVersion,
            onCommit = { ids -> controller.perform(Command.ReorderCategories(ledger, direction, ids, s.contentVersion)) },
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), spacing = 16.dp,
            header = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picker = true }, enabled = !busy) { Text("账本：" + s.ledgers.find { it.id == ledger }?.name.orEmpty() + " ▾") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(direction == Direction.EXPENSE, { direction = Direction.EXPENSE }, label = { Text("支出") }); FilterChip(direction == Direction.INCOME, { direction = Direction.INCOME }, label = { Text("收入") }) }
                Button(onClick = { edit = null; show = true }, enabled = !busy && writable, modifier = Modifier.fillMaxWidth()) { Text("新建分类") }
                if (!writable) Text("账本已归档，取消归档后可修改分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            footer = { Text("长按分类拖动排序，点名称查看流水", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) { category ->
            var menu by remember(category.id) { mutableStateOf(false) }
            ReorderSettingRow(
                title = category.name,
                description = (if (category.direction == Direction.EXPENSE) "支出分类" else "收入分类") + if (category.enabled) " · 已启用" else " · 已停用",
                checked = category.enabled,
                enabled = !busy,
                toggleEnabled = !busy && writable,
                toggleLabel = "启用${category.name}",
                onToggle = { controller.perform(Command.SaveCategory(category.ledgerId, category.direction, category.name, category.id, it, category.sortOrder)) },
                onClick = { onActivity(ActivityScope(ledgerIds = setOf(category.ledgerId), categoryIds = setOf(category.id))) },
                trailing = {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy && writable, modifier = Modifier.size(22.dp).semantics { contentDescription = "管理分类${category.name}" }) { Text("⋮", fontSize = 18.sp) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(enabled = !busy && writable, text = { Text("重命名") }, onClick = { menu = false; edit = category; show = true })
                            DropdownMenuItem(enabled = !busy && writable, text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onConfirm(Confirmation("删除分类？", "已有账单引用的分类无法删除，请使用停用。") { controller.perform(Command.DeleteCategory(category.id)) }) })
                        }
                    }
                },
            )
        }
    }
    if (picker) ChoiceDialog("选择账本", s.ledgers.sortedWith(compareBy<Ledger> { it.sortOrder }.thenBy { it.id }).map { it.id to (it.name + if (it.archived) " · 已归档" else "") }, ledger, { ledger = it; picker = false }, { picker = false })
    if (show) NameDialog(if (edit == null) "新建分类" else "重命名分类", edit?.name.orEmpty(), { show = false }) { name -> controller.perform(Command.SaveCategory(ledger, direction, name, edit?.id, edit?.enabled ?: true, edit?.sortOrder ?: (s.categories.filter { it.ledgerId == ledger && it.direction == direction }.maxOfOrNull { it.sortOrder } ?: -1) + 1)) { show = false } }
}

@Composable internal fun NameDialog(title:String,initial:String,onDismiss:()->Unit,onSave:(String)->Unit) {var name by remember {mutableStateOf(initial)};AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={OutlinedTextField(name,{name=it},label={Text("名称")},singleLine=true)},confirmButton={TextButton(onClick={onSave(name)},enabled=name.isNotBlank()) {Text("保存")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})}
