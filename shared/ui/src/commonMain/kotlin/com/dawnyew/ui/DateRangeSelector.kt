package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*

internal fun dateRangeLabel(range: DateRange): String = when (range.kind) {
    DateRangeKind.MONTH -> "${range.startDate.take(4)}年${range.startDate.substring(5, 7).toInt()}月"
    DateRangeKind.YEAR -> "${range.startDate.take(4)}年"
    DateRangeKind.CUSTOM -> if (range.startDate == range.endDate) range.startDate
        else "${range.startDate} 至 ${range.endDate}"
}

/** The selector stays mounted while the result below it changes. */
@Composable internal fun DateRangeSelector(
    start: String, end: String, today: String, onSelect: (DateRange) -> Unit,
) {
    val range = remember(start, end) { DateRange(start, end) }
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val previous = remember(range) { runCatching { range.shift(-1) }.getOrNull() }
    val next = remember(range) { runCatching { range.shift(1) }.getOrNull() }
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { previous?.let(onSelect) }, enabled = previous != null,
                modifier = Modifier.semantics { contentDescription = "上一期" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择日期范围" }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dateRangeLabel(range), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        Text("选择日期范围 ▾", style = MaterialTheme.typography.labelSmall)
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf(
                        DateRangePreset.CURRENT_MONTH to "本月", DateRangePreset.PREVIOUS_MONTH to "上月",
                        DateRangePreset.CURRENT_YEAR to "今年", DateRangePreset.LAST_7_DAYS to "近 7 天",
                        DateRangePreset.LAST_30_DAYS to "近 30 天",
                    ).forEach { (preset, label) ->
                        val candidate = runCatching { dateRangePreset(preset, today) }.getOrNull()
                        DropdownMenuItem(text = { Text(label) }, enabled = candidate != null,
                            onClick = { expanded = false; candidate?.let(onSelect) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("自定义日期…") }, onClick = { expanded = false; custom = true })
                }
            }
            IconButton(onClick = { next?.let(onSelect) }, enabled = next != null,
                modifier = Modifier.semantics { contentDescription = "下一期" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
        }
    }
    if (custom) DateRangeDialog(range, onDismiss = { custom = false }, onApply = { custom = false; onSelect(it) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateRangeDialog(initial: DateRange, onDismiss: () -> Unit, onApply: (DateRange) -> Unit) {
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = dateToUtcMillis(initial.startDate),
        initialSelectedEndDateMillis = dateToUtcMillis(initial.endDate),
        initialDisplayedMonthMillis = dateToUtcMillis(initial.startDate),
        yearRange = 1..9999,
    )
    val selected = remember(picker.selectedStartDateMillis, picker.selectedEndDateMillis) {
        val start = picker.selectedStartDateMillis
        val end = picker.selectedEndDateMillis
        if (start == null || end == null) null else runCatching { DateRange(utcMillisToDate(start), utcMillisToDate(end)) }.getOrNull()
    }
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { selected?.let(onApply) }, enabled = selected != null) { Text("应用日期") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DateRangePicker(state = picker, modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            title = { Text("选择起止日期", Modifier.padding(start = 24.dp, top = 16.dp)) },
            headline = {
                Text(selected?.let(::dateRangeLabel) ?: "选择开始日期和结束日期",
                    Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium)
            }, showModeToggle = true)
    }
}

/** A fixed-height status slot avoids moving the content for short local reads. */
@Composable internal fun QueryStatus(loading: Boolean, displayed: QueryScope?, requested: QueryScope, error: String?, onRetry: () -> Unit) {
    val changingRange = displayed != null && displayed != requested
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            error != null -> {
                Text(error + if (changingRange) "；当前仍为上次筛选结果：${displayed!!.startDate} 至 ${displayed.endDate}" else "", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("重试") }
            }
            changingRange -> Text("正在更新 · 上次筛选结果：${displayed!!.startDate} 至 ${displayed.endDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            loading && displayed == null -> Text("正在读取…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
