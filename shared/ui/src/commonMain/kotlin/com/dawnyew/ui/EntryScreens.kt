package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.dawnyew.core.*
import com.dawnyew.presentation.EditorChange
import com.dawnyew.presentation.AmountCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EntryForm(
    s: LedgerState, draft: com.dawnyew.presentation.EditorDraft, busy: Boolean,
    onChange: (EditorChange) -> Unit, onSave: (Boolean) -> Unit, onCapture: () -> Unit,
    onRetryDuplicateCheck: () -> Unit, newId: () -> String,
    onSaveDimension: (Dimension, String, () -> Unit) -> Unit, onTransfer: (String) -> Unit,
) {
    val bindings = rememberUpdatedState(EntryComposerBindings(draft, busy, onChange, onSave, onTransfer))
    val amountFocus = remember(draft.operationId) { androidx.compose.ui.focus.FocusRequester() }
    val noteFocus = remember(draft.operationId) { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val contentScroll = key(draft.operationId) { rememberScrollState() }
    val editing = draft.editId != null
    val refundParent = draft.refundParentId?.let { id -> s.entries.find { it.id == id } }
    val ordinary = !editing && refundParent == null && draft.capture == null
    val continueDefault = ordinary && s.preferences.find { it.key == "continueEntry" }?.value == "true"
    val direction = if (draft.kind == EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
    val categories = s.categories.filter { it.ledgerId == draft.ledgerId && it.direction == direction && (it.enabled || it.id == draft.categoryId) }
        .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id })
    val canSave = !busy && !draft.duplicateCheckPending && draft.duplicateCheckError == null &&
        (draft.capture == null || draft.confirmed) && (draft.capture?.duplicateWarning != true || draft.duplicateAcknowledged)

    // Capacity is measured before the one IME padding, using the Scaffold's actual content body.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fitsCalculator = entryCalculatorFits(maxHeight.value, density.fontScale)
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val ui = remember(draft.operationId) {
            EntryComposerState(
                if (draft.capture == null && maxHeight >= 600.dp && density.fontScale <= 1.4f && fitsCalculator) EntryInputMode.CALCULATOR else EntryInputMode.NONE,
                draft.amount.length, draft.note.length,
            )
        }
        fun change(event: EditorChange) { ui.error = null; bindings.value.onChange(event) }
        fun save(keep: Boolean) {
            val current = bindings.value
            if (current.busy) return
            try { current.draft.command(); current.onSave(keep) } catch (error: DomainException) { ui.error = error.message }
        }
        fun transfer() {
            val current = bindings.value
            if (current.busy) return
            try {
                val expression = current.draft.amount
                val amount = if (expression.isBlank()) "" else Money.format(AmountCalculator.evaluate(expression))
                ui.error = null
                current.onTransfer(amount)
            } catch (error: DomainException) { ui.error = error.message }
        }
        fun amountKey(key: String) {
            val current = bindings.value
            if (current.busy) return
            val result = editEntryAmount(current.draft.amount, ui.amountSelection, key)
            ui.amountSelection = result.selection
            ui.error = result.error
            if (result.error == null && result.text != current.draft.amount) current.onChange(EditorChange.Amount(result.text))
        }
        val requestAmount = {
            if (!bindings.value.busy) ui.requestInput(if (fitsCalculator) EntryInputMode.CALCULATOR else EntryInputMode.SYSTEM_AMOUNT)
        }
        val requestNote = { if (!bindings.value.busy) ui.requestInput(EntryInputMode.NOTE) }
        val openDialog: (EntryComposerDialog) -> Unit = { if (!bindings.value.busy) ui.openDialog(it) }
        val mode = ui.inputMode
        val request = ui.inputRequest
        LaunchedEffect(mode, request, fitsCalculator) {
            if (ui.inputMode != mode || ui.inputRequest != request) return@LaunchedEffect
            when (mode) {
                EntryInputMode.NONE -> { focusManager.clearFocus(); keyboard?.hide() }
                EntryInputMode.CALCULATOR -> if (fitsCalculator) { amountFocus.requestFocus(); keyboard?.hide() }
                    else ui.requestInput(EntryInputMode.SYSTEM_AMOUNT)
                EntryInputMode.NOTE -> { noteFocus.requestFocus(); keyboard?.show() }
                EntryInputMode.SYSTEM_AMOUNT -> { amountFocus.requestFocus(); keyboard?.show() }
            }
        }
        LaunchedEffect(ui.error) { if (ui.error != null) { withFrameNanos { }; contentScroll.animateScrollTo(contentScroll.maxValue) } }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (refundParent != null) Text("为原支出 ¥ ${Money.format(refundParent.amountMinor)} 添加退款", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    EntryOcrReview(draft, busy, { change(it) }, onRetryDuplicateCheck)
                    EntryAmountCategorySection(draft, busy, ordinary, ui,
                        s.ledgers.find { it.id == draft.ledgerId }?.name ?: "选择账本", categories, amountFocus,
                        onAmountClick = requestAmount,
                        onAmountChange = { value ->
                            ui.amountSelection = value.selection
                            if (value.text != bindings.value.draft.amount) change(EditorChange.Amount(value.text))
                        },
                        onChange = { change(it) }, onDialog = openDialog, onTransfer = { transfer() },
                    )
                    EntryMetadataSection(s, draft, busy, ordinary, ui, noteFocus,
                        onNoteClick = requestNote,
                        onNoteChange = { value ->
                            ui.noteSelection = value.selection
                            ui.noteComposition = value.composition
                            if (value.text != bindings.value.draft.note) change(EditorChange.Note(value.text))
                        },
                        onChange = { change(it) }, onDialog = openDialog,
                        onCapture = { ui.requestInput(EntryInputMode.NONE); onCapture() },
                    )
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                EntryKeyboardAndSave(
                    calculatorVisible = ui.inputMode == EntryInputMode.CALCULATOR && fitsCalculator && !imeVisible,
                    busy = busy, canSave = canSave,
                    primaryLabel = if (editing) "保存修改" else if (refundParent != null) "确认退款" else if (continueDefault) "保存并继续" else "保存账单",
                    secondaryLabel = if (!ordinary) null else if (continueDefault) "保存并返回" else "保存并继续",
                    onKey = { amountKey(it) }, onPrimary = { save(continueDefault) }, onSecondary = { save(!continueDefault) },
                )
            }
        }
        EntryComposerDialogs(s, draft, busy, ui, categories, { change(it) }, newId, onSaveDimension)
    }
}

