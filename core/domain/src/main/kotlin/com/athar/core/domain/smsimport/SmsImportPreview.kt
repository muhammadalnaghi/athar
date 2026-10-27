package com.athar.core.domain.smsimport

/**
 * Aggregate preview counts only — never carries SMS bodies.
 */
data class SmsImportPreview(
    val matchingSmsCount: Int = 0,
    val parseableCount: Int = 0,
    val duplicateCount: Int = 0,
    val skippedCount: Int = 0,
)

data class SmsImportResult(
    val scanned: Int = 0,
    val accepted: Int = 0,
    val parseable: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val failed: Boolean = false,
    val failureReason: String? = null,
)

data class DiscoveredSmsSender(
    /** Exact address string as returned by the SMS provider. */
    val address: String,
    val messageCount: Int,
)
