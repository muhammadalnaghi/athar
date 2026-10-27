package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate

/**
 * As-of price availability for a holding.
 *
 * A holding with no usable dated quote is [Unavailable] — it is *never* valued at zero;
 * the surrounding valuation is reported as partial instead.
 */
sealed interface PriceStatus {
    /**
     * Priced at the latest quote dated on or before the as-of date.
     * [stale] is true when [quoteDate] is older than the configured staleness window;
     * the price is still usable, but the staleness is disclosed.
     */
    data class Priced(
        val unitPrice: Money,
        val quoteDate: LocalDate,
        val stale: Boolean,
    ) : PriceStatus

    /** No quote exists on or before the as-of date; no market value can be reported. */
    data object Unavailable : PriceStatus
}
