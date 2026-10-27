package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.DatedValuation
import com.athar.core.domain.model.performance.XirrAmbiguity
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrRejection
import com.athar.core.domain.model.performance.XirrResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Behavior-first synthetic tests for [XirrCalc]. Every numeric expectation is an independent
 * tool-calculated oracle (Python `decimal` bisection or closed-form algebra at 60-digit
 * precision, actual-days/365 basis) — never the implementation's own output.
 */
class XirrCalcTest {

    private fun flow(id: String, date: String, amount: String) =
        DatedCashFlow(id, LocalDate.parse(date), Money.of(amount))

    private fun assertRate(result: XirrResult, expected: String, tolerance: String = "1e-9") {
        assertWithMessage("expected a Rate but got $result")
            .that(result).isInstanceOf(XirrResult.Rate::class.java)
        val rate = (result as XirrResult.Rate).annualRateRatio
        val diff = (rate - BigDecimal(expected)).abs()
        assertWithMessage("rate $rate vs oracle $expected (+-$tolerance), diff $diff")
            .that(diff <= BigDecimal(tolerance)).isTrue()
    }

    private fun assertRejected(result: XirrResult, reason: XirrRejection) {
        assertWithMessage("expected Rejected($reason) but got $result")
            .that(result).isInstanceOf(XirrResult.Rejected::class.java)
        assertThat((result as XirrResult.Rejected).reason).isEqualTo(reason)
    }

