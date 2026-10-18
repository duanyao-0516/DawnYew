package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable internal fun DebugPage(
    data: LedgerState,
    today: String,
    busy: Boolean,
    onGenerate: (ledgerId: String, startDate: String, endDate: String, count: Int) -> Unit,
    onUndo: (batchId: String) -> Unit,
) {
    val ledgers = remember(data.ledgers) { data.ledgers.filterNot { it.archived }.sortedBy { it.sortOrder } }
    val recentMonth = remember(today) { dateRangePreset(DateRangePreset.LAST_30_DAYS, today) }
    var ledgerId by rememberSaveable(data.datasetId) {
        mutableStateOf(ledgers.firstOrNull { it.name == "日常" }?.id ?: ledgers.firstOrNull()?.id)
    }
    var start by rememberSaveable(data.datasetId) { mutableStateOf(recentMonth.startDate) }
    var end by rememberSaveable(data.datasetId) { mutableStateOf(recentMonth.endDate) }
    var countText by rememberSaveable(data.datasetId) { mutableStateOf("100") }
    var chooseLedger by remember { mutableStateOf(false) }
    var chooseDates by remember { mutableStateOf(false) }
    var undoBatch by remember { mutableStateOf<ImportBatch?>(null) }
    val ledger = ledgers.find { it.id == ledgerId }
    val range = remember(start, end) { runCatching { DateRange(start, end) }.getOrNull() }
    val count = countText.toIntOrNull()
    val error = when {
        ledger == null -> "请选择可用账本"
        range == null -> "请输入有效的起止日期，开始日期不能晚于结束日期"
        end > today -> "结束日期不能晚于今天（$today）"
        count == null || count !in 1..10_000 -> "数据条数须为 1–10,000 的整数"
        data.categories.none { it.ledgerId == ledger.id && it.enabled } -> "这个账本没有启用的分类，请先在分类管理中添加或启用分类"
        else -> null
    }
    val batches = remember(data.importBatches) {
        data.importBatches.filter { it.adapterId == DEBUG_BATCH_ADAPTER_ID }.sortedByDescending { it.createdAt }
    }
    val entriesById = remember(data.entries) { data.entries.associateBy { it.id } }
    val membersByBatch = remember(data.importMembers) { data.importMembers.groupBy { it.batchId } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("generator") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("生成测试账单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("按所选日期和账本追加测试数据，用于查看曲线、统计和明细。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { chooseLedger = true }, enabled = !busy && ledgers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("账本：${ledger?.name ?: "请选择账本"} ▾")
                    }
                    Text("日期范围", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = range == recentMonth, enabled = !busy,
                            onClick = { start = recentMonth.startDate; end = recentMonth.endDate }, label = { Text("最近一个月") })
                        val recentWeek = dateRangePreset(DateRangePreset.LAST_7_DAYS, today)
                        FilterChip(selected = range == recentWeek, enabled = !busy,
                            onClick = { start = recentWeek.startDate; end = recentWeek.endDate }, label = { Text("近 7 天") })
                        FilterChip(selected = start == today.take(7) + "-01" && end == today, enabled = !busy,
                            onClick = { start = today.take(7) + "-01"; end = today }, label = { Text("本月至今") })
                    }
                    OutlinedButton(onClick = { chooseDates = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("$start 至 $end ▾")
                    }
                    Text("最近一个月 = 含今天的近 30 天；起止日期均包含在内。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(countText, { countText = it }, label = { Text("数据条数") }, supportingText = { Text("每次 1–10,000 条，默认 100 条") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("生成的账单会参与首页和统计，备注标注为「DEBUG 测试」。已有账单保持不变。", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { if (error == null && ledger != null && range != null && count != null) onGenerate(ledger.id, range.startDate, range.endDate, count) },
                        enabled = !busy && error == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (busy) "正在处理…" else "生成 ${count ?: ""} 条测试账单") }
                }
            }
        }
        item("overview") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地数据概览", style = MaterialTheme.typography.titleMedium)
                    Text("${data.ledgers.size} 个账本 · ${data.entries.count { it.active }} 笔有效账单 · ${data.entries.count { !it.active }} 笔回收站账单")
                    Text("${batches.count { it.status == "COMMITTED" }} 个未撤销测试批次", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item("batch-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("测试批次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("可整批撤销未改动的测试数据。修改、删除或新增关联退款后，该批次无法整批撤销。", style = MaterialTheme.typography.bodySmall)
                if (batches.isEmpty()) Text("还没有生成过测试数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(batches, key = { it.id }) { batch ->
            val batchEntries = membersByBatch[batch.id].orEmpty().mapNotNull { entriesById[it.entryId] }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${batch.count} 条测试账单 · ${if (batch.status == "UNDONE") "已撤销" else "已生成"}", style = MaterialTheme.typography.titleSmall)
                    val created = remember(batch.createdAt) { runCatching { Instant.parse(batch.createdAt).toLocalDateTime(TimeZone.currentSystemDefault()).toString().take(19).replace('T', ' ') }.getOrDefault(batch.createdAt) }
                    Text(created, style = MaterialTheme.typography.bodySmall)
                    if (batchEntries.isNotEmpty()) {
                        Text(batchEntries.mapNotNull { e -> data.ledgers.find { it.id == e.ledgerId }?.name }.distinct().joinToString("、"))
                        Text("${batchEntries.minOf { it.localDate }} 至 ${batchEntries.maxOf { it.localDate }}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (batch.status == "COMMITTED") OutlinedButton(onClick = { undoBatch = batch }, enabled = !busy) { Text("撤销这批测试数据") }
                }
            }
        }
    }
    if (chooseLedger) ChoiceDialog("选择生成账本", ledgers.map { it.id to it.name }, ledgerId,
        onSelect = { ledgerId = it; chooseLedger = false }, onDismiss = { chooseLedger = false })
    if (chooseDates) DateRangeDialog(range ?: recentMonth,
        onDismiss = { chooseDates = false }, onApply = { start = it.startDate; end = it.endDate; chooseDates = false })
    undoBatch?.let { batch ->
        AlertDialog(onDismissRequest = { if (!busy) undoBatch = null }, title = { Text("撤销这批测试数据？") },
            text = { Text("将移除本批生成的 ${batch.count} 条测试账单，其他账单不受影响。若批次已被修改，会拒绝整批撤销。") },
            confirmButton = { TextButton(onClick = { undoBatch = null; onUndo(batch.id) }, enabled = !busy) { Text("撤销此批次") } },
            dismissButton = { TextButton(onClick = { undoBatch = null }, enabled = !busy) { Text("取消") } })
    }
}
