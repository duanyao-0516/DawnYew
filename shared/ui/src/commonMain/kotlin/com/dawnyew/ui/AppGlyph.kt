package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small monochrome vectors use the current content color in both light and dark themes. */
@Composable internal fun AppGlyph(name: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), 1.8f * unit, cap = StrokeCap.Round)
        val stroke = Stroke(1.8f * unit, cap = StrokeCap.Round)
        when (name) {
            "首页" -> {
                val roof = Path().apply { moveTo(3 * unit, 11 * unit); lineTo(12 * unit, 3 * unit); lineTo(21 * unit, 11 * unit) }
                drawPath(roof, color, style = stroke)
                val walls = Path().apply {
                    moveTo(5 * unit, 10 * unit); lineTo(5 * unit, 21 * unit); lineTo(10 * unit, 21 * unit)
                    lineTo(10 * unit, 15 * unit); lineTo(14 * unit, 15 * unit); lineTo(14 * unit, 21 * unit)
                    lineTo(19 * unit, 21 * unit); lineTo(19 * unit, 10 * unit)
                }
                drawPath(walls, color, style = stroke)
            }
            "统计" -> { line(4f, 20f, 20f, 20f); line(6f, 15f, 6f, 8f); line(12f, 15f, 12f, 3f); line(18f, 15f, 18f, 6f) }
            "更多" -> listOf(4f to 4f, 14f to 4f, 4f to 14f, 14f to 14f).forEach { (x, y) ->
                drawRoundRect(color, point(x, y), Size(6 * unit, 6 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit), style = stroke)
            }
            "我的" -> {
                drawCircle(color, 4 * unit, point(12f, 7f), style = stroke)
                drawArc(color, 180f, 180f, false, point(4f, 14f), Size(16 * unit, 12 * unit), style = stroke)
                line(4f, 20f, 20f, 20f)
            }
            "搜索" -> { drawCircle(color, 6.5f * unit, point(10.5f, 10.5f), style = stroke); line(15.5f, 15.5f, 21f, 21f) }
            "新增" -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            "调色盘" -> {
                drawCircle(color, 9 * unit, point(12f, 12f), style = stroke)
                listOf(7f to 9f, 12f to 6f, 17f to 9f, 8f to 15f).forEach { (x,y) -> drawCircle(color, 1.5f * unit, point(x,y)) }
                drawCircle(color, 2 * unit, point(16f, 16f), style = stroke)
            }
            "尺子" -> {
                drawRoundRect(color, point(6f, 2f), Size(12 * unit, 20 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(unit), style = stroke)
                (5..19 step 3).forEach { y -> line(6f, y.toFloat(), if(y % 2 == 1) 12f else 10f, y.toFloat()) }
            }
            "噪音测量" -> { line(4f, 9f, 4f, 15f); line(8f, 5f, 8f, 19f); line(12f, 2f, 12f, 22f); line(16f, 6f, 16f, 18f); line(20f, 9f, 20f, 15f) }
            "做个决定" -> {
                drawRoundRect(color, point(3f, 3f), Size(18 * unit, 18 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * unit), style = stroke)
                listOf(7f to 7f, 17f to 7f, 12f to 12f, 7f to 17f, 17f to 17f).forEach { (x,y) -> drawCircle(color, 1.5f * unit, point(x,y)) }
            }
            "石头剪刀布" -> {
                drawCircle(color, 3 * unit, point(6f, 18f), style = stroke); drawCircle(color, 3 * unit, point(18f, 18f), style = stroke)
                line(8f, 16f, 19f, 3f); line(16f, 16f, 5f, 3f)
            }
        }
    }
}
