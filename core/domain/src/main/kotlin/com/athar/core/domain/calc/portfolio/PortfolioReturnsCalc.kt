package com.athar.core.domain.calc.portfolio

import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.ExternalWithdrawal
import com.athar.core.domain.model.portfolio.CashDividend
import com.athar.core.domain.model.portfolio.DividendDestination
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.SecurityQuote
import java.math.BigDecimal
import kotlinx.datetime.LocalDate

/**
 * Why a money-weighted (XIRR) or time-weighted (TWR) return is undefined.
 *
 * Every reason is an honest statement about inputs the user's dated document cannot
 * certify — a missing boundary valuation is disclosed together with the unpriced
 * securities that caused it. Neither engine ever substitutes a fabricated price, an
 * index, or a proxy.
 */
enum class ReturnUndefinedReason {
    /**
     * A boundary (flow date or as-of) valuation could not be certified from the user's
     * dated document: some holding had no usable dated quote, or — for TWR — the document
     * carries no actual before/after-flow boundary observations, which the reviewed TWR
     * contract requires and which daily-close data cannot substitute for. It also covers
     * a nonzero XIRR opening balance without a saved opening date.
     */
    MISSING_BOUNDARY_VALUATION,
    /** Explicit opening capital does not yet exist at the requested as-of date. */
    AS_OF_BEFORE_OPENING_DATE,

    /** There are no dated external flows against which a return is defined. */
    NO_DATED_FLOWS,

    /** A sub-period base (value at start + net external flow) was zero or negative. */
    NON_POSITIVE_PERIOD_BASE,

    /**
     * No reliable single rate could be certified. Beyond a bounded-search miss (no NPV
     * sign change inside the search window), this covers NON-CONVENTIONAL series whose
     * sign pattern admits multiple roots or unproven uniqueness: ambiguity is reported
     * as undefined, never narrowed to a first-found root.
     */
    NO_CONVERGENCE,
}

/** Annualized money-weighted return (XIRR) over the ledger's dated external flows. */
sealed interface XirrResult {
    /** [annualRatePercent] at 8dp HALF_EVEN, e.g. `12.34567890` meaning 12.34567890% per year. */
    data class Available(val annualRatePercent: BigDecimal) : XirrResult

    data class Undefined(val reason: ReturnUndefinedReason, val unpricedSecurityIds: List<String> = emptyList()) : XirrResult
}

/** Geometrically linked time-weighted return across every external-flow boundary. */
sealed interface TwrResult {
    /** [totalPercent] at 8dp HALF_EVEN over [periodCount] sub-periods; not annualized. */
    data class Available(val totalPercent: BigDecimal, val periodCount: Int) : TwrResult

    data class Undefined(val reason: ReturnUndefinedReason, val unpricedSecurityIds: List<String> = emptyList()) : TwrResult
}

/**
 * XIRR and TWR computed strictly from the user's own dated ledger and dated quotes.
 *
 * Conventions (fixed, documented, never tuned per call):
 * - External flows only: contributions are money IN (negative from the portfolio's
 *   perspective), withdrawals and dividends paid to an EXTERNAL_ACCOUNT are money OUT
 *   (positive). Trades, portfolio-internal dividends, reinvestments and fees are
 *   internal and never appear as flows.
 * - XIRR delegates to the reviewed engine
 *   `com.athar.core.domain.calc.performance.XirrCalc` (exact BigDecimal arithmetic,
 *   bounded 4096-point scan + bisection). Explicitly dated opening cash enters once
 *   as the opening valuation. Undated capital still withholds XIRR; no first-trade
 *   date is invented. With zero opening cash, actual dated external flows and a full
 *   terminal valuation feed the engine; partial prices are never fabricated.
 *   A non-conventional series (sign pattern admitting several roots or unproven
 *   uniqueness) is undefined [ReturnUndefinedReason.NO_CONVERGENCE] — never a
 *   first-found root presented as the return.
 * - TWR is fail-closed: the reviewed `TwrCalc` contract requires actual observed
 *   before/after-flow boundary values (`valueAfter = valueBefore + externalAmount`).
 *   A daily dated-quote document cannot supply those observations, so any nonzero net
 *   external flow yields [ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION] instead of
 *   a chain fabricated from same-day valuations; with no nonzero dated flow it is
 *   [ReturnUndefinedReason.NO_DATED_FLOWS]. `TwrCalc` is invoked only when a caller can
 *   supply certified boundary observations.
 *
 * Out of scope (typed as undefined, never approximated): valuations at dates without
 * usable quotes, FX conversion, risk-adjusted or benchmark-relative figures.
 */
object PortfolioReturnsCalc {

    fun xirr(ledger: PortfolioLedger, quotes: List<SecurityQuote>, asOf: LocalDate): XirrResult =
        PortfolioReturns042XirrBridge.xirr(ledger, quotes, asOf)

    fun timeWeighted(ledger: PortfolioLedger, quotes: List<SecurityQuote>, asOf: LocalDate): TwrResult {
        val openingDate = ledger.openingCashDate
        if (openingDate != null && ledger.openingCash.amount.signum() != 0) {
            return TwrResult.Undefined(
                if (asOf < openingDate) ReturnUndefinedReason.AS_OF_BEFORE_OPENING_DATE
                else ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION,
            )
        }
        val boundaries = netFlowBoundaries(ledger, asOf)
        if (boundaries.isEmpty()) {
            return TwrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS)
        }
        // The document holds daily dated quotes only; it cannot certify the observed
        // valueBefore/valueAfter pair around each flow. Return a typed unavailable
        // state — do not synthesize boundary observations for the reviewed engine.
        return TwrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION)
    }

    /** Dates carrying a nonzero net external flow (contributions in, withdrawals/external dividends out). */
    private fun netFlowBoundaries(ledger: PortfolioLedger, asOf: LocalDate): List<Pair<LocalDate, BigDecimal>> {
        val netFlowByDate = sortedMapOf<LocalDate, BigDecimal>()
        for (event in ledger.orderedEvents) {
            if (event.date > asOf) break
            val signed: BigDecimal? = when (event) {
                is ExternalContribution -> event.amount.amount
                is ExternalWithdrawal -> event.amount.amount.negate()
                is CashDividend ->
                    if (event.destination == DividendDestination.EXTERNAL_ACCOUNT) event.amount.amount.negate() else null
                else -> null
            }
            if (signed != null) netFlowByDate.merge(event.date, signed, BigDecimal::add)
        }
        return netFlowByDate.entries.filter { it.value.signum() != 0 }.map { it.key to it.value }
    }
}
