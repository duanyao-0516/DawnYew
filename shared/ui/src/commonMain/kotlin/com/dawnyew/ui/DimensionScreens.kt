package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.*

internal fun dimensionName(kind: DimensionKind): String = when (kind) { DimensionKind.PROJECT -> "项目"; DimensionKind.MERCHANT -> "商家"; DimensionKind.TAG -> "标签" }

@Composable internal fun DimensionsPage(s: LedgerState, kind: DimensionKind, selectedLedger: String?, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onActivity: (ActivityScope) -> Unit) {
    var ledger by rememberSaveable(kind) { mutableStateOf(defaultLedger(s, selectedLedger)) }; var show by remember { mutableStateOf(false) }; var edit by remember { mutableStateOf<Dimension?>(null) }; var archived by rememberSaveable(kind) { mutableStateOf(false) }
    val complete = s.dimensions.filter { it.ledgerId == ledger && it.kind == kind }.sortedWith(compareBy<Dimension> { it.sortOrder }.thenBy { it.id })
    val values = complete.filter { archived || !it.archived }
    val writable = s.ledgers.any { it.id == ledger && !it.archived }
    key(ledger, kind, archived) {
        LongPressReorderableList(values, key = { it.id }, label = { it.name }, busy = busy || !writable,
            sourceVersion = s.datasetId to s.contentVersion,
            onCommit = { ids -> dispatch(FinanceAction.ReorderDimensions(ledger, kind, mergeVisibleOrder(complete.map { it.id }, ids), s.contentVersion), newId()) {} },
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), spacing = 16.dp,
            header = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DashboardCard { LedgerChoice(s, ledger, busy) { ledger = it }; FinanceToggle("显示归档", value = archived, enabled = !busy) { archived = it } }
                Button(onClick = { edit = null; show = true }, enabled = !busy && writable, modifier = Modifier.fillMaxWidth()) { Text("新增${dimensionName(kind)}") }
                if (values.isEmpty()) EmptyState("暂无${dimensionName(kind)}", "创建后可在记账时选择，并查看关联流水。")
            } },
            footer = { Text("长按${dimensionName(kind)}拖动调整顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        ) { value ->
            var menu by remember(value.id) { mutableStateOf(false) }
            ReorderSettingRow(
                title = value.name,
                description = if (value.archived) "已归档 · 点击查看关联流水" else "点击查看关联流水",
                enabled = !busy,
                onClick = { onActivity(when (kind) { DimensionKind.PROJECT -> ActivityScope(ledgerIds = setOf(ledger), projectIds = setOf(value.id)); DimensionKind.MERCHANT -> ActivityScope(ledgerIds = setOf(ledger), merchantIds = setOf(value.id)); DimensionKind.TAG -> ActivityScope(ledgerIds = setOf(ledger), tagIds = setOf(value.id)) }) },
                trailing = {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy && writable, modifier = Modifier.size(22.dp).semantics { contentDescription = "管理${dimensionName(kind)}${value.name}" }) { Text("⋮", fontSize = 18.sp) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(enabled = !busy && writable, text = { Text("重命名") }, onClick = { menu = false; edit = value; show = true })
                            DropdownMenuItem(enabled = !busy && writable, text = { Text(if (value.archived) "取消归档" else "归档") }, onClick = { menu = false; dispatch(FinanceAction.SaveDimension(value.copy(archived = !value.archived), value.revision), newId()) {} })
                            DropdownMenuItem(enabled = !busy && writable, text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dispatch(FinanceAction.DeleteDimension(value.id, value.revision), newId()) {} })
                        }
                    }
                },
            )
        }
    }
    if (show) DimensionEditor(s, kind, edit, ledger, busy, newId, dispatch, {}) { show = false }
}

@Composable internal fun DimensionEditor(s: LedgerState, kind: DimensionKind, original: Dimension?, ledger: String, busy: Boolean, newId: () -> String, dispatch: FinanceDispatch, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val id = remember { original?.id ?: newId() }; val operationId = remember { newId() }; var name by remember { mutableStateOf(original?.name.orEmpty()) }; var dirty by remember { mutableStateOf(false) }
    FinanceFormDialog(if (original == null) "新增${dimensionName(kind)}" else "重命名${dimensionName(kind)}", busy, dirty, onDismiss, onSave = { dispatch(FinanceAction.SaveDimension(Dimension(id, ledger, kind, name, original?.archived ?: false, original?.sortOrder ?: ((s.dimensions.filter { it.ledgerId == ledger && it.kind == kind }.maxOfOrNull { it.sortOrder } ?: -1) + 1), original?.revision ?: 1), original?.revision), operationId) { onCreated(id); onDismiss() } }) { FinanceTextField(name, { name = it; dirty = true }, "名称", busy) }
}
