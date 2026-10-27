package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate

/**
 * A user-known historical unit price for a security, observed on [date].
 *
 * Prices are **never fetched or invented** by this ledger: callers supply only quotes
 * they actually know. Valuation picks the latest quote dated on or before the as-of date.
 *
 * Price sign: an explicitly supplied **zero** is a valid observation — the security is
 * known to be worthless on that date — and stays distinct from a missing quote, which
 * leaves the holding unpriced (never valued at zero, never synthesized). A **negative**
 * price does not exist and is rejected with [LedgerRejection.NegativeQuotePrice].
 */
data class SecurityQuote(
    val securityId: String,
    val date: LocalDate,
    val unitPrice: Money,
)
