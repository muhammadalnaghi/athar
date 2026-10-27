package com.athar.feature.plan.budget

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * WM032 — the target-editor write boundary: Save exact nonnegative minor units in the
 * target's literal currency; malformed, negative, fractional-minor-unit and overflowing
 * input are rejected with a reason so a failed Save can never degrade into a clear.
 */
class BudgetTargetInputTest {

    private fun parse(raw: String, currency: String = "SAR") = BudgetTargetInput.parse(raw, currency)

    @Test fun `valid sar amount converts exactly to minor units`() {
        assertThat(parse("12.34")).isEqualTo(TargetInput.Valid(1234L))
        assertThat(parse("100")).isEqualTo(TargetInput.Valid(10000L))
    }

    @Test fun `arabic indic digits from a native keyboard are accepted`() {
        assertThat(parse("١٢.٥")).isEqualTo(TargetInput.Valid(1250L))
        assertThat(parse("٤٠", "SAR")).isEqualTo(TargetInput.Valid(4000L))
    }

    @Test fun `more precision than the currency supports is rejected without a minor value`() {
        assertThat(parse("10.505")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.FRACTIONAL))
    }

    @Test fun `zero fraction currency rejects halalas`() {
        assertThat(parse("10.5", "JPY")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.FRACTIONAL))
        assertThat(parse("10", "JPY")).isEqualTo(TargetInput.Valid(10L))
    }

    @Test fun `malformed text and empty input are rejected`() {
        assertThat(parse("abc")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.MALFORMED))
        assertThat(parse("")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.MALFORMED))
        assertThat(parse("1,000")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.MALFORMED))
    }

    @Test fun `negative amount is rejected`() {
        assertThat(parse("-5")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.NEGATIVE))
    }

    @Test fun `amount beyond long minor units is rejected not truncated`() {
        assertThat(parse("99999999999999999999")).isEqualTo(TargetInput.Invalid(TargetInput.Reason.OVERFLOW))
    }

    @Test fun `explicit zero is a valid save distinct from clearing`() {
        assertThat(parse("0")).isEqualTo(TargetInput.Valid(0L))
        assertThat(parse("0.00")).isEqualTo(TargetInput.Valid(0L))
    }
}
