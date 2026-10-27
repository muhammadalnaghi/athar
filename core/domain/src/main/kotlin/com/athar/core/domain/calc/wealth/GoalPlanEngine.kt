package com.athar.core.domain.calc.wealth

import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalTarget
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Declared deterministic arithmetic for the goal solver (WM028-03). BigDecimal only —
 * no Float/Double money, no fractional powers, no floating-point logs.
 *
 * Conventions (all declared, all tested):
 * - Monthly rate from an annual percent: percent / 100 / 12, division rounded to
 *   [INTERNAL_SCALE] (12) HALF_EVEN once, then reused.
 * - Balance and annuity-accumulator recurrences step month-by-month; each step is
 *   rounded to at least [INTERNAL_SCALE] HALF_EVEN, preserving finer source money. This is the model definition, not an
 *   optimization: identical inputs always produce identical outputs.
 * - Money boundaries preserve standard currency units and meaningful source precision (in [GoalPlanCalc]).
 * - Compounding over many months uses exact BigDecimal integer powers only.
 */
internal object GoalPlanEngine {

    const val INTERNAL_SCALE = 12
    const val TECHNICAL_HORIZON_LIMIT_MONTHS = 1200

    val SOLVER_MIN_NOMINAL_APR_PERCENT = BigDecimal("-99.99")
    val SOLVER_MAX_NOMINAL_APR_PERCENT = BigDecimal("1000")

    private val ONE = BigDecimal.ONE
    private val MONTHS_PER_YEAR = BigDecimal(12)
    private val HUNDRED = BigDecimal("100")

    /** Net monthly growth rate from a stated APR and fee: (apr - fee) / 100 / 12. */
    fun netMonthlyAprRate(assumptions: GoalAssumptions.FixedNominalApr): BigDecimal =
        assumptions.annualAprPercent
            .subtract(assumptions.annualFeePercent)
            .divide(HUNDRED.multiply(MONTHS_PER_YEAR), INTERNAL_SCALE, RoundingMode.HALF_EVEN)

    /** Monthly inflation rate: percent / 100 / 12. */
    fun monthlyInflationRate(annualInflationPercent: BigDecimal): BigDecimal =
        annualInflationPercent.divide(HUNDRED.multiply(MONTHS_PER_YEAR), INTERNAL_SCALE, RoundingMode.HALF_EVEN)

    /** Monthly growth factor at the declared solver lower APR bound. */
    fun solverGrowthFactorLow(): BigDecimal =
        ONE.add(SOLVER_MIN_NOMINAL_APR_PERCENT.divide(HUNDRED.multiply(MONTHS_PER_YEAR), INTERNAL_SCALE, RoundingMode.HALF_EVEN))

    /** Monthly growth factor at the declared solver upper APR bound. */
    fun solverGrowthFactorHigh(): BigDecimal =
        ONE.add(SOLVER_MAX_NOMINAL_APR_PERCENT.divide(HUNDRED.multiply(MONTHS_PER_YEAR), INTERNAL_SCALE, RoundingMode.HALF_EVEN))

    /** Monthly growth factor: 1 for cash-only, else 1 + net monthly APR rate. */
    fun monthlyGrowthFactor(assumptions: GoalAssumptions): BigDecimal = when (assumptions) {
        is GoalAssumptions.CashOnly -> ONE
        is GoalAssumptions.FixedNominalApr -> ONE.add(netMonthlyAprRate(assumptions))
    }

    /**
     * Monthly growth factor from a stated nominal APR percent (fee-free path):
     * 1 + apr / 100 / 12 at [INTERNAL_SCALE]. Mirrors [netMonthlyAprRate] so a solved
     * rate round-trips through the projection model exactly as a user-stated one would.
     */
    fun growthFactorFromAprPercent(aprPercent: BigDecimal): BigDecimal =
        ONE.add(aprPercent.divide(HUNDRED.multiply(MONTHS_PER_YEAR), INTERNAL_SCALE, RoundingMode.HALF_EVEN))

    /**
     * One month of the balance recurrence with per-step scale-12 rounding:
     * END: `balance * g + c`; BEGINNING: `(balance + c) * g`.
     */
    fun stepBalance(
        balance: BigDecimal,
        contribution: BigDecimal,
        growthFactor: BigDecimal,
        timing: ContributionTiming,
    ): BigDecimal = when (timing) {
        ContributionTiming.END_OF_MONTH -> balance.multiply(growthFactor).add(contribution)
        ContributionTiming.BEGINNING_OF_MONTH -> balance.add(contribution).multiply(growthFactor)
    }.setScale(workingScale(balance, contribution), RoundingMode.HALF_EVEN)

