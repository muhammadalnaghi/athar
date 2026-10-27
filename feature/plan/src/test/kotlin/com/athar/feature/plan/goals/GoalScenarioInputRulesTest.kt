package com.athar.feature.plan.goals

import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.time.YearMonth
import org.junit.jupiter.api.Test

class GoalScenarioInputRulesTest {
    @Test
    fun `Arabic grouping and decimal input remain exact`() {
        assertThat(parseDecimal("١٬٢٣٤٫٥٦")!!.compareTo(BigDecimal("1234.56"))).isEqualTo(0)
        assertThat(parseDecimal(".5")!!.compareTo(BigDecimal("0.5"))).isEqualTo(0)
    }

    @Test
    fun `internal whitespace cannot silently join different amounts`() {
        listOf("1 2", "1\t2", "1 234.56").forEach { assertThat(parseDecimal(it)).isNull() }
    }

    @Test
    fun `malformed grouping and nonnumeric values reject instead of being coerced`() {
        listOf("1٬23", "12,34", "1.2.3", "NaN", "1e99999999").forEach { assertThat(parseDecimal(it)).isNull() }
    }

    @Test
    fun `numeric resource limit rejects before BigDecimal parsing`() {
        assertThat(parseDecimal("1".repeat(129))).isNull()
        assertThat(NumericText.normalize("1".repeat(129))).isEmpty()
    }

    @Test
    fun `malformed horizon cannot become a different valid planning duration`() {
        val result = GoalScenarioDraft(
            targetAmountText = "300", currentAllocationText = "100", monthlyContributionText = "50", horizonMonthsText = "1 2",
        ).solve("SAR", YearMonth.of(2026, 10))
        assertThat(result).isInstanceOf(GoalSolveUi.Invalid::class.java)
        assertThat((result as GoalSolveUi.Invalid).fieldErrors).contains(GoalField.HORIZON_MONTHS)
    }
}
