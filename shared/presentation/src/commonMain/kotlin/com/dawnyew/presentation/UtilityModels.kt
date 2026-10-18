package com.dawnyew.presentation

import kotlin.math.roundToInt
import kotlin.random.Random

/** An opaque sRGB color. Keeping channels as integers avoids input rounding drift. */
data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    init { require(red in 0..255 && green in 0..255 && blue in 0..255) }

    val hex: String get() = "#" + listOf(red, green, blue).joinToString("") {
        it.toString(16).padStart(2, '0').uppercase()
    }

    fun toHsv(): HsvColor {
        val r = red / 255f
        val g = green / 255f
        val b = blue / 255f
        val maximum = maxOf(r, g, b)
        val minimum = minOf(r, g, b)
        val delta = maximum - minimum
        val hue = when {
            delta == 0f -> 0f
            maximum == r -> 60f * (((g - b) / delta) % 6f)
            maximum == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return HsvColor((hue + 360f) % 360f, if (maximum == 0f) 0f else delta / maximum, maximum)
    }
}

data class HsvColor(val hue: Float, val saturation: Float, val brightness: Float) {
    init {
        require(hue.isFinite())
        require(saturation in 0f..1f && brightness in 0f..1f)
    }

    fun toRgb(): RgbColor {
        val sector = ((hue % 360f + 360f) % 360f) / 60f
        val chroma = brightness * saturation
        val second = chroma * (1f - kotlin.math.abs(sector % 2f - 1f))
        val minimum = brightness - chroma
        val channels = when (sector.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        fun channel(value: Float) = ((value + minimum) * 255f).roundToInt().coerceIn(0, 255)
        return RgbColor(channel(channels.first), channel(channels.second), channel(channels.third))
    }
}

fun parseRgbColor(red: String, green: String, blue: String): RgbColor? {
    fun channel(text: String): Int? {
        val value = text.trim()
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toIntOrNull()?.takeIf { it in 0..255 }
    }
    return RgbColor(channel(red) ?: return null, channel(green) ?: return null, channel(blue) ?: return null)
}

fun parseHexColor(text: String): RgbColor? {
    val value = text.trim()
    if (!value.startsWith('#') || value.length !in listOf(4, 7)) return null
    val digits = value.drop(1)
    if (digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    val expanded = if (digits.length == 3) digits.flatMap { listOf(it, it) }.joinToString("") else digits
    return RgbColor(expanded.substring(0, 2).toInt(16), expanded.substring(2, 4).toInt(16), expanded.substring(4, 6).toInt(16))
}

fun decisionOptionsError(options: List<String>): String? = when {
    options.size !in 2..20 -> "请保留 2 至 20 个选项"
    options.any { it.length > 200 } -> "每个选项最多 200 个字符"
    options.any { it.isBlank() } -> "每个选项都需要填写内容"
    options.map { it.trim() }.distinct().size != options.size -> "选项不能重复（忽略首尾空格）"
    else -> null
}

fun chooseDecision(options: List<String>, random: Random = Random.Default): String {
    require(decisionOptionsError(options) == null) { decisionOptionsError(options).orEmpty() }
    return options[random.nextInt(options.size)].trim()
}

enum class Hand { ROCK, SCISSORS, PAPER }
enum class RpsOutcome { WIN, LOSE, DRAW }

/** Outcome from the first player's perspective. */
fun judgeRps(first: Hand, second: Hand): RpsOutcome = when {
    first == second -> RpsOutcome.DRAW
    first == Hand.ROCK && second == Hand.SCISSORS ||
        first == Hand.SCISSORS && second == Hand.PAPER ||
        first == Hand.PAPER && second == Hand.ROCK -> RpsOutcome.WIN
    else -> RpsOutcome.LOSE
}

fun randomHand(random: Random = Random.Default): Hand = Hand.entries[random.nextInt(Hand.entries.size)]
