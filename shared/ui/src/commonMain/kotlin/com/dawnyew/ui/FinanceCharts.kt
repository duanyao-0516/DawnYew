package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.Money
import kotlin.math.*

internal data class FinanceChartPoint(val id: String, val label: String, val value: Long)
internal val financeChartColors = listOf(FinanceColors.brand, Color(0xFF64B4E6), Color(0xFFFFB46E), Color(0xFFAD9BDC), Color(0xFFFF8EA0), Color(0xFF95C86D))

/** Preserve every queried bucket. The axis may thin labels; it must never discard dated amounts. */
internal fun homeTrendPoints(snapshot: com.dawnyew.core.StatisticsSnapshot, amount: (com.dawnyew.core.TrendPoint) -> Long): List<FinanceChartPoint> =
    snapshot.trend.map { FinanceChartPoint(it.period, financeTrendLabel(it.period), amount(it)) }

internal fun financeTrendLabel(period: String): String = if (period.length == 7) period.replace('-', '.')
    else "${period.substring(5, 7).toInt()}.${period.takeLast(2).toInt()}"

internal fun chartMonthWindow(points: List<FinanceChartPoint>, year: String, today: String): List<FinanceChartPoint> {
    val anchor = if (today.startsWith(year)) today.substring(5, 7).toInt()
        else points.lastOrNull { it.value != 0L && it.id.startsWith(year) }?.id?.takeLast(2)?.toIntOrNull() ?: 12
    val first = (anchor - 3).coerceIn(1, 6)
    val values = points.associateBy { it.id }
    return (first..first + 6).map { month -> val key = "$year-${month.toString().padStart(2, '0')}"; values[key]?.copy(label = "${month}月") ?: FinanceChartPoint(key, "${month}月", 0) }
}

internal fun chartLabelIndices(count: Int, maximum: Int): Set<Int> {
    if (count <= 0) return emptySet()
    if (count <= maximum.coerceAtLeast(2)) return (0 until count).toSet()
    val step = ceil((count - 1).toDouble() / (maximum.coerceAtLeast(2) - 1)).toInt()
    val values = (0 until count step step).toMutableList()
    if (values.last() != count - 1) {
        if (values.size > 1 && count - 1 - values.last() < step) values.removeAt(values.lastIndex)
        values += count - 1
    }
    return values.toSet()
}

private fun axisAmount(value: Double): String = when {
    abs(value) >= 100_000_000 -> "${(value / 100_000_000).roundToLong()}百万"
    abs(value) >= 1_000_000 -> "${(value / 1_000_000).roundToLong()}万"
    else -> (value / 100).roundToInt().toString()
}

@Composable internal fun FinanceAreaChart(
    points: List<FinanceChartPoint>, modifier: Modifier = Modifier,
    color: Color = FinanceColors.expense, showYAxis: Boolean = true, negativeLabels: Boolean = false, fillArea: Boolean = true, onPoint: ((String) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(color = muted, fontSize = 9.sp)
    val target = modifier.fillMaxWidth().aspectRatio(1f / .28f).semantics {
        contentDescription = "趋势图，" + points.joinToString("；") { "${it.label} ${Money.format(it.value)}" }
    }.then(if (onPoint == null) Modifier else Modifier.pointerInput(points, onPoint, showYAxis) {
        detectTapGestures { offset ->
            val left = if (showYAxis) 28.dp.toPx() else 4.dp.toPx()
            val available = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            if (points.isNotEmpty()) onPoint(points[((offset.x - left) / available * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)].id)
        }
    })
    Canvas(target) {
        val left = if (showYAxis) 28.dp.toPx() else 4.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 7.dp.toPx()
        val bottom = (size.height - 22.dp.toPx()).coerceAtLeast(top + 1f)
        val low = minOf(0.0, points.minOfOrNull { it.value.toDouble() } ?: 0.0)
        val high = maxOf(0.0, points.maxOfOrNull { it.value.toDouble() } ?: 0.0).let { if (it == low) low + 100.0 else it }
        fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
        val zero = y(0.0)
        if (showYAxis) listOf(high, (high + low) / 2, low).forEach { value ->
            val layout = textMeasurer.measure(axisAmount(if (negativeLabels) -value else value), labelStyle)
            drawText(layout, topLeft = Offset(0f, (y(value) - layout.size.height / 2f).coerceIn(0f, bottom)))
        }
        if (low < 0) drawLine(axis, Offset(left, zero), Offset(right, zero), .5.dp.toPx())
        if (points.isNotEmpty()) {
            val coordinates = points.mapIndexed { index, point ->
                Offset(if (points.size == 1) (left + right) / 2 else left + (right - left) * index / (points.size - 1), y(point.value.toDouble()))
            }
            val curve = Path().apply {
                moveTo(coordinates.first().x, coordinates.first().y)
                coordinates.zipWithNext().forEach { (previous, next) ->
                    val middle = (previous.x + next.x) / 2
                    cubicTo(middle, previous.y, middle, next.y, next.x, next.y)
                }
            }
            val fill = Path().apply {
                addPath(curve); lineTo(coordinates.last().x, zero); lineTo(coordinates.first().x, zero); close()
            }
            if (fillArea) drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .24f), color.copy(alpha = .015f)), startY = top, endY = bottom))
            drawPath(curve, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            if (points.size == 1) drawCircle(color, 2.dp.toPx(), coordinates.first())
            val labelIndices = chartLabelIndices(points.size, (size.width / (44.dp.toPx() * fontScale)).toInt())
            points.forEachIndexed { index, point -> if (index in labelIndices) {
                val layout = textMeasurer.measure(point.label, labelStyle)
                drawText(layout, topLeft = Offset((coordinates[index].x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)), size.height - layout.size.height))
            } }
        }
    }
}

