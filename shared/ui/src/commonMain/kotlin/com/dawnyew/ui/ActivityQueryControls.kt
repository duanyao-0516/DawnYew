package com.dawnyew.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.ActivityQueryDraft

private enum class ActivityFilter(val title: String) {
    DATE("日期"), TYPE("类型"), LEDGER("账本"), AMOUNT("金额"), CATEGORY("分类"), ACCOUNT("账户"),
    PROJECT("项目"), MERCHANT("商家"), TAG("标签"), WEEKDAY("周几"), BUDGET("预算"), RECORDED("入账"), RECONCILED("对账"), RESET("清空筛选")
}

/** Applied scope is owned by FinanceController; only this control owns pending field edits. */
@Composable internal fun ActivityQueryControls(s: LedgerState, scope: ActivityScope, onApply: (ActivityScope) -> Unit, onCapture: () -> Unit) {
    var draft by remember(s.datasetId, scope) { mutableStateOf(ActivityQueryDraft(scope)) }
    var editor by remember(s.datasetId, scope) { mutableStateOf<ActivityFilter?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember(s.datasetId, scope) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    fun apply() {
        try {
            val validated = draft.validatedScope()
            error = null
            editor = null
            focus.clearFocus()
            onApply(validated)
        } catch (e: DomainException) { error = e.message }
    }
    fun cancel() { draft = ActivityQueryDraft(scope); error = null; editor = null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(draft.scope.keyword, { draft = draft.copy(scope = draft.scope.copy(keyword = it)) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索备注") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { apply() }),
            leadingIcon = { AppGlyph("搜索", Modifier.size(20.dp)) }, trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { apply() }) { Text("搜索") }; IconButton(onClick = onCapture, modifier = Modifier.semantics { contentDescription = "截图记账" }) { AppGlyph("自动", Modifier.size(20.dp)) } } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QueryPill(dateFilterLabel(scope), scope.startDate != "0001-01-01" || scope.endDate != "9999-12-31") { editor = ActivityFilter.DATE; error = null }
            QueryPill(if (scope.kinds.isEmpty()) "类型" else scope.kinds.joinToString("、", transform = ::activityKindName), scope.kinds.isNotEmpty()) { editor = ActivityFilter.TYPE; error = null }
            if (!expanded) QueryPill("更多", hasExtraFilters(scope)) { expanded = true }
            else {
                listOf(ActivityFilter.LEDGER, ActivityFilter.AMOUNT, ActivityFilter.CATEGORY, ActivityFilter.ACCOUNT, ActivityFilter.PROJECT, ActivityFilter.MERCHANT, ActivityFilter.TAG, ActivityFilter.WEEKDAY, ActivityFilter.BUDGET, ActivityFilter.RECORDED, ActivityFilter.RECONCILED).forEach { field ->
                    QueryPill(field.title, filterActive(field, scope)) { editor = field; error = null }
                }
                QueryPill("收起", false) { expanded = false }
                QueryPill("清空", false) { draft = draft.reset(); editor = ActivityFilter.RESET; error = null }
            }
        }
        if (editor == null) error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    editor?.let { field ->
        AlertDialog(onDismissRequest = { cancel() }, title = { Text(field.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityFilterFields(s, field, draft) { draft = it; error = null }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { apply() }) { Text("应用") } }, dismissButton = { TextButton(onClick = { cancel() }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QueryPill(label: String, active: Boolean, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified) {
        Surface(onClick = onClick, modifier = Modifier.heightIn(min = 32.dp), shape = RoundedCornerShape(18.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun ActivityFilterFields(s: LedgerState, field: ActivityFilter, draft: ActivityQueryDraft, onChange: (ActivityQueryDraft) -> Unit) {
    val q = draft.scope
    fun update(scope: ActivityScope) = onChange(draft.copy(scope = scope))
    when (field) {
        ActivityFilter.DATE -> {
            FinanceTextField(draft.start, { onChange(draft.copy(start = it)) }, "开始日期 YYYY-MM-DD", false, supporting = "留空表示不限")
            FinanceTextField(draft.end, { onChange(draft.copy(end = it)) }, "结束日期 YYYY-MM-DD", false, supporting = "留空表示不限")
        }
        ActivityFilter.AMOUNT -> {
            FinanceTextField(draft.minimum, { onChange(draft.copy(minimum = it)) }, "最小金额", false, money = true)
            FinanceTextField(draft.maximum, { onChange(draft.copy(maximum = it)) }, "最大金额", false, money = true)
            Text("留空表示不限，按每笔交易金额筛选。", style = MaterialTheme.typography.bodySmall)
        }
        ActivityFilter.TYPE -> QueryMultipleChoices(ActivityKind.entries.map { it.name to activityKindName(it) }, q.kinds.map { it.name }.toSet(), "全部类型") { ids -> update(q.copy(kinds = ids.map { ActivityKind.valueOf(it) }.toSet())) }
        ActivityFilter.LEDGER -> QueryMultipleChoices(s.ledgers.map { it.id to (it.name + if (it.archived) " · 已归档" else "") }, q.ledgerIds, "全部账本") { onChange(draft.selectLedgers(it)) }
        ActivityFilter.CATEGORY -> QueryMultipleChoices(s.categories.filter { q.ledgerIds.isEmpty() || it.ledgerId in q.ledgerIds }.map { it.id to it.name }, q.categoryIds, "全部分类") { update(q.copy(categoryIds = it)) }
        ActivityFilter.ACCOUNT -> QueryMultipleChoices(s.accounts.filter { q.ledgerIds.isEmpty() || it.ledgerId in q.ledgerIds }.map { it.id to (s.accountDisplayName(it.id).orEmpty() + if (it.archived) " · 已归档" else "") }, q.accountIds, "全部账户", q.accountUnassigned, "无账户") { ids, unassigned -> update(q.copy(accountIds = ids, accountUnassigned = unassigned)) }
        ActivityFilter.PROJECT, ActivityFilter.MERCHANT, ActivityFilter.TAG -> {
            val kind = when (field) { ActivityFilter.PROJECT -> DimensionKind.PROJECT; ActivityFilter.MERCHANT -> DimensionKind.MERCHANT; else -> DimensionKind.TAG }
            val ids = when (kind) { DimensionKind.PROJECT -> q.projectIds; DimensionKind.MERCHANT -> q.merchantIds; DimensionKind.TAG -> q.tagIds }
            val unassigned = when (kind) { DimensionKind.PROJECT -> q.projectUnassigned; DimensionKind.MERCHANT -> q.merchantUnassigned; DimensionKind.TAG -> q.tagUnassigned }
            QueryMultipleChoices(s.dimensions.filter { it.kind == kind && (q.ledgerIds.isEmpty() || it.ledgerId in q.ledgerIds) }.map { it.id to (it.name + if (it.archived) " · 已归档" else "") }, ids, "全部${field.title}", unassigned, "无${field.title}") { values, none ->
                update(when (kind) { DimensionKind.PROJECT -> q.copy(projectIds = values, projectUnassigned = none); DimensionKind.MERCHANT -> q.copy(merchantIds = values, merchantUnassigned = none); DimensionKind.TAG -> q.copy(tagIds = values, tagUnassigned = none) })
            }
        }
        ActivityFilter.WEEKDAY -> QueryMultipleChoices(listOf("1" to "周一", "2" to "周二", "3" to "周三", "4" to "周四", "5" to "周五", "6" to "周六", "7" to "周日"), q.weekdays.map { it.toString() }.toSet(), "不限星期") { ids -> update(q.copy(weekdays = ids.map(String::toInt).toSet())) }
        ActivityFilter.BUDGET -> QueryBooleanChoices(q.excludedFromBudget, "不计入预算", "计入预算") { update(q.copy(excludedFromBudget = it)) }
        ActivityFilter.RECORDED -> QueryBooleanChoices(q.recorded, "已入账", "未入账") { update(q.copy(recorded = it)) }
        ActivityFilter.RECONCILED -> QueryBooleanChoices(q.reconciled, "已对账", "未对账") { update(q.copy(reconciled = it)) }
        ActivityFilter.RESET -> Text("清除日期、金额、类型、账本、分类、账户、项目、商家、标签、备注和状态等筛选条件。点击应用后显示全部匹配流水。")
    }
}

@Composable private fun QueryBooleanChoices(value: Boolean?, trueLabel: String, falseLabel: String, onChange: (Boolean?) -> Unit) {
    listOf(null to "全部", true to trueLabel, false to falseLabel).forEach { (option, label) ->
        QueryChoiceRow(label, value == option) { onChange(option) }
    }
}

@Composable private fun QueryMultipleChoices(options: List<Pair<String, String>>, selected: Set<String>, allLabel: String, onChange: (Set<String>) -> Unit) {
    QueryMultipleChoices(options, selected, allLabel, false, null) { ids, _ -> onChange(ids) }
}

@Composable private fun QueryMultipleChoices(options: List<Pair<String, String>>, selected: Set<String>, allLabel: String, unassigned: Boolean, unassignedLabel: String?, onChange: (Set<String>, Boolean) -> Unit) {
    QueryChoiceRow(allLabel, selected.isEmpty() && !unassigned) { onChange(emptySet(), false) }
    unassignedLabel?.let { QueryChoiceRow(it, unassigned) { onChange(emptySet(), true) } }
    options.forEach { (id, label) ->
        QueryChoiceRow(label, id in selected) { onChange(if (id in selected) selected - id else selected + id, false) }
    }
}

@Composable private fun QueryChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(selected, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun dateFilterLabel(scope: ActivityScope): String = when {
    scope.startDate == "0001-01-01" && scope.endDate == "9999-12-31" -> "全部时间"
    scope.startDate == scope.endDate -> scope.startDate
    scope.startDate.take(7) == scope.endDate.take(7) -> "${scope.startDate.take(7)} · 日期"
    else -> "自定义日期"
}
private fun filterActive(field: ActivityFilter, q: ActivityScope): Boolean = when (field) {
    ActivityFilter.DATE -> q.startDate != "0001-01-01" || q.endDate != "9999-12-31"
    ActivityFilter.TYPE -> q.kinds.isNotEmpty()
    ActivityFilter.LEDGER -> q.ledgerIds.isNotEmpty()
    ActivityFilter.AMOUNT -> q.minAmountMinor != null || q.maxAmountMinor != null
    ActivityFilter.CATEGORY -> q.categoryIds.isNotEmpty()
    ActivityFilter.ACCOUNT -> q.accountIds.isNotEmpty() || q.accountUnassigned
    ActivityFilter.PROJECT -> q.projectIds.isNotEmpty() || q.projectUnassigned
    ActivityFilter.MERCHANT -> q.merchantIds.isNotEmpty() || q.merchantUnassigned
    ActivityFilter.TAG -> q.tagIds.isNotEmpty() || q.tagUnassigned
    ActivityFilter.WEEKDAY -> q.weekdays.isNotEmpty()
    ActivityFilter.BUDGET -> q.excludedFromBudget != null
    ActivityFilter.RECORDED -> q.recorded != null
    ActivityFilter.RECONCILED -> q.reconciled != null
    ActivityFilter.RESET -> false
}
private fun hasExtraFilters(scope: ActivityScope): Boolean = ActivityFilter.entries.filter { it != ActivityFilter.DATE && it != ActivityFilter.TYPE && it != ActivityFilter.RESET }.any { filterActive(it, scope) }
