package com.dawnyew.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dawnyew.presentation.NoiseUi
import com.dawnyew.presentation.UtilityPlatform
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable internal fun NoisePage(platform: UtilityPlatform) {
    val noise by platform.noise.collectAsState()
    var calibrationOpen by rememberSaveable { mutableStateOf(false) }
    var referenceDb by rememberSaveable { mutableStateOf("") }
    var calibrationError by rememberSaveable { mutableStateOf<String?>(null) }
    val dbfs = noise.validDbfs()
    val offset = noise.calibrationOffsetDb?.takeIf { it.isFinite() }
    val displayValue = dbfs?.let { value -> if (offset != null) value + offset else value }
        ?.takeIf { it.isFinite() }

    DisposableEffect(platform) {
        onDispose { platform.stopNoise() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (noise.running) "正在测量" else "测量已停止", style = MaterialTheme.typography.titleMedium)
                Text(
                    displayValue?.let(::noiseDecimal) ?: "—",
                    style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (offset != null) "估算 dB · 已做单点校准" else "dBFS · 未校准",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (dbfs == null) {
                    Text(
                        if (noise.running) "等待数据，或声音低于当前测量下限。" else "点击开始后读取麦克风电平。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (offset != null) {
                    Text(
                        "原始电平 ${dbfs?.let(::noiseDecimal) ?: "—"} dBFS · 校准偏移 ${noiseDecimal(offset)} dB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (noise.sourceLabel.isNotBlank()) {
            Text("音源：${noise.sourceLabel}", style = MaterialTheme.typography.bodyMedium)
        }
        if (noise.clipped) {
            Text(
                "检测到削波：声音超过麦克风可测范围，当前读数不可靠，无法校准。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            )
        }
        noise.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (!noise.running) {
                Text("检查麦克风权限后可再次点击开始重试。", style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { if (noise.running) platform.stopNoise() else platform.startNoise() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (noise.running) "停止测量" else if (noise.error != null) "开始 / 重试" else "开始测量") }
            OutlinedButton(
                onClick = { calibrationOpen = true; calibrationError = null },
                enabled = noise.canCalibrate(), modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("校准读数") }
            TextButton(
                onClick = { platform.resetNoiseCalibration(); calibrationError = null },
                enabled = noise.calibrationOffsetDb != null, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("重置校准") }
        }
        if (!noise.canCalibrate()) {
            Text(
                "开始测量并取得有效、未削波的数据后，可以用参考声级计校准。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Text("读数如何理解", style = MaterialTheme.typography.titleMedium)
        Text(
            "未校准时显示 dBFS，即麦克风数字信号相对满量程的电平，并非环境声压级。单点校准后显示原始 dBFS 加校准偏移得到的估算 dB。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "未做 A / C 等频率计权，也不是专业声级计。麦克风、自动增益和音源处理会影响结果，读数仅供参考。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "只有点击开始才使用麦克风。停止或离开此页即结束测量，不保存录音。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
    }

    if (calibrationOpen) {
        val reference = referenceDb.trim().toDoubleOrNull()
        val referenceValid = reference != null && reference.isFinite() && reference in 20.0..120.0
        AlertDialog(
            onDismissRequest = { calibrationOpen = false; calibrationError = null },
            title = { Text("参考声级计校准") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("将参考声级计与手机放在同一位置，保持稳定声源，再输入参考仪器当前读数。单点偏移不能补偿频率响应或自动增益。")
                    Text("当前原始电平：${dbfs?.let(::noiseDecimal) ?: "—"} dBFS")
                    OutlinedTextField(
                        value = referenceDb,
                        onValueChange = { referenceDb = it.take(16); calibrationError = null },
                        label = { Text("参考读数") },
                        suffix = { Text("dB") },
                        supportingText = { Text("输入 20–120 dB") },
                        isError = referenceDb.isNotBlank() && !referenceValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!noise.canCalibrate()) {
                        Text("请保持测量开启，等待有效且未削波的读数。", color = MaterialTheme.colorScheme.error)
                    }
                    calibrationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = referenceValid && noise.canCalibrate(),
                    onClick = {
                        val currentReference = referenceDb.trim().toDoubleOrNull()
                        if (currentReference == null || !currentReference.isFinite() || currentReference !in 20.0..120.0) {
                            calibrationError = "请输入 20–120 dB 之间的参考读数。"
                        } else if (!platform.noise.value.canCalibrate()) {
                            calibrationError = "当前没有有效、未削波的测量数据，请稍后再试。"
                        } else {
                            try {
                                platform.calibrateNoise(currentReference)
                                calibrationOpen = false
                                calibrationError = null
                            } catch (_: IllegalArgumentException) {
                                calibrationError = "无法使用当前读数校准，请保持稳定声源后重试。"
                            }
                        }
                    },
                ) { Text("保存校准") }
            },
            dismissButton = {
                TextButton(onClick = { calibrationOpen = false; calibrationError = null }) { Text("取消") }
            },
        )
    }
}

private fun NoiseUi.validDbfs(): Double? = if (running) dbfs?.takeIf { it.isFinite() && it <= 0.0 } else null

private fun NoiseUi.canCalibrate(): Boolean = running && !clipped && validDbfs() != null

/** Common Kotlin formatting keeps shared UI independent of JVM locale APIs. */
private fun noiseDecimal(value: Double): String {
    val tenths = (abs(value) * 10.0).roundToLong()
    val sign = if (value < 0.0 && tenths != 0L) "−" else ""
    return "$sign${tenths / 10}.${tenths % 10}"
}
