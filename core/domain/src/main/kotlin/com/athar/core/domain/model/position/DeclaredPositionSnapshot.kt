package com.athar.core.domain.model.position

/**
 * What a declared position IS: user-authored input. It is never a verified bank
 * balance, never a posting history, and never the verified whole wealth or
 * liquidity of the user — consumers must label it exactly this way.
 */
enum class DeclaredPositionStatus { USER_DECLARED }

/**
 * WM033 read-side view of ONE currently saved declared-position document
 * (written by the Plan feature as `balance_anchor` v1 inside the shared CASH
 * lane). This is a projection of a single stored document — values from
 * different saved documents are never summed here or anywhere downstream, and
 * nothing is converted across currencies.
 *
 * - [asOfDate] is the stored ISO date literal, verbatim and unnormalized.
 * - Currency maps are keyed by the literal currency string as entered; amounts
 *   are exact decimal strings (no scale normalization, no rounding). A currency
 *   the document never declares is an ABSENT key, never a zero.
 * - [netByCurrency] exists only for currencies the document actually declares
 *   (assets − liabilities within that same literal currency; an absent side is
 *   an empty sum, not missing data).
 * - [hasUnstructuredOwnershipLabels] only reports that free-text ownership
 *   labels are present; the labels are never parsed into shares or percentages.
 * - [updatedAtEpochMillis] and [revision] are the storage envelope's values,
 *   carried so ordering and conflict-free display stay deterministic.
 */
data class DeclaredPositionSnapshot(
    val id: String,
    val title: String,
    val revision: Long,
    val asOfDate: String,
    val updatedAtEpochMillis: Long,
    val status: DeclaredPositionStatus,
    val hasUnstructuredOwnershipLabels: Boolean,
    val entryCount: Int,
    val assetsByCurrency: Map<String, String>,
    val liabilitiesByCurrency: Map<String, String>,
    val netByCurrency: Map<String, String>,
)
