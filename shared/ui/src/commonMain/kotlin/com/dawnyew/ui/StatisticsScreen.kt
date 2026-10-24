package com.dawnyew.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.*

/** Panel requests carry the exact query presented by the page. No truncated overview rows are aggregated here. */
internal fun financePanelRequest(id: String, month: QueryScope, year: String, kind: ActivityKind? = null, category: String? = null): PanelRequest {
    val source = month.toActivityScope().copy(kinds = kind?.let { setOf(it) } ?: emptySet())
    val scope = when (id) {
        "monthly", "category_monthly", "count", "maximum", "quarterly" -> source.copy(startDate = "$year-01-01", endDate = "$year-12-31")
        "yearly" -> source.copy(startDate = "0001-01-01", endDate = "$year-12-31")
        else -> source
    }
    return PanelRequest(id, if (id == "category_monthly") scope.copy(categoryIds = category?.let { setOf(it) } ?: emptySet()) else scope)
}

internal fun currentPanel(state: LedgerState, page: PanelUiState?, request: PanelRequest): PanelStatistics? = page?.snapshot?.takeIf {
    !page.loading && page.error == null && page.request == request && it.request == request && it.datasetId == state.datasetId && it.contentVersion == state.contentVersion
}

@Composable internal fun FinanceStatisticsPage(
    s: LedgerState, monthPage: StatisticsPageUi, yearPage: StatisticsPageUi, requestedRange: QueryScope,
    selectedYear: String, today: String, listState: LazyListState, panel: String? = null,
    onMonth: (DateRange) -> Unit, onYear: (String) -> Unit, onRetryMonth: () -> Unit, onRetryYear: () -> Unit,
    onDrill: (QueryScope) -> Unit, onOpenPanel: (String) -> Unit,
    panels: Map<String, PanelUiState> = emptyMap(), onLoadPanel: (PanelRequest) -> Unit = {},
    onActivities: (ActivityScope) -> Unit = {}, onEntry: (String) -> Unit = {}, onChooseLedger: () -> Unit = {},
) {
    val definitions = remember { PanelCatalog.definitions(PanelSurface.STATISTICS) }
    val layout = remember(s.preferences) { PanelCatalog.resolve(s.preferences, PanelSurface.STATISTICS) }
    val detail = definitions.firstOrNull { it.id == panel || it.title == panel }
    var kindName by rememberSaveable(panel) { mutableStateOf<String?>(null) }
    val kind = kindName?.let(ActivityKind::valueOf)
    var categoryId by rememberSaveable(panel, requestedRange.ledgerIds) { mutableStateOf<String?>(null) }
    var chooseCategory by remember { mutableStateOf(false) }
    var editYear by remember { mutableStateOf(false) }
    var yearText by remember(selectedYear) { mutableStateOf(selectedYear) }
    var yearError by remember { mutableStateOf<String?>(null) }
    val detailScroll = rememberLazyListState()
    val visible = if (detail != null) listOf(detail.id) else layout.order.filter { it in layout.enabled }
    val requests = visible.associateWith { financePanelRequest(it, requestedRange, selectedYear, kind, categoryId) }
    LaunchedEffect(requests, s.datasetId, s.contentVersion) { requests.values.forEach(onLoadPanel) }
    LazyColumn(Modifier.fillMaxSize(), state = if (detail == null) listState else detailScroll, contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (detail != null) item("filters") {
            DashboardCard {
                val annual = detail.id in setOf("monthly", "category_monthly", "count", "maximum", "quarterly", "yearly")
                if (annual) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { (selectedYear.toIntOrNull()?.minus(1))?.takeIf { it >= 1 }?.let { onYear(it.toString().padStart(4, '0')) } }) { Text("‹") }
                    TextButton(onClick = { editYear = true }, modifier = Modifier.weight(1f)) { Text("$selectedYear 年 ▾") }
                    TextButton(onClick = { (selectedYear.toIntOrNull()?.plus(1))?.takeIf { it <= 9999 }?.let { onYear(it.toString().padStart(4, '0')) } }) { Text("›") }
                } else DateRangeSelector(requestedRange.startDate, requestedRange.endDate, today, onMonth)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val types = if (detail.id == "count") listOf(null to "全部", ActivityKind.EXPENSE to "支出", ActivityKind.INCOME to "收入", ActivityKind.REFUND to "退款") else listOf(null to "支出", ActivityKind.INCOME to "收入", ActivityKind.REFUND to "退款")
                    types.forEach { (value, label) ->
                        FilterChip(selected = kind == value, onClick = { kindName = value?.name; categoryId = null }, label = { Text(label) })
                    }
                }
                if (detail.id == "category_monthly") TextButton(onClick = { chooseCategory = true }) { Text(s.categories.find { it.id == categoryId }?.name ?: "选择要统计的分类") }
                Text(detail.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visible, key = { it }) { id ->
            val definition = definitions.first { it.id == id }
            val request = requests.getValue(id)
            FinanceStatisticCard(s, definition, panels[id], request, today, detail != null, { onLoadPanel(request) }, if (detail == null) ({ onOpenPanel(definition.title) }) else null, onActivities, onEntry,
                onChooseLedger = if (detail == null && id == visible.firstOrNull()) onChooseLedger else null,
                ledgerLabel = if (requestedRange.ledgerIds.size == 1) s.ledgers.find { it.id in requestedRange.ledgerIds }?.name ?: "选择账本" else "全部账本")
        }
        if (detail != null) {
            val request = requests.getValue(detail.id)
            val snapshot = currentPanel(s, panels[detail.id], request)
            if (snapshot != null) {
                item("exact_header") { Text("完整数据 · ${snapshot.rows.size} 项", style = MaterialTheme.typography.titleMedium) }
                if (detail.id == "tag") item { Text("一笔交易可归属多个标签，各标签金额不可相加作为总支出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (snapshot.rows.isEmpty()) item { DashboardCard { Text(if (detail.id == "category_monthly" && categoryId == null) "选择分类后查看其全年走势。" else "这个范围没有符合条件的交易。", style = MaterialTheme.typography.bodyMedium) } }
                items(snapshot.rows, key = { "row-" + it.id }) { row ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().then(if (row.entryId != null || row.drill != null) Modifier.clickable { row.entryId?.let(onEntry) ?: row.drill?.let(onActivities) } else Modifier).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(if (snapshot.unit == PanelUnit.COUNT) "${row.value} 笔" else Money.format(row.value), style = MaterialTheme.typography.titleMedium, color = FinanceColors.expense)
                                if (row.entryId != null || row.drill != null) { Spacer(Modifier.width(6.dp)); AppGlyph("前进", Modifier.size(14.dp)) }
                            }
                            Text("${row.count} 笔 · 收入 ${Money.format(row.income)} · 支出 ${Money.format(row.expense)} · 退款 ${Money.format(row.refunds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            if (visible.isEmpty()) item { DashboardCard { Text("尚未开启统计面板", style = MaterialTheme.typography.titleMedium); Text("在统计面板中打开想看的图表。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = onChooseLedger) { Text("选择账本") } } }
            item("manage") { TextButton(onClick = { onOpenPanel("统计面板") }, modifier = Modifier.fillMaxWidth()) { Text("${definitions.size} 个统计面板  ›") } }
        }
    }
    if (editYear) AlertDialog(onDismissRequest = { editYear = false }, title = { Text("选择年份") }, text = { Column { FinanceTextField(yearText, { yearText = it; yearError = null }, "年份 YYYY", false); yearError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { val value = yearText.toIntOrNull(); if (value == null || value !in 1..9999) yearError = "请输入1至9999的年份" else { onYear(value.toString().padStart(4, '0')); editYear = false } }) { Text("确定") } }, dismissButton = { TextButton(onClick = { editYear = false }) { Text("取消") } })
    if (chooseCategory) AlertDialog(onDismissRequest = { chooseCategory = false }, title = { Text("选择分类") }, text = {
        LazyColumn { items(s.categories.filter { (requestedRange.ledgerIds.isEmpty() || it.ledgerId in requestedRange.ledgerIds) && it.direction == if (kind == ActivityKind.INCOME) Direction.INCOME else Direction.EXPENSE }, key = { it.id }) { category ->
            TextButton(onClick = { categoryId = category.id; chooseCategory = false }, modifier = Modifier.fillMaxWidth()) { Text(category.name) }
        } }
    }, confirmButton = { TextButton(onClick = { chooseCategory = false }) { Text("关闭") } })
}

