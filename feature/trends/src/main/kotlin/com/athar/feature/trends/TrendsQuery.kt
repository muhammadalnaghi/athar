package com.athar.feature.trends

import com.athar.core.common.time.Period
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxType
import kotlinx.datetime.LocalDate

/** Which figure a drilldown points at. */
enum class TrendsMetric { EXPENSE, INCOME, SAVINGS }

/**
 * How the comparison window is chosen — independent of the current period selection.
 *
 * AUTO: completed periods compare against the full previous window; in-progress
 * periods compare against the equal-elapsed slice of the previous window (explicitly
 * labeled in the UI). CUSTOM_PRIOR: the user picked independent prior dates.
 */
enum class ComparisonMode { AUTO, CUSTOM_PRIOR }

data class ComparisonSelection(
    val mode: ComparisonMode = ComparisonMode.AUTO,
    val customPrior: Period.Custom? = null,
)

/**
 * Validation failure for a user-entered range (current or prior). Disclosed calmly in
 * AR/EN; the query keeps its previous value instead of crashing on a Period require.
 */
enum class TrendsRangeError { END_BEFORE_START, START_IN_FUTURE }

/**
 * Filters over persisted [Transaction] fields. They apply to every aggregate AND to
 * the evidence lists — never only to the visible rows.
 */
data class TrendsFilters(
    val accountIds: Set<String> = emptySet(),
    val sources: Set<IngestSource> = emptySet(),
    val type: TxType? = null,
    val categoryId: String? = null,
    val merchantQuery: String? = null,
) {
    val isDefault: Boolean
        get() = accountIds.isEmpty() && sources.isEmpty() && type == null &&
            categoryId == null && merchantQuery.isNullOrBlank()

    fun matches(tx: Transaction): Boolean {
        if (accountIds.isNotEmpty() && tx.accountId !in accountIds) return false
        if (sources.isNotEmpty() && tx.source !in sources) return false
        if (type != null && tx.type != type) return false
        if (categoryId != null) {
            if (categoryId == UNCATEGORIZED_KEY) {
                // Explicit "uncategorized" selection matches rows with no category at all.
                if (tx.categoryId != null) return false
            } else if (tx.categoryId != categoryId) return false
        }
        val query = merchantQuery?.trim()
        if (!query.isNullOrBlank()) {
            val hit = tx.merchantNormalized.contains(query, ignoreCase = true) ||
                tx.merchant.contains(query, ignoreCase = true)
            if (!hit) return false
        }
        return true
    }

    fun withToggledAccount(accountId: String): TrendsFilters = copy(
        accountIds = if (accountId in accountIds) accountIds - accountId else accountIds + accountId,
    )

    fun withToggledSource(source: IngestSource): TrendsFilters = copy(
        sources = if (source in sources) sources - source else sources + source,
    )
}

/**
 * The single explicit query state for the whole screen. Every number on Trends is a
 * function of this plus the persisted data — no hidden secondary selections.
 */
data class TrendsQuery(
    val periodKey: PeriodKey = PeriodKey.MONTH,
    val customRange: Period.Custom? = null,
    val comparison: ComparisonSelection = ComparisonSelection(),
    /** ISO-4217 reporting currency override; null = follow the user preference. */
    val currencyOverride: String? = null,
    val filters: TrendsFilters = TrendsFilters(),
    /** Disclosed validation failure of the last custom CURRENT range attempt (null = none). */
    val customRangeError: TrendsRangeError? = null,
    /** Disclosed validation failure of the last custom PRIOR range attempt (null = none). */
    val comparisonRangeError: TrendsRangeError? = null,
)

/** Keys of the synthetic expense buckets that are not real category ids. */
const val UNCATEGORIZED_KEY = "__uncategorized__"
const val OTHER_KEY = "__other__"

sealed interface TrendsEvent {
    data class SelectPeriod(val key: PeriodKey) : TrendsEvent
    data class SelectCustomRange(val start: LocalDate, val endExclusive: LocalDate) : TrendsEvent

    // Comparison selection (independent of the current period).
    data class SetComparisonCustomRange(val start: LocalDate, val endExclusive: LocalDate) : TrendsEvent
    data object ClearComparisonCustom : TrendsEvent

    // Reporting currency.
    data class SetCurrencyOverride(val currency: String?) : TrendsEvent

    // Filters.
    data class ToggleAccountFilter(val accountId: String) : TrendsEvent
    data class ToggleSourceFilter(val source: IngestSource) : TrendsEvent
    data class SetTypeFilter(val type: TxType?) : TrendsEvent
    data class SetCategoryFilter(val categoryId: String?) : TrendsEvent
    data class SetMerchantQuery(val query: String) : TrendsEvent
    data object ClearFilters : TrendsEvent

    // Drilldowns: evidence sheet + read-only transaction detail. Closing either
    // never mutates the query — back always preserves the selected context.
    data class OpenEvidence(val request: TrendsEvidenceRequest) : TrendsEvent
    data object CloseEvidence : TrendsEvent

    /** One intra-sheet drill back: return to the previous evidence view, else close. */
    data object BackEvidence : TrendsEvent
    data class OpenTransactionDetail(val transactionId: String) : TrendsEvent
    data object CloseTransactionDetail : TrendsEvent
}