@Composable internal fun EntryDetail(s: LedgerState, e: Entry, busy: Boolean, onEdit: () -> Unit, onRefund: () -> Unit, onDelete: () -> Unit, onMove: (String, String, () -> Unit) -> Unit, onOpenEntry: (String) -> Unit = {}) {
    var move by remember { mutableStateOf(false) }
    val archived = s.ledgers.find { it.id == e.ledgerId }?.archived == true
    val writable = !busy && !archived && e.active
    val refunds = s.entries.filter { it.parentExpenseId == e.id && it.active }.sortedWith(compareByDescending<Entry> { it.occurrence }.thenByDescending { it.id })
    val refundTotal = refunds.fold(0L) { total, item -> Money.add(total, item.amountMinor) }
    val details = s.entryDetails.find { it.entryId == e.id }
    val label = when (e.kind) { EntryKind.EXPENSE -> "支出"; EntryKind.INCOME -> "收入"; EntryKind.REFUND -> "退款" }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { DashboardCard {
            CardHeading(s.categories.find { it.id == e.categoryId }?.name ?: label, label + if (!e.active) " · 已删除" else "")
            FinanceAmount(e.amountMinor, large = true, color = if (e.kind == EntryKind.EXPENSE) FinanceColors.expense else MaterialTheme.colorScheme.primary)
            if (refundTotal > 0) Text("已退 ${Money.format(refundTotal)} · 净支出 ${Money.format(Money.add(e.amountMinor, -refundTotal))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit, enabled = writable, modifier = Modifier.weight(1f)) { Text("编辑账单") }
                if (e.kind == EntryKind.EXPENSE) OutlinedButton(onClick = onRefund, enabled = writable && refundTotal < e.amountMinor, modifier = Modifier.weight(1f)) { Text("添加退款") }
            }
        } }
        item { DashboardCard {
            DetailFact("账本", s.ledgers.find { it.id == e.ledgerId }?.name.orEmpty())
            DetailFact("时间", e.occurrence.replace('T', ' '))
            DetailFact("账户", s.accountDisplayName(details?.accountId) ?: "无账户")
            DetailFact("入账状态", if (details?.recorded != false) "已入账" else "未入账")
            DetailFact("对账状态", if (details?.reconciled == true) "已对账" else "未对账")
            DetailFact("预算", if (effectiveBudgetExcluded(s, e)) "不计入预算" else "计入预算")
            if (e.note.isNotBlank()) DetailFact("备注", e.note)
            details?.projectId?.let { id -> DetailFact("项目", s.dimensions.find { it.id == id }?.name.orEmpty()) }
            details?.merchantId?.let { id -> DetailFact("商家", s.dimensions.find { it.id == id }?.name.orEmpty()) }
            val tags = s.entryTags.filter { it.entryId == e.id }.mapNotNull { tag -> s.dimensions.find { it.id == tag.tagId }?.name }
            if (tags.isNotEmpty()) DetailFact("标签", tags.joinToString("、"))
            DetailFact("来源", when (e.originKind) { OriginKind.MANUAL -> "手动记账"; OriginKind.OCR -> "截图识别后确认"; OriginKind.IMPORT -> "文件导入" })
        } }
        if (e.kind == EntryKind.REFUND) s.entries.find { it.id == e.parentExpenseId }?.let { parent -> item { DashboardCard {
            CardHeading("原支出")
            BusinessRecordRow(s.categories.find { it.id == parent.categoryId }?.name ?: "支出", parent.occurrence.replace('T', ' ') + if (parent.note.isNotBlank()) " · ${parent.note}" else "", parent.amountMinor, FinanceColors.expense) { onOpenEntry(parent.id) }
        } } }
        if (refunds.isNotEmpty()) {
            item { Text("退款记录 · ${refunds.size} 笔", style = MaterialTheme.typography.titleMedium) }
            items(refunds, key = { it.id }) { refund -> DashboardCard {
                BusinessRecordRow("退款", refund.occurrence.replace('T', ' ') + if (refund.note.isNotBlank()) " · ${refund.note}" else "", refund.amountMinor, MaterialTheme.colorScheme.primary) { onOpenEntry(refund.id) }
            } }
        }
        if (archived) item { Text("此账本已归档，取消归档后可修改。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        if (!e.active) item { Text("此记录已移入回收站，可在回收站恢复。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.kind != EntryKind.REFUND) OutlinedButton(onClick = { move = true }, enabled = writable, modifier = Modifier.weight(1f)) { Text("移动到账本") }
            TextButton(onClick = onDelete, enabled = writable, modifier = Modifier.weight(1f)) { Text("移入回收站", color = MaterialTheme.colorScheme.error) }
        } }
    }
    if (move) MoveDialog(s, e, { move = false }) { ledger, category -> onMove(ledger, category) { move = false } }
}

@Composable private fun MoveDialog(s:LedgerState,e:Entry,onDismiss:()->Unit,onMove:(String,String)->Unit) {
    var ledger by remember {mutableStateOf(e.ledgerId)};var category by remember {mutableStateOf(e.categoryId)}
    val direction=if(e.kind==EntryKind.INCOME) Direction.INCOME else Direction.EXPENSE
    AlertDialog(onDismissRequest=onDismiss,title={Text("移动账单")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("原支出的有效退款将一起移动。")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {s.ledgers.filter {!it.archived}.forEach {l->FilterChip(ledger==l.id,{ledger=l.id;category=s.categories.filter {it.ledgerId==l.id && it.direction==direction && it.enabled}.minWithOrNull(compareBy<Category> {it.sortOrder}.thenBy {it.id})?.id.orEmpty()},label={Text(l.name)})}}
        Text("目标分类")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {s.categories.filter {it.ledgerId==ledger && it.direction==direction && it.enabled}.forEach {c->FilterChip(category==c.id,{category=c.id},label={Text(c.name)})}}
    }},confirmButton={TextButton(onClick={onMove(ledger,category)},enabled=category.isNotEmpty()) {Text("确认移动")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
