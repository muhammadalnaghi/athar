package com.athar.feature.plan.budget

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class BudgetTargetMonthsTest {
    private fun months(a: String, b: String) = budgetTargetMonths(LocalDate.parse(a), LocalDate.parse(b))
    @Test fun `whole months are exact regardless of short or leap month`() {
        assertThat(months("2026-02-01", "2026-02-28")).isEqualTo(1.0)
        assertThat(months("2024-02-01", "2024-02-29")).isEqualTo(1.0)
    }
    @Test fun `full year is twelve monthly targets not a mean day approximation`() {
        assertThat(months("2026-01-01", "2026-12-31")).isEqualTo(12.0)
        assertThat(months("2024-01-01", "2024-12-31")).isEqualTo(12.0)
    }
    @Test fun `a short custom span is not mistaken for a calendar month`() {
        assertThat(months("2026-01-02", "2026-01-29")).isWithin(0.000000001).of(28.0 / 30.4375)
    }
    @Test fun `a full calendar quarter contains exactly three targets`() {
        assertThat(months("2026-07-01", "2026-09-30")).isEqualTo(3.0)
    }
}
