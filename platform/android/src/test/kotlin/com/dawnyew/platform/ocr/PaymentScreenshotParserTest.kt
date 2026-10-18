package com.dawnyew.platform.ocr

import com.dawnyew.core.OcrPoint
import com.dawnyew.core.OcrResult
import com.dawnyew.core.OcrTextBlock
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

    @Test fun everyDangerousStateIsRejectedBeforeAmountSelection() {
        val unsafe = listOf("支付失败", "未支付", "待付款", "交易关闭", "付款已取消", "退款成功", "退款处理中", "部分退款", "退款", "转账成功", "收款成功", "收入", "交易记录")
        unsafe.forEach { state ->
            val candidate = PaymentScreenshotParser.parse(input("支付宝", "支付成功", state, "￥99.00"))
            assertEquals(state, CaptureDisposition.UNSUPPORTED, candidate.disposition)
            assertNull(state, candidate.amountMinor)
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

    @Test fun repeatedSuccessPagesAreRejected() {
        val candidate = PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34", "支付成功", "￥22.34"))
        assertEquals(CaptureDisposition.UNSUPPORTED, candidate.disposition)
    }

    @Test fun amountWithoutSuccessfulExpenseEvidenceIsUnsupported() {
        assertEquals(CaptureDisposition.UNSUPPORTED, PaymentScreenshotParser.parse(input("支付宝", "￥12.34")).disposition)
        assertEquals(CaptureDisposition.UNSUPPORTED, PaymentScreenshotParser.parse(input("交易成功", "￥12.34")).disposition)
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

    @Test fun mixedRepeatedSuccessPagesAreRejected() {
        assertEquals(CaptureDisposition.UNSUPPORTED, PaymentScreenshotParser.parse(input("微信支付", "支付成功", "￥12.34", "支付宝", "付款成功", "￥22.34")).disposition)
    }

    @Test fun duplicateSuccessTextWithinOneBlockIsRejected() {
        assertEquals(CaptureDisposition.UNSUPPORTED, PaymentScreenshotParser.parse(input("微信支付", "支付成功 支付成功", "￥12.34")).disposition)
    }

    @Test fun nonFiniteMerchantConfidenceDoesNotPrefillMerchant() {
        val result = OcrResult(listOf(block("支付宝", 0f), block("支付成功", 32f), block("￥12.34", 64f), block("商户名称：不可信商户", 96f, Float.NaN)), "fixture")
        assertNull(PaymentScreenshotParser.parse(result).merchant)
    }
}
