package com.dawnyew.app.capture

import com.dawnyew.core.Category
import com.dawnyew.core.Ledger
import com.dawnyew.presentation.CaptureDraftUi

/** Process-only, bounded to one explicit screenshot request and its original dataset. */
internal data class FloatingCaptureReview(
    val requestId: String,
    val capturedAt: String,
    val datasetId: String,
    val draft: CaptureDraftUi,
    val ledgers: List<Ledger>,
    val categories: List<Category>,
    val ledgerId: String,
    val categoryId: String?,
    val defaultedTime: Boolean = false,
    val defaultLedgerId: String? = null,
    val defaultCategoryId: String? = null,
)
