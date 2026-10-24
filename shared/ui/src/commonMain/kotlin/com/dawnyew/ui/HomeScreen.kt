package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.*
import com.dawnyew.presentation.StatisticsPageUi
import com.dawnyew.presentation.homePeriodDrillScope
import com.dawnyew.presentation.homeStatisticsScope
import com.dawnyew.presentation.homeMonthlyScope

private fun StatisticsPageUi.canOpen(requested: QueryScope): Boolean =
    !loading && error == null && snapshot?.scope == requested && scope == requested

@Composable internal fun FinanceHomePage(
    s: LedgerState, period: StatisticsPageUi, scope: QueryScope, today: String, listState: LazyListState,
    recent: com.dawnyew.presentation.ActivityPageUi, calendar: com.dawnyew.presentation.ActivitySummaryUi,
    pending: com.dawnyew.presentation.ActivitySummaryUi, finance: FinanceSnapshot?,
    onRange: (DateRange) -> Unit, onRetry: () -> Unit, onOpen: (String) -> Unit,
    onActivity: (FinancialActivity) -> Unit, onActivities: (ActivityScope) -> Unit,
    panels: Map<String, com.dawnyew.presentation.PanelUiState> = emptyMap(), onLoadPanel: (PanelRequest) -> Unit = {},
    monthlyPeriod: StatisticsPageUi, calendarScope: QueryScope, onCalendarMonth: (DateRange) -> Unit,
) {
    val requested = homeStatisticsScope(scope)
    val activityScope = requested.toActivityScope()
    val current = period.snapshot?.takeIf { period.canOpen(requested) && it.datasetId == s.datasetId && it.contentVersion == s.contentVersion }
    val activeRecent = !recent.loading && recent.error == null && recent.scope == activityScope && recent.contentScope == activityScope && recent.datasetId == s.datasetId && recent.contentVersion == s.contentVersion
    val selectedRange = DateRange(requested.startDate, requested.endDate)
    val monthlyScope = homeMonthlyScope(requested)
    val monthly = monthlyPeriod.snapshot?.takeIf { monthlyPeriod.canOpen(monthlyScope) && it.datasetId == s.datasetId && it.contentVersion == s.contentVersion }
    val month = monthlyScope.startDate.take(7)
    val monthlyLabel = "${dateRangeLabel(DateRange(monthlyScope.startDate, monthlyScope.endDate))} · 按月"
    val calendarMonth = calendarScope.startDate.take(7)
    val calendarActivityScope = calendarScope.toActivityScope()
    var chooseHomeMonth by remember { mutableStateOf(false) }
    var chooseCalendarMonth by remember { mutableStateOf(false) }
    val selectedLedger = requested.ledgerIds.singleOrNull()
    val budget = s.budgets.find { it.ledgerId == selectedLedger && it.yearMonth == month }
    val layout = remember(s.preferences) { PanelCatalog.resolve(s.preferences, PanelSurface.HOME) }
    val definitions = remember { PanelCatalog.definitions(PanelSurface.HOME).associateBy { it.id } }
    val visible = layout.order.filter { it in layout.enabled }
    val projected = visible.filter { it in setOf("budget_tracking", "category_change", "category_radar") }.associateWith {
        financePanelRequest(it, if (it in setOf("budget_tracking", "category_change")) monthlyScope else requested, month.take(4))
    }
    LaunchedEffect(projected, s.datasetId, s.contentVersion) { projected.values.forEach(onLoadPanel) }
    val pendingSummary = pending.summary?.takeIf { pending.scope == activityScope.copy(recorded = false) && !pending.loading && pending.error == null && pending.datasetId == s.datasetId && pending.contentVersion == s.contentVersion }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val backing = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height * .70f); quadraticTo(size.width / 2, size.height * 1.25f, 0f, size.height * .70f); close() }
            drawPath(backing, FinanceColors.brand)
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if ("monthly_expense" !in visible) item("month_selector") {
            Surface(
                Modifier.fillMaxWidth().clickable { chooseHomeMonth = true }
                    .semantics { contentDescription = "首页月份选择" },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("统计范围", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dateRangeLabel(selectedRange) + " ▾", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        visible.forEachIndexed { index, id ->
            item(id) {
                when (id) {
                    "monthly_expense" -> Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(vertical = 16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dateRangeLabel(selectedRange) + " 支出 ▾", Modifier.weight(1f).clickable { chooseHomeMonth = true }.semantics { contentDescription = "首页月份选择" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AppGlyph("前进", Modifier.size(18.dp).clickable { onActivities(homePeriodDrillScope(requested)) }.semantics { contentDescription = "查看所选范围交易" })
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
                                Text(current?.netExpense?.let { if (it > 0) "−${summaryAmount(it, LocalCompactAmounts.current)}" else summaryAmount(-it, LocalCompactAmounts.current) } ?: "—", color = FinanceColors.expense, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text(current?.let { "收入 ${summaryAmount(it.income, LocalCompactAmounts.current)}  结余 ${summaryAmount(it.netIncome, LocalCompactAmounts.current)}" } ?: "", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (current != null) {
                                val points = homeTrendPoints(current) { it.netExpense }
                                FinanceAreaChart(points, Modifier.padding(horizontal = 8.dp), showYAxis = false) { day -> onActivities(homePeriodDrillScope(current.scope, period = day)) }
                                if (current.entryCount == 0L) Text("记下第一笔收支，开始了解每一天。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Column(Modifier.padding(16.dp)) { QueryStatus(period.loading, period.snapshot?.scope, requested, period.error, onRetry) }
                        }
                    }
                    "recent_transactions" -> DashboardCard {
                        CardHeading("最近交易", onClick = { onActivities(activityScope) })
                        if (recent.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        recent.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = onRetry) { Text("重试") } }
                        if (recent.loaded && activeRecent && recent.activities.isEmpty()) Text("还没有交易，点击右下角记一笔。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (activeRecent) Column { recent.activities.forEach { activity -> HomeActivityRow(s, activity) { onActivity(activity) } } }
                    }
                    "budget_overview" -> DashboardCard {
                        CardHeading("预算概览", monthlyLabel, onClick = { onOpen("预算") })
                        if (selectedLedger == null) Text("选择单个账本查看预算", style = MaterialTheme.typography.bodySmall)
                        else if (budget == null) TextButton(onClick = { onOpen("预算") }) { Text("设置月度预算") }
                        else if (monthly != null) {
                            val remaining = budget.amountMinor - monthly.budgetUsed
                            val daysInMonth = monthRange(month).second.takeLast(2).toInt()
                            val elapsed = if (month < today.take(7)) daysInMonth else if (month == today.take(7)) today.takeLast(2).toInt() else 0
                            val daysLeft = (daysInMonth - elapsed).coerceAtLeast(1)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("已用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(Money.format(monthly.budgetUsed), style = MaterialTheme.typography.bodyMedium)
                                    Text("日均 ${Money.format(monthly.budgetUsed / elapsed.coerceAtLeast(1))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FinanceRing((monthly.budgetUsed.toDouble() / budget.amountMinor).toFloat(), if (remaining < 0) "超出预算" else "预算剩余", Money.format(kotlin.math.abs(remaining)), "${Money.format(budget.amountMinor)}")
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("剩余日均", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(Money.format(remaining.coerceAtLeast(0) / daysLeft), style = MaterialTheme.typography.bodyMedium)
                                    Text("${(daysInMonth - elapsed).coerceAtLeast(0)} 天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else QueryStatus(monthlyPeriod.loading, monthlyPeriod.snapshot?.scope, monthlyScope, monthlyPeriod.error, onRetry)
                    }
                    "calendar" -> DashboardCard {
                        CardHeading("日历视图", "${dateRangeLabel(DateRange(calendarScope.startDate, calendarScope.endDate))} · 按月") { chooseCalendarMonth = true }
                        if (calendar.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        calendar.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = onRetry) { Text("重试") } }
                        val days = calendar.summary?.takeIf { calendar.scope == calendarActivityScope && !calendar.loading && calendar.error == null && calendar.datasetId == s.datasetId && calendar.contentVersion == s.contentVersion }?.days
                        if (days != null) FinanceCalendar(calendarMonth, days) { date -> onActivities(calendarActivityScope.copy(startDate = date, endDate = date)) }
                    }
                    "account_tracking" -> DashboardCard {
                        CardHeading("账户追踪", onClick = { onOpen("账户") })
                        val accounts = finance?.accounts?.filter { !it.account.hidden && !it.account.archived }.orEmpty()
                        if (finance == null) Text("正在读取账户余额…", style = MaterialTheme.typography.bodySmall)
                        else if (accounts.isEmpty()) TextButton(onClick = { onOpen("账户") }) { Text("添加资金账户") }
                        accounts.forEach { balance -> Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) { Text(balance.account.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); FinanceAmount(balance.balanceMinor) } }
                        if (accounts.isNotEmpty()) TextButton(onClick = { onOpen("资产趋势") }) { Text("查看资产趋势") }
                    }
                    "savings" -> DashboardCard {
                        CardHeading("存钱计划", onClick = { onOpen("存钱") })
                        val plans = finance?.savings?.filter { !it.plan.archived }.orEmpty()
                        if (finance == null) Text("正在读取存钱计划…", style = MaterialTheme.typography.bodySmall)
                        else if (plans.isEmpty()) TextButton(onClick = { onOpen("存钱") }) { Text("创建一个存钱计划") }
                        plans.forEach { progress ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(progress.plan.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(Money.format(progress.savedMinor), style = MaterialTheme.typography.bodyMedium) }
                            Text(progress.plan.targetMinor?.let { "目标 ${Money.format(it)}" } ?: "无固定目标", style = MaterialTheme.typography.bodySmall)
                            progress.plan.targetMinor?.let { target -> LinearProgressIndicator(progress = { (progress.savedMinor.toDouble() / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                    "category_radar" -> {
                        val request = projected.getValue(id)
                        FinanceStatisticCard(s, definitions.getValue(id), panels[id], request, today, onRetry = { onLoadPanel(request) }, onOpen = { onOpen("分类统计") }, onActivities = onActivities)
                    }
                    "income_expense" -> DashboardCard {
                        CardHeading("收支趋势", dateRangeLabel(selectedRange))
                        if (current == null) QueryStatus(period.loading, period.snapshot?.scope, requested, period.error, onRetry)
                        else {
                            val incomePoints = homeTrendPoints(current) { it.income }
                            val expensePoints = homeTrendPoints(current) { it.expense }
                            FinanceCashFlowChart(incomePoints, expensePoints) { period -> onActivities(homePeriodDrillScope(current.scope, period = period)) }
                            Text("收入 ${Money.format(current.income)} · 支出 ${Money.format(current.expense)} · 退款 ${Money.format(current.refunds)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        val request = projected.getValue(id)
                        val snapshot = currentPanel(s, panels[id], request)
                        DashboardCard {
                            CardHeading(definitions.getValue(id).title, monthlyLabel, onClick = if (id == "budget_tracking") ({ onOpen("预算") }) else null)
                            if (snapshot == null) {
                                val state = panels[id]
                                if (state?.error != null && state.request == request) { Text(state.error ?: "读取失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); TextButton(onClick = { onLoadPanel(request) }) { Text("重试") } }
                                else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取…", style = MaterialTheme.typography.bodySmall) }
                            } else {
                                if (snapshot.rows.isEmpty()) Text(if (id == "budget_tracking") "尚未设置分类预算，在预算中分配后查看。" else "本月暂无分类收支。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                snapshot.rows.forEach { row -> Column(Modifier.fillMaxWidth().then(row.drill?.let { drill -> Modifier.clickable { onActivities(drill) } } ?: Modifier).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth()) { Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(Money.format(row.value), style = MaterialTheme.typography.bodyMedium) }
                                    if (id == "budget_tracking") {
                                        val limit = row.comparison ?: 0
                                        Text("预算 ${Money.format(limit)} · " + if (row.value > limit) "超出 ${financeDifferenceText(row.value, limit)}" else "剩余 ${financeDifferenceText(limit, row.value)}", style = MaterialTheme.typography.bodySmall)
                                        LinearProgressIndicator(progress = { if (limit <= 0) 0f else (row.value.toDouble() / limit).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    } else Text("上月 ${Money.format(row.comparison ?: 0)} · 变化 ${financeDifferenceText(row.value, row.comparison ?: 0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } }
                            }
                        }
                    }
                }
            }
            if (index == 0 && pendingSummary != null && pendingSummary.activityCount > 0) item("pending") { DashboardCard {
                CardHeading("未入账", "${pendingSummary.activityCount} 笔", { onActivities(activityScope.copy(recorded = false)) })
                Text("支出 ${Money.format(pendingSummary.expense)} · 收入 ${Money.format(pendingSummary.income)} · 转账 ${Money.format(pendingSummary.transferAmount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        if (visible.isEmpty() && pendingSummary != null && pendingSummary.activityCount > 0) item("pending") { DashboardCard {
            CardHeading("未入账", "${pendingSummary.activityCount} 笔", { onActivities(activityScope.copy(recorded = false)) })
            Text("支出 ${Money.format(pendingSummary.expense)} · 收入 ${Money.format(pendingSummary.income)} · 转账 ${Money.format(pendingSummary.transferAmount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (visible.isEmpty()) item { DashboardCard { Text("尚未开启主页面板", style = MaterialTheme.typography.titleMedium); Text("打开下方主页面板，选择想看的内容。", style = MaterialTheme.typography.bodySmall) } }
        item("manage") { TextButton(onClick = { onOpen("主页面板") }, modifier = Modifier.fillMaxWidth()) { Text("${definitions.size} 个主页面板  ›") } }
    }
    }
    if (chooseHomeMonth) HomeRangePicker(selectedRange, today, onRange) { chooseHomeMonth = false }
    if (chooseCalendarMonth) HomeMonthPicker(calendarMonth, onCalendarMonth) { chooseCalendarMonth = false }
}

@Composable private fun HomeRangePicker(range: DateRange, today: String, onRange: (DateRange) -> Unit, onDismiss: () -> Unit) {
    var month by remember(range) { mutableStateOf(range.endDate.take(7)) }
    var custom by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (custom) DateRangeDialog(range, onDismiss = { custom = false }, onApply = { onRange(it); onDismiss() })
    else AlertDialog(onDismissRequest = onDismiss, title = { Text("选择日期范围") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DateRangePreset.CURRENT_MONTH to "本月", DateRangePreset.PREVIOUS_MONTH to "上月", DateRangePreset.CURRENT_YEAR to "今年", DateRangePreset.LAST_7_DAYS to "近7天", DateRangePreset.LAST_30_DAYS to "近30天").forEach { (preset, label) ->
                    TextButton(onClick = { onRange(dateRangePreset(preset, today)); onDismiss() }) { Text(label) }
                }
            }
            FinanceTextField(month, { month = it; error = null }, "YYYY-MM", false)
            TextButton(onClick = { custom = true }) { Text("自定义日期…") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { try { val bounds = monthRange(month); onRange(DateRange(bounds.first, bounds.second)); onDismiss() } catch (e: DomainException) { error = e.message } }) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun HomeMonthPicker(month: String, onRange: (DateRange) -> Unit, onDismiss: () -> Unit) {
    var value by remember(month) { mutableStateOf(month) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择月份") }, text = {
        Column { FinanceTextField(value, { value = it; error = null }, "YYYY-MM", false); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { TextButton(onClick = { try { val range = monthRange(value); onRange(DateRange(range.first, range.second)); onDismiss() } catch (e: DomainException) { error = e.message } }) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun HomeActivityRow(s: LedgerState, activity: FinancialActivity, onClick: () -> Unit) {
    val title = if (activity.family == ActivityFamily.ENTRY) s.categories.find { it.id == activity.categoryId }?.name ?: activityKindName(activity.kind) else when (activity.businessKind) {
        TransferBusinessKind.OBLIGATION_PRINCIPAL -> s.obligations.find { it.id == activity.businessId }?.let { obligationName(it.kind) } ?: "往来本金"
        TransferBusinessKind.OBLIGATION_SETTLEMENT -> s.obligations.find { it.id == activity.businessId }?.let { if (it.kind == ObligationKind.BORROW) "还款" else if (it.kind == ObligationKind.REIMBURSEMENT) "报销收款" else "收款" } ?: "往来收付"
        TransferBusinessKind.SAVINGS -> "存钱"
        else -> "转账"
    }
    val color = if (activity.kind == ActivityKind.EXPENSE) FinanceColors.expense else if (activity.kind == ActivityKind.TRANSFER) FinanceColors.transfer else FinanceColors.income
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 61.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (activity.kind == ActivityKind.EXPENSE) "−" else if (activity.kind == ActivityKind.TRANSFER) "↔" else "+", color = color, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text((if (activity.kind == ActivityKind.EXPENSE) "−" else if (activity.kind == ActivityKind.TRANSFER) "" else "+") + Money.format(activity.amountMinor), color = color, style = MaterialTheme.typography.bodyLarge) }
            Text(activity.localDate.takeLast(5) + " " + activity.occurrence.substringAfter('T').take(5) + (s.accounts.find { it.id == activity.accountId }?.let { " · ${it.name}" } ?: "") + (if (activity.note.isNotBlank()) " · ${activity.note}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun MonthSelector(month: String, onRange: (DateRange) -> Unit) {
    var selecting by remember { mutableStateOf(false) }; var value by remember(month) { mutableStateOf(month) }; var error by remember { mutableStateOf<String?>(null) }
    val range = remember(month) { monthRange(month).let { DateRange(it.first, it.second) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { runCatching { range.shift(-1) }.getOrNull()?.let(onRange) }, modifier = Modifier.semantics { contentDescription = "上个月" }) { AppGlyph("返回") }
        TextButton(onClick = { selecting = true }, modifier = Modifier.weight(1f)) { Text(dateRangeLabel(range) + " ▾") }
        IconButton(onClick = { runCatching { range.shift(1) }.getOrNull()?.let(onRange) }, modifier = Modifier.semantics { contentDescription = "下个月" }) { AppGlyph("前进") }
    }
    if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("选择月份") }, text = { Column { FinanceTextField(value, { value = it }, "YYYY-MM", false); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { try { val next = monthRange(value); onRange(DateRange(next.first, next.second)); selecting = false } catch (e: DomainException) { error = e.message } }) { Text("确定") } }, dismissButton = { TextButton(onClick = { selecting = false }) { Text("取消") } })
}

/** Transfers move funds between accounts and do not change the day's net income. */
internal fun calendarNetAmount(day: ActivityDaySummary): Long = Money.add(day.income, Money.add(day.refunds, -day.expense))

@Composable private fun FinanceCalendar(month: String, days: List<ActivityDaySummary>, onDate: (String) -> Unit) {
    val range = monthRange(month); val length = range.second.takeLast(2).toInt()
    val firstOffset = (((dateToUtcMillis(range.first) / 86_400_000L + 3L) % 7L + 7L) % 7L).toInt()
    val cells = List(firstOffset) { 0 } + (1..length).toList()
    Column(Modifier.fillMaxWidth()) {
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        cells.chunked(7).forEach { week -> Row {
            (week + List(7 - week.size) { 0 }).forEach { day ->
                if (day == 0) Spacer(Modifier.weight(1f).height(42.dp))
                else {
                    val date = month + "-" + day.toString().padStart(2, '0')
                    val summary = days.find { it.date == date }
                    val net = summary?.let { runCatching { calendarNetAmount(it) }.getOrNull() }
                    val amount = if (summary == null) "" else net?.let { (if (it > 0) "+" else "") + Money.format(it) } ?: "超出范围"
                    TextButton(onClick = { onDate(date) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { contentDescription = "日历$date" + if (summary == null) "，无收支" else "，净收支$amount" }, contentPadding = PaddingValues(0.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            // Every date reserves the same amount line, including dates with no transactions.
                            Box(Modifier.fillMaxWidth().height(with(LocalDensity.current) { 14.sp.toDp() }), contentAlignment = Alignment.Center) {
                                Text(amount, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
                                    color = when { net == null || net == 0L -> MaterialTheme.colorScheme.onSurfaceVariant; net > 0 -> FinanceColors.income; else -> FinanceColors.expense },
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } }
    }
}
