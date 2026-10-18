package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import com.dawnyew.presentation.StatisticsPageUi
import com.dawnyew.presentation.homeStatisticsScope
import com.dawnyew.presentation.todayStatisticsScope
import kotlin.math.roundToInt

/** Home keeps the selected period and today's ledger totals in independent result slots. */
@Composable internal fun HomePage(
    period: StatisticsPageUi,
    today: StatisticsPageUi,
    scope: QueryScope,
    todayDate: String,
    listState: LazyListState,
    onRange: (DateRange) -> Unit,
    onRetryPeriod: () -> Unit,
    onRetryToday: () -> Unit,
    onDetails: () -> Unit,
    onPeriodDrill: (String) -> Unit,
    onTodayDrill: (EntryKind) -> Unit,
) {
    val periodScope = homeStatisticsScope(scope)
    val todayScope = todayStatisticsScope(scope, todayDate)
    Column(Modifier.fillMaxSize()) {
        DateRangeSelector(scope.startDate, scope.endDate, todayDate, onRange)
        LazyColumn(
            modifier = Modifier.weight(1f), state = listState,
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("period") { HomePeriodCard(period, periodScope, todayDate, onRetryPeriod, onPeriodDrill) }
            item("today") { HomeTodayCard(today, todayScope, todayDate, onRetryToday, onTodayDrill) }
            item("details") {
                TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("查看明细 ›")
                }
            }
        }
    }
}

private fun StatisticsPageUi.canOpen(requested: QueryScope): Boolean =
    !loading && error == null && snapshot?.scope == requested && scope == requested

