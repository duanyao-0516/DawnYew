package com.dawnyew.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dawnyew.core.Money

@Composable internal fun DetailFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun BusinessRecordRow(title: String, supporting: String, amount: Long? = null, color: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 56.dp).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        amount?.let { Text(Money.format(it), color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
        if (onClick != null) AppGlyph("前进", Modifier.size(18.dp))
    }
}

@Composable internal fun MissingBusinessDetail(loading: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else { EmptyState("记录已不存在", "返回列表查看当前账本中的记录。"); TextButton(onClick = onBack) { Text("返回列表") } }
    }
}
