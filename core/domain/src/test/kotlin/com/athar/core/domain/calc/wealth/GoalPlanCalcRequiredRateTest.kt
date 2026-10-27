package com.athar.core.domain.calc.wealth

import com.athar.core.common.money.Money
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalNotReachableReason
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Numeric oracles for the bounded required-APR solve. Expected values computed
 * independently with exact decimal arithmetic (docs/specs/WM028-03).
 */
class GoalPlanCalcRequiredRateTest {

    @Test
    fun `required apr for twelve months of ten percent total growth`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("1100")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        val rate = available(outcome)
        assertThat(rate.nominalAprPercent).isEqualToIgnoringScale("9.5690")
        assertThat(rate.method).isEqualTo(GoalPlanCalc.METHOD)

        // Round-trip: projecting at the solved rate must land on the target.
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1100")),
                    current = Money.of("1000"),
                    contribution = Money.zero(),
                    assumptions = GoalAssumptions.FixedNominalApr(
                        annualAprPercent = rate.nominalAprPercent,
                        annualFeePercent = BigDecimal("0"),
                    ),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 12,
            ),
        )
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("1100.00")
    }

    @Test
    fun `required rate above the solver bound is not extrapolated`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("1000000000")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        val reason = notReachable(outcome)
        assertThat(reason).isEqualTo(GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS)
    }

    @Test
    fun `need below the solver floor returns the verified floor rate not a shortfall`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("90")),
                current = Money.zero(),
                contribution = Money.of("100"),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 1,
        )

        // F(1) = 100 >= 90 at every allowed rate: the need is below the lower bound,
        // so the floor itself is the conservative sufficient (verified) rate —
        // genuinely reachable, not a BEYOND_SOLVER_RATE_BOUNDS shortfall.
        val rate = available(outcome)
        assertThat(rate.nominalAprPercent).isEqualToIgnoringScale("-99.99")

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("90")),
                    current = Money.zero(),
                    contribution = Money.of("100"),
                    assumptions = GoalAssumptions.FixedNominalApr(
                        annualAprPercent = rate.nominalAprPercent,
                        annualFeePercent = BigDecimal("0"),
                    ),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 1,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `required rate reports already funded without solving`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("5000")),
                current = Money.of("5000"),
                contribution = Money.zero(),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        assertThat(outcome).isEqualTo(GoalPlanOutcome.AlreadyFunded)
    }

    private fun apr12() = GoalAssumptions.FixedNominalApr(
        annualAprPercent = BigDecimal("12"),
        annualFeePercent = BigDecimal("0"),
    )

    private fun scenario(
        target: GoalTarget,
        current: Money,
        contribution: Money,
        assumptions: GoalAssumptions,
        timing: ContributionTiming,
    ): GoalScenario = GoalScenario(
        name = "oracle",
        target = target,
        currentAllocation = current,
        monthlyContribution = contribution,
        assumptions = assumptions,
        contributionTiming = timing,
    )
}
