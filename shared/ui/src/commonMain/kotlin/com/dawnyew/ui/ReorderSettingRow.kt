package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared appearance for reorderable settings; drag gestures belong to the enclosing list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReorderSettingRow(
    title: String,
    description: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    toggleEnabled: Boolean = enabled,
    toggleLabel: String = "开关$title",
    onToggle: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    transientBusy: Boolean = false,
) {
    val toggle = if (checked != null) requireNotNull(onToggle) { "A setting with a switch requires onToggle" } else null
    val rowClick: (() -> Unit)? = onClick ?: checked?.let { value -> { toggle?.invoke(!value) } }
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { trailing() }
                    }
                }
                if (checked != null) {
                    Spacer(Modifier.width(8.dp))
                    CompactFinanceSwitch(checked, toggle, enabled && toggleEnabled,
                        Modifier.semantics { contentDescription = toggleLabel }, transientBusy = transientBusy)
                }
            }
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (rowClick != null) {
        Surface(
            onClick = rowClick,
            enabled = enabled && !transientBusy && (onClick != null || toggleEnabled),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** Every finance switch uses the panel size, including filters and form settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompactFinanceSwitch(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true,
    modifier: Modifier = Modifier,
    transientBusy: Boolean = false,
) {
    val normalColors = SwitchDefaults.colors()
    // A short write gate must not recolor unrelated settings. Business-disabled controls still
    // use the standard disabled palette, and transiently blocked controls remain non-interactive.
    val colors = if (enabled && transientBusy) normalColors.copy(
        disabledCheckedThumbColor = normalColors.checkedThumbColor,
        disabledCheckedTrackColor = normalColors.checkedTrackColor,
        disabledCheckedBorderColor = normalColors.checkedBorderColor,
        disabledCheckedIconColor = normalColors.checkedIconColor,
        disabledUncheckedThumbColor = normalColors.uncheckedThumbColor,
        disabledUncheckedTrackColor = normalColors.uncheckedTrackColor,
        disabledUncheckedBorderColor = normalColors.uncheckedBorderColor,
        disabledUncheckedIconColor = normalColors.uncheckedIconColor,
    ) else normalColors
    Box(Modifier.size(36.dp, 22.dp), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Switch(checked = checked, enabled = enabled && !transientBusy, onCheckedChange = onCheckedChange, colors = colors,
                modifier = Modifier.requiredSize(52.dp, 32.dp).scale(.70f).then(modifier))
        }
    }
}
