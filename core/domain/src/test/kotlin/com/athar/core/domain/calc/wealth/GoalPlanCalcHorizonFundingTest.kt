package com.athar.core.domain.calc.wealth

import com.athar.core.common.money.Money
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalNotReachableReason
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.GoalProjection
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * WM028-03 F3B regression oracles: the required-APR solve is GROSS of the declared fee
 * and round-trips through the full declared model; fixed-horizon `AlreadyFunded` is
 * horizon-aware (current-day funding alone is never sufficient). Expected values
 * computed independently with exact decimal arithmetic (WM028-03 amendment).
 */
class GoalPlanCalcHorizonFundingTest {

    @Test
    fun `required apr is gross of the declared fee and round-trips through the real model`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("1002")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = fees(apr = "3", fee = "1"),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        // Independent oracle: the fee-free twin answer is 0.1999%, but re-projecting
        // that through FixedNominalApr(0.1999, fee 1) yields 992.03 — a shortfall. The
        // gross answer must carry the declared 1pp fee: 1200*(1.002^(1/12) - 1) + 1
        // quantizes to 1.1998 (still short by verification) then verifies at 1.1999.
        val rate = available(outcome)
        assertThat(rate.nominalAprPercent).isEqualToIgnoringScale("1.1999")

        val projection = projectWithRate(target = "1002", current = "1000", apr = rate.nominalAprPercent, fee = "1", months = 12)
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `fee leaving no positive growth gross rate reports beyond solver bounds`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("5000")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = fees(apr = "1201", fee = "2400"),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        // Valid declared model (net monthly rate stays above -100%), but netting a
        // 2400% fee keeps the monthly growth factor non-positive at every gross rate
        // within the -99.99..1000 solver bounds: typed out-of-bounds, never a rate.
        assertThat(notReachable(outcome)).isEqualTo(GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS)
    }

    @Test
    fun `fee dominated declared model still solves a verified gross rate with positive growth`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("30")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = fees(apr = "200", fee = "1300"),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        // The fee-free solver floor (-99.99%) is NOT the answer: netting a 1300% fee
        // makes it a non-positive factor. The lower bound must clamp up to positive
        // growth and the gross solve must verify through the declared-fee model.
        val rate = available(outcome)
        assertThat(rate.nominalAprPercent).isAtLeast(BigDecimal.ZERO)
        assertThat(rate.nominalAprPercent).isAtMost(BigDecimal("1000"))
        val projection = projectWithRate(target = "30", current = "1000", apr = rate.nominalAprPercent, fee = "1300", months = 12)
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `inflating horizon target defeats today funded cash allocation for contributions`() {
        val outcome = GoalPlanCalc.requiredMonthlyContribution(
            scenario(
                target = GoalTarget.TodayPrice(Money.of("500"), BigDecimal("10")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = GoalAssumptions.CashOnly,
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 120,
        )

        // Today's 1000 covers today's 500, but the horizon target is
        // 500*(1 + 0.10/12)^120 = 1353.520745377433 while cash stays 1000:
        // (1353.520745... - 1000) / 120 = 2.9460... -> cent-ceiling 2.95, verified.
        val contribution = available(outcome)
        assertThat(contribution.nominalTargetAtHorizon.amount).isEqualToIgnoringScale("1353.52")
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("2.95")
    }

    @Test
    fun `negative net growth defeats today funded allocation for contributions`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("900")),
            current = Money.of("1000"),
            contribution = Money.zero(),
            assumptions = fees(apr = "0", fee = "5"),
            timing = ContributionTiming.END_OF_MONTH,
        )

        // Today's 1000 covers today's 900, but a 5% fee on a 0% APR erodes the balance
        // below 900 at the horizon: a real top-up must be solved and verified.
        val contribution = available(GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths = 120))
        assertThat(contribution.monthlyContribution.amount.signum()).isGreaterThan(0)

        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(monthlyContribution = contribution.monthlyContribution),
                horizonMonths = 120,
            ),
        )
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `negative net growth still requires solving the apr not a today shortcut`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.Nominal(Money.of("900")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = fees(apr = "0", fee = "5"),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 120,
        )

        // Not AlreadyFunded from today's allocation: the declared model loses ground,
        // so the future question must be solved and the answer verified with the fee.
        val rate = available(outcome)
        val projection = projectWithRate(target = "900", current = "1000", apr = rate.nominalAprPercent, fee = "5", months = 120)
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `cash only required apr is the declared zero fee counterfactual on the horizon question`() {
        val outcome = GoalPlanCalc.requiredNominalApr(
            scenario(
                target = GoalTarget.TodayPrice(Money.of("900"), BigDecimal("10")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = GoalAssumptions.CashOnly,
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 120,
        )

        // Today's 1000 covers today's 900, but the inflating horizon target
        // 900*(1 + 0.10/12)^120 = 2436.34 is far beyond flat cash: solve the future
        // question as the declared zero-fee counterfactual, never report funded.
        val rate = available(outcome)
        assertThat(rate.nominalAprPercent).isGreaterThan(BigDecimal.ZERO)
        val projection = projectWithRate(target = "900", current = "1000", apr = rate.nominalAprPercent, fee = "0", months = 120)
        assertThat(projection.futureAmount.amount).isAtLeast(projection.nominalTargetAtHorizon.amount)
    }

    @Test
    fun `genuinely funded cash goals still report already funded on both solves`() {
        val funded = scenario(
            target = GoalTarget.Nominal(Money.of("1500")),
            current = Money.of("2000"),
            contribution = Money.zero(),
            assumptions = GoalAssumptions.CashOnly,
            timing = ContributionTiming.END_OF_MONTH,
        )

        assertThat(GoalPlanCalc.requiredMonthlyContribution(funded, horizonMonths = 60))
            .isEqualTo(GoalPlanOutcome.AlreadyFunded)
        assertThat(GoalPlanCalc.requiredNominalApr(funded, horizonMonths = 60))
            .isEqualTo(GoalPlanOutcome.AlreadyFunded)
    }

    /** Full declared-fee round-trip: project a FixedNominalApr scenario at the rate. */
    private fun projectWithRate(
        target: String,
        current: String,
        apr: BigDecimal,
        fee: String,
        months: Int,
    ): GoalProjection = available(
        GoalPlanCalc.projectFutureAmount(
            scenario(
                target = GoalTarget.Nominal(Money.of(target)),
                current = Money.of(current),
                contribution = Money.zero(),
                assumptions = fees(apr = apr.toPlainString(), fee = fee),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = months,
        ),
    )

    private fun fees(apr: String, fee: String) = GoalAssumptions.FixedNominalApr(
        annualAprPercent = BigDecimal(apr),
        annualFeePercent = BigDecimal(fee),
    )

    private fun scenario(
        target: GoalTarget,
        current: Money,
        contribution: Money,
        assumptions: GoalAssumptions,
        timing: ContributionTiming,
    ): GoalScenario = GoalScenario(
        name = "f3b-oracle",
        target = target,
        currentAllocation = current,
        monthlyContribution = contribution,
        assumptions = assumptions,
        contributionTiming = timing,
    )
}
