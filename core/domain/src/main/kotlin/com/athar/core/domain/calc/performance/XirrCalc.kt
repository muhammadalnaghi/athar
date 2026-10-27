package com.athar.core.domain.calc.performance

import ch.obermuhlner.math.big.BigDecimalMath
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.XirrAmbiguity
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrRejection
import com.athar.core.domain.model.performance.XirrResult
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * XIRR — annualized money-weighted return of a dated investor cash-flow series.
 *
 * Solves `sum(cf_i * (1+r)^(-days_i/365)) = 0` in exact [BigDecimal] arithmetic:
 * day count is ACTUAL DAYS over a 365-day year (leap days counted, `LocalDate.daysUntil`),
 * fractional exponents via [BigDecimalMath.pow] (`ch.obermuhlner:big-math`, MIT) in
 * [MATH_CONTEXT]. No `double` conversion ever touches a money amount or the rate.
 *
 * Search is BOUNDED to `r in [LOWER_BOUND, UPPER_BOUND]`. Exact same-date netting with at
 * most one chronological sign variation permits at most one positive discount-factor root;
 * endpoint evaluation and the existing bisection suffice for these conventional series.
 * Non-conventional series retain the 4096-point scan and all ambiguity policies.
 * Honesty contract (spec WM028-08): a grid sign change proves a root's EXISTENCE, never uniqueness
 * or exhaustiveness — multiple sign changes surface [XirrResult.Ambiguous] with candidates and
 * that caveat, never a silently picked rate; no sign change is [XirrResult.Rejected]
 * NO_SIGN_CHANGE, which is a bounded-search miss, NOT a proof that no rate exists (e.g. all
 * same-sign flows, or the only root outside the bounds).
 *
 * Returns a RATIO (0.10 = +10%/yr); percent conversion is a presentation concern.
 * Valuation convention: [XirrInput.openingValuation] folds in as a negative flow at its date,
 * [XirrInput.terminalValuation] as a positive flow at its date; valuations are nonnegative
 * states and known zero is valid. Caller-supplied zero external flows remain invalid.
 */
object XirrCalc {

    /** Explicit precision for every pow/divide; DECIMAL128 per the WM-028 dependency ruling. */
    val MATH_CONTEXT: MathContext = MathContext.DECIMAL128

    /** Lower search bound: keeps `1 + r` strictly positive (NPV defined). */
    private val LOWER_BOUND: BigDecimal = BigDecimal("-0.999999999999")

    /** Upper search bound: annualized ratios above +10,000% are out of the bounded domain. */
    private val UPPER_BOUND: BigDecimal = BigDecimal("100")

    private const val SCAN_POINTS = 4096
    private const val MAX_BISECTION_STEPS = 200

    /** Bisection stops once the bracket is this wide; the midpoint is then within 5e-31 of the root. */
    private val TARGET_BRACKET_WIDTH: BigDecimal = BigDecimal("1e-30")

    /** Exact halving divisor — decimal division by 2 always terminates, so `divide(TWO)` never rounds. */
    private val TWO: BigDecimal = BigDecimal(2)
    private const val RATE_SCALE = 12
    private val DAYS_PER_YEAR: BigDecimal = BigDecimal(365)
    private val ONE: BigDecimal = BigDecimal.ONE
    private const val OPENING_ID = "wm028-opening-valuation"
    private const val TERMINAL_ID = "wm028-terminal-valuation"

