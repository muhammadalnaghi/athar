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
 * WM047 regressions: once exact same-date netting leaves at most one chronological sign
 * variation, the exponential polynomial `sum(a_i * v^t_i)`, `v = 1/(1+r) > 0`, has at most one
 * positive-`v` root (generalized Descartes sign-variation rule). The two window bounds alone
 * therefore decide the outcome, and every expectation below is the independent closed-form or
 * tool-calculated oracle already reviewed for [XirrCalc] — never the implementation's output.
 * The pre-fast-path 4096-point scan produced these exact same results; the fast path must
 * remain indistinguishable in result shape, value and rounding.
 */
class XirrConventionalFastPath047Test {

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
    fun `wm047 public fixture - 90 day conventional window keeps the reviewed oracle ratio`() {
        // The exact production-observed series (SAR 1000 in on 2026-01-01, 100 units x 12 quote
        // on 2026-04-01 -> +1200 out). 90 actual days; reviewed oracle: (1.2)^(365/90) - 1
        // = +109.47101751% (see PortfolioReturnsCalc042ConsolidationTest), i.e. ratio 1.0947101751.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("wm047-in", "2026-01-01", "-1000"),
                    flow("wm047-out", "2026-04-01", "1200"),
                ),
            ),
        )
        assertRate(result, "1.0947101751")
    }

    @Test
    fun `wm047 public fixture via opening and terminal valuations keeps the reviewed oracle`() {
        // Same window expressed the way the product feeds it: opening valuation 1000 folds in as
        // a -1000 flow, terminal valuation 1200 as +1200. Identical NPV, identical oracle.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = emptyList(),
                openingValuation = DatedValuation(LocalDate.parse("2026-01-01"), Money.of("1000")),
                terminalValuation = DatedValuation(LocalDate.parse("2026-04-01"), Money.of("1200")),
            ),
        )
        assertRate(result, "1.0947101751")
    }

    @Test
    fun `same-date netting turns a raw double sign change into one conventional variation`() {
        // Raw sequence -1000, +700, -300 has two sign changes, but on 2024-01-01 the flows net
        // exactly to +400, so the netted sign pattern is (-, +): one variation -> conventional.
        // Closed form: -1000 + 400/(1+r) = 0 with t = 1 (365 days) -> r = -0.6 exactly.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("in", "2023-01-01", "-1000"),
                    flow("out-a", "2024-01-01", "700"),
                    flow("out-b", "2024-01-01", "-300"),
                ),
            ),
        )
        assertRate(result, "-0.6", tolerance = "1e-12")
    }

    @Test
    fun `near total loss at the lower window edge stays exact`() {
        // -1000 @t0, +1 @t1 (365 days): 1 + r = 1/1000 -> r = -0.999 exactly, just inside the
        // -0.999999999999 lower bound. The root-hugging-the-bound case must keep its value.
        val result = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("in", "2023-01-01", "-1000"), flow("out", "2024-01-01", "1"))),
        )
        assertRate(result, "-0.999", tolerance = "1e-12")
    }

    @Test
    fun `conventional root exactly at the upper bound is an honest bounded-search outcome`() {
        // -100 @t0, +10100 @t1 (365 days): closed-form root r = 100, exactly the upper bound.
        // NPV(-0.999999999999) = -100 + 10100e12 > 0 while NPV(100) = -100 + 10100/101 is zero
        // only in exact arithmetic; DECIMAL128 pow rounds the 1/101 term, so the computed bound
        // value sits a few ulps off zero. The bounded search reports whatever it can honestly
        // prove - the same outcome the 4096-point scan produced for this series before WM047.
        val result = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("in", "2023-01-01", "-100"), flow("out", "2024-01-01", "10100"))),
        )
        assertWithMessage("bounded endpoint search outcome: $result")
            .that(result is XirrResult.Rate || result is XirrResult.Rejected).isTrue()
        if (result is XirrResult.Rejected) {
            // A miss must stay an honest bounded-search miss, never a picked rate.
            assertThat(result.reason).isEqualTo(XirrRejection.NO_SIGN_CHANGE)
        } else {
            assertRate(result, "100", tolerance = "1e-9")
        }
    }

    @Test
    fun `all flows with one sign keep the honest no-root rejection on the fast path`() {
        // Zero sign variations: NPV keeps one sign on the whole window, so no root exists inside
        // the bounds and the bounded-search miss is reported, exactly as the scan reported it.
        val result = XirrCalc.annualRate(
            XirrInput(flows = listOf(flow("a", "2024-01-01", "-1000"), flow("b", "2024-06-01", "-500"))),
        )
        assertRejected(result, XirrRejection.NO_SIGN_CHANGE)
    }

    @Test
    fun `non-conventional series still goes through the full grid scan with all candidates`() {
        // Two net sign variations: -100, +230, -132. The fast path must not engage; the scan
        // reports both proven roots 0.1 and 0.2 as an ambiguity, never a picked rate.
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
}
