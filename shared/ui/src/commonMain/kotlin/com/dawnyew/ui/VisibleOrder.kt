package com.dawnyew.ui

/** Move visible records through their original slots without dropping hidden or archived records. */
internal fun mergeVisibleOrder(complete: List<String>, reorderedVisible: List<String>): List<String> {
    require(complete.size == complete.toSet().size) { "完整排序包含重复记录" }
    require(reorderedVisible.size == reorderedVisible.toSet().size && complete.containsAll(reorderedVisible)) { "拖动记录不属于当前列表" }
    val visible = reorderedVisible.toSet()
    var index = 0
    return complete.map { if (it in visible) reorderedVisible[index++] else it }
}
