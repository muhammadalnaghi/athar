package com.athar.core.domain.calc.wealth

import com.athar.core.common.money.Money
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalInputProblemCode
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Invalid inputs must produce typed `InvalidInputs` outcomes — never a coerced number
 * (WM028-03). The 1200-month technical horizon is asserted as a validation bound.
 */
class GoalPlanCalcValidationTest {

    @Test
    fun `currency mismatch is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1000", "EUR")),
                    current = Money.of("100"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.CashOnly,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(GoalInputProblemCode.CURRENCY_MISMATCH)
    }

    @Test
    fun `non positive target is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.zero()),
                    current = Money.of("100"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.CashOnly,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(GoalInputProblemCode.TARGET_NOT_POSITIVE)
    }

    @Test
    fun `negative current allocation is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1000")),
                    current = Money.of("-1"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.CashOnly,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(GoalInputProblemCode.NEGATIVE_CURRENT_ALLOCATION)
    }

    @Test
    fun `negative contribution is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1000")),
                    current = Money.of("100"),
                    contribution = Money.of("-10"),
                    assumptions = GoalAssumptions.CashOnly,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(GoalInputProblemCode.NEGATIVE_MONTHLY_CONTRIBUTION)
    }

    @Test
    fun `horizon outside one to 1200 months is rejected as a technical bound`() {
        for (horizon in listOf(0, -1, GoalPlanCalc.TECHNICAL_HORIZON_LIMIT_MONTHS + 1)) {
            val problems = invalidProblems(
                GoalPlanCalc.projectFutureAmount(validScenario(), horizonMonths = horizon),
            )
            assertThat(problems).containsExactly(GoalInputProblemCode.HORIZON_OUT_OF_TECHNICAL_BOUNDS)
        }
    }

    @Test
    fun `monthly rate at or below minus 100 percent is rejected`() {
        for (apr in listOf("-1300", "-1200")) {
            val problems = invalidProblems(
                GoalPlanCalc.projectFutureAmount(
                    scenario(
                        target = GoalTarget.Nominal(Money.of("1000")),
                        current = Money.of("100"),
                        contribution = Money.of("10"),
                        assumptions = GoalAssumptions.FixedNominalApr(
                            annualAprPercent = BigDecimal(apr),
                            annualFeePercent = BigDecimal("0"),
                        ),
                    ),
                    horizonMonths = 12,
                ),
            )
            assertThat(problems).containsExactly(GoalInputProblemCode.APR_MONTHLY_RATE_OUT_OF_RANGE)
        }
    }

    @Test
    fun `rate just above minus 100 percent monthly equivalent passes validation`() {
        val outcome = GoalPlanCalc.requiredMonthlyContribution(
            scenario(
                target = GoalTarget.Nominal(Money.of("1010")),
                current = Money.zero(),
                contribution = Money.zero(),
                assumptions = GoalAssumptions.FixedNominalApr(
                    annualAprPercent = BigDecimal("-1199.99"),
                    annualFeePercent = BigDecimal("0"),
                ),
            ),
            horizonMonths = 1,
        )

        // g = 1 - 1199.99/1200 > 0, END: F(1) = c = 1010
        val contribution = available(outcome)
        assertThat(contribution.monthlyContribution.amount).isEqualToIgnoringScale("1010.00")
    }

    @Test
    fun `negative fee is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1000")),
                    current = Money.of("100"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.FixedNominalApr(
                        annualAprPercent = BigDecimal("12"),
                        annualFeePercent = BigDecimal("-1"),
                    ),
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).contains(GoalInputProblemCode.NEGATIVE_FEE)
    }

    @Test
    fun `inflation at or below minus 100 percent monthly equivalent is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.TodayPrice(Money.of("1000"), BigDecimal("-1300")),
                    current = Money.of("100"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.CashOnly,
                ),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(GoalInputProblemCode.INFLATION_MONTHLY_RATE_OUT_OF_RANGE)
    }

    @Test
    fun `blank scenario name is rejected`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.of("1000")),
                    current = Money.of("100"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.CashOnly,
                ).copy(name = "   "),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(GoalInputProblemCode.BLANK_SCENARIO_NAME)
    }

    @Test
    fun `problems accumulate instead of failing fast`() {
        val problems = invalidProblems(
            GoalPlanCalc.projectFutureAmount(
                scenario(
                    target = GoalTarget.Nominal(Money.zero()),
                    current = Money.of("-1"),
                    contribution = Money.of("10"),
                    assumptions = GoalAssumptions.CashOnly,
                ).copy(name = ""),
                horizonMonths = 12,
            ),
        )

        assertThat(problems).containsExactly(
            GoalInputProblemCode.BLANK_SCENARIO_NAME,
            GoalInputProblemCode.TARGET_NOT_POSITIVE,
            GoalInputProblemCode.NEGATIVE_CURRENT_ALLOCATION,
        )
    }

    private fun invalidProblems(outcome: GoalPlanOutcome<*>): List<GoalInputProblemCode> = when (outcome) {
        is GoalPlanOutcome.InvalidInputs -> outcome.problems.map { it.code }
        else -> throw AssertionError("expected InvalidInputs, got $outcome")
    }

    private fun validScenario() = scenario(
        target = GoalTarget.Nominal(Money.of("1000")),
        current = Money.of("100"),
        contribution = Money.of("10"),
        assumptions = GoalAssumptions.CashOnly,
    )

    private fun scenario(
        target: GoalTarget,
        current: Money,
        contribution: Money,
        assumptions: GoalAssumptions,
    ): GoalScenario = GoalScenario(
        name = "oracle",
        target = target,
        currentAllocation = current,
        monthlyContribution = contribution,
        assumptions = assumptions,
        contributionTiming = ContributionTiming.END_OF_MONTH,
    )
}
