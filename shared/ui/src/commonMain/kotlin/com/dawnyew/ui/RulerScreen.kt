package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dawnyew.presentation.UtilityPlatform
import kotlin.math.floor
import kotlin.math.roundToInt

/** Tick positions use physical screen pixels; dp is only used for decoration and safe spacing. */
@Composable internal fun RulerPage(platform: UtilityPlatform, onBack: () -> Unit) {
    val scale by platform.rulerScale.collectAsState()
    var calibrationOpen by rememberSaveable { mutableStateOf(false) }
    var measuredCm by rememberSaveable { mutableStateOf("") }
    var measuredAtScale by rememberSaveable { mutableFloatStateOf(0f) }
    var measuredAtRevision by rememberSaveable { mutableLongStateOf(0L) }
    var calibrationError by rememberSaveable { mutableStateOf<String?>(null) }
    var canvasHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val zeroInsetPx = with(density) { 24.dp.toPx() }
    val labelMarginPx = with(density) { 12.dp.toPx() }
    val validScale = scale.pixelsPerCm.isFinite() && scale.pixelsPerCm > 0f
    val fiveCmFits = validScale && zeroInsetPx + scale.pixelsPerCm * 5f + labelMarginPx <= canvasHeight
    val measurement = measuredCm.trim().toFloatOrNull()
    val measurementValid = measurement != null && measurement.isFinite() && measurement in 2.5f..10f
    val scaleChanged = measuredCm.isNotBlank() &&
        (measuredAtScale != scale.pixelsPerCm || measuredAtRevision != scale.revision)

    DisposableEffect(platform) {
        platform.setRulerFullscreen(true)
        onDispose { platform.setRulerFullscreen(false) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().onSizeChanged { canvasHeight = it.height }) {
            RulerTicks(scale.pixelsPerCm, validScale, zeroInsetPx)
            Column(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .padding(horizontal = 72.dp, vertical = 40.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("屏幕尺子", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    "从上方高亮的 0 cm 零线起量，左右每小格为 1 mm。",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
                Text(
                    if (scale.calibrated) "已校准 · 仅用于当前设备" else "未校准 · 屏幕报告的尺寸可能有误，请先用实物尺校准。",
                    color = if (scale.calibrated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
                if (!validScale) {
                    Text("无法取得有效屏幕比例，请重置后再试。", color = MaterialTheme.colorScheme.error)
                }
                if (calibrationOpen) {
                    HorizontalDivider()
                    Text("用实物尺校准", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "测量任意一侧高亮的 0 至 5 cm 段，将实物尺读到的实际长度填在下方。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!fiveCmFits) {
                        Text(
                            "当前窗口放不下完整 5 cm 段，请竖屏或放大窗口后测量。",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = measuredCm,
                        onValueChange = {
                            measuredCm = it.take(16)
                            measuredAtScale = scale.pixelsPerCm
                            measuredAtRevision = scale.revision
                            calibrationError = null
                        },
                        label = { Text("实测厘米") },
                        suffix = { Text("cm") },
                        supportingText = { Text("输入 2.5–10 cm，例如 4.8") },
                        isError = measuredCm.isNotBlank() && (!measurementValid || scaleChanged),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (scaleChanged) {
                        Text("屏幕配置已变化，请重新测量并输入。", color = MaterialTheme.colorScheme.error)
                    }
                    calibrationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            val currentScale = platform.rulerScale.value
                            val actualCm = measuredCm.trim().toFloatOrNull()
                            if (actualCm == null || !actualCm.isFinite() || actualCm !in 2.5f..10f) {
                                calibrationError = "请输入 2.5–10 cm 之间的实际长度。"
                            } else if (currentScale.pixelsPerCm != measuredAtScale || currentScale.revision != measuredAtRevision) {
                                calibrationError = "屏幕配置已变化，请重新测量并输入。"
                            } else {
                                val correctedScale = currentScale.pixelsPerCm * 5f / actualCm
                                try {
                                    require(correctedScale.isFinite() && correctedScale in 10f..1000f)
                                    platform.saveRulerScale(correctedScale)
                                    calibrationOpen = false
                                    measuredCm = ""
                                    calibrationError = null
                                } catch (_: IllegalArgumentException) {
                                    calibrationError = "校准结果超出支持范围，请核对实物尺读数或重置后再试。"
                                }
                            }
                        },
                        enabled = fiveCmFits && measurementValid && !scaleChanged,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("保存校准", textAlign = TextAlign.Center) }
                    TextButton(
                        onClick = { calibrationOpen = false; calibrationError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("取消校准") }
                } else {
                    Button(
                        onClick = { calibrationOpen = true; measuredCm = ""; calibrationError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("校准尺子", textAlign = TextAlign.Center) }
                }
                OutlinedButton(
                    onClick = {
                        platform.resetRulerScale()
                        measuredCm = ""
                        calibrationError = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("重置比例", textAlign = TextAlign.Center) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回工具箱") }
                Text(
                    "屏幕边框不是零点。更换设备或显示设置后请核对刻度；本工具仅供日常粗略测量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable private fun RulerTicks(pixelsPerCm: Float, validScale: Boolean, zeroInsetPx: Float) {
    val tickColor = MaterialTheme.colorScheme.onSurface
    val guideColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = tickColor, fontWeight = FontWeight.Medium)
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        Modifier.fillMaxSize().semantics {
            contentDescription = "左右屏幕边缘的毫米尺；上方留白后是零线，0 到 5 厘米为高亮校准段。"
        },
    ) {
        if (!validScale) return@Canvas
        val minorTickPx = 10.dp.toPx()
        val halfTickPx = 17.dp.toPx()
        val majorTickPx = 25.dp.toPx()
        val labelGapPx = 5.dp.toPx()
        val millimeterPx = pixelsPerCm / 10f
        val tickCount = floor(((size.height - zeroInsetPx) / millimeterPx).toDouble())
            .coerceIn(0.0, 2000.0).roundToInt()
        val strokePx = 1.dp.toPx()
        drawLine(guideColor.copy(alpha = 0.5f), Offset(0f, zeroInsetPx), Offset(size.width, zeroInsetPx), strokePx)
        val fiveCmY = zeroInsetPx + pixelsPerCm * 5f
        if (fiveCmY <= size.height) {
            listOf(3.dp.toPx(), size.width - 3.dp.toPx()).forEach { x ->
                drawLine(guideColor.copy(alpha = 0.35f), Offset(x, zeroInsetPx), Offset(x, fiveCmY), 3.dp.toPx())
            }
        }
        for (millimeter in 0..tickCount) {
            // pixelsPerCm comes from display metrics/calibration, never from Compose density.
            val y = zeroInsetPx + millimeter * millimeterPx
            val isCm = millimeter % 10 == 0
            val isGuide = millimeter == 0 || millimeter == 50
            val tickLength = when {
                isCm -> majorTickPx
                millimeter % 5 == 0 -> halfTickPx
                else -> minorTickPx
            }
            val color = if (isGuide) guideColor else tickColor
            val width = if (isGuide) 2.dp.toPx() else strokePx
            drawLine(color, Offset(0f, y), Offset(tickLength, y), width)
            drawLine(color, Offset(size.width - tickLength, y), Offset(size.width, y), width)
            if (isCm) {
                val label = if (millimeter == 0) "0 cm" else (millimeter / 10).toString()
                val layout = textMeasurer.measure(label, labelStyle.copy(color = color))
                val labelY = (y - layout.size.height / 2f).coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
                drawText(layout, topLeft = Offset(majorTickPx + labelGapPx, labelY))
                drawText(layout, topLeft = Offset(size.width - majorTickPx - labelGapPx - layout.size.width, labelY))
                if (isGuide) {
                    drawCircle(guideColor, 2.dp.toPx(), Offset(majorTickPx, y), style = Stroke(strokePx))
                    drawCircle(guideColor, 2.dp.toPx(), Offset(size.width - majorTickPx, y), style = Stroke(strokePx))
                }
            }
        }
    }
}
