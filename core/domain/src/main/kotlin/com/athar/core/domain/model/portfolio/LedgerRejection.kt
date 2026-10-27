package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * Typed rejections for portfolio ledger construction and accounting.
 *
 * These are *rejections of unsupported input*, not runtime failures: a caller that
 * receives one is holding data this foundation refuses to silently repair — duplicate
 * ids, short sales, negative synthetic cash, cross-currency relabeling, or prices that
 * do not exist.
 */
sealed interface LedgerRejection {
    val eventId: String

    /** Two events share a stable id. */
    data class DuplicateEventId(val duplicateEventId: String) : LedgerRejection {
        override val eventId: String get() = duplicateEventId
    }

    /** A blank/whitespace event id cannot be a stable identifier. */
    data class InvalidBlankEventId(override val eventId: String) : LedgerRejection

    /**
     * Two security profiles share one securityId. Even when the profiles are identical,
     * the duplicate is rejected: a security's identity is supplied exactly once and is
     * never silently repaired by last-wins collapsing.
     */
    data class DuplicateSecurityId(val duplicateSecurityId: String) : LedgerRejection {
        override val eventId: String get() = "security:$duplicateSecurityId"
    }

    /** A blank/whitespace security id cannot be a stable identifier. */
    data class InvalidBlankSecurityId(override val eventId: String) : LedgerRejection

    /** Two events share the same (date, sequence) — their relative order is ambiguous. */
    data class AmbiguousOrdering(val date: LocalDate, val sequence: Int) : LedgerRejection {
        override val eventId: String get() = "date=$date,sequence=$sequence"
    }

    /** An event (or the opening cash) is denominated outside the ledger's single base currency. */
    data class CrossCurrencyEvent(
        override val eventId: String,
        val eventCurrencies: Set<String>,
        val baseCurrency: String,
    ) : LedgerRejection

    /** The event references a security absent from the ledger's security list. */
    data class UnknownSecurity(override val eventId: String, val securityId: String) : LedgerRejection

    /** Trade/distribution quantity is zero or negative. */
    data class NonPositiveQuantity(override val eventId: String, val quantity: BigDecimal) : LedgerRejection

    /** A unit price is zero or negative. */
    data class NonPositiveUnitPrice(override val eventId: String, val unitPrice: Money) : LedgerRejection

    /** An amount that must be positive (or a fee / opening cash that must be non-negative) is not. */
    data class NonPositiveAmount(override val eventId: String, val amount: Money) : LedgerRejection

    /**
     * A [DividendReinvestment]'s declared amount disagrees with quantity × unitPrice.
     *
     * Convention (exact, no tolerance band): the declared amount must equal
     * `quantity × unitPrice.amount` rounded to the settlement scale of 2 fractional
     * digits with banker's rounding (HALF_EVEN) — the same scale/rounding contract as
     * [Money.rounded]. This accommodates legitimate fractional-share rounding of the
     * quoted price; any difference at or beyond one halala after that rounding is
     * rejected. Fees are never folded into this comparison: fees are separate events
     * or fields with their own amounts.
     */
    data class InconsistentReinvestmentConsideration(
        override val eventId: String,
        val declaredAmount: Money,
        val expectedAmount: Money,
    ) : LedgerRejection

    /** Selling more units than held — shorting is unsupported, not silently allowed. */
    data class UnsupportedShortSale(
        override val eventId: String,
        val securityId: String,
        val requestedQuantity: BigDecimal,
        val heldQuantity: BigDecimal,
    ) : LedgerRejection

    /**
     * A buy (incl. fee), portfolio fee, or withdrawal would drive portfolio cash negative.
     * Synthetic negative cash is flagged unsupported rather than silently repaired.
     */
    data class InsufficientCash(
        override val eventId: String,
        val required: Money,
        val available: Money,
    ) : LedgerRejection

    /** Two quotes for the same security share a date — the as-of price would be ambiguous. */
    data class AmbiguousQuotes(val securityId: String, val date: LocalDate) : LedgerRejection {
        override val eventId: String get() = "quote:$securityId@$date"
    }

    /**
     * A supplied quote price is negative — a price below zero does not exist. The
     * contrast is deliberate: an explicitly supplied dated **zero** quote is valid
     * (a known-worthless observation) and stays distinct from a missing/unpriced quote,
     * which is never valued at zero.
     */
    data class NegativeQuotePrice(
        val securityId: String,
        val date: LocalDate,
        val unitPrice: Money,
    ) : LedgerRejection {
        override val eventId: String get() = "quote:$securityId@$date"
    }

    /** A negative freshness window would arbitrarily flip staleness; rejected, not interpreted. */
    data class NegativeStaleAfterDays(val staleAfterDays: Int) : LedgerRejection {
        override val eventId: String get() = "staleAfterDays=$staleAfterDays"
    }
}
