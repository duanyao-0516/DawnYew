package com.dawnyew.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

private class ReorderLanding(val key: String, val viewportTop: Float) {
    val progress = Animatable(0f)
}

/** Reorders only keys from the same group, preserving fixed headers and every other group's slots. */
internal fun reorderGroupKeys(order: List<String>, dragged: String, target: String, groups: Map<String, String?>): List<String> {
    val group = groups[dragged] ?: return order
    if (groups[target] != group || dragged == target) return order
    val members = order.filter { groups[it] == group }.toMutableList()
    val from = members.indexOf(dragged); val to = members.indexOf(target)
    if (from < 0 || to < 0) return order
    members.removeAt(from); members.add(to, dragged)
    var next = 0
    return order.map { if (groups[it] == group) members[next++] else it }
}

/** The preview is temporary. Only the parent's committed items survive cancellation or a failed write. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun <T> LongPressReorderableList(
    items: List<T>, key: (T) -> String, label: (T) -> String, busy: Boolean,
    onCommit: (List<String>) -> Unit,
    modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(16.dp), spacing: Dp = 16.dp,
    reorderGroup: (T) -> String? = { "default" }, sourceVersion: Any? = items.map(key),
    header: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    val committed = items.map(key)
    val itemMap = items.associateBy(key)
    val groups = items.associate { key(it) to reorderGroup(it) }
    val currentCommit by rememberUpdatedState(onCommit)
    val currentBusy by rememberUpdatedState(busy)
    val currentGroups by rememberUpdatedState(groups)
    val currentKeys by rememberUpdatedState(committed)
    val currentVersion by rememberUpdatedState(sourceVersion)
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var pending by remember { mutableStateOf<List<String>?>(null) }
    var pendingSawBusy by remember { mutableStateOf(false) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragVersion by remember { mutableStateOf<Any?>(null) }
    var dragCommit by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<ReorderLanding?>(null) }
    val display = (preview ?: pending ?: committed).takeIf { it.toSet() == committed.toSet() } ?: committed

    fun updateTarget() {
        val active = dragged ?: return
        val order = preview ?: return
        val group = currentGroups[active] ?: return
        val center = origin + dragDelta + dragHeight / 2
        val currentIndex = order.indexOf(active)
        val target = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val id = info.key as? String
            if (id == null || id == active || currentGroups[id] != group) false
            else {
                val targetIndex = order.indexOf(id)
                center in info.offset.toFloat()..(info.offset + info.size).toFloat() &&
                    if (targetIndex > currentIndex) center > info.offset + info.size / 2 else center < info.offset + info.size / 2
            }
        }?.key as? String ?: return
        preview = reorderGroupKeys(order, active, target, currentGroups)
    }

    fun finish(commit: Boolean) {
        val active = dragged ?: return
        val result = preview ?: currentKeys
        val shouldCommit = commit && !currentBusy && dragVersion == currentVersion && result != currentKeys
        val commitCallback = dragCommit
        // Preserve the rendered top, not a translation relative to a possibly stale layout slot.
        // Landing and the new order are installed before releasing the active drag.
        settling = ReorderLanding(active, origin + dragDelta)
        if (shouldCommit) pendingSawBusy = false
        pending = if (shouldCommit) result else null
        dragged = null
        preview = null
        dragCommit = null
        if (shouldCommit) commitCallback?.invoke(result)
    }

    LaunchedEffect(settling) {
        val landing = settling ?: return@LaunchedEffect
        // Let the reordered slot be measured while the item remains at its pointer-release top.
        withFrameNanos { }
        landing.progress.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy))
        if (settling === landing) settling = null
    }

    fun rollbackPending() {
        val landing = settling
        if (landing != null) {
            val slot = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == landing.key }
            // Retarget a failed write from the current rendered top, not the original release top.
            settling = if (slot != null && landing.key in currentKeys) {
                val visualTop = slot.offset + (landing.viewportTop - slot.offset) * (1f - landing.progress.value)
                ReorderLanding(landing.key, visualTop)
            } else null
        }
        pending = null
    }

    // A busy->idle cycle without the new order is a failed/rejected commit and restores canonical order.
    LaunchedEffect(committed, busy, pending) {
        if (pending == null) return@LaunchedEffect
        if (pending == committed) { pending = null; return@LaunchedEffect }
        if (pending?.toSet() != committed.toSet()) { rollbackPending(); return@LaunchedEffect }
        if (busy) pendingSawBusy = true
        else if (pendingSawBusy) rollbackPending()
        else { delay(750); if (!currentBusy && pending != currentKeys) rollbackPending() }
    }
    LaunchedEffect(committed, busy, sourceVersion) {
        if (dragged != null && (busy || dragVersion != sourceVersion || preview?.toSet() != committed.toSet())) finish(false)
    }
    LaunchedEffect(dragged) {
        while (dragged != null) {
            withFrameNanos { }
            val layout = state.layoutInfo
            val center = origin + dragDelta + dragHeight / 2
            val edge = minOf(dragHeight, 120f).coerceAtLeast(64f)
            val scroll = when {
                center < layout.viewportStartOffset + edge -> (center - layout.viewportStartOffset - edge) * .12f
                center > layout.viewportEndOffset - edge -> (center - layout.viewportEndOffset + edge) * .12f
                else -> 0f
            }.coerceIn(-32f, 32f)
            if (scroll != 0f) { state.scrollBy(scroll); updateTarget() }
        }
    }
    DisposableEffect(Unit) { onDispose { /* Local drag/pending previews never persist across navigation. */ } }
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(spacing)) {
        if (header != null) item("__reorder_header") { header() }
        items(display, key = { it }) { id ->
            val value = itemMap.getValue(id)
            val canDrag = groups[id] != null && !busy && pending == null
            val active = dragged == id
            val landing = settling?.takeIf { it.key == id }
            val gesture = if (!canDrag) Modifier else Modifier.pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (!currentBusy && pending == null) {
                            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                            if (info != null) {
                                settling = null; dragged = id; dragVersion = currentVersion; dragCommit = currentCommit; origin = info.offset.toFloat(); dragHeight = info.size.toFloat(); dragDelta = 0f; preview = currentKeys
                            }
                        }
                    },
                    onDrag = { change, amount -> if (dragged == id && !currentBusy) { change.consume(); dragDelta += amount.y; updateTarget() } },
                    onDragEnd = { finish(true) }, onDragCancel = { finish(false) },
                )
            }
            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                placementSpec = if (active || landing != null) null else spring(stiffness = Spring.StiffnessMediumLow))
                .zIndex(if (active || landing != null) 1f else 0f)
                .graphicsLayer {
                    val itemOffset = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset ?: 0
                    translationY = when {
                        active -> origin + dragDelta - itemOffset
                        landing != null -> (landing.viewportTop - itemOffset) * (1f - landing.progress.value)
                        else -> 0f
                    }
                    scaleX = if (active) 1.015f else 1f
                    scaleY = if (active) 1.015f else 1f
                }
                .then(if (active) Modifier.shadow(8.dp, RoundedCornerShape(16.dp)) else Modifier)
                .then(if (groups[id] != null) Modifier.semantics { contentDescription = "拖动排序${label(value)}" } else Modifier)
                .then(gesture)) { itemContent(value) }
        }
        if (footer != null) item("__reorder_footer") { footer() }
    }
}
