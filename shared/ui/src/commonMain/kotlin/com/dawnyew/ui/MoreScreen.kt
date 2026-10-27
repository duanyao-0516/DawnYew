package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val utilityEntries = listOf(
    "调色盘" to "RGB、十六进制色值与点选颜色",
    "尺子" to "双侧厘米刻度，支持实物校准",
    "噪音测量" to "本机麦克风声级与参考校准",
    "做个决定" to "添加选项，随机选出一个",
    "石头剪刀布" to "双人对坐，同时揭晓",
    "MBTI 测试" to "93题，离线查看类型与维度得分",
)

@Composable internal fun MorePage(summaries: Map<String, String> = emptyMap(), onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("往来管理", style = MaterialTheme.typography.titleMedium) }
        item { FeatureGrid(listOf("借入", "借出", "报销", "往来").map { it to summaries[it] }, onOpen = onOpen) }
        item { Text("计划与工具", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium) }
        item { FeatureGrid(listOf("周期", "预算", "存钱", "工具箱").map { it to (summaries[it] ?: if (it == "工具箱") "日常实用工具" else null) }, onOpen = onOpen) }
        item { SettingRow("主页面板", "选择与排序首页的 ${com.dawnyew.core.PanelCatalog.definitions(com.dawnyew.core.PanelSurface.HOME).size} 个面板") { onOpen("主页面板") } }
        item { SettingRow("统计面板", "选择与排序 ${com.dawnyew.core.PanelCatalog.definitions(com.dawnyew.core.PanelSurface.STATISTICS).size} 个统计面板") { onOpen("统计面板") } }
    }
}

@Composable internal fun ToolboxPage(onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { SettingsSection {
            utilityEntries.forEachIndexed { index, (title, description) ->
                if (index > 0) SettingsDivider()
                SettingItem(title, description, onClick = { onOpen(title) })
            }
        } }
    }
}
