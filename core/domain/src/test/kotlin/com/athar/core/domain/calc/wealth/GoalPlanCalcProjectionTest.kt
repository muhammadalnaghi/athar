package com.athar.core.domain.calc.wealth

import com.athar.core.common.money.Money
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.GoalProjection
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Numeric oracles for [GoalPlanCalc.projectFutureAmount]. Every expected value was
 * computed independently with exact decimal arithmetic (see docs/specs/WM028-03).
 */
class GoalPlanCalcProjectionTest {

    @Test
    fun `cash-only projection sums principal and contributions`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("30000")),
                    current = Money.of("10000"),
                    contribution = Money.of("1000"),
                    assumptions = GoalAssumptions.CashOnly,
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("22000.00")
        assertThat(projection.totalContributions.amount).isEqualToIgnoringScale("12000.00")
        assertThat(projection.growthAmount.amount).isEqualToIgnoringScale("0.00")
        assertThat(projection.nominalTargetAtHorizon.amount).isEqualToIgnoringScale("30000")
        assertThat(projection.horizonMonths).isEqualTo(12)
    }

    @Test
    fun `cash-only projection is timing-independent`() {
        val beginning = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("30000")),
                    current = Money.of("10000"),
                    contribution = Money.of("1000"),
                    assumptions = GoalAssumptions.CashOnly,
                    timing = ContributionTiming.BEGINNING_OF_MONTH,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(beginning.futureAmount.amount).isEqualToIgnoringScale("22000.00")
    }

    @Test
    fun `twelve percent apr grows one month by exactly one percent`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("20000")),
                    current = Money.of("12000"),
                    contribution = Money.zero(),
                    assumptions = apr12(fee = BigDecimal("0")),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 1,
            ),
        )

        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("12120.00")
        assertThat(projection.growthAmount.amount).isEqualToIgnoringScale("120.00")
    }

    @Test
    fun `two months of end-of-month contributions compound at one percent`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("5000")),
                    current = Money.of("1000"),
                    contribution = Money.of("100"),
                    assumptions = apr12(fee = BigDecimal("0")),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 2,
            ),
        )

        // 1000*1.01 + 100 = 1110.00; 1110*1.01 + 100 = 1221.10
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("1221.10")
    }

    @Test
    fun `beginning-of-month contributions earn their first month of growth`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("5000")),
                    current = Money.of("1000"),
                    contribution = Money.of("100"),
                    assumptions = apr12(fee = BigDecimal("0")),
                    timing = ContributionTiming.BEGINNING_OF_MONTH,
                ),
                horizonMonths = 2,
            ),
        )

        // (1000+100)*1.01 = 1111.00; (1111+100)*1.01 = 1223.11
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("1223.11")
    }

    @Test
    fun `fee drags the nominal apr before monthly conversion`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("5000")),
                    current = Money.zero(),
                    contribution = Money.of("100"),
                    assumptions = apr12(fee = BigDecimal("2")),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 2,
            ),
        )

        // monthly rate (12-2)/1200 = 1/120; END timing: F(1) = 0*g + 100 = 100.00;
        // F(2) = 100*1.008333... + 100 = 200.83
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("200.83")
    }

    @Test
    fun `today-price target is inflated with its own declared convention`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.TodayPrice(Money.of("10000"), BigDecimal("12")),
                    current = Money.of("10000"),
                    contribution = Money.zero(),
                    assumptions = GoalAssumptions.CashOnly,
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 12,
            ),
        )

        // growth untouched (cash): 10000.00; nominal target 10000*1.01^12 = 11268.250301...
        assertThat(projection.futureAmount.amount).isEqualToIgnoringScale("10000.00")
        assertThat(projection.nominalTargetAtHorizon.amount).isEqualToIgnoringScale("11268.25")
    }

    @Test
    fun `money output rounds half to even`() {
        fun futureOf(annualApr: String): BigDecimal = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("999999")),
                    current = Money.of("1000.00"),
                    contribution = Money.zero(),
                    assumptions = GoalAssumptions.FixedNominalApr(BigDecimal(annualApr), BigDecimal.ZERO),
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 1,
            ),
        ).futureAmount.amount

        // Computed half-cents, not source fractions: source principal remains1000.00.
        assertThat(futureOf("60.006")).isEqualToIgnoringScale("1050.00")
        assertThat(futureOf("60.018")).isEqualToIgnoringScale("1050.02")
    }

    @Test
    fun `projection payload is self-describing`() {
        val projection = available(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("30000")),
                    current = Money.zero(),
                    contribution = Money.zero(),
                    assumptions = GoalAssumptions.CashOnly,
                    timing = ContributionTiming.END_OF_MONTH,
                ),
                horizonMonths = 1,
            ),
        )

        assertThat(projection.method).isEqualTo(GoalPlanCalc.METHOD)
    }

    private fun apr12(fee: BigDecimal) = GoalAssumptions.FixedNominalApr(
        annualAprPercent = BigDecimal("12"),
        annualFeePercent = fee,
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

internal fun <T> available(outcome: GoalPlanOutcome<T>): T = when (outcome) {
    is GoalPlanOutcome.Available -> outcome.value
    else -> throw AssertionError("expected Available, got $outcome")
}
