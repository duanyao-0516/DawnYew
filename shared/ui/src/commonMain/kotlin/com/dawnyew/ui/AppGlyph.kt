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
        }
    }
}
