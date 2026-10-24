package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Uses the original reference artwork on Android; utility-only fallbacks inherit the current content color. */
@Composable internal fun AppGlyph(name: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    referenceArtwork(name)?.let { artwork ->
        Icon(artwork.painter,contentDescription=null,modifier=modifier.size(24.dp),tint=if(artwork.tinted) color else Color.Unspecified)
        return
    }
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
            "前进" -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            "返回" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "可见" -> {
                val eye = Path().apply { moveTo(2 * unit, 12 * unit); quadraticTo(12 * unit, 0f, 22 * unit, 12 * unit); quadraticTo(12 * unit, 24 * unit, 2 * unit, 12 * unit) }
                drawPath(eye, color, style = stroke); drawCircle(color, 3 * unit, point(12f, 12f), style = stroke)
            }
            "账户", "报销", "存钱", "现金", "信用卡", "储蓄卡", "网络支付", "充值卡" -> {
                drawRoundRect(color, point(3f, 5f), Size(18 * unit, 15 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * unit), style = stroke)
                line(4f, 9f, 20f, 9f); line(15f, 13f, 21f, 13f); line(15f, 16f, 21f, 16f)
                drawCircle(color, unit, point(17f, 14.5f))
            }
            "借入", "借出", "应收账户", "应付账户", "转账", "导入", "导出" -> {
                drawCircle(color, 9 * unit, point(12f, 12f), style = stroke)
                val down = name in listOf("借入", "应付账户", "导入")
                line(12f, 6f, 12f, 18f)
                if (down) { line(7f, 13f, 12f, 18f); line(12f, 18f, 17f, 13f) }
                else { line(7f, 11f, 12f, 6f); line(12f, 6f, 17f, 11f) }
            }
            "往来", "共享" -> {
                drawCircle(color, 3 * unit, point(8f, 7f), style = stroke); drawCircle(color, 3 * unit, point(17f, 9f), style = stroke)
                drawArc(color, 180f, 180f, false, point(2f, 13f), Size(12 * unit, 12 * unit), style = stroke)
                drawArc(color, 180f, 180f, false, point(12f, 15f), Size(10 * unit, 10 * unit), style = stroke)
            }
            "周期", "提醒", "月历" -> {
                drawRoundRect(color, point(3f, 5f), Size(18 * unit, 16 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * unit), style = stroke)
                line(3f, 10f, 21f, 10f); line(7f, 3f, 7f, 7f); line(17f, 3f, 17f, 7f)
                line(8f, 14f, 8f, 17f); line(12f, 14f, 12f, 17f); line(16f, 14f, 16f, 17f)
            }
            "预算", "分期" -> {
                drawRoundRect(color, point(5f, 2f), Size(14 * unit, 20 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * unit), style = stroke)
                line(8f, 7f, 16f, 7f); line(8f, 12f, 10f, 12f); line(14f, 12f, 16f, 12f); line(8f, 17f, 10f, 17f); line(14f, 17f, 16f, 17f)
            }
            "存钱挑战" -> {
                line(5f, 3f, 5f, 21f); line(2f, 21f, 11f, 21f)
                val flag = Path().apply { moveTo(5 * unit, 4 * unit); lineTo(20 * unit, 4 * unit); lineTo(16 * unit, 9 * unit); lineTo(20 * unit, 14 * unit); lineTo(5 * unit, 14 * unit) }
                drawPath(flag, color, style = stroke)
            }
            "梦想计划" -> {
                val star = Path().apply {
                    moveTo(12 * unit, 2 * unit); lineTo(15 * unit, 8 * unit); lineTo(22 * unit, 9 * unit)
                    lineTo(17 * unit, 14 * unit); lineTo(18 * unit, 21 * unit); lineTo(12 * unit, 18 * unit)
                    lineTo(6 * unit, 21 * unit); lineTo(7 * unit, 14 * unit); lineTo(2 * unit, 9 * unit)
                    lineTo(9 * unit, 8 * unit); close()
                }
                drawPath(star, color, style = stroke)
            }
            "目标随心" -> {
                drawCircle(color, 8 * unit, point(10f, 14f), style = stroke)
                drawCircle(color, 4 * unit, point(10f, 14f), style = stroke)
                line(10f, 14f, 21f, 3f); line(16f, 3f, 21f, 3f); line(21f, 3f, 21f, 8f)
            }
            "随手攒钱" -> {
                drawCircle(color, 6 * unit, point(9f, 9f), style = stroke)
                line(6f, 9f, 12f, 9f); line(9f, 6f, 9f, 12f)
                drawArc(color, 0f, 180f, false, point(3f, 12f), Size(18 * unit, 9 * unit), style = stroke)
                line(17f, 7f, 22f, 7f); line(19.5f, 4.5f, 19.5f, 9.5f)
            }
            "分类", "组件", "工具箱" -> listOf(3f to 3f, 14f to 3f, 3f to 14f, 14f to 14f).forEach { (x,y) -> drawRoundRect(color,point(x,y),Size(7 * unit,7 * unit),cornerRadius=androidx.compose.ui.geometry.CornerRadius(2 * unit),style=stroke) }
            "项目", "账本", "日志", "备份", "手册" -> {
                drawRoundRect(color, point(4f, 3f), Size(16 * unit, 18 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * unit), style = stroke)
                line(8f, 3f, 8f, 21f); line(11f, 8f, 16f, 8f); line(11f, 12f, 16f, 12f)
            }
            "商家" -> {
                line(4f, 9f, 20f, 9f); line(4f, 9f, 6f, 3f); line(6f, 3f, 18f, 3f); line(18f, 3f, 20f, 9f)
                line(5f, 10f, 5f, 21f); line(5f, 21f, 19f, 21f); line(19f, 21f, 19f, 10f); line(9f, 21f, 9f, 15f); line(9f, 15f, 15f, 15f); line(15f, 15f, 15f, 21f)
            }
            "标签" -> {
                val tag = Path().apply { moveTo(4 * unit, 3 * unit); lineTo(20 * unit, 3 * unit); lineTo(20 * unit, 22 * unit); lineTo(12 * unit, 17 * unit); lineTo(4 * unit, 22 * unit); close() }
                drawPath(tag, color, style = stroke); line(8f, 8f, 16f, 8f); line(8f, 12f, 14f, 12f)
            }
            "相机", "自动" -> {
                drawRoundRect(color, point(3f, 6f), Size(18 * unit, 15 * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2 * unit), style = stroke)
                drawCircle(color, 4 * unit, point(12f, 13f), style = stroke); line(8f, 6f, 9f, 3f); line(9f, 3f, 15f, 3f); line(15f, 3f, 16f, 6f)
            }
            "设置", "安全" -> {
                drawCircle(color, 8 * unit, point(12f, 12f), style = stroke); drawCircle(color, 3 * unit, point(12f, 12f), style = stroke)
                line(12f, 1f, 12f, 4f); line(12f, 20f, 12f, 23f); line(1f, 12f, 4f, 12f); line(20f, 12f, 23f, 12f)
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
            else -> {
                drawCircle(color, 8 * unit, point(12f, 12f), style = stroke)
                line(8f, 12f, 16f, 12f)
            }
        }
    }
}
