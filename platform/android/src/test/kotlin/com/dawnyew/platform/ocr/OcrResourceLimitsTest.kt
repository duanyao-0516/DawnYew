package com.dawnyew.platform.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrResourceLimitsTest {
    @Test fun decodedImageMustBeBoundedBeforeNativeConversion() {
        OcrResourceLimits.checkBitmap(1080, 1920)
        listOf(0 to 100, 12001 to 10, 6000 to 6000).forEach { (width, height) ->
            try { OcrResourceLimits.checkBitmap(width, height); fail("$width x $height must be rejected") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun extremeTextAspectRatioCannotAllocateAnUnboundedRecognitionTensor() {
        OcrResourceLimits.checkTextCrop(3200, 48)
        try { OcrResourceLimits.checkTextCrop(1920, 1); fail("Unsupported text aspect ratio must be rejected") } catch (_: IllegalArgumentException) { }
    }
}