    @Test
    fun `one year 1000 to 1100 via opening and terminal valuations is exactly 10 percent as a ratio`() {
        // Closed form: 1100/1000 = (1+r)^(365/365) -> r = 0.1. Ratio, NOT percent.
        // 2023-01-01 -> 2024-01-01 is a non-leap 365-day window, so ACT/365 gives t = 1 exactly.
        // (A 2024 window would span 366 days and the true root would be 1.1^(365/366) - 1.)
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = emptyList(),
                openingValuation = DatedValuation(LocalDate.parse("2023-01-01"), Money.of("1000")),
                terminalValuation = DatedValuation(LocalDate.parse("2024-01-01"), Money.of("1100")),
            ),
        )
        assertRate(result, "0.1", tolerance = "1e-12")
        // Ratio-not-percent is pinned by the 1e-12 oracle above (percent would be 10).
    }

    @Test
    fun `multiple contributions then terminal value match the closed form 10 percent`() {
        // -1000 @t0, -1000 @t1(365d), +2310 @t2(730d): 2310x^2 - 1000x - 1000 = 0, x = 1/(1+r)
        // -> x = 10/11 -> r = 0.1 exactly. Oracle cross-checked with Python decimal bisection.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("sub-1", "2022-01-01", "-1000"),
                    flow("sub-2", "2023-01-01", "-1000"),
                ),
                terminalValuation = DatedValuation(LocalDate.parse("2024-01-01"), Money.of("2310")),
            ),
        )
        assertRate(result, "0.1", tolerance = "1e-12")
    }

    @Test
    fun `actual-days basis counts Feb 29 - leap window and non-leap window differ`() {
        // Same calendar span (Feb 28 -> Mar 1), same growth 1000 -> 1005:
        //  2024 (leap): 2 days  -> r = 1.005^(365/2) - 1   = 1.48488486321423870666...
        //  2023:         1 day  -> r = 1.005^365     - 1   = 5.17465278343124580758...
        // Oracles: Python decimal pow at 60-digit precision.
        val leap = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("in", "2024-02-28", "-1000"), flow("out", "2024-03-01", "1005"))),
        )
        val nonLeap = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("in", "2023-02-28", "-1000"), flow("out", "2023-03-01", "1005"))),
        )
        assertRate(leap, "1.48488486321423870666")
        assertRate(nonLeap, "5.17465278343124580758")
    }

    @Test
    fun `irregular dates with fractional-year exponents match the independent decimal oracle`() {
        // Days (actual/365, Python date arithmetic): 0, 177 (2024-01-15 -> 2024-07-10),
        // 415 (2024-01-15 -> 2025-03-05). Oracle bisection: r = 0.17045184571390133423...
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("buy-1", "2024-01-15", "-1000"),
                    flow("buy-2", "2024-07-10", "-500"),
                    flow("sell", "2025-03-05", "1750"),
                ),
            ),
        )
        assertRate(result, "0.17045184571390133423")
    }

    @Test
    fun `same-date flows netting to zero - every rate is a root, reported as ambiguous`() {
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("a", "2024-05-01", "-100"),
                    flow("b", "2024-05-01", "60"),
                    flow("c", "2024-05-01", "40"),
                ),
            ),
        )
        assertThat(result).isInstanceOf(XirrResult.Ambiguous::class.java)
        val ambiguous = result as XirrResult.Ambiguous
        assertThat(ambiguous.reason).isEqualTo(XirrAmbiguity.EVERY_RATE_IS_A_ROOT)
        assertThat(ambiguous.candidateAnnualRateRatios).isEmpty()
    }

    @Test
    fun `same-date flows netting negative - rejected, no timespan exists`() {
        val result = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("a", "2024-05-01", "-100"), flow("b", "2024-05-01", "80"))),
        )
        assertRejected(result, XirrRejection.ZERO_TIMESPAN)
    }

    @Test
    fun `zero amount flow is rejected - missing sign is never treated as missing data`() {
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("a", "2024-01-01", "-1000"),
                    flow("z", "2024-06-01", "0"),
                    flow("b", "2024-12-31", "1100"),
                ),
            ),
        )
        assertRejected(result, XirrRejection.ZERO_AMOUNT)
    }

    @Test
    fun `non-conventional series with two sign changes reports both candidate roots`() {
        // -100 @t0, +230 @t1, -132 @t2 (365d steps). NPV = 0 with x = 1/(1+r):
        // 132x^2 - 230x + 100 = 0 -> x in {10/11, 5/6} -> r in {0.1, 0.2} exactly.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("in", "2022-01-01", "-100"),
                    flow("out", "2023-01-01", "230"),
                    flow("rein", "2024-01-01", "-132"),
                ),
            ),
        )
        assertThat(result).isInstanceOf(XirrResult.Ambiguous::class.java)
        val ambiguous = result as XirrResult.Ambiguous
        assertThat(ambiguous.reason).isEqualTo(XirrAmbiguity.MULTIPLE_SIGN_CHANGES)
        assertThat(ambiguous.candidateAnnualRateRatios).hasSize(2)
        val near = { expected: String ->
            ambiguous.candidateAnnualRateRatios.any { (it - BigDecimal(expected)).abs() <= BigDecimal("1e-9") }
        }
        assertThat(near("0.1")).isTrue()
        assertThat(near("0.2")).isTrue()
    }

    @Test
    fun `one scanned candidate never implies uniqueness for a non conventional series`() {
        // Independent exact polynomial: (11x-10)(1101x-1000)(3x-2), x = 1/(1+r).
        // True roots: r = 0.1, 0.101, 0.5. Both close roots are in scan cell 44,
        // so its endpoint signs agree and only the distant root is bracketed.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("a", "2021-01-01", "-20000"),
                    flow("b", "2022-01-01", "74020"),
                    flow("c", "2023-01-01", "-90252"),
                    flow("d", "2024-01-01", "36333"),
                ),
            ),
        )
        assertThat(result).isInstanceOf(XirrResult.Ambiguous::class.java)
        val ambiguous = result as XirrResult.Ambiguous
        assertThat(ambiguous.reason).isEqualTo(XirrAmbiguity.UNIQUENESS_UNPROVEN)
        val candidates = ambiguous.candidateAnnualRateRatios
        assertThat(candidates).hasSize(1)
        assertThat((candidates.single() - BigDecimal("0.5")).abs() <= BigDecimal("1e-9")).isTrue()
    }

    @Test
    fun `near total loss lands at minus 99_9 percent`() {
        // -1000 @t0, +1 @t1(365d, non-leap 2023-01-01 -> 2024-01-01 window):
        // 1 + r = 1/1000 -> r = -0.999 exactly under ACT/365 with t = 1.
        val result = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("in", "2023-01-01", "-1000"), flow("out", "2024-01-01", "1"))),
        )
        assertRate(result, "-0.999", tolerance = "1e-12")
    }

    @Test
    fun `all flows with the same sign - no sign change inside the bounded window`() {
        val result = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("a", "2024-01-01", "-1000"), flow("b", "2024-06-01", "-500"))),
        )
        assertRejected(result, XirrRejection.NO_SIGN_CHANGE)
    }

    @Test
    fun `mixed currencies are rejected - never converted`() {
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    DatedCashFlow("a", LocalDate.parse("2024-01-01"), Money.of("-1000")),
                    DatedCashFlow("b", LocalDate.parse("2024-12-31"), Money.of("1100", "USD")),
                ),
            ),
        )
        assertRejected(result, XirrRejection.CURRENCY_MISMATCH)
    }

    @Test
    fun `empty series is rejected`() {
        assertRejected(XirrCalc.annualRate(XirrInput(flows = emptyList())), XirrRejection.EMPTY_INPUT)
    }
}
