package com.dawnyew.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Each tab has one gesture/accessibility owner, including the local debug long press. */
@Composable internal fun RowScope.FinanceNavigationItem(
    title:String,
    isSelected:Boolean,
    onClick:()->Unit,
    onLongClick:(()->Unit)?=null,
) {
    val color=if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.weight(1f).fillMaxHeight()
        .semantics {contentDescription=title;selected=isSelected}
        .combinedClickable(role=Role.Tab,onLongClickLabel=if(onLongClick!=null) "打开 Debug" else null,onLongClick=onLongClick,onClick=onClick),
        contentAlignment=Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides color) {AppGlyph(title,Modifier.size(24.dp))}
    }
}
