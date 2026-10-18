package com.dawnyew.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/** One gesture owner covers the whole tab, retaining Material's layout and indicator. */
@Composable internal fun RowScope.MyNavigationItem(
    isSelected: Boolean,
    onClick: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth()) {
            NavigationBarItem(
                selected = isSelected, onClick = onClick,
                modifier = Modifier.focusProperties { canFocus = false }.clearAndSetSemantics { },
                icon = { AppGlyph("我的") }, label = { Text("我的") },
                interactionSource = interaction,
            )
        }
        // This sibling receives taps/long presses before the visual item's selectable does.
        Box(Modifier.matchParentSize().semantics { contentDescription = "我的"; selected = isSelected }
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Tab,
                onLongClickLabel = "打开 Debug", onLongClick = onOpenDebug, onClick = onClick,
            ))
    }
}
