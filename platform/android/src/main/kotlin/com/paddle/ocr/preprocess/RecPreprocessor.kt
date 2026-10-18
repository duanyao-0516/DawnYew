// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Modified by DawnYew on 2026-10-18: Honor pinned BGR model input, bound crop width, and release owned Mats.

package com.paddle.ocr.preprocess

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil

data class RecPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
)

object RecPreprocessor {
    private const val FIXED_HEIGHT = 48
    private const val MAX_IMG_W = 3200

    fun preprocessBatch(crops: List<Mat>): RecPreprocessResult {
        require(crops.isNotEmpty()) { "No text crops" }
        crops.forEach { com.dawnyew.platform.ocr.OcrResourceLimits.checkTextCrop(it.cols(), it.rows()) }
        // DawnYew: the pinned inference.yml declares DecodeImage.img_mode=BGR.
        // Every intermediate Mat is owned here and released on success or failure.
        val owned = mutableListOf<Mat>()
        fun own(mat: Mat): Mat = mat.also { owned.add(it) }
        return try {
            val floatMats = crops.map { crop ->
                val width = ceil(FIXED_HEIGHT * crop.cols().toDouble() / crop.rows()).toInt().coerceAtMost(MAX_IMG_W)
                val resized = own(Mat())
                Imgproc.resize(crop, resized, Size(width.toDouble(), FIXED_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                val normalized = own(Mat())
                resized.convertTo(normalized, CvType.CV_32F)
                Core.divide(normalized, org.opencv.core.Scalar(127.5, 127.5, 127.5), normalized)
                Core.subtract(normalized, org.opencv.core.Scalar(1.0, 1.0, 1.0), normalized)
                normalized
            }
            val maxWidth = floatMats.maxOf { it.cols() }
            val channelSize = FIXED_HEIGHT * maxWidth
            val data = FloatArray(crops.size * 3 * channelSize)
            for ((batch, mat) in floatMats.withIndex()) {
                val padded = if (mat.cols() == maxWidth) mat else {
                    own(Mat(FIXED_HEIGHT, maxWidth, CvType.CV_32FC3, org.opencv.core.Scalar(0.0))).also {
                        val roi = own(it.submat(0, FIXED_HEIGHT, 0, mat.cols()))
                        mat.copyTo(roi)
                    }
                }
                val channels = mutableListOf<Mat>()
                try {
                    Core.split(padded, channels)
                    for (channel in 0..2) {
                        val values = FloatArray(channelSize)
                        channels[channel].get(0, 0, values)
                        System.arraycopy(values, 0, data, (batch * 3 + channel) * channelSize, channelSize)
                    }
                } finally {
                    channels.forEach { it.release() }
                }
            }
            RecPreprocessResult(data, longArrayOf(crops.size.toLong(), 3, FIXED_HEIGHT.toLong(), maxWidth.toLong()))
        } finally {
            owned.asReversed().forEach { it.release() }
        }
    }
}
