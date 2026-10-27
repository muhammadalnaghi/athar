package com.athar.core.domain.calc.wealth

import com.athar.core.common.money.Money
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.GoalProjection
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import com.athar.core.domain.model.wealth.RequiredContribution
import com.athar.core.domain.model.wealth.RequiredMonths
import com.athar.core.domain.model.wealth.RequiredRate
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.time.YearMonth
import org.junit.jupiter.api.Test

class GoalPlanCurrencyPrecisionTest {
    @Test
    fun `KWD cash projection preserves source precision and cannot fabricate negative growth`() {
        val result = available(GoalPlanCalc.projectFutureAmount(scenario("KWD", "10.123", "10.123", "0.500"), 12))
        exact(result.futureAmount, "16.123")
        exact(result.totalContributions, "6.000")
        exact(result.growthAmount, "0")
        exact(result.nominalTargetAtHorizon, "10.123")
    }

    @Test
    fun `required KWD contribution uses a thousandth quantum and verifies the target`() {
        val input = scenario("KWD", "1", "0", "0")
        val result = available(GoalPlanCalc.requiredMonthlyContribution(input, 3))
        exact(result.monthlyContribution, "0.334")
        val forward = available(GoalPlanCalc.projectFutureAmount(input.copy(monthlyContribution = result.monthlyContribution), 3))
        assertThat(forward.futureAmount.amount >= BigDecimal.ONE).isTrue()
    }

    @Test
    fun `required whole JPY contribution rounds upward without inventing fractional units`() {
        val result = available(GoalPlanCalc.requiredMonthlyContribution(scenario("JPY", "1000", "0", "0"), 3))
        exact(result.monthlyContribution, "334")
        assertThat(result.monthlyContribution.amount.scale()).isEqualTo(0)
    }

    @Test
    fun `JPY projection uses whole units when all source amounts are whole`() {
        val result = available(GoalPlanCalc.projectFutureAmount(scenario("JPY", "2000", "1000", "1"), 2))
        exact(result.futureAmount, "1002")
        assertThat(result.futureAmount.amount.scale()).isEqualTo(0)
    }

    @Test
    fun `meaningful extra source precision is never discarded to force standard minor units`() {
        val result = available(GoalPlanCalc.projectFutureAmount(scenario("SAR", "1", "0.001", "0.001"), 1))
        exact(result.futureAmount, "0.002")
        exact(result.growthAmount, "0")
    }

    @Test
    fun `unknown literal currency keeps meaningful nine decimal source amounts`() {
        val result = available(GoalPlanCalc.projectFutureAmount(scenario("BTC", "1", "0.000000126", "0.000000001"), 1))
        exact(result.futureAmount, "0.000000127")
        exact(result.growthAmount, "0")
        assertThat(result.futureAmount.currency).isEqualTo("BTC")
    }

    @Test
    fun `reach result retains the exact KWD target`() {
        val result = available(GoalPlanCalc.requiredMonthsToDate(scenario("KWD", "10.123", "10.120", "0.001"), YearMonth.of(2026, 10)))
        assertThat(result.months).isEqualTo(3)
        exact(result.nominalTargetAtReach, "10.123")
    }

    @Test
    fun `required rate result retains the exact declared KWD target`() {
        val result = available(GoalPlanCalc.requiredNominalApr(scenario("KWD", "30.123", "10.123", "0"), 12))
        exact(result.nominalTargetAtHorizon, "30.123")
    }

    @Test
    fun `cash amounts finer than internal rate precision remain exact`() {
        val input = scenario("BTC", "0.000000000000127", "0.000000000000126", "0.000000000000001")
        val projection = available(GoalPlanCalc.projectFutureAmount(input, 1))
        exact(projection.futureAmount, "0.000000000000127")
        exact(projection.growthAmount, "0")
        val required = available(GoalPlanCalc.requiredMonthlyContribution(input.copy(monthlyContribution = Money.zero("BTC")), 1))
        exact(required.monthlyContribution, "0.000000000000001")
    }

    private fun scenario(currency: String, target: String, allocation: String, monthly: String) = GoalScenario(
        name = "synthetic-currency-regression",
        target = GoalTarget.Nominal(Money.of(target, currency)),
        currentAllocation = Money.of(allocation, currency),
        monthlyContribution = Money.of(monthly, currency),
        assumptions = GoalAssumptions.CashOnly,
        contributionTiming = ContributionTiming.END_OF_MONTH,
    )

    private fun <T> available(outcome: GoalPlanOutcome<T>): T {
        assertThat(outcome).isInstanceOf(GoalPlanOutcome.Available::class.java)
        return (outcome as GoalPlanOutcome.Available<T>).value
    }

    private fun exact(actual: Money, expected: String) {
        assertThat(actual.amount.compareTo(BigDecimal(expected))).isEqualTo(0)
    }
}
