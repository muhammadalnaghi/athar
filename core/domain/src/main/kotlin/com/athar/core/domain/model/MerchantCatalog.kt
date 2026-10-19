package com.athar.core.domain.model

import kotlinx.datetime.Instant

/**
 * One bounded entry of the signed shared merchant catalog (ENH-009).
 * Public, nonfinancial data; patterns are validated at download time and
 * re-validated against live categories at match time.
 */
data class MerchantCatalogEntry(
    val pattern: String,
    val patternType: PatternType,
    val categoryId: String,
)

/** One coherent, defensive read of authenticated downloaded knowledge. */
data class MerchantCatalogSnapshot(
    val entries: List<MerchantCatalogEntry>,
    val version: Long,
)

/** Refresh lifecycle of the shared merchant catalog. Never carries error bodies. */
enum class CatalogRefreshPhase { IDLE, CHECKING, CURRENT, UPDATED, FAILED, UNAVAILABLE }

/**
 * Public, nonfinancial state of the shared merchant catalog.
 * Contains no merchant or financial values and no error bodies — internal
 * failure enums map to localized generic strings at the UI layer.
 */
data class MerchantCatalogState(
    val updatesEnabled: Boolean = false,
    val disablePersistencePending: Boolean = false,
    val networkSupported: Boolean = false,
    val version: Long? = null,
    val entryCount: Int? = null,
    val checkedAt: Instant? = null,
    val phase: CatalogRefreshPhase = CatalogRefreshPhase.IDLE,
)