@Composable internal fun FinanceDonutChart(points: List<FinanceChartPoint>, modifier: Modifier = Modifier, center: String = "全部", negativeLabels: Boolean = false, onPoint: ((String) -> Unit)? = null) {
    val textMeasurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant
    val positive = points.filter { it.value > 0 }
    val total = positive.sumOf { it.value.toDouble() }
    val exactTotal = runCatching { positive.fold(0L) { sum, point -> Money.add(sum, point.value) } }.getOrNull()
    val totalLabel = exactTotal?.let { Money.format(if (negativeLabels) -it else it) } ?: "超出可显示范围"
    // The overview groups tiny slices only visually. The detail table always receives the original rows.
    val shown = if (positive.size <= 5 || exactTotal == null) positive else positive.take(4) + FinanceChartPoint("__other", "其他", positive.drop(4).fold(0L) { sum, point -> Money.add(sum, point.value) })
    Canvas(modifier.fillMaxWidth().aspectRatio(1f / .48f).semantics {
        contentDescription = "分类环图，总计$totalLabel；" + positive.joinToString("；") { "${it.label} ${Money.format(it.value)}" }
    }.then(if (onPoint == null) Modifier else Modifier.pointerInput(shown, total, onPoint) {
        detectTapGestures { offset ->
            val centerPoint = Offset(size.width / 2f, size.height / 2f)
            val angle = ((atan2(offset.y - centerPoint.y, offset.x - centerPoint.x) * 180 / PI - 45 + 360) % 360).toFloat()
            var accumulated = 0f
            shown.firstOrNull { accumulated += (it.value.toDouble() / total.coerceAtLeast(1.0) * 360).toFloat(); angle <= accumulated }?.takeIf { it.id != "__other" }?.let { onPoint(it.id) }
        }
    })) {
        val diameter = minOf(size.height * .78f, size.width * .44f)
        val centerPoint = Offset(size.width / 2f, size.height / 2f)
        val stroke = diameter * .125f
        val radius = (diameter - stroke) / 2
        val arcTop = Offset(centerPoint.x - radius, centerPoint.y - radius)
        val arcSize = Size(radius * 2, radius * 2)
        drawArc(track, 45f, 360f, false, arcTop, arcSize, style = Stroke(stroke))
        var angle = 45f
        data class Label(val point: FinanceChartPoint, val mid: Double, val color: Color)
        val labels = shown.mapIndexed { index, point ->
            val sweep = if (total == 0.0) 0f else (point.value.toDouble() / total * 360).toFloat()
            val color = financeChartColors[index % financeChartColors.size]
            drawArc(color, angle, sweep, false, arcTop, arcSize, style = Stroke(stroke))
            Label(point, (angle + sweep / 2) * PI / 180, color).also { angle += sweep }
        }
        listOf(false, true).forEach { right ->
            val side = labels.filter { (cos(it.mid) >= 0) == right }.sortedBy { sin(it.mid) }
            side.forEachIndexed { index, label ->
                val start = Offset(centerPoint.x + cos(label.mid).toFloat() * diameter / 2, centerPoint.y + sin(label.mid).toFloat() * diameter / 2)
                val text = "${label.point.label.take(6)} ${(label.point.value.toDouble() / total * 100).roundToInt()}%"
                val layout = textMeasurer.measure(text, TextStyle(color = muted, fontSize = 9.sp))
                val targetY = if (side.size == 1) start.y.coerceIn(10.dp.toPx(), size.height - 10.dp.toPx()) else size.height * (.18f + .64f * index / (side.size - 1))
                val elbowX = centerPoint.x + (if (right) 1 else -1) * diameter * .64f
                val endX = if (right) (size.width - layout.size.width).coerceAtLeast(elbowX) else minOf(layout.size.width.toFloat(), elbowX)
                drawLine(muted.copy(alpha = .5f), start, Offset(elbowX, targetY), .7.dp.toPx())
                drawLine(muted.copy(alpha = .5f), Offset(elbowX, targetY), Offset(endX, targetY), .7.dp.toPx())
                drawText(layout, topLeft = Offset(if (right) (size.width - layout.size.width).coerceAtLeast(0f) else 0f, (targetY - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
        }
        val middle = textMeasurer.measure("$center\n$totalLabel", TextStyle(color = muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        drawText(middle, topLeft = Offset(centerPoint.x - middle.size.width / 2f, centerPoint.y - middle.size.height / 2f))
    }
}

@Composable internal fun FinanceBarChart(points: List<FinanceChartPoint>, modifier: Modifier = Modifier, count: Boolean = false, negativeLabels: Boolean = false, onPoint: ((String) -> Unit)? = null) {
    val textMeasurer = rememberTextMeasurer(); val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outlineVariant; val color = FinanceColors.expense
    Canvas(modifier.fillMaxWidth().aspectRatio(1f / .40f).semantics { contentDescription = "柱状图；" + points.joinToString("；") { "${it.label} ${if (count) "${it.value}笔" else Money.format(it.value)}" } }
        .then(if (onPoint == null) Modifier else Modifier.pointerInput(points, onPoint) { detectTapGestures { tap ->
            val left = 28.dp.toPx(); val width = (size.width - left - 8.dp.toPx()).coerceAtLeast(1f)
            if (points.isNotEmpty()) onPoint(points[((tap.x - left) / width * points.size).toInt().coerceIn(0, points.lastIndex)].id)
        } })) {
        val left = 28.dp.toPx(); val right = size.width - 8.dp.toPx(); val top = 8.dp.toPx(); val bottom = (size.height - 22.dp.toPx()).coerceAtLeast(top + 1)
        val low = minOf(0.0, points.minOfOrNull { it.value.toDouble() } ?: 0.0)
        val high = maxOf(0.0, points.maxOfOrNull { it.value.toDouble() } ?: 0.0).let { if (it == low) low + if (count) 1.0 else 100.0 else it }
        fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
        val zero = y(0.0)
        drawLine(axis, Offset(left, zero), Offset(right, zero), .5.dp.toPx())
        listOf(high, (high + low) / 2, low).forEach { value ->
            val label = if (count) value.roundToInt().toString() else axisAmount(if (negativeLabels) -value else value)
            val layout = textMeasurer.measure(label, TextStyle(color = muted, fontSize = 9.sp))
            drawText(layout, topLeft = Offset(0f, (y(value) - layout.size.height / 2f).coerceAtLeast(0f)))
        }
        val group = (right - left) / points.size.coerceAtLeast(1)
        val width = minOf(group * .50f, 16.dp.toPx())
        val labelIndices = chartLabelIndices(points.size, (size.width / (44.dp.toPx() * fontScale)).toInt())
        points.forEachIndexed { index, point ->
            val x = left + group * (index + .5f)
            val end = y(point.value.toDouble())
            if (point.value != 0L) drawRoundRect(color, Offset(x - width / 2, minOf(end, zero)), Size(width, abs(end - zero)), CornerRadius(width / 2, width / 2))
            if (index in labelIndices) {
                val layout = textMeasurer.measure(point.label.take(6), TextStyle(color = muted, fontSize = 9.sp))
                drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)), size.height - layout.size.height))
            }
        }
    }
}

@Composable internal fun FinanceRadarChart(points: List<FinanceChartPoint>) {
    val textMeasurer = rememberTextMeasurer(); val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outlineVariant
    val shown = points.sortedByDescending { it.value }.take(6)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f / .65f).semantics { contentDescription = "分类倾向；" + points.joinToString("；") { "${it.label} ${Money.format(it.value)}" } }) {
        if (shown.isEmpty()) return@Canvas
        val count = maxOf(shown.size, 3); val center = Offset(size.width / 2, size.height / 2); val radius = size.height * .33f
        fun at(index: Int, fraction: Float): Offset { val angle = -PI / 2 + index * 2 * PI / count; return Offset(center.x + cos(angle).toFloat() * radius * fraction, center.y + sin(angle).toFloat() * radius * fraction) }
        listOf(.33f, .66f, 1f).forEach { level -> val path = Path(); repeat(count) { index -> val p = at(index, level); if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }; path.close(); drawPath(path, track, style = Stroke(.7.dp.toPx())) }
        val max = (shown.maxOfOrNull { it.value } ?: 0).coerceAtLeast(1).toDouble(); val shape = Path()
        repeat(count) { index -> val end = at(index, 1f); drawLine(track, center, end, .7.dp.toPx()); val p = at(index, ((shown.getOrNull(index)?.value ?: 0) / max).toFloat().coerceIn(0f, 1f)); if (index == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
            shown.getOrNull(index)?.let { row -> val label = textMeasurer.measure(row.label.take(6), TextStyle(color = muted, fontSize = 10.sp)); val position = at(index, 1.18f); drawText(label, topLeft = Offset(position.x - label.size.width / 2, position.y - label.size.height / 2)) }
        }
        shape.close(); drawPath(shape, FinanceColors.brand.copy(alpha = .20f)); drawPath(shape, FinanceColors.brand, style = Stroke(1.5.dp.toPx()))
    }
}

/** Paired daily bars use one shared scale, so equal-height income and expense bars mean equal amounts. */
@Composable internal fun FinanceCashFlowChart(income: List<FinanceChartPoint>, expense: List<FinanceChartPoint>, onPoint: (String) -> Unit) {
    val textMeasurer = rememberTextMeasurer(); val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outlineVariant
    val expenses = expense.associateBy { it.id }
    Column {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            androidx.compose.material3.Text("● 收入", color = FinanceColors.income, style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.Text("● 支出", color = FinanceColors.expense, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f / .40f).semantics { contentDescription = income.joinToString("；") { "${it.label} 收入${Money.format(it.value)} 支出${Money.format(expenses[it.id]?.value ?: 0)}" } }
            .pointerInput(income, onPoint) { detectTapGestures { tap -> if (income.isNotEmpty()) onPoint(income[(tap.x / size.width * income.size).toInt().coerceIn(0, income.lastIndex)].id) } }) {
            val top = 12.dp.toPx(); val bottom = (size.height - 22.dp.toPx()).coerceAtLeast(top + 1)
            val maximum = maxOf(income.maxOfOrNull { it.value } ?: 0, expense.maxOfOrNull { it.value } ?: 0, 1).toDouble()
            val group = size.width / income.size.coerceAtLeast(1); val width = minOf(group * .22f, 12.dp.toPx())
            drawLine(axis, Offset(0f, bottom), Offset(size.width, bottom), .5.dp.toPx())
            val indices = chartLabelIndices(income.size, (size.width / (44.dp.toPx() * fontScale)).toInt())
            income.forEachIndexed { index, point ->
                val center = group * (index + .5f)
                listOf(point.value to FinanceColors.income, (expenses[point.id]?.value ?: 0) to FinanceColors.expense).forEachIndexed { series, (value, color) ->
                    val height = (value / maximum * (bottom - top)).toFloat().coerceAtLeast(0f)
                    if (height > 0f) drawRoundRect(color, Offset(center + (series - 1) * (width + 2.dp.toPx()), bottom - height), Size(width, height), CornerRadius(width / 2, width / 2))
                }
                if (index in indices) { val text = textMeasurer.measure(point.label, TextStyle(color = muted, fontSize = 9.sp)); drawText(text, topLeft = Offset((center - text.size.width / 2).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f)), size.height - text.size.height)) }
            }
        }
    }
}