    /**
     * One month of the annuity-accumulator recurrence (the future value at month m of
     * contributions of 1): END: `a * g + 1`; BEGINNING: `(a + 1) * g`. With g = 1 this
     * converges to the plain month count, so cash-only needs no special case.
     */
    fun stepAnnuity(
        accumulator: BigDecimal,
        growthFactor: BigDecimal,
        timing: ContributionTiming,
    ): BigDecimal = when (timing) {
        ContributionTiming.END_OF_MONTH -> accumulator.multiply(growthFactor).add(ONE)
        ContributionTiming.BEGINNING_OF_MONTH -> accumulator.add(ONE).multiply(growthFactor)
    }.setScale(INTERNAL_SCALE, RoundingMode.HALF_EVEN)

    /**
     * Balance after [months] applications of [stepBalance], starting from
     * [currentAllocation] (working scale is at least12, preserving finer input).
     */
    fun projectBalance(
        currentAllocation: BigDecimal,
        monthlyContribution: BigDecimal,
        growthFactor: BigDecimal,
        months: Int,
        timing: ContributionTiming,
    ): BigDecimal {
        var balance = currentAllocation.setScale(workingScale(currentAllocation, monthlyContribution), RoundingMode.HALF_EVEN)
        repeat(months) {
            balance = stepBalance(balance, monthlyContribution, growthFactor, timing)
        }
        return balance
    }

    /** Annuity accumulator after [months] applications of [stepAnnuity] from zero. */
    fun annuityAccumulator(
        growthFactor: BigDecimal,
        months: Int,
        timing: ContributionTiming,
    ): BigDecimal {
        var accumulator = BigDecimal.ZERO
        repeat(months) {
            accumulator = stepAnnuity(accumulator, growthFactor, timing)
        }
        return accumulator
    }

    /**
     * Nominal target at [month]: the stated amount for [GoalTarget.Nominal], or the
     * amount inflated by exact BigDecimal integer power for [GoalTarget.TodayPrice].
     * Rounded to at least scale12 HALF_EVEN, preserving finer target money, for deterministic comparisons.
     */
    fun nominalTargetAt(target: GoalTarget, month: Int): BigDecimal = when (target) {
        is GoalTarget.Nominal -> target.amount.amount
        is GoalTarget.TodayPrice -> target.amount.amount
            .multiply(ONE.add(monthlyInflationRate(target.annualInflationPercent)).pow(month))
            .setScale(workingScale(target.amount.amount), RoundingMode.HALF_EVEN)
    }

    /**
     * Bounded bisection on the monthly growth factor for the required-APR solve.
     * Precondition checked by the caller: F(months; gLow) < target <= F(months; gHigh),
     * which holds because F is non-decreasing in g for non-negative contributions.
     * Fixed [BISECTION_ITERATIONS] iterations, always keeping the bracket invariant;
     * returns the upper end of the final bracket (F >= target there). Deterministic.
     */
    fun bisectGrowthFactor(
        currentAllocation: BigDecimal,
        monthlyContribution: BigDecimal,
        nominalTarget: BigDecimal,
        months: Int,
        timing: ContributionTiming,
        growthFactorLow: BigDecimal,
        growthFactorHigh: BigDecimal,
    ): BigDecimal {
        var low = growthFactorLow
        var high = growthFactorHigh
        repeat(BISECTION_ITERATIONS) {
            val mid = low.add(high).divide(TWO, INTERNAL_SCALE, RoundingMode.HALF_EVEN)
            val reached = projectBalance(currentAllocation, monthlyContribution, mid, months, timing)
                .compareTo(nominalTarget) >= 0
            if (reached) high = mid else low = mid
        }
        return high
    }

    /** Nominal APR percent (scale 4 HALF_EVEN) from a monthly growth factor. */
    fun aprPercentFromGrowthFactor(growthFactor: BigDecimal): BigDecimal =
        growthFactor.subtract(ONE)
            .multiply(MONTHS_PER_YEAR)
            .multiply(HUNDRED)
            .setScale(RATE_OUTPUT_SCALE, RoundingMode.HALF_EVEN)

    /** Rate precision is the baseline, never permission to discard finer source money. */
    fun workingScale(vararg amounts: BigDecimal): Int = maxOf(
        INTERNAL_SCALE,
        amounts.maxOfOrNull { it.stripTrailingZeros().scale().coerceAtLeast(0) } ?: 0,
    )

    private val TWO = BigDecimal(2)
    private const val BISECTION_ITERATIONS = 60
    private const val RATE_OUTPUT_SCALE = 4
}
