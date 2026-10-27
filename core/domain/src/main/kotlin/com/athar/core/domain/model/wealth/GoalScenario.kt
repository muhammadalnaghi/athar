package com.athar.core.domain.model.wealth

import com.athar.core.common.money.Money
import java.math.BigDecimal

/**
 * When within a month a contribution is credited, and therefore whether it earns that
 * month's growth. Explicit by contract (WM028-03): the solver never assumes a timing.
 *
 * - [BEGINNING_OF_MONTH] — annuity-due: contribution is added before growth
 *   (`(balance + c) * g`), so it earns the month's growth.
 * - [END_OF_MONTH] — ordinary annuity: contribution is added after growth
 *   (`balance * g + c`), so it does not.
 */
enum class ContributionTiming {
    BEGINNING_OF_MONTH,
    END_OF_MONTH,
}

/**
 * User-stated growth assumptions for a goal scenario. Sealed on purpose: there is no
 * default investment return anywhere in the goal solver. A caller either declares the
 * model cash-only, or states a nominal APR (and fee) explicitly. (WM028-03)
 */
sealed interface GoalAssumptions {

    /** Zero-return model. Growth factor is exactly 1 every month. */
    data object CashOnly : GoalAssumptions

    /**
     * Nominal annual percentage rate (APR), converted with the declared convention
     * monthlyRate = aprPercent / 100 / 12 and compounded monthly.
     *
     * Fee basis (declared): [annualFeePercent] is a nominal annual drag netted from the
     * APR before monthly conversion — monthly rate = (aprPercent - feePercent) / 100 / 12.
     * It is NOT a per-period deduction on the balance (no AUM-style compounding of fees).
     * Both values are mandatory: no defaults, so no return can be implied.
     *
     * The net monthly rate must stay above -100% ( APR percent above -1200 ) so the
     * monthly growth factor remains positive.
     */
    data class FixedNominalApr(
        val annualAprPercent: BigDecimal,
        val annualFeePercent: BigDecimal,
    ) : GoalAssumptions
}

/**
 * What the user is aiming at, in one of two explicitly separate price conventions.
 *
 * - [Nominal] — the target amount is already a future nominal amount.
 * - [TodayPrice] — the target is expressed in today's prices and is inflated to a
 *   nominal amount with the declared convention: monthly inflation rate =
 *   annualInflationPercent / 100 / 12, compounded monthly via exact BigDecimal
 *   integer powers. Inflation is never silently netted against growth.
 */
sealed interface GoalTarget {
    val amount: Money

    data class Nominal(override val amount: Money) : GoalTarget

    data class TodayPrice(
        override val amount: Money,
        val annualInflationPercent: BigDecimal,
    ) : GoalTarget
}

/**
 * One goal-planning scenario: named, with explicit target, current allocation,
 * contribution, timing and assumptions. Currencies are the [Money] currencies and must
 * match across [target], [currentAllocation] and [monthlyContribution] — mismatches are
 * reported as invalid inputs, never coerced.
 *
 * Allocation overlap between several goals is deliberately out of scope here: callers
 * compose allocations; the solver never shares or dedupes them. (WM028-03)
 */
data class GoalScenario(
    val name: String,
    val target: GoalTarget,
    val currentAllocation: Money,
    val monthlyContribution: Money,
    val assumptions: GoalAssumptions,
    val contributionTiming: ContributionTiming,
)
