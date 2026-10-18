package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val utilityEntries = listOf(
    "调色盘" to "输入 RGB 或十六进制色值，也可以点选颜色",
    "尺子" to "全屏双侧厘米刻度，支持实物校准",
    "噪音测量" to "使用麦克风查看声级，支持参考值校准",
    "做个决定" to "添加选项，随机选出一个",
    "石头剪刀布" to "双人对坐，点击中央同时揭晓",
)

@Composable internal fun MorePage(onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(utilityEntries, key = { it.first }) { (title, description) ->
            Card(onClick = { onOpen(title) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Box(Modifier.padding(12.dp)) { AppGlyph(title) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
