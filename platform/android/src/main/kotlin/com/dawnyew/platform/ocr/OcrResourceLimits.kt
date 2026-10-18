package com.dawnyew.platform.ocr

/** Bounds are checked before large OpenCV/tensor allocations. No partial-page fallback. */
object OcrResourceLimits {
    const val MAX_TEXT_BLOCKS = 128
    const val MAX_CONTOURS = 1000
    const val RECOGNITION_HEIGHT = 48
    const val MAX_RECOGNITION_WIDTH = 3200

    fun checkBitmap(width: Int, height: Int) {
        require(width in 1..1920 && height in 1..1920) {
            "图片尺寸超出识别范围"
        }
    }

    fun checkTextCrop(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * RECOGNITION_HEIGHT <= height.toLong() * MAX_RECOGNITION_WIDTH) {
            "文字区域比例超出模型范围"
        }
    }
}
