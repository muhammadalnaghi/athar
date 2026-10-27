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
 * Rounding-safety round-trip regressions (028-F3): every solved contribution and rate
 * must actually fund the nominal target when the forward model is evaluated at the
 * returned value. Quantization to money scale (2, HALF_EVEN boundary in `Money`) and
 * rate scale (4) may only ever leave a small verified excess — never a shortfall.
 * Expected values computed independently with exact decimal arithmetic.
 */
class GoalPlanCalcRoundingSafetyTest {

    @Test
    fun `cash only contribution rounds up so the projection funds the target`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("300.01")),
            current = Money.zero(),
            assumptions = GoalAssumptions.CashOnly,
            timing = ContributionTiming.END_OF_MONTH,
        )
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths = 3),
        )

        // 300.01 / 3 = 100.0033… : HALF_EVEN cents would return 100.00 and fall short.
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("100.01")

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(monthlyContribution = contribution.monthlyContribution),
                horizonMonths = 3,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
        // Cent granularity: a small excess, not an exact landing — and the solver says so.
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("300.03")
    }

    @Test
    fun `beginning timing contribution rounds up so the projection funds the target`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("300.01")),
            current = Money.zero(),
            assumptions = GoalAssumptions.CashOnly,
            timing = ContributionTiming.BEGINNING_OF_MONTH,
        )
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths = 3),
        )

        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("100.01")

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(monthlyContribution = contribution.monthlyContribution),
                horizonMonths = 3,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("300.03")
    }

    @Test
    fun `positive apr contribution rounds up to a funding amount`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("999.90")),
            current = Money.zero(),
            assumptions = apr12(),
            timing = ContributionTiming.END_OF_MONTH,
        )
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths = 12),
        )

        // raw 78.8409… : HALF_EVEN cents (78.84) projects to 999.89 < 999.90.
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("78.85")

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(monthlyContribution = contribution.monthlyContribution),
                horizonMonths = 12,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `negative apr contribution rounds up to a funding amount`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("100")),
            current = Money.zero(),
            assumptions = GoalAssumptions.FixedNominalApr(
                annualAprPercent = BigDecimal("-6"),
                annualFeePercent = BigDecimal("0"),
            ),
            timing = ContributionTiming.END_OF_MONTH,
        )
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths = 12),
        )

        // raw 8.5649… : HALF_EVEN cents (8.56) projects to 99.94 < 100.
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("8.57")

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(monthlyContribution = contribution.monthlyContribution),
                horizonMonths = 12,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `rate that rounds down at four decimals is bumped until it funds`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("1002")),
            current = Money.of("1000"),
            assumptions = apr12(),
            timing = ContributionTiming.END_OF_MONTH,
        )
        val rate = available(
            GoalPlanCalc.requiredNominalApr(scenario, horizonMonths = 12),
        )

        // Bisection root APR rounds at scale 4 to 0.1998, which projects to
        // 1001.999830… < 1002; the conservative answer is one step up: 0.1999.
        assertThat(rate.nominalAprPercent).isEqualToIgnoringScale("0.1999")

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(
                    assumptions = GoalAssumptions.FixedNominalApr(
                        annualAprPercent = rate.nominalAprPercent,
                        annualFeePercent = BigDecimal("0"),
                    ),
                ),
                horizonMonths = 12,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `contributions alone funding the target return the verified floor rate`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("90")),
            current = Money.zero(),
            assumptions = apr12(),
            timing = ContributionTiming.END_OF_MONTH,
            contribution = Money.of("100"),
        )
        val outcome = GoalPlanCalc.requiredNominalApr(scenario, horizonMonths = 1)

        // F(1) = 100 >= 90 at every allowed rate: the need is below the solver floor,
        // so the floor itself is the conservative sufficient rate — genuinely reachable,
        // not a BEYOND_SOLVER_RATE_BOUNDS shortfall.
        val rate = available(outcome)
        assertThat(rate.nominalAprPercent).isEqualToIgnoringScale("-99.99")
        assertThat(rate.nominalAprPercent.scale()).isEqualTo(4)

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(
                    assumptions = GoalAssumptions.FixedNominalApr(
                        annualAprPercent = rate.nominalAprPercent,
                        annualFeePercent = BigDecimal("0"),
                    ),
                ),
                horizonMonths = 1,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `required rate above the upper bound remains genuinely unreachable`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("1000000000")),
                current = Money.of("1000"),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        val reason = notReachable(outcome)
        assertThat(reason).isEqualTo(GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS)
    }

    private fun apr12() = GoalAssumptions.FixedNominalApr(
        annualAprPercent = BigDecimal("12"),
        annualFeePercent = BigDecimal("0"),
    )

    private fun scenario(
        target: GoalTarget,
        current: Money,
        assumptions: GoalAssumptions,
        timing: ContributionTiming,
        contribution: Money = Money.zero(),
    ): GoalScenario = GoalScenario(
        name = "rounding-safety",
        target = target,
        currentAllocation = current,
        monthlyContribution = contribution,
        assumptions = assumptions,
        contributionTiming = timing,
    )
}
