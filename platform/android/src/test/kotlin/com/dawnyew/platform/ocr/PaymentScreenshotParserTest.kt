package com.dawnyew.platform.ocr

import com.dawnyew.core.OcrPoint
import com.dawnyew.core.OcrResult
import com.dawnyew.core.OcrTextBlock
import com.dawnyew.core.Direction
import org.junit.Assert.*
import org.junit.Test

class PaymentScreenshotParserTest {
    private fun input(vararg lines: String) = OcrResult(lines.mapIndexed { index, text ->
        block(text, index * 32f)
    }, "fixture")
    private fun block(text: String, y: Float, confidence: Float = 0.99f) = OcrTextBlock(
        text, confidence, listOf(OcrPoint(10f, y), OcrPoint(500f, y), OcrPoint(500f, y + 24), OcrPoint(10f, y + 24)),
    )

    @Test fun successfulSingleExpenseRequiresReview() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34", "商户全称：示例咖啡店", "支付时间 2026-10-18 12:34:56"))
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
        assertEquals(1234L, candidate.amountMinor)
        assertEquals("示例咖啡店", candidate.merchant)
        assertEquals("2026-10-18", candidate.localDate)
        assertEquals("12:34:56", candidate.localTime)
    }

    @Test fun workflowWordsDoNotRejectUserSelectedImagesBeforeAmountReading() {
        val words = listOf("支付失败", "未支付", "待付款", "交易关闭", "付款已取消", "退款成功", "退款处理中", "部分退款", "退款", "转账成功", "收款成功", "收入", "交易记录", "全部账单", "月账单")
        words.forEach { text ->
            val candidate = PaymentScreenshotParser.parse(input("支付宝", "支付成功", text, "￥99.00"))
            assertEquals(text, CaptureDisposition.REVIEW, candidate.disposition)
            assertEquals(text, 9900L, candidate.amountMinor)
        }
    }

    @Test fun refundActionIsNotMistakenForRefundState() {
        val candidate = PaymentScreenshotParser.parse(input("支付宝", "支付成功", "￥12.34", "申请退款"))
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
    }

    @Test fun multipleUnlabelledAmountsStayAmbiguous() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34", "￥22.34"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
        assertEquals(listOf(1234L, 2234L), candidate.amountCandidates)
    }

    @Test fun explicitPaidLabelCanDisambiguateOriginalPriceAndDiscount() {
        val candidate = PaymentScreenshotParser.parse(input("支付宝", "支付成功", "原价 ￥20.00", "优惠 ￥5.00", "实付款 ￥15.00"))
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
        assertEquals(1500L, candidate.amountMinor)
    }

    @Test fun conflictingPaidLabelsStayAmbiguous() {
        val candidate = PaymentScreenshotParser.parse(input("支付宝", "支付成功", "实付款 ￥15.00", "支付金额 ￥16.00"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
    }

    @Test fun aSingleClearAmountIsNotDiscardedBecauseOfItsLabel() {
        for (text in listOf("合计 ￥12.34", "总额 ￥12.34", "订单金额 ￥12.34")) {
            val candidate = PaymentScreenshotParser.parse(input(text))
            assertEquals(text, CaptureDisposition.REVIEW, candidate.disposition)
            assertEquals(text, 1234L, candidate.amountMinor)
        }
        val multiple = PaymentScreenshotParser.parse(input("合计 ￥12.34", "余额 ￥99.00"))
        assertEquals(CaptureDisposition.AMBIGUOUS, multiple.disposition)
        assertNull(multiple.amountMinor)
        assertEquals(listOf(1234L, 9900L), multiple.amountCandidates)
    }

    @Test fun descriptionAndAmountMayShareAnOcrBlockWithoutAFieldAllowlist() {
        for (text in listOf("收入 +12.34", "退款金额 -12.34", "已收款 +12.34", "账单金额 12.34")) {
            val candidate = PaymentScreenshotParser.parse(input(text))
            assertEquals(text, CaptureDisposition.REVIEW, candidate.disposition)
            assertEquals(text, 1234L, candidate.amountMinor)
        }
    }

    @Test fun datesAndAlphanumericIdentifiersAreNotDecimalAmountTokens() {
        val candidate = PaymentScreenshotParser.parse(input("2026.10.25", "2026-10-25 12:34:56", "订单号 ABC12.34XYZ", "编号 12.34XYZ", "编号 ABC12.34"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
        assertTrue(candidate.amountCandidates.isEmpty())
    }

    @Test fun missingDecimalPointIsNeverRepaired() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥1234"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
    }

    @Test fun lowConfidenceAmountCannotBePrefilled() {
        val result = OcrResult(listOf(block("支付宝", 0f), block("支付成功", 32f), block("￥12.34", 64f, 0.6f)), "fixture")
        val candidate = PaymentScreenshotParser.parse(result)
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
    }

    @Test fun dateMissingRemainsMissingInsteadOfUsingScreenshotOrToday() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34"))
        assertNull(candidate.localDate)
        assertNull(candidate.localTime)
    }

    @Test fun repeatedSuccessTextWithDifferentAmountsRemainsAmbiguous() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34", "支付成功", "￥22.34"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
        assertEquals(listOf(1234L, 2234L), candidate.amountCandidates)
    }

    @Test fun amountCanBeReadWithoutChannelOrPaymentStatus() {
        listOf(input("支付宝", "￥12.34"), input("交易成功", "￥12.34"), input("￥12.34"), input("其他应用", "-12.34")).forEach { result ->
            val candidate = PaymentScreenshotParser.parse(result)
            assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
            assertEquals(1234L, candidate.amountMinor)
        }
    }

    @Test fun lowConfidenceStatusDoesNotInvalidateAClearAmount() {
        val result = OcrResult(listOf(block("支付宝", 0f), block("支付成功", 32f, 0.2f), block("￥12.34", 64f)), "fixture")
        val candidate = PaymentScreenshotParser.parse(result)
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
        assertEquals(1234L, candidate.amountMinor)
    }

    @Test fun automaticDebitDetailsCanExposeAnAmountWithoutChannelEvidence() {
        // Minimal non-personal text fixture from the first reported screenshot.
        val candidate = PaymentScreenshotParser.parse(input("全部账单", "自动扣款成功", "-1.98"))
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
        assertEquals(198L, candidate.amountMinor)
        assertEquals(listOf(198L), candidate.amountCandidates)
    }

    @Test fun tenpayDetailsCanExposeAnAmountWithoutAChannelAllowlist() {
        // Minimal non-personal text fixture from the second reported screenshot.
        val candidate = PaymentScreenshotParser.parse(input("财付通", "支付成功", "-35.50"))
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
        assertEquals(3550L, candidate.amountMinor)
        assertEquals(listOf(3550L), candidate.amountCandidates)
    }

    @Test fun moreThanEightAmountsRemainAvailableForManualSelection() {
        val expected = (1L..9L).map { it * 100 }
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", *(1..9).map { "￥$it.00" }.toTypedArray()))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
        assertEquals(expected, candidate.amountCandidates)
    }

    @Test fun explicitSignsAndCurrencyPositionsPreserveTheAmountMagnitude() {
        for (text in listOf("+12.34", "-12.34", "−12.34", "+￥12.34", "-￥12.34", "−￥12.34", "￥+12.34", "￥-12.34", "￥−12.34", "+¥12.34", "-¥12.34", "¥+12.34", "¥−12.34")) {
            val candidate = PaymentScreenshotParser.parse(input(text))
            assertEquals(text, CaptureDisposition.REVIEW, candidate.disposition)
            // The amount stays positive; direction is carried separately.
            assertEquals(text, 1234L, candidate.amountMinor)
            assertEquals(text, listOf(1234L), candidate.amountCandidates)
        }
    }

    @Test fun selectedAmountSignDeterminesDirectionAndUnsignedDefaultsToExpense() {
        val income = listOf("+12.34", "+￥12.34", "￥+12.34", "+¥ 12.34", "¥ +12.34", "收入 +12.34")
        val expense = listOf("-12.34", "−12.34", "-￥12.34", "￥−12.34", "¥-12.34", "12.34", "￥12.34", "收入 12.34")
        for ((texts, expected) in listOf(income to Direction.INCOME, expense to Direction.EXPENSE)) {
            for (text in texts) {
                val candidate = PaymentScreenshotParser.parse(input(text))
                assertEquals(text, CaptureDisposition.REVIEW, candidate.disposition)
                assertEquals(text, 1234L, candidate.amountMinor)
                assertEquals(text, expected, candidate.direction)
            }
        }
    }

    @Test fun directionComesFromTheSelectedAmountRatherThanAnotherAmountOnThePage() {
        val candidate = PaymentScreenshotParser.parse(input("实付款 ￥+15.00", "优惠 -5.00", "余额 -100.00"))
        assertEquals(1500L, candidate.amountMinor)
        assertEquals(Direction.INCOME, candidate.direction)
    }

    @Test fun oppositeSignsForTheSameMagnitudeRemainUnresolved() {
        val candidate = PaymentScreenshotParser.parse(input("+12.34", "-12.34"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
        assertNull(candidate.direction)
        assertEquals(listOf(1234L), candidate.amountCandidates)
        assertTrue(candidate.messages.any { it.contains("收支") })
    }

    @Test fun unclearOrMultipleAmountsDoNotProduceAnAutomaticDirection() {
        val lowConfidence = OcrResult(listOf(block("+12.34", 0f, 0.6f)), "fixture")
        for (result in listOf(lowConfidence, input("+12.34", "+22.34"))) {
            val candidate = PaymentScreenshotParser.parse(result)
            assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
            assertNull(candidate.amountMinor)
            assertNull(candidate.direction)
        }
    }

    @Test fun emptyOcrAndTextResourceOverflowRemainTechnicalRejections() {
        val empty = PaymentScreenshotParser.parse(input())
        assertEquals(CaptureDisposition.UNSUPPORTED, empty.disposition)
        assertNull(empty.amountMinor)
        val withinLimit = input("微信支付", "支付成功", *List(125) { "说明" }.toTypedArray(), "￥12.34")
        assertEquals(128, withinLimit.blocks.size)
        assertEquals(1234L, PaymentScreenshotParser.parse(withinLimit).amountMinor)
        val overLimit = withinLimit.copy(blocks = withinLimit.blocks + block("说明", 5000f))
        assertEquals(CaptureDisposition.UNSUPPORTED, PaymentScreenshotParser.parse(overLimit).disposition)
    }

    @Test fun groupingSeparatorsAreStrictAndOverflowIsRejected() {
        assertEquals(123456L, PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥1,234.56")).amountMinor)
        assertNull(PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12,34.56")).amountMinor)
        assertNull(PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥99999999999999999999.99")).amountMinor)
    }

    @Test fun invalidAndConflictingDatesRemainUnresolved() {
        assertNull(PaymentScreenshotParser.parse(input("支付宝", "支付成功", "￥12.34", "2026-02-30 12:30:00")).localDate)
        assertNull(PaymentScreenshotParser.parse(input("支付宝", "支付成功", "￥12.34", "2026-10-18 12:30:00", "2026-10-19 12:30:00")).localDate)
    }

    @Test fun labelsCanMatchAmountOnTheSameVisualRow() {
        val label = OcrTextBlock("实付款", 0.99f, listOf(OcrPoint(10f, 70f), OcrPoint(110f, 70f), OcrPoint(110f, 94f), OcrPoint(10f, 94f)))
        val amount = OcrTextBlock("￥15.00", 0.99f, listOf(OcrPoint(200f, 70f), OcrPoint(350f, 70f), OcrPoint(350f, 94f), OcrPoint(200f, 94f)))
        val result = OcrResult(listOf(block("支付宝", 0f), block("支付成功", 32f), label, amount, block("原价￥20.00", 110f)), "fixture")
        assertEquals(1500L, PaymentScreenshotParser.parse(result).amountMinor)
    }

    @Test fun lowConfidencePaidLabelCannotChooseBetweenAmounts() {
        val label = OcrTextBlock("实付款", 0.2f, listOf(OcrPoint(10f, 70f), OcrPoint(110f, 70f), OcrPoint(110f, 94f), OcrPoint(10f, 94f)))
        val amount = OcrTextBlock("￥15.00", 0.99f, listOf(OcrPoint(200f, 70f), OcrPoint(350f, 70f), OcrPoint(350f, 94f), OcrPoint(200f, 94f)))
        val result = OcrResult(listOf(block("支付宝", 0f), block("支付成功", 32f), label, amount, block("￥20.00", 110f)), "fixture")
        assertNull(PaymentScreenshotParser.parse(result).amountMinor)
    }

    @Test fun mixedChannelTextDoesNotRejectMultipleAmountCandidates() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34", "支付宝", "付款成功", "￥22.34"))
        assertEquals(CaptureDisposition.AMBIGUOUS, candidate.disposition)
        assertNull(candidate.amountMinor)
        assertEquals(listOf(1234L, 2234L), candidate.amountCandidates)
    }

    @Test fun duplicateSuccessTextDoesNotRejectASingleClearAmount() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功 支付成功", "￥12.34"))
        assertEquals(CaptureDisposition.REVIEW, candidate.disposition)
        assertEquals(1234L, candidate.amountMinor)
    }

    @Test fun nonFiniteMerchantConfidenceDoesNotPrefillMerchant() {
        val result = OcrResult(listOf(block("支付宝", 0f), block("支付成功", 32f), block("￥12.34", 64f), block("商户名称：不可信商户", 96f, Float.NaN)), "fixture")
        assertNull(PaymentScreenshotParser.parse(result).merchant)
    }
}
