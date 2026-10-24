package com.dawnyew.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dawnyew.core.Category

/** Keep a selected category visible even when it falls beyond the compact preview. */
internal fun entryCategoryPreview(categories: List<Category>, selectedId: String?, count: Int): List<Category> {
    val first = categories.take(count)
    val selected = categories.find { it.id == selectedId }
    return if (selected != null && selected !in first && first.isNotEmpty()) first.dropLast(1) + selected else first
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EntryComposerChip(
    label: String, selected: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary, filled: Boolean = false, small: Boolean = false,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) { Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.semantics { this.selected = selected },
        shape = RoundedCornerShape(16.dp),
        color = if (selected && filled) accent else if (filled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        contentColor = if (selected && filled) MaterialTheme.colorScheme.onPrimary else if (selected) accent else if (filled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        border = if (filled) null else BorderStroke(.7.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
    ) {
        Box(Modifier.heightIn(min = 32.dp).padding(horizontal = if (small) 10.dp else 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(label, style = if (small) MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp) else MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EntryComposerRow(
    label: String, value: String, enabled: Boolean = true, modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) { Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 36.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AppGlyph("前进", Modifier.size(12.dp))
        }
    } }
}

@Composable internal fun EntryComposerToggle(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        CompactFinanceSwitch(value, onChange, enabled, Modifier.semantics { contentDescription = label })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EntryAmountKeyboard(enabled: Boolean, onKey: (String) -> Unit) {
    val currentOnKey = rememberUpdatedState(onKey)
    val fontScale = LocalDensity.current.fontScale
    val minHeight = entryKeyboardRowHeight(fontScale).dp
    val keys = listOf(listOf("7", "8", "9", "⌫", "C"), listOf("4", "5", "6", "×", "÷"), listOf("1", "2", "3", "+", "−"), listOf("(", "0", ".", ")", "="))
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { key ->
                        val operator = key in listOf("×", "÷", "+", "−", "=")
                        Surface(onClick = { currentOnKey.value(key) }, enabled = enabled,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "金额键$key" },
                            shape = RoundedCornerShape(16.dp),
                            color = if (operator) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            contentColor = if (operator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
                            Box(Modifier.heightIn(min = minHeight).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                Text(key, fontSize = 18.sp, lineHeight = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