    fun annualRate(input: XirrInput): XirrResult {
        val flows = input.flows.toList()
        val valuations = listOfNotNull(input.openingValuation, input.terminalValuation)
        val amounts = flows.map { it.amount } + valuations.map { it.value }
        if (amounts.isEmpty()) {
            return XirrResult.Rejected(XirrRejection.EMPTY_INPUT, "No cash flows or observed valuations.")
        }
        val currency = amounts.first().currency
        if (amounts.any { it.currency != currency }) {
            return XirrResult.Rejected(
                XirrRejection.CURRENCY_MISMATCH,
                "All flows and valuation states must share one currency, including known zero states.",
            )
        }
        if (valuations.any { it.value.isNegative() }) {
            return XirrResult.Rejected(
                XirrRejection.NEGATIVE_VALUATION,
                "Opening and terminal valuations are nonnegative observed states, not signed cash flows.",
            )
        }
        flows.firstOrNull { it.amount.isZero() }?.let {
            return XirrResult.Rejected(
                XirrRejection.ZERO_AMOUNT,
                "External flow '${it.id}' has no sign; a known zero valuation is a different input type.",
            )
        }
        val series = buildList {
            input.openingValuation?.let {
                add(DatedCashFlow(OPENING_ID, it.date, -it.value))
            }
            addAll(flows)
            input.terminalValuation?.let {
                add(DatedCashFlow(TERMINAL_ID, it.date, it.value))
            }
        }
        val sorted = series.sortedBy { it.date }
        // Same-date netting is only for the sign-pattern proof; retain every source flow.
        val chronologicalSigns = sorted.groupBy { it.date }.values
            .map { sameDate -> sameDate.fold(BigDecimal.ZERO) { total, flow -> total + flow.amount.amount }.signum() }
            .filter { it != 0 }
        if (chronologicalSigns.isEmpty()) {
            return XirrResult.Ambiguous(
                XirrAmbiguity.EVERY_RATE_IS_A_ROOT,
                emptyList(),
                "Exact net cash flow is zero on every date: NPV is identically zero, so no unique return exists.",
            )
        }
        val nonConventional = chronologicalSigns.zipWithNext().count { (a, b) -> a != b } > 1
        val firstDate = sorted.first().date
        val timed = sorted.map { (it.date.toEpochDays() - firstDate.toEpochDays()) to it.amount.amount }
        if (timed.maxOf { it.first } == 0) {
            val net = timed.fold(BigDecimal.ZERO) { acc, (_, amount) -> acc + amount }
            return if (net.signum() == 0) {
                XirrResult.Ambiguous(
                    XirrAmbiguity.EVERY_RATE_IS_A_ROOT,
                    emptyList(),
                    "All flows fall on $firstDate and net to zero: every rate in ($LOWER_BOUND, $UPPER_BOUND] is a root.",
                )
            } else {
                XirrResult.Rejected(
                    XirrRejection.ZERO_TIMESPAN,
                    "All flows fall on $firstDate and net to ${net.toPlainString()} $currency; with zero timespan no rate can produce a nonzero net.",
                )
            }
        }

        fun npv(r: BigDecimal): BigDecimal {
            val base = ONE + r
            var acc = BigDecimal.ZERO
            for ((days, amount) in timed) {
                val t = BigDecimal(days).divide(DAYS_PER_YEAR, MATH_CONTEXT)
                acc = acc.add(amount.multiply(BigDecimalMath.pow(base, -t, MATH_CONTEXT)))
            }
            return acc
        }

        val roots: List<BigDecimal> = try {
            if (nonConventional) scanAndSolve(::npv) else solveConventional(::npv)
        } catch (e: ArithmeticException) {
            return XirrResult.Rejected(XirrRejection.SOLVER_FAILED, "Bounded search failed: ${e.message}")
        }
        val searchMethod = if (nonConventional) "$SCAN_POINTS-point scan" else "conventional endpoint bracket"
        return when {
            roots.size == 1 && nonConventional ->
                XirrResult.Ambiguous(
                    XirrAmbiguity.UNIQUENESS_UNPROVEN,
                    roots,
                    "One candidate found in the bounded scan [$LOWER_BOUND, $UPPER_BOUND], but the " +
                        "non-conventional cash-flow sign pattern permits additional roots between " +
                        "scan points or outside the bounds. No unique annual rate is asserted.",
                )
            roots.size == 1 -> XirrResult.Rate(roots.single())
            roots.isEmpty() ->
                XirrResult.Rejected(
                    XirrRejection.NO_SIGN_CHANGE,
                    "NPV keeps one sign across the bounded window [$LOWER_BOUND, $UPPER_BOUND] " +
                        "($searchMethod). Typical causes: all flows share one sign, or the " +
                        "only rate lies outside the bounds. This is a bounded-search miss, not a proof " +
                        "that no rate exists.",
                )
            else ->
                XirrResult.Ambiguous(
                    XirrAmbiguity.MULTIPLE_SIGN_CHANGES,
                    roots,
                    "${roots.size} NPV sign changes in the bounded scan [$LOWER_BOUND, $UPPER_BOUND]. " +
                        "Each candidate is a proven root of the scanned grid, but a finite scan cannot " +
                        "prove uniqueness or exhaustiveness (non-conventional series); no single rate is " +
                        "asserted.",
                )
        }
    }

