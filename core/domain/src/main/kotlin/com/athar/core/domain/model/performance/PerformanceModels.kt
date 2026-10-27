package com.athar.core.domain.model.performance

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * One dated investor cash flow in the XIRR series.
 *
 * Sign convention (fixed; callers must not re-sign):
 * - negative amount = capital INTO the portfolio (purchase, contribution, opening valuation)
 * - positive amount = capital OUT of the portfolio (sale, withdrawal, payout, terminal valuation)
 *
 * Zero amounts are rejected by [com.athar.core.domain.calc.performance.XirrCalc]: a zero flow
 * carries no sign and must never silently mean "missing" data.
 */
data class DatedCashFlow(
    val id: String,
    val date: LocalDate,
    val amount: Money,
)

/** Nonnegative observed portfolio state; a genuine zero is known, not missing or a signed flow. */
data class DatedValuation(
    val date: LocalDate,
    val value: Money,
)

/**
 * Input to XIRR: the dated cash-flow series plus optional start/end valuations.
 *
 * Valuation convention (explicit, spec WM028-08): [openingValuation] is folded into the series as
 * a NEGATIVE flow (capital already invested) on its date; [terminalValuation] is folded in as a
 * POSITIVE flow (capital paid out) on its date. Every [Money] across flows and valuations must
 * share one currency — mixed currencies are rejected, never converted.
 * Known zero valuations contribute zero to NPV; negative valuations are invalid. A zero terminal
 * does not rule out an XIRR when earlier real payouts exist. External zero flows remain invalid.
 */
data class XirrInput(
    val flows: List<DatedCashFlow>,
    val openingValuation: DatedValuation? = null,
    val terminalValuation: DatedValuation? = null,
)

/** Why an XIRR request has no single well-defined answer. */
enum class XirrRejection {
    /** No cash flows after folding valuations. */
    EMPTY_INPUT,

    /** A caller-supplied flow amount is zero (missing sign) — rejected, never treated as missing
     *  data. A folded zero VALUATION is a known state (see [NEGATIVE_VALUATION]'s counterpart):
     *  it is allowed and is never this rejection. */
    ZERO_AMOUNT,

    /**
     * An opening or terminal valuation is negative. Valuations are nonnegative observed STATE,
     * never signed flows; a negative valuation is corrupt upstream data and is rejected BEFORE
     * folding, so it can never masquerade as a same-sign flow (fake `NO_SIGN_CHANGE`) or a
     * flipped positive flow (fake `Rate`).
     */
    NEGATIVE_VALUATION,

    /** Flows and/or valuations do not all share one currency. */
    CURRENCY_MISMATCH,

    /** All flows share one date: NPV(r) is constant and net != 0, so no rate can produce it. */
    ZERO_TIMESPAN,

    /**
     * NPV keeps one sign across the whole bounded search window (e.g. all flows same sign), or
     * the only root(s) lie outside the window. A bounded-search miss is NOT a proof that no rate
     * exists — the detail names the bounds that were searched.
     */
    NO_SIGN_CHANGE,

    /** The bounded bisection failed inside a valid bracket (arithmetic error). */
    SOLVER_FAILED,
}

/** Why an XIRR request has an answer only up to documented ambiguity. */
enum class XirrAmbiguity {
    /**
     * More than one NPV sign change across the bounded scan (non-conventional series, e.g. loss
     * after gain). The listed candidates are the roots found by a FINITE grid scan: each one's
     * existence is proven, but a finite scan cannot prove uniqueness or exhaustiveness — other
     * roots may exist between or outside scanned points. Never silently pick one candidate.
     */
    MULTIPLE_SIGN_CHANGES,

    /** A non-conventional flow series has only one scanned candidate; uniqueness is unproven. */
    UNIQUENESS_UNPROVEN,

    /** Exact net flow is zero on every date: NPV is identically zero, so no unique rate exists. */
    EVERY_RATE_IS_A_ROOT,
}

/** Typed XIRR outcomes — no exceptions cross this API. */
sealed interface XirrResult {
    /**
     * Annualized rate as a RATIO, not percent: 0.10 means +10%/yr, -0.999 means -99.9%.
     * Multiply by 100 only at presentation time. Day-count basis: actual days / 365.
     */
    data class Rate(val annualRateRatio: BigDecimal) : XirrResult

    data class Ambiguous(
        val reason: XirrAmbiguity,
        val candidateAnnualRateRatios: List<BigDecimal>,
        val detail: String,
    ) : XirrResult

    data class Rejected(val reason: XirrRejection, val detail: String) : XirrResult
}

/**
 * One external-flow boundary in the TWR series. [externalAmount] sign: POSITIVE = investor money
 * INTO the portfolio (contribution); NEGATIVE = money OUT to the investor (withdrawal).
 * Dividend reinvestment is internal (money never leaves the portfolio) and must NOT be passed
 * here — this API never classifies flows on the caller's behalf.
 * Before/after values are actual observations for this boundary, not reused daily-close estimates.
 * Equal dates with different sequence values do not imply no market move between the boundaries.
 */
data class ExternalFlowBoundary(
    val flowId: String,
    val date: LocalDate,
    val sequence: Int,
    val valueBefore: Money,
    val externalAmount: Money,
    val valueAfter: Money,
)

/**
 * Input to TWR. [initialValue] is the market value at [startDate] AFTER any start-date flows;
 * [closingValue] is the value at [endDate] BEFORE any hypothetical end-date flow. Boundaries must
 * lie strictly inside the open window (startDate, endDate) and be listed in ascending
 * (date, sequence) order.
 */
data class TwrInput(
    val startDate: LocalDate,
    val initialValue: Money,
    val endDate: LocalDate,
    val closingValue: Money,
    val boundaries: List<ExternalFlowBoundary>,
)

/** Why a TWR request has no well-defined answer. */
enum class TwrRejection {
    /** startDate is not strictly before endDate. */
    ZERO_TIMESPAN,

    /** A boundary lies on or outside the open window (startDate, endDate). */
    BOUNDARY_OUTSIDE_WINDOW,

    /** Duplicate flowId, duplicate (date, sequence), or boundaries not in ascending order. */
    BOUNDARY_UNORDERED_OR_DUPLICATE,

    /** valueAfter != valueBefore + externalAmount — missing or fabricated boundary data. */
    BOUNDARY_AFTER_MISMATCH,

    /** Valuations and boundaries do not all share one currency. */
    CURRENCY_MISMATCH,

    /**
     * A sub-period runs from zero capital to nonzero value (impossible without an unrecorded
     * external flow), starts from a negative valuation or flips sign — chaining is undefined.
     */
    ZERO_OR_SIGN_CHANGING_BASE,
}

/** Typed TWR outcomes — no exceptions cross this API. */
sealed interface TwrResult {
    /**
     * Period return as a RATIO (0.10 = +10% over the whole window). [subPeriodCount] is the
     * number of chained market-only growth factors (boundaries + 1).
     */
    data class Rate(val periodReturnRatio: BigDecimal, val subPeriodCount: Int) : TwrResult

    data class Rejected(val reason: TwrRejection, val detail: String) : TwrResult
}
