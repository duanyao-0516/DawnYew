package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import com.dawnyew.core.Money

internal val LocalCompactAmounts = staticCompositionLocalOf { false }
internal fun summaryAmount(amount: Long, compact: Boolean): String = Money.format(amount).let { if (compact) it.trimEnd('0').trimEnd('.') else it }

internal fun financeDifferenceText(a: Long, b: Long): String = runCatching {
    check(b != Long.MIN_VALUE)
    Money.format(Money.add(a, -b))
}.getOrElse { "超出可显示范围" }

@Composable internal fun DashboardCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable internal fun CardHeading(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onClick != null) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { AppGlyph("前进", Modifier.size(14.dp)) }
        }
    }
}

@Composable internal fun FinanceAmount(value: Long?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, large: Boolean = false, summary: Boolean = false) {
    Text(value?.let { summaryAmount(it, summary && LocalCompactAmounts.current) } ?: "—", modifier, color = color,
        style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold)
}

@Composable internal fun FinanceRing(fraction: Float, label: String, value: String, supporting: String, modifier: Modifier = Modifier, color: Color = FinanceColors.brand) {
    val track = MaterialTheme.colorScheme.primaryContainer
    Box(modifier.size(136.dp).semantics { contentDescription = "$label $value，$supporting" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0f) drawArc(color, -90f, fraction.coerceIn(0f, 1f) * 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable internal fun FeatureTile(title: String, summary: String?, modifier: Modifier = Modifier, vertical: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 84.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        if (vertical) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FeatureIcon(title)
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Row(Modifier.padding(horizontal = 16.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureIcon(title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun FeatureIcon(title: String) {
    AppGlyph(title, Modifier.size(36.dp))
}

@Composable internal fun FeatureGrid(items: List<Pair<String, String?>>, vertical: Boolean = false, onOpen: (String) -> Unit) {
    val columns = if (LocalDensity.current.fontScale > 1.4f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (title, summary) -> FeatureTile(title, summary, Modifier.weight(1f), vertical) { onOpen(title) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable internal fun ManagementGrid(items: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    val columns = if (LocalDensity.current.fontScale > 1.4f) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (title, route) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onOpen(route) }.padding(vertical = 8.dp).heightIn(min = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { Box(Modifier.size(30.dp).padding(4.dp)) { AppGlyph(title, Modifier.size(22.dp)) } }
                        Text(title, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable internal fun GroupedFormRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        AppGlyph("前进")
    }
}

@Composable internal fun FinanceToggle(label: String, supporting: String? = null, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        CompactFinanceSwitch(value, onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AssetHero(
    net: Long?, assets: Long?, liabilities: Long?, hidden: Boolean, onToggle: () -> Unit,
    historyPoints: List<FinanceChartPoint>? = null, historyLoading: Boolean = false, historyError: String? = null,
    onTrend: () -> Unit,
) {
    val compact = LocalCompactAmounts.current
    val background = referenceArtwork("资产背景")
    val chartScheme = MaterialTheme.colorScheme.copy(onSurfaceVariant = Color.White, outlineVariant = Color.White.copy(alpha = .35f))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FinanceColors.brand)) {
        background?.let { Image(it.painter, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompositionLocalProvider(LocalContentColor provides Color.White, LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hidden) "••••" else net?.let { summaryAmount(it, compact) } ?: "—", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = onToggle, modifier = Modifier.padding(start = 8.dp).size(24.dp).semantics { contentDescription = if (hidden) "显示金额" else "隐藏金额" }) { AppGlyph("可见", Modifier.size(20.dp)) }
                    }
                    Surface(onClick = onTrend, shape = CircleShape, color = Color.Transparent, contentColor = Color.White, border = BorderStroke(.7.dp, Color.White)) {
                        Text("资产趋势", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("资产  " + if (hidden) "••••" else assets?.let { summaryAmount(it, compact) } ?: "—", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text("负债  " + if (hidden) "••••" else liabilities?.let { summaryAmount(-it, compact) } ?: "—", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            if (hidden) Box(Modifier.fillMaxWidth().aspectRatio(1f / .28f), contentAlignment = Alignment.Center) {
                Text("金额已隐藏", color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
            } else if (historyPoints != null && historyPoints.isNotEmpty()) {
                MaterialTheme(colorScheme = chartScheme) {
                    FinanceAreaChart(historyPoints, color = Color.White, showYAxis = false, fillArea = false)
                }
            } else Box(Modifier.fillMaxWidth().aspectRatio(1f / .28f), contentAlignment = Alignment.Center) {
                Text(historyError ?: if (historyLoading) "正在读取资产趋势…" else "暂无资产趋势", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
