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
import java.time.YearMonth

/**
 * Numeric oracles for the required-contribution, required-months/date and required-APR
 * solves. Expected values computed independently with exact decimal arithmetic
 * (docs/specs/WM028-03).
 */
class GoalPlanCalcRequiredSolvesTest {

    @Test
    fun `one month end timing needs the full shortfall as contribution`() {
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1010")),
                    current = Money.zero(),
                    contribution = Money.zero(),
                    assumptions = apr12(),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 1,
            ),
        )

        // END: F(1) = c (principal zero), so c = 1010
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("1010.00")
        assertThat(contribution.method).isEqualTo(GoalPlanCalc.METHOD)
    }

    @Test
    fun `one month begin timing contribution grows before counting`() {
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1010")),
                    current = Money.zero(),
                    contribution = Money.zero(),
                    assumptions = apr12(),
                    timing = ContributionTiming.BEGINNING_OF_MONTH,
                ),
                horizonMonths = 1,
            ),
        )

        // BEGINNING: F(1) = c*1.01, so c = 1010/1.01 = 1000 exactly
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("1000.00")
    }

    @Test
    fun `inexact required contribution still lands on target when projected back`() {
        val scenario = scenario(
            target = GoalTarget.Nominal(Money.of("1500")),
            current = Money.of("500"),
            contribution = Money.zero(),
            assumptions = apr12(),
            timing = ContributionTiming.END_OF_MONTH,
        )
        val contribution = available(
            GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths = 12),
        )

        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("73.85")

        // Round-trip: projecting with the solved contribution must reach the target.
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario.copy(monthlyContribution = contribution.monthlyContribution),
                horizonMonths = 12,
            ),
        )
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("1500.02")
    }

    @Test
    fun `already funded targets report already funded not zero`() {
        val funded = GoalPlanCalc.requiredMonthlyContribution(
            scenario(
                target = GoalTarget.Nominal(Money.of("5000")),
                current = Money.of("5000"),
                contribution = Money.zero(),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 12,
        )

        assertThat(funded).isEqualTo(GoalPlanOutcome.AlreadyFunded)
    }

    @Test
    fun `growth alone covering the horizon reports already funded`() {
        val outcome = GoalPlanCalc.requiredMonthlyContribution(
            scenario(
                target = GoalTarget.Nominal(Money.of("1100")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = apr12(),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            horizonMonths = 10,
        )

        // 1000*1.01^10 = 1104.62 >= 1100 with no further saving: horizon-aware
        // AlreadyFunded (WM028-03 F3B amendment) — a solved zero is the same fact.
        assertThat(outcome).isEqualTo(GoalPlanOutcome.AlreadyFunded)
    }

    @Test
    fun `zero contribution with positive growth reaches in ten months`() {
        val months = available(
            GoalPlanCalc.requiredMonthsToDate(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1100")),
                    current = Money.of("1000"),
                    contribution = Money.zero(),
                    assumptions = apr12(),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                startMonth = YearMonth.of(2026, 10),
            ),
        )

        // 1.01^9 = 1093.69 < 1100 <= 1.01^10 = 1104.62
        assertThat(months.months).isEqualTo(10)
        assertThat(months.reachMonth).isEqualTo(YearMonth.of(2027, 8))
        assertThat(months.nominalTargetAtReach.amount).isEqualToIgnoringScale("1100")
        assertThat(months.method).isEqualTo(GoalPlanCalc.METHOD)
    }

    @Test
    fun `beginning timing reaches a month earlier than end timing`() {
        fun monthsFor(timing: ContributionTiming): Int = available(
            GoalPlanCalc.requiredMonthsToDate(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1275")),
                    current = Money.zero(),
                    contribution = Money.of("100"),
                    assumptions = apr12(),
                    timing = timing,
                ),
                startMonth = YearMonth.of(2026, 10),
            ),
        ).months

        // END: F(12) = 1268.25 < 1275 <= F(13) = 1380.93; BEGINNING: F(12) = 1280.93
        assertThat(monthsFor(ContributionTiming.END_OF_MONTH)).isEqualTo(13)
        assertThat(monthsFor(ContributionTiming.BEGINNING_OF_MONTH)).isEqualTo(12)
    }

    @Test
    fun `inflating target moves the finish line while saving`() {
        val months = available(
            GoalPlanCalc.requiredMonthsToDate(
                scenario(
                    target = GoalTarget.TodayPrice(Money.of("1000"), BigDecimal("12")),
                    current = Money.zero(),
                    contribution = Money.of("100"),
                    assumptions = apr12(),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                startMonth = YearMonth.of(2026, 10),
            ),
        )

        // month 10: 1046.22 < 1104.62; month 11: 1156.68 >= 1115.67
        assertThat(months.months).isEqualTo(11)
        assertThat(months.nominalTargetAtReach.amount).isEqualToIgnoringScale("1115.67")
    }

    @Test
    fun `technical horizon boundary of 1200 months is reachable`() {
        val months = available(
            GoalPlanCalc.requiredMonthsToDate(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1200")),
                    current = Money.zero(),
                    contribution = Money.of("1"),
                    assumptions = GoalAssumptions.CashOnly,
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                startMonth = YearMonth.of(2026, 10),
            ),
        )

        // Exactly at the technical limit: 1200 months of 1 = 1200
        assertThat(months.months).isEqualTo(1200)
        assertThat(months.reachMonth).isEqualTo(YearMonth.of(2126, 10))
    }

    @Test
    fun `decaying balance capped below target reports beyond technical horizon`() {
        val outcome = GoalPlanCalc.requiredMonthsToDate(
            scenario(
                target = GoalTarget.Nominal(Money.of("100000")),
                current = Money.zero(),
                contribution = Money.of("10"),
                assumptions = GoalAssumptions.FixedNominalApr(
                    annualAprPercent = BigDecimal("-50"),
                    annualFeePercent = BigDecimal("0"),
                ),
                timing = ContributionTiming.END_OF_MONTH,
            ),
            startMonth = YearMonth.of(2026, 10),
        )

        // Balance converges to 240.00 < target; still saving, so provably-never does not apply.
        val reason = notReachable(outcome)
        assertThat(reason).isEqualTo(GoalNotReachableReason.BEYOND_TECHNICAL_HORIZON)
    }

    @Test
    fun `zero contribution with cash model provably never reaches`() {
        val outcome = GoalPlanCalc.requiredMonthsToDate(
            scenario(
                target = GoalTarget.Nominal(Money.of("2000")),
                current = Money.of("1000"),
                contribution = Money.zero(),
                assumptions = GoalAssumptions.CashOnly,
                timing = ContributionTiming.END_OF_MONTH,
            ),
            startMonth = YearMonth.of(2026, 10),
        )

        val reason = notReachable(outcome)
        assertThat(reason).isEqualTo(GoalNotReachableReason.NEVER_REACHES)
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

internal fun notReachable(outcome: GoalPlanOutcome<*>): GoalNotReachableReason = when (outcome) {
    is GoalPlanOutcome.NotReachable -> outcome.reason
    else -> throw AssertionError("expected NotReachable, got $outcome")
}
