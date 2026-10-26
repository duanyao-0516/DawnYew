package com.dawnyew.platform.ocr

import com.dawnyew.core.OcrResult
import com.dawnyew.core.OcrTextBlock
import com.dawnyew.core.Direction
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max

enum class CaptureDisposition { REVIEW, AMBIGUOUS, UNSUPPORTED }

/** Every field is an unconfirmed candidate. This object cannot write to the ledger. */
data class PaymentCaptureCandidate(
    val disposition: CaptureDisposition,
    /** Positive minor-unit magnitude; the selected token's sign is represented by direction. */
    val amountMinor: Long? = null,
    val amountCandidates: List<Long> = emptyList(),
    val merchant: String? = null,
    val localDate: String? = null,
    val localTime: String? = null,
    val messages: List<String> = emptyList(),
    /** Null when no single confident amount and direction pair can be selected. */
    val direction: Direction? = null,
)

/** Extract unconfirmed fields from user-selected screenshots, independent of channel or payment status. */
object PaymentScreenshotParser {
    const val VERSION = "payment-screenshot-3"
    private const val MIN_FIELD_CONFIDENCE = 0.85f
    private val success = Regex("支付成功|付款成功|交易成功")
    private val paidLabel = Regex("实付款|实付金额|实付|支付金额|付款金额|实际支付")
    private val otherAmountLabel = Regex("余额|优惠|原价|立减|红包|积分|抵扣|服务费|手续费|订单金额|商品金额|总额|应付|合计")
    private const val AMOUNT_PREFIX = "(?:[+\\-−]\\s*[￥¥]?\\s*|[￥¥]\\s*[+\\-−]?\\s*)?"
    private val amount = Regex("(?<![0-9A-Za-z.,])${AMOUNT_PREFIX}((?:[1-9]\\d{0,2}(?:,\\d{3})+|0|[1-9]\\d*)\\.\\d{2})(?![0-9A-Za-z.,])")
    private val merchantLabel = Regex("^(?:商户全称|商户名称|收款方|商品说明)[：:]?\\s*(.*)$")
    private val date = Regex("(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?(?!\\d)")

    fun parse(result: OcrResult): PaymentCaptureCandidate {
        val blocks = result.blocks.filter { it.text.isNotBlank() }.take(OcrResourceLimits.MAX_TEXT_BLOCKS + 1)
        if (blocks.isEmpty()) return unsupported("未识别到可用文字，请换一张清晰截图")
        if (blocks.size > OcrResourceLimits.MAX_TEXT_BLOCKS) return unsupported("文字区域超出模型处理上限，请缩小识别范围")
        val readings = blocks.flatMap(::readAmounts)
        val eligible = readings.filter { reading ->
            paidLabel.containsMatchIn(normalize(reading.block.text)) || !otherAmountLabel.containsMatchIn(normalize(reading.block.text))
        }
        val directPaid = eligible.filter { paidLabel.containsMatchIn(normalize(it.block.text)) }
        val spatialPaid = if (directPaid.isEmpty()) eligible.filter { reading ->
            blocks.any { label -> label !== reading.block && label.confidence.isFinite() && label.confidence >= MIN_FIELD_CONFIDENCE && paidLabel.matches(normalize(label.text).trim(' ', ':', '：')) && sameRow(label, reading.block) }
        } else emptyList()
        val best = directPaid.ifEmpty { spatialPaid }.ifEmpty { eligible }.ifEmpty { readings }
        val choices = best.map { it.minor to it.direction }.distinct()
        val confident = best.isNotEmpty() && best.all { it.block.confidence.isFinite() && it.block.confidence >= MIN_FIELD_CONFIDENCE }
        val resolved = choices.singleOrNull()?.takeIf { confident }
        val dates = parseDates(blocks)
        val messages = buildList {
            if (resolved == null) add(if (choices.size > 1) "金额或收支类型存在多个候选，请核对后填写" else "未确定清晰金额，请人工核对")
            if (dates.first == null) add("未确定交易日期，请核对日期")
            add("识别结果尚未入账，请核对金额、收支类型与其他信息后保存")
        }
        return PaymentCaptureCandidate(
            disposition = if (resolved != null) CaptureDisposition.REVIEW else CaptureDisposition.AMBIGUOUS,
            amountMinor = resolved?.first,
            amountCandidates = readings.map { it.minor }.distinct(),
            merchant = parseMerchant(blocks), localDate = dates.first, localTime = dates.second,
            messages = messages,
            direction = resolved?.second,
        )
    }