@Composable internal fun FinanceStatisticCard(
    s: LedgerState, definition: PanelDefinition, page: PanelUiState?, request: PanelRequest, today: String,
    detail: Boolean = false, onRetry: () -> Unit, onOpen: (() -> Unit)? = null,
    onActivities: (ActivityScope) -> Unit, onEntry: (String) -> Unit = {},
    onChooseLedger: (() -> Unit)? = null, ledgerLabel: String? = null,
) {
    val snapshot = currentPanel(s, page, request)
    val expenseSign = snapshot?.unit != PanelUnit.COUNT && request.scope.kinds.singleOrNull() !in setOf(ActivityKind.INCOME, ActivityKind.REFUND)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CardHeading(definition.title, onClick = onOpen)
                val value = snapshot?.let { data -> if (definition.id == "maximum") data.rows.maxOfOrNull { it.value } ?: 0 else if (definition.id in setOf("average", "tag")) null else runCatching { data.rows.fold(0L) { sum, row -> Money.add(sum, row.value) } }.getOrNull() }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (snapshot == null) "${request.scope.startDate} 至 ${request.scope.endDate}" else (if (value == null) (if (definition.id == "tag") "标签可重复归属，金额不可相加" else if (definition.id == "average") "分类平均金额" else "合计超出可显示范围") else if (snapshot.unit == PanelUnit.COUNT) "$value 笔" else Money.format(if (expenseSign && value >= 0) -value else value)) + "  ·  " + if (request.scope.startDate == "0001-01-01") "历史年度" else dateRangeLabel(DateRange(request.scope.startDate, request.scope.endDate)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onChooseLedger != null) Text((ledgerLabel ?: "选择账本") + " ▾", Modifier.clickable(onClick = onChooseLedger).padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (snapshot == null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (page?.error != null && page.request == request) { Text(page.error ?: "读取失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = onRetry) { Text("重试") } }
                    else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取统计…", style = MaterialTheme.typography.bodySmall) }
                }
            } else {
                val points = snapshot.rows.map { row ->
                    val label = when (definition.id) {
                        "monthly", "category_monthly", "count", "maximum" -> row.id.takeLast(2).toIntOrNull()?.let { "${it}月" } ?: row.label
                        "quarterly" -> "Q${row.id.takeLast(1)}"
                        "daily", "monthly_expense", "income_expense" -> row.id.takeLast(5).replace('-', '.')
                        else -> row.label
                    }
                    FinanceChartPoint(row.id, label, row.value)
                }
                val drill: (String) -> Unit = { id -> snapshot.rows.find { it.id == id }?.let { row -> row.entryId?.let(onEntry) ?: row.drill?.let(onActivities) } }
                Box(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp)) {
                    when (definition.id) {
                        "category", "account", "project", "merchant" -> FinanceDonutChart(points, negativeLabels = expenseSign, onPoint = if (detail) drill else null)
                        "category_radar" -> FinanceRadarChart(points)
                        "daily", "monthly_expense", "income_expense" -> FinanceAreaChart(points, negativeLabels = expenseSign, onPoint = if (detail) drill else null)
                        else -> FinanceBarChart(if (!detail && definition.id == "monthly") chartMonthWindow(points, request.scope.startDate.take(4), today) else points, count = snapshot.unit == PanelUnit.COUNT, negativeLabels = expenseSign, onPoint = if (detail) drill else null)
                    }
                }
                if (snapshot.rows.isEmpty() || snapshot.rows.all { it.value == 0L }) Text(if (definition.id == "category_monthly" && request.scope.categoryIds.isEmpty()) "打开详情，选择要查看的分类" else "本期暂无数据", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
