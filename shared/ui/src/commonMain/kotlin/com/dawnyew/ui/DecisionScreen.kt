package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dawnyew.presentation.chooseDecision
import com.dawnyew.presentation.decisionOptionsError

@Composable internal fun DecisionPage() {
    var options by rememberSaveable(stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })) {
        mutableStateOf(listOf("", ""))
    }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val error = decisionOptionsError(options)

    fun updateOptions(next: List<String>) {
        options = next
        result = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("在 2 至 20 个选项中等概率抽取一个。", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (result == null) "让选择简单一点" else "这次就选", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Text(result ?: "填写选项，再轻点下方按钮。", style = if (result == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineMedium)
                    }
                    Button(
                        onClick = { attempted = true; if (error == null) result = chooseDecision(options) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (result == null) "帮我决定" else "再选一次") }
                    if (attempted && error != null) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        itemsIndexed(options) { index, option ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = option,
                    onValueChange = { value -> updateOptions(options.mapIndexed { i, previous -> if (i == index) value.take(200) else previous }) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("选项 ${index + 1}") },
                    minLines = 1, maxLines = 5,
                    supportingText = { Text("${option.length}/200 · 每项最多 200 个字符") },
                    isError = attempted && (option.isBlank() || options.count { it.trim() == option.trim() } > 1),
                )
                TextButton(
                    onClick = { updateOptions(options.filterIndexed { i, _ -> i != index }) },
                    enabled = options.size > 2, modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("删除选项 ${index + 1}") }
            }
        }
        item {
            OutlinedButton(
                onClick = { updateOptions(options + "") }, enabled = options.size < 20,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("添加选项（${options.size}/20）") }
        }
        item {
            Text("选项不能为空或重复，首尾空格会自动忽略。修改、添加或删除选项后会清除旧结果。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