    private data class AmountReading(val minor: Long, val direction: Direction, val block: OcrTextBlock)

    private fun readAmounts(block: OcrTextBlock): List<AmountReading> {
        val text = normalize(block.text)
        return amount.findAll(text).mapNotNull { match ->
            val value = match.groupValues[1].replace(",", "")
            val pieces = value.split('.')
            val whole = pieces[0].toLongOrNull() ?: return@mapNotNull null
            if (whole > (Long.MAX_VALUE - 99) / 100) return@mapNotNull null
            val minor = (whole * 100 + pieces[1].toLong()).takeIf { it > 0 } ?: return@mapNotNull null
            // The matched token contains only its own optional sign/currency prefix and digits.
            // An unsigned token follows the user's default expense rule.
            AmountReading(minor, if ('+' in match.value) Direction.INCOME else Direction.EXPENSE, block)
        }.toList()
    }

    private fun sameRow(left: OcrTextBlock, right: OcrTextBlock): Boolean {
        if (left.polygon.size != 4 || right.polygon.size != 4) return false
        if ((left.polygon + right.polygon).any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val leftY = left.polygon.map { it.y }.average().toFloat()
        val rightY = right.polygon.map { it.y }.average().toFloat()
        val height = max(left.polygon.maxOf { it.y } - left.polygon.minOf { it.y }, right.polygon.maxOf { it.y } - right.polygon.minOf { it.y })
        return right.polygon.minOf { it.x } >= left.polygon.maxOf { it.x } && abs(leftY - rightY) <= height / 2
    }

    private fun parseMerchant(blocks: List<OcrTextBlock>): String? {
        val candidates = blocks.mapIndexedNotNull { index, block ->
            if (!block.confidence.isFinite() || block.confidence < MIN_FIELD_CONFIDENCE) return@mapIndexedNotNull null
            val match = merchantLabel.matchEntire(normalize(block.text)) ?: return@mapIndexedNotNull null
            val inline = match.groupValues[1].trim()
            val value = inline.ifEmpty {
                blocks.getOrNull(index + 1)?.takeIf { next -> next.confidence >= MIN_FIELD_CONFIDENCE && (sameRow(block, next) || nearbyBelow(block, next)) }?.text?.trim().orEmpty()
            }
            value.takeIf { it.length in 1..80 && !it.contains('：') && !it.contains(':') && !success.containsMatchIn(it) && readAmounts(block.copy(text = it)).isEmpty() && !date.containsMatchIn(it) }
        }.distinct()
        return candidates.singleOrNull()
    }

    private fun nearbyBelow(label: OcrTextBlock, value: OcrTextBlock): Boolean {
        if (label.polygon.size != 4 || value.polygon.size != 4) return false
        val bottom = label.polygon.maxOf { it.y }
        val height = bottom - label.polygon.minOf { it.y }
        val gap = value.polygon.minOf { it.y } - bottom
        return height > 0 && gap in 0f..(height * 1.5f)
    }

    private fun parseDates(blocks: List<OcrTextBlock>): Pair<String?, String?> {
        val values = blocks.filter { it.confidence >= MIN_FIELD_CONFIDENCE }.flatMap { block ->
            date.findAll(normalize(block.text)).mapNotNull { match ->
                runCatching {
                    val day = LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()).toString()
                    val time = if (match.groupValues[4].isNotEmpty()) LocalTime.of(match.groupValues[4].toInt(), match.groupValues[5].toInt(), match.groupValues[6].ifEmpty { "0" }.toInt()).toString() else null
                    day to time
                }.getOrNull()
            }.toList()
        }.distinct()
        return values.singleOrNull() ?: (null to null)
    }

    private fun normalize(text: String) = text.replace('\u3000', ' ').replace('\u00a0', ' ').trim()
    private fun unsupported(message: String) = PaymentCaptureCandidate(CaptureDisposition.UNSUPPORTED, messages = listOf(message))
}