@Composable private fun HomePeriodCard(
    page: StatisticsPageUi, requested: QueryScope, today: String,
    onRetry: () -> Unit, onPoint: (String) -> Unit,
) {
    val snapshot = page.snapshot
    val displayed = snapshot?.scope
    val range = displayed ?: requested
    val stale = displayed != null && displayed != requested
    val interactive = page.canOpen(requested)
    var showData by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(if (stale) "上次筛选净花销" else "本期净花销", style = MaterialTheme.typography.titleMedium)
                Text("支出 − 退款", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                snapshot?.let { "¥ ${Money.format(it.netExpense)}" } ?: "—",
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
            )
            Text(dateRangeLabel(DateRange(range.startDate, range.endDate)), style = MaterialTheme.typography.bodySmall)
            if (stale && displayed!!.ledgerIds != requested.ledgerIds) {
                Text(
                    if (page.error != null) "仍显示上次账本的结果，请重试以读取当前账本。"
                    else "仍显示上次账本的结果，更新完成后可查看明细。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (snapshot != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("原始支出 ¥ ${Money.format(snapshot.expense)}", style = MaterialTheme.typography.bodySmall)
                    Text("退款 ¥ ${Money.format(snapshot.refunds)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        QueryStatus(page.loading, displayed, requested, page.error, onRetry)
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot == null) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(if (page.error != null) "暂未取得花销数据" else "正在读取花销…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                HomeExpenseChart(snapshot.trend, today, interactive, onPoint)
                Text(
                    if (snapshot.entryCount == 0L) "本期暂无账单，记账后会在这里形成花销曲线。"
                    else "净花销可以为负，表示退款多于支出。点击曲线查看对应日期明细。",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { showData = !showData }, contentPadding = PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (showData) "收起趋势数据" else "查看趋势数据") }
                if (showData) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(snapshot.trend, key = { it.period }) { point ->
                        val futureEmpty = homeFuturePoint(point, today) && !homePointHasValues(point)
                        TextButton(
                            onClick = { onPoint(point.period) }, enabled = interactive && !futureEmpty,
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            FlowRow(
                                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(point.period)
                                Text(if (futureEmpty) "— 尚未发生" else "¥ ${Money.format(point.netExpense)}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HomeTodayCard(
    page: StatisticsPageUi, requested: QueryScope, today: String,
    onRetry: () -> Unit, onOpen: (EntryKind) -> Unit,
) {
    val snapshot = page.snapshot
    val displayed = snapshot?.scope
    val stale = displayed != null && displayed != requested
    val interactive = page.canOpen(requested)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(if (stale) "上次筛选收支" else "今日收支", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (stale) dateRangeLabel(DateRange(displayed!!.startDate, displayed.endDate)) else today,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (stale && page.error != null) "当前仍为上次账本或日期的结果，请重试以读取今日收支。"
                else if (stale) "当前仍为上次账本或日期的结果，正在读取今日收支。"
                else "按当前账本统计，不受上方日期范围影响",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QueryStatus(page.loading, displayed, requested, page.error, onRetry)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeTodayAmount("收入", snapshot?.income, interactive, Modifier.weight(1f)) { onOpen(EntryKind.INCOME) }
                HomeTodayAmount("支出", snapshot?.expense, interactive, Modifier.weight(1f)) { onOpen(EntryKind.EXPENSE) }
            }
            TextButton(
                onClick = { onOpen(EntryKind.REFUND) }, enabled = interactive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(snapshot?.let { "退款 ¥ ${Money.format(it.refunds)} · 单独列示" } ?: "退款 — · 单独列示")
            }
        }
    }
}

@Composable private fun HomeTodayAmount(
    title: String, amount: Long?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.widthIn(min = (120 * LocalDensity.current.fontScale).dp).heightIn(min = 80.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(amount?.let { "¥ ${Money.format(it)}" } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun homeFuturePoint(point: TrendPoint, today: String): Boolean =
    if (point.period.length == 7) point.period > today.take(7) else point.period > today

private fun homePointHasValues(point: TrendPoint): Boolean =
    point.income != 0L || point.expense != 0L || point.refunds != 0L

@Composable private fun HomeExpenseChart(
    points: List<TrendPoint>, today: String, interactive: Boolean, onPoint: (String) -> Unit,
) {
    val visibleIndices = remember(points, today) {
        points.indices.filter { !homeFuturePoint(points[it], today) || homePointHasValues(points[it]) }.toSet()
    }
    val maximumValue = visibleIndices.maxOfOrNull { points[it].netExpense.toDouble() }?.coerceAtLeast(0.0) ?: 0.0
    val minimum = visibleIndices.minOfOrNull { points[it].netExpense.toDouble() }?.coerceAtMost(0.0) ?: 0.0
    val maximum = if (maximumValue == 0.0 && minimum == 0.0) 1.0 else maximumValue
    val span = (maximum - minimum).coerceAtLeast(1.0)
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
    val chartDescription = if (visibleIndices.isEmpty()) "本期暂无花销数据" else
        "净花销趋势图，共 ${visibleIndices.size} 个日期；净花销等于原始支出减退款。可展开下方趋势数据查看每个日期和金额。"
    Canvas(
        Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = chartDescription }
            .pointerInput(points, visibleIndices, interactive, onPoint) {
                detectTapGestures { position ->
                    if (interactive && points.isNotEmpty()) {
                        val inset = 8.dp.toPx()
                        val width = (size.width - 2 * inset).coerceAtLeast(1f)
                        val index = if (points.size == 1) 0 else
                            (((position.x - inset) / width) * points.lastIndex).roundToInt().coerceIn(0, points.lastIndex)
                        if (index in visibleIndices) onPoint(points[index].period)
                    }
                }
            },
    ) {
        val inset = 8.dp.toPx()
        val width = (size.width - 2 * inset).coerceAtLeast(1f)
        val height = (size.height - 2 * inset).coerceAtLeast(1f)
        fun y(value: Double) = inset + ((maximum - value) / span).toFloat() * height
        val zero = y(0.0)
        listOf(inset, inset + height / 2, inset + height).forEach { gridY ->
            drawLine(axisColor, Offset(inset, gridY), Offset(size.width - inset, gridY), 1.dp.toPx())
        }
        drawLine(axisColor.copy(alpha = 0.35f), Offset(inset, zero), Offset(size.width - inset, zero), 1.dp.toPx())
        val path = Path()
        var previous: Offset? = null
        points.forEachIndexed { index, point ->
            if (index !in visibleIndices) {
                previous = null
            } else {
                val x = inset + if (points.size == 1) width / 2 else index.toFloat() / points.lastIndex * width
                val offset = Offset(x, y(point.netExpense.toDouble()))
                val last = previous
                if (last == null) path.moveTo(offset.x, offset.y) else {
                    val middle = (last.x + offset.x) / 2
                    path.cubicTo(middle, last.y, middle, offset.y, offset.x, offset.y)
                }
                if (points.size <= 32 || last == null || index + 1 !in visibleIndices) drawCircle(lineColor, 3.dp.toPx(), offset)
                previous = offset
            }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
    if (points.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(points.first().period, style = MaterialTheme.typography.labelSmall)
            if (points.size > 1) Text(points.last().period, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
    }
    if (visibleIndices.isEmpty()) {
        Text(if (points.isEmpty()) "本期暂无花销数据" else "所选日期尚未发生", style = MaterialTheme.typography.bodySmall)
    }
    if (visibleIndices.any { homeFuturePoint(points[it], today) }) {
        Text("部分账单日期晚于本机今天，请核对。", style = MaterialTheme.typography.bodySmall)
    }
}
