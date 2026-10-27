package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.TwrInput
import com.athar.core.domain.model.performance.TwrRejection
import com.athar.core.domain.model.performance.TwrResult
import java.math.BigDecimal
import java.math.MathContext

/**
 * TWR — true time-weighted return by EXACT GEOMETRIC CHAINING at external-flow boundaries.
 * Not Modified Dietz; not a simple cash-adjusted `(end - start - netFlows) / start` return.
 *
 * Each external flow splits the timeline into sub-periods whose growth factors use only MARKET
 * valuations (`valueBefore` just before the flow, `valueAfter` just after; the previous
 * boundary's `valueAfter` starts the next sub-period). Investor timing therefore cannot distort
 * the result: 100 -> [+100 contribution] -> 200 -> 220 is exactly 10%, never 120%/20%.
 *
 * Conventions (spec WM028-08):
 * - [TwrInput.initialValue] is the value at startDate AFTER any start-date flows; boundaries lie
 *   strictly inside the open window (startDate, endDate) and must be LISTED in ascending
 *   (date, sequence) order; [TwrInput.closingValue] is the value at endDate BEFORE any
 *   hypothetical end-date flow.
 * - `externalAmount > 0` = contribution INTO the portfolio; `< 0` = withdrawal OUT.
 * - `valueAfter == valueBefore + externalAmount` is verified — missing/fabricated boundary data
 *   is rejected, never assumed.
 * - A known zero closing value is a real outcome (final factor 0 => total -100%), not missing
 *   data; zero-capital -> zero-capital sub-periods are NEUTRAL (factor 1); zero-to-nonzero or
 *   negative or sign-flipping bases are rejected (chaining undefined there).
 * - Ordered same-date boundaries may contain real market changes between their independently
 *   observed before/after values. A daily-close snapshot cannot substitute for those observations.
 * - Dividend reinvestment is internal and must NOT be passed as a boundary; this API never
 *   classifies flows or infers account ownership.
 */
object TwrCalc {

    /** Division precision for every sub-period growth factor. */
    val MATH_CONTEXT: MathContext = MathContext.DECIMAL128

    private val ONE: BigDecimal = BigDecimal.ONE

    fun timeWeightedReturn(input: TwrInput): TwrResult {
        if (input.startDate >= input.endDate) {
            return TwrResult.Rejected(
                TwrRejection.ZERO_TIMESPAN,
                "startDate ${input.startDate} must be strictly before endDate ${input.endDate}.",
            )
        }
        val sorted = input.boundaries.sortedWith(compareBy({ it.date }, { it.sequence }))
        if (sorted != input.boundaries) {
            return TwrResult.Rejected(
                TwrRejection.BOUNDARY_UNORDERED_OR_DUPLICATE,
                "Boundaries must be listed in ascending (date, sequence) order; order is part of the contract.",
            )
        }
        if (sorted.map { it.flowId }.distinct().size != sorted.size) {
            return TwrResult.Rejected(
                TwrRejection.BOUNDARY_UNORDERED_OR_DUPLICATE,
                "Duplicate flowId in boundaries; every external flow needs a stable unique id.",
            )
        }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            if (a.date == b.date && a.sequence == b.sequence) {
                return TwrResult.Rejected(
                    TwrRejection.BOUNDARY_UNORDERED_OR_DUPLICATE,
                    "Duplicate boundary at (${b.date}, sequence=${b.sequence}).",
                )
            }
        }
        for (b in sorted) {
            if (b.date <= input.startDate || b.date >= input.endDate) {
                return TwrResult.Rejected(
                    TwrRejection.BOUNDARY_OUTSIDE_WINDOW,
                    "Boundary '${b.flowId}' at ${b.date} is outside the open window " +
                        "(${input.startDate}, ${input.endDate}).",
                )
            }
        }
        val currencies = buildList {
            add(input.initialValue.currency)
            add(input.closingValue.currency)
            sorted.forEach {
                add(it.valueBefore.currency)
                add(it.externalAmount.currency)
                add(it.valueAfter.currency)
            }
        }.distinct()
        if (currencies.size > 1) {
            return TwrResult.Rejected(
                TwrRejection.CURRENCY_MISMATCH,
                "TWR requires one currency across valuations and boundaries, found $currencies.",
            )
        }
        for (b in sorted) {
            if (b.valueAfter.compareTo(b.valueBefore + b.externalAmount) != 0) {
                return TwrResult.Rejected(
                    TwrRejection.BOUNDARY_AFTER_MISMATCH,
                    "Boundary '${b.flowId}': valueAfter ${b.valueAfter} != valueBefore " +
                        "${b.valueBefore} + external ${b.externalAmount}; missing or fabricated boundary data.",
                )
            }
        }

        var growth = ONE
        var prev = input.initialValue
        for (b in sorted) {
            val factor = growthFactor(prev, b.valueBefore)
                ?: return TwrResult.Rejected(
                    TwrRejection.ZERO_OR_SIGN_CHANGING_BASE,
                    "Sub-period ending at boundary '${b.flowId}' runs $prev -> ${b.valueBefore}: " +
                        "negative base, zero base with nonzero end, or a sign flip; " +
                        "geometric chaining is undefined.",
                )
            growth = growth.multiply(factor)
            prev = b.valueAfter
        }
        val closingFactor = growthFactor(prev, input.closingValue)
            ?: return TwrResult.Rejected(
                TwrRejection.ZERO_OR_SIGN_CHANGING_BASE,
                "Final sub-period runs $prev -> ${input.closingValue}: negative base, zero base with nonzero end " +
                    "or a sign flip; geometric chaining is undefined.",
            )
        growth = growth.multiply(closingFactor)
        return TwrResult.Rate(growth - ONE, sorted.size + 1)
    }

    /** Market-only growth factor for one sub-period; null where chaining is undefined. */
    private fun growthFactor(from: Money, to: Money): BigDecimal? {
        if (from.isNegative()) return null
        if (from.isZero()) {
            return if (to.isZero()) ONE else null
        }
        val factor = to.amount.divide(from.amount, MATH_CONTEXT)
        return if (factor.signum() < 0) null else factor
    }
}
