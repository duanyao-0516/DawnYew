package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.dawnyew.core.*

/** The switch and order both derive from the same committed preference observed by the dashboard. */
@Composable internal fun PanelManagementPage(s: LedgerState, surface: PanelSurface, busy: Boolean, onSave: (PanelLayout) -> Unit) {
    val layout = remember(s.preferences, surface) { PanelCatalog.resolve(s.preferences, surface) }
    val definitions = remember(surface) { PanelCatalog.definitions(surface).associateBy { it.id } }
    LongPressReorderableList(items = layout.order.map(definitions::getValue), key = { it.id }, label = { it.title }, busy = busy,
        onCommit = { order -> onSave(layout.copy(order = order)) }, sourceVersion = s.datasetId to s.contentVersion,
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        header = { Text("${layout.enabled.size} / ${layout.order.size} 个面板已开启 · 长按拖动调整顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) { definition ->
        fun toggle(enabled: Boolean) { onSave(layout.copy(enabled = if (enabled) layout.enabled + definition.id else layout.enabled - definition.id)) }
        ReorderSettingRow(
            title = definition.title, description = definition.description,
            checked = definition.id in layout.enabled, transientBusy = busy,
            toggleLabel = "开关${definition.title}", onToggle = ::toggle,
        )
    }
}
