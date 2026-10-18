package com.dawnyew.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dawnyew.presentation.HsvColor
import com.dawnyew.presentation.RgbColor
import com.dawnyew.presentation.parseHexColor
import com.dawnyew.presentation.parseRgbColor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Suppress("DEPRECATION")
@Composable internal fun PalettePage() {
    var red by rememberSaveable { mutableStateOf(0) }
    var green by rememberSaveable { mutableStateOf(106) }
    var blue by rememberSaveable { mutableStateOf(96) }
    var redText by rememberSaveable { mutableStateOf("0") }
    var greenText by rememberSaveable { mutableStateOf("106") }
    var blueText by rememberSaveable { mutableStateOf("96") }
    var hexText by rememberSaveable { mutableStateOf("#006A60") }
    var hue by rememberSaveable { mutableStateOf(RgbColor(0, 106, 96).toHsv().hue) }
    var saturation by rememberSaveable { mutableStateOf(1f) }
    var brightness by rememberSaveable { mutableStateOf(106f / 255f) }
    var copied by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val color = RgbColor(red, green, blue)
    val rgbError = parseRgbColor(redText, greenText, blueText) == null
    val hexError = parseHexColor(hexText) == null

    fun applyColor(next: RgbColor, syncRgb: Boolean = true, syncHex: Boolean = true, syncHsv: Boolean = true) {
        red = next.red
        green = next.green
        blue = next.blue
        if (syncRgb) {
            redText = next.red.toString()
            greenText = next.green.toString()
            blueText = next.blue.toString()
        }
        if (syncHex) hexText = next.hex
        if (syncHsv) {
            val hsv = next.toHsv()
            hue = hsv.hue
            saturation = hsv.saturation
            brightness = hsv.brightness
        }
        copied = null
    }
    fun applyRgbInput() {
        copied = null
        parseRgbColor(redText, greenText, blueText)?.let { applyColor(it, syncRgb = false) }
    }
    fun applyHsv() = applyColor(HsvColor(hue, saturation, brightness).toRgb(), syncHsv = false)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("输入 RGB、十六进制颜色，或轻点、拖动色盘选色。", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(112.dp).background(Color(red, green, blue), MaterialTheme.shapes.medium)
                        .semantics { contentDescription = "当前颜色 ${color.hex}，RGB $red，$green，$blue" },
                )
                Text(color.hex, style = MaterialTheme.typography.headlineMedium)
                Text("RGB ($red, $green, $blue)", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(color.hex)); copied = "已复制 ${color.hex}" }) {
                        Text("复制 HEX")
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString("rgb($red, $green, $blue)"))
                        copied = "已复制 RGB"
                    }) { Text("复制 RGB") }
                }
                copied?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaletteChannel("R", redText, { redText = it.take(16); applyRgbInput() }, Modifier.weight(1f))
            PaletteChannel("G", greenText, { greenText = it.take(16); applyRgbInput() }, Modifier.weight(1f))
            PaletteChannel("B", blueText, { blueText = it.take(16); applyRgbInput() }, Modifier.weight(1f))
        }
        if (rgbError) Text("RGB 每项请输入 0 至 255 的整数；预览保留上次有效颜色。", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = hexText,
            onValueChange = { value ->
                hexText = value.take(32)
                copied = null
                parseHexColor(hexText)?.let { applyColor(it, syncHex = false) }
            },
            label = { Text("十六进制颜色") }, placeholder = { Text("#RGB 或 #RRGGBB") },
            isError = hexError, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(if (hexError) "请输入 #RGB 或 #RRGGBB；预览保留上次有效颜色。" else "支持大小写，例如 #0A8、#006A60") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            HueSaturationWheel(hue, saturation, brightness) { nextHue, nextSaturation ->
                hue = nextHue
                saturation = nextSaturation
                applyHsv()
            }
        }
        Text("色相 ${hue.roundToInt()}° · 饱和度 ${(saturation * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        Column {
            Text("亮度 ${(brightness * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = brightness, onValueChange = { brightness = it; applyHsv() },
                modifier = Modifier.semantics { contentDescription = "颜色亮度" },
            )
        }
        Text("色盘从中心向外增加饱和度，亮度由滑块单独调整。也可用上方 RGB 输入精确选色。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PaletteChannel(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier) {
    val valid = value.trim().let { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } && it.toIntOrNull() in 0..255 }
    OutlinedTextField(
        value, onValue, modifier = modifier, label = { Text(label) }, singleLine = true, isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable private fun HueSaturationWheel(hue: Float, saturation: Float, brightness: Float, onColor: (Float, Float) -> Unit) {
    val currentOnColor by rememberUpdatedState(onColor)
    Canvas(
        Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
            .semantics { contentDescription = "色相和饱和度色盘；可使用 RGB 输入进行无障碍精确选色" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f - 10.dp.toPx()
                    fun distance(position: Offset): Float {
                        val delta = position - center
                        return sqrt(delta.x * delta.x + delta.y * delta.y)
                    }
                    fun select(position: Offset) {
                        val dx = position.x - center.x
                        val dy = position.y - center.y
                        val selectedHue = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                        currentOnColor(selectedHue, (distance(position) / radius).coerceIn(0f, 1f))
                    }
                    if (radius > 0f && distance(down.position) <= radius) {
                        down.consume()
                        select(down.position)
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            change.consume()
                            select(change.position)
                        } while (change.pressed)
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f - 10.dp.toPx()
        drawCircle(
            Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red), center),
            radius = radius,
        )
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius), radius)
        drawCircle(Color.Black.copy(alpha = 1f - brightness), radius)
        val angle = hue * PI.toFloat() / 180f
        val selected = center + Offset(cos(angle) * saturation * radius, sin(angle) * saturation * radius)
        drawCircle(Color.Black, 7.dp.toPx(), selected, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), selected, style = Stroke(1.5.dp.toPx()))
    }
}
