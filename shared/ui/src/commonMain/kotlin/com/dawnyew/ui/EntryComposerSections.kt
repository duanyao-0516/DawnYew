package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.*
import com.dawnyew.presentation.EditorChange
import com.dawnyew.presentation.EditorDraft
import kotlinx.coroutines.flow.collect

/** Observe the field's completed taps without consuming selection or text gestures. */
@Composable private fun entryFieldInteractions(onClick: () -> Unit): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    val currentClick by rememberUpdatedState(onClick)
    LaunchedEffect(source) { source.interactions.collect { if (it is PressInteraction.Release) currentClick() } }
    return source
}

@Composable internal fun EntryAmountCategorySection(
    draft: EditorDraft, busy: Boolean, ordinary: Boolean, ui: EntryComposerState,
    ledgerName: String, categories: List<Category>, amountFocus: FocusRequester,
    onAmountClick: () -> Unit, onAmountChange: (TextFieldValue) -> Unit,
    onChange: (EditorChange) -> Unit, onDialog: (EntryComposerDialog) -> Unit, onTransfer: () -> Unit,
) {
    val interactions = entryFieldInteractions(onAmountClick)
    val accent = if (draft.kind == EntryKind.EXPENSE) FinanceColors.expense else MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (draft.kind == EntryKind.REFUND) EntryComposerChip("退款", selected = true, enabled = false, filled = true, modifier = Modifier.weight(1f)) {}
                else {
                    EntryComposerChip("支出", draft.kind == EntryKind.EXPENSE, !busy, Modifier.weight(1f), filled = true) { onChange(EditorChange.Kind(EntryKind.EXPENSE)) }
                    EntryComposerChip("收入", draft.kind == EntryKind.INCOME, !busy, Modifier.weight(1f), filled = true) { onChange(EditorChange.Kind(EntryKind.INCOME)) }
                    if (ordinary) EntryComposerChip("转账", enabled = !busy, modifier = Modifier.weight(1f), filled = true, onClick = onTransfer)
                }
                TextButton(onClick = { onDialog(EntryComposerDialog.Ledger) }, enabled = !busy && draft.kind != EntryKind.REFUND,
                    modifier = Modifier.weight(1.35f).heightIn(min = 32.dp).semantics { contentDescription = "选择记账账本" }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                    Text("$ledgerName ▾", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("¥", fontSize = 24.sp, color = accent)
                BasicTextField(
                    value = TextFieldValue(draft.amount, ui.amountSelection.within(draft.amount.length)),
                    onValueChange = onAmountChange, enabled = !busy, readOnly = ui.inputMode != EntryInputMode.SYSTEM_AMOUNT,
                    singleLine = true, interactionSource = interactions,
                    textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, color = accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { ui.requestInput(EntryInputMode.NONE) }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).focusRequester(amountFocus).semantics {
                        contentDescription = "金额输入"
                        onClick("输入金额") { if (busy) false else { onAmountClick(); true } }
                    },
                    decorationBox = { field -> Box {
                        if (draft.amount.isEmpty()) Text("0.00", style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
                        field()
                    } },
                )
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(draft.date.substringAfter('-').replace('-', '.'),
                        Modifier.clickable(enabled = !busy) { onDialog(EntryComposerDialog.Date) }.heightIn(min = 22.dp).padding(horizontal = 4.dp, vertical = 2.dp)
                            .semantics { contentDescription = "编辑记账日期" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.primary)
                    Text(draft.time.take(5),
                        Modifier.clickable(enabled = !busy) { onDialog(EntryComposerDialog.Time) }.heightIn(min = 22.dp).padding(horizontal = 4.dp, vertical = 2.dp)
                            .semantics { contentDescription = "编辑记账时间" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            val columns = if (LocalDensity.current.fontScale > 1.4f) 2 else 3
            val more = categories.size > columns * 2
            val preview = entryCategoryPreview(categories, draft.categoryId, columns * 2 - if (more) 1 else 0)
            val choices = preview.map { it to it.name } + if (more) listOf(null to "全部") else emptyList()
            choices.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (category, title) -> EntryComposerChip(title, category != null && draft.categoryId == category.id,
                        !busy && draft.kind != EntryKind.REFUND,
                        Modifier.weight(1f).semantics { contentDescription = category?.let { "分类${it.name}" } ?: "全部分类" }, accent = accent) {
                        if (category == null) onDialog(EntryComposerDialog.Category) else onChange(EditorChange.Category(category.id))
                    } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (categories.isEmpty()) Text(if (draft.ledgerId == null) "先选择本次记账的账本" else "当前没有可用分类，请先在「我的」中创建", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun EntryMetadataSection(
    s: LedgerState, draft: EditorDraft, busy: Boolean, ordinary: Boolean, ui: EntryComposerState,
    noteFocus: FocusRequester, onNoteClick: () -> Unit, onNoteChange: (TextFieldValue) -> Unit,
    onChange: (EditorChange) -> Unit, onDialog: (EntryComposerDialog) -> Unit, onCapture: () -> Unit,
) {
    val interactions = entryFieldInteractions(onNoteClick)
    val tags = s.dimensions.filter { it.kind == DimensionKind.TAG && it.ledgerId == draft.ledgerId && (!it.archived || it.id in draft.tagIds) }
        .sortedWith(compareBy<Dimension> { it.sortOrder }.thenBy { it.id })
    val quickTags = (tags.filter { it.id in draft.tagIds } + tags).distinctBy { it.id }.take(3)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            EntryComposerRow("账户", s.accountDisplayName(draft.accountId) ?: "无账户", !busy && draft.ledgerId != null, Modifier.semantics { contentDescription = "选择记账账户" }) { onDialog(EntryComposerDialog.Account) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("标签", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    quickTags.forEach { tag -> EntryComposerChip(tag.name, tag.id in draft.tagIds, !busy, Modifier.semantics { contentDescription = "标签${tag.name}" }, small = true) {
                        onChange(EditorChange.Tags(if (tag.id in draft.tagIds) draft.tagIds - tag.id else draft.tagIds + tag.id))
                    } }
                    if (tags.isEmpty()) TextButton(onClick = { onDialog(EntryComposerDialog.NewDimension(DimensionKind.TAG)) }, enabled = !busy && draft.ledgerId != null, contentPadding = PaddingValues(2.dp)) { Text("新增标签", style = MaterialTheme.typography.bodyMedium) }
                }
                TextButton(onClick = { onDialog(EntryComposerDialog.Tags) }, enabled = !busy && draft.ledgerId != null, modifier = Modifier.semantics { contentDescription = "全部标签" }, contentPadding = PaddingValues(4.dp)) {
                    Text(if (draft.tagIds.size > quickTags.count { it.id in draft.tagIds }) "+${draft.tagIds.size - quickTags.count { it.id in draft.tagIds }}" else "全部", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("备注", Modifier.clickable(enabled = !busy, onClick = onNoteClick).padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    BasicTextField(
                        value = TextFieldValue(draft.note, ui.noteSelection.within(draft.note.length), ui.noteComposition?.within(draft.note.length)),
                        onValueChange = onNoteChange, enabled = !busy, minLines = 1, maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        interactionSource = interactions, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().focusRequester(noteFocus).semantics {
                            contentDescription = "备注输入"
                            onClick("输入备注") { if (busy) false else { onNoteClick(); true } }
                        },
                        decorationBox = { field -> Box {
                            if (draft.note.isEmpty()) Text("添加备注", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        } },
                    )
                    if (ui.inputMode == EntryInputMode.NOTE) Text("${runCatching { codePointCount(draft.note) }.getOrDefault(draft.note.length)}/500", Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = buildList {
                    listOfNotNull(draft.projectId, draft.merchantId).forEach { id -> s.dimensions.find { it.id == id }?.name?.let(::add) }
                    if (!draft.recorded) add("未入账")
                    if (draft.excludedFromBudget && draft.kind == EntryKind.EXPENSE) add("不计预算")
                    if (draft.reconciled) add("已对账")
                }
                EntryComposerRow("更多", labels.joinToString(" · ").ifBlank { "项目、商家及记账选项" }, !busy, Modifier.weight(1f).semantics { contentDescription = "更多选项" }) { onDialog(EntryComposerDialog.More) }
                if (ordinary) IconButton(onClick = onCapture, enabled = !busy, modifier = Modifier.size(28.dp).semantics { contentDescription = "从本地截图识别" }) { AppGlyph("相机", Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable internal fun EntryOcrReview(draft: EditorDraft, busy: Boolean, onChange: (EditorChange) -> Unit, onRetry: () -> Unit) {
    val capture = draft.capture ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("截图识别草稿 · 尚未记账", fontWeight = FontWeight.SemiBold)
            (capture.warnings + draft.duplicateMessages).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (draft.duplicateCheckPending) Text("正在核对本机疑似重复记录…")
            draft.duplicateCheckError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry, enabled = !busy) { Text("重试核对") } }
            val mayConfirm = !busy && !draft.duplicateCheckPending && draft.duplicateCheckError == null
            if (capture.duplicateWarning) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.duplicateAcknowledged, { onChange(EditorChange.DuplicateAcknowledged(it)) }, enabled = mayConfirm)
                Text("我已核对疑似重复，仍要保留本次账单", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.confirmed, { onChange(EditorChange.Confirmed(it)) }, enabled = mayConfirm)
                Text("我已核对金额、时间、账本和账单类型", Modifier.weight(1f))
            }
        }
    }
}

@Composable internal fun EntryKeyboardAndSave(
    calculatorVisible: Boolean, busy: Boolean, canSave: Boolean, primaryLabel: String, secondaryLabel: String?,
    onKey: (String) -> Unit, onPrimary: () -> Unit, onSecondary: () -> Unit,
) {
    if (calculatorVisible) Box(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp)) { EntryAmountKeyboard(!busy, onKey) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPrimary, enabled = canSave, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(primaryLabel) }
            if (secondaryLabel != null) TextButton(onClick = onSecondary, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(secondaryLabel, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