    /** Reuse the original evaluator and bisection only when the net chronological signs certify at most one root. */
    private fun solveConventional(npv: (BigDecimal) -> BigDecimal): List<BigDecimal> {
        val low = npv(LOWER_BOUND)
        val high = npv(UPPER_BOUND)
        return when {
            // Preserve fail-closed ambiguity if finite-precision evaluations cannot separate the bounds.
            low.signum() == 0 && high.signum() == 0 -> scanAndSolve(npv)
            low.signum() == 0 -> listOf(LOWER_BOUND.setScale(RATE_SCALE, RoundingMode.HALF_EVEN))
            high.signum() == 0 -> listOf(UPPER_BOUND.setScale(RATE_SCALE, RoundingMode.HALF_EVEN))
            low.signum() != high.signum() -> listOf(bisect(npv, LOWER_BOUND, UPPER_BOUND))
            else -> emptyList()
        }
    }

    /** Grid sign scan over the bounded window, then one bisection per bracketing interval. */
    private fun scanAndSolve(npv: (BigDecimal) -> BigDecimal): List<BigDecimal> {
        val step = UPPER_BOUND.subtract(LOWER_BOUND).divide(BigDecimal(SCAN_POINTS), MATH_CONTEXT)
        val exactZeroAt = mutableListOf<BigDecimal>()
        val brackets = mutableListOf<Pair<BigDecimal, BigDecimal>>()
        var prevR = LOWER_BOUND
        var prevV = npv(prevR)
        if (prevV.signum() == 0) {
            exactZeroAt.add(prevR)
        }
        for (i in 1..SCAN_POINTS) {
            val r = if (i == SCAN_POINTS) UPPER_BOUND else LOWER_BOUND.add(step.multiply(BigDecimal(i)))
            val v = npv(r)
            when {
                v.signum() == 0 -> exactZeroAt.add(r)
                prevV.signum() != 0 && v.signum() != prevV.signum() -> brackets.add(prevR to r)
            }
            prevR = r
            prevV = v
        }
        val roots = mutableListOf<BigDecimal>()
        exactZeroAt.forEach { roots.add(it.setScale(RATE_SCALE, RoundingMode.HALF_EVEN)) }
        brackets.forEach { roots.add(bisect(npv, it.first, it.second)) }
        return roots.sortedWith(compareBy { it })
    }

    /**
     * Bracketed bisection on exact decimal arithmetic: midpoints are computed with
     * `divide(TWO)` (never rounds — decimal halving always terminates) and the loop
     * terminates on an explicit absolute bracket width, so the returned midpoint is
     * within [TARGET_BRACKET_WIDTH]/2 of the true root before the final scale-12
     * presentation rounding. The sign invariant (fLo keeps lo's sign) is unchanged.
     */
    private fun bisect(npv: (BigDecimal) -> BigDecimal, loIn: BigDecimal, hiIn: BigDecimal): BigDecimal {
        var lo = loIn
        var hi = hiIn
        var fLo = npv(lo)
        repeat(MAX_BISECTION_STEPS) {
            if (hi.subtract(lo).abs() <= TARGET_BRACKET_WIDTH) {
                return lo.add(hi).divide(TWO).setScale(RATE_SCALE, RoundingMode.HALF_EVEN)
            }
            val mid = lo.add(hi).divide(TWO)
            val fMid = npv(mid)
            if (fMid.signum() == 0) {
                return mid.setScale(RATE_SCALE, RoundingMode.HALF_EVEN)
            }
            if ((fMid.signum() > 0) == (fLo.signum() > 0)) {
                lo = mid
                fLo = fMid
            } else {
                hi = mid
            }
        }
        return lo.add(hi).divide(TWO).setScale(RATE_SCALE, RoundingMode.HALF_EVEN)
    }
}
