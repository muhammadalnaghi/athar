package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.ExternalFlowBoundary
import com.athar.core.domain.model.performance.TwrInput
import com.athar.core.domain.model.performance.TwrRejection
import com.athar.core.domain.model.performance.TwrResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Behavior-first synthetic tests for [TwrCalc]. All expectations are exact closed-form
 * geometric products computed independently with Python `decimal` (60-digit precision).
 * Key invariant under test: returns chain across EXTERNAL-FLOW BOUNDARY valuations, so
 * investor timing must not distort the market-only result.
 */
class TwrCalcTest {

    private fun money(amount: String) = Money.of(amount)

    private fun boundary(id: String, date: String, seq: Int, before: String, ext: String, after: String) =
        ExternalFlowBoundary(id, LocalDate.parse(date), seq, money(before), money(ext), money(after))

    private fun input(
        start: String,
        initial: String,
        end: String,
        closing: String,
        boundaries: List<ExternalFlowBoundary>,
    ) = TwrInput(LocalDate.parse(start), money(initial), LocalDate.parse(end), money(closing), boundaries)

    private fun assertRate(result: TwrResult, expected: String, subPeriods: Int) {
        assertWithMessage("expected a Rate but got $result")
            .that(result).isInstanceOf(TwrResult.Rate::class.java)
        val rate = result as TwrResult.Rate
        val diff = (rate.periodReturnRatio - BigDecimal(expected)).abs()
        assertWithMessage("ratio ${rate.periodReturnRatio} vs oracle $expected, diff $diff")
            .that(diff <= BigDecimal("1e-9")).isTrue()
        assertThat(rate.subPeriodCount).isEqualTo(subPeriods)
    }

    private fun assertRejected(result: TwrResult, reason: TwrRejection) {
        assertWithMessage("expected Rejected($reason) but got $result")
            .that(result).isInstanceOf(TwrResult.Rejected::class.java)
        assertThat((result as TwrResult.Rejected).reason).isEqualTo(reason)
    }

    @Test
    fun `boundary contribution doubles the value but return stays 10 percent - flow independence`() {
        // Counterexample from the spec: 100 -> [contribute +100 at boundary] -> 200 -> 220.
        // Cash-adjusted mis-labels give 120% or 20%; geometric chaining gives exactly
        // (100/100) * (220/200) - 1 = 0.1.
        val result = TwrCalc.timeWeightedReturn(
            input("2024-01-01", "100", "2024-12-31", "220", listOf(boundary("c1", "2024-06-30", 1, "100", "100", "200"))),
        )
        assertRate(result, "0.1", subPeriods = 2)
    }

    @Test
    fun `no boundaries - plain closing over initial`() {
        val result = TwrCalc.timeWeightedReturn(input("2024-01-01", "100", "2024-12-31", "220", emptyList()))
        assertRate(result, "1.2", subPeriods = 1)
    }

    @Test
    fun `explicit zero closing value is minus 100 percent - not missing data`() {
        val result = TwrCalc.timeWeightedReturn(input("2024-01-01", "100", "2024-12-31", "0", emptyList()))
        assertRate(result, "-1", subPeriods = 1)
    }

    @Test
    fun `multi-boundary chain matches the manual product oracle`() {
        // (1100/1000) * (950/900) * (1100/1000) - 1 = 2299/1800 - 1 = 0.2772222222...
        // b1 withdraws 200 (external -200), b2 contributes 50 (external +50).
        val result = TwrCalc.timeWeightedReturn(
            input(
                "2024-01-01", "1000", "2024-12-31", "1100",
                listOf(
                    boundary("w1", "2024-03-31", 1, "1100", "-200", "900"),
                    boundary("c2", "2024-09-30", 2, "950", "50", "1000"),
                ),
            ),
        )
        assertRate(result, "0.27722222222222222222", subPeriods = 3)
    }

    @Test
    fun `after value must equal before plus external - fabricated boundary rejected`() {
        val result = TwrCalc.timeWeightedReturn(
            input("2024-01-01", "100", "2024-12-31", "220", listOf(boundary("bad", "2024-06-30", 1, "100", "100", "150"))),
        )
        assertRejected(result, TwrRejection.BOUNDARY_AFTER_MISMATCH)
    }

    @Test
    fun `boundary on the window edge is rejected`() {
        val result = TwrCalc.timeWeightedReturn(
            input("2024-01-01", "100", "2024-12-31", "220", listOf(boundary("edge", "2024-01-01", 1, "100", "10", "110"))),
        )
        assertRejected(result, TwrRejection.BOUNDARY_OUTSIDE_WINDOW)
    }

    @Test
    fun `duplicate flow id is rejected`() {
        val result = TwrCalc.timeWeightedReturn(
            input(
                "2024-01-01", "100", "2024-12-31", "220",
                listOf(
                    boundary("dup", "2024-03-31", 1, "110", "10", "120"),
                    boundary("dup", "2024-09-30", 2, "120", "-20", "100"),
                ),
            ),
        )
        assertRejected(result, TwrRejection.BOUNDARY_UNORDERED_OR_DUPLICATE)
    }

    @Test
    fun `boundaries listed out of order are rejected - order must be explicit`() {
        val result = TwrCalc.timeWeightedReturn(
            input(
                "2024-01-01", "100", "2024-12-31", "220",
                listOf(
                    boundary("late", "2024-09-30", 1, "120", "-20", "100"),
                    boundary("early", "2024-03-31", 2, "110", "10", "120"),
                ),
            ),
        )
        assertRejected(result, TwrRejection.BOUNDARY_UNORDERED_OR_DUPLICATE)
    }

    @Test
    fun `zero start value growing without a recorded boundary is rejected - missing external flow`() {
        // 0 -> 100 with no external flow is impossible; rejecting catches silently missing data.
        val result = TwrCalc.timeWeightedReturn(input("2024-01-01", "0", "2024-12-31", "100", emptyList()))
        assertRejected(result, TwrRejection.ZERO_OR_SIGN_CHANGING_BASE)
    }

    @Test
    fun `zero capital to zero capital sub-period is neutral and contributions still chain correctly`() {
        // Start empty; +1000 contributed at a boundary whose valueBefore is 0; no market growth.
        // Factors: (0->0 neutral 1) * (1000/1000) = 1 -> exactly 0% return over 2 sub-periods.
        val result = TwrCalc.timeWeightedReturn(
            input("2024-01-01", "0", "2024-12-31", "1000", listOf(boundary("fund", "2024-06-30", 1, "0", "1000", "1000"))),
        )
        assertRate(result, "0", subPeriods = 2)
    }

    @Test
    fun `sign-flipping sub-period base is rejected - geometric chaining undefined`() {
        val result = TwrCalc.timeWeightedReturn(input("2024-01-01", "-100", "2024-12-31", "50", emptyList()))
        assertRejected(result, TwrRejection.ZERO_OR_SIGN_CHANGING_BASE)
    }

    @Test
    fun `mixed currencies are rejected`() {
        val result = TwrCalc.timeWeightedReturn(
            TwrInput(LocalDate.parse("2024-01-01"), money("100"), LocalDate.parse("2024-12-31"), money("220"), emptyList())
                .copy(closingValue = Money.of("220", "USD")),
        )
        assertRejected(result, TwrRejection.CURRENCY_MISMATCH)
    }
}
