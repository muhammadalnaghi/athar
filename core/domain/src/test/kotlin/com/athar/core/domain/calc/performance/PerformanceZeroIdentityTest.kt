package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.DatedValuation
import com.athar.core.domain.model.performance.XirrAmbiguity
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrRejection
import com.athar.core.domain.model.performance.XirrResult
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class PerformanceZeroIdentityTest {
    private val start = LocalDate(2024, 1, 1)
    private val end = LocalDate(2024, 12, 31)

    @Test
    fun `net zero on each separate date is identically zero NPV not thousands of candidate rates`() {
        val flows = listOf(
            DatedCashFlow("a", start, Money.of("-100")),
            DatedCashFlow("b", start, Money.of("100")),
            DatedCashFlow("c", end, Money.of("-200")),
            DatedCashFlow("d", end, Money.of("200")),
        )
        val result = XirrCalc.annualRate(XirrInput(flows))
        assertThat(result).isInstanceOf(XirrResult.Ambiguous::class.java)
        val ambiguity = result as XirrResult.Ambiguous
        assertThat(ambiguity.reason).isEqualTo(XirrAmbiguity.EVERY_RATE_IS_A_ROOT)
        assertThat(ambiguity.candidateAnnualRateRatios).isEmpty()
    }

    @Test
    fun `known zero opening still participates in currency validation`() {
        val result = XirrCalc.annualRate(XirrInput(
            flows = listOf(DatedCashFlow("in", start, Money.of("-1000"))),
            openingValuation = DatedValuation(start, Money.zero("USD")),
            terminalValuation = DatedValuation(end, Money.of("1100")),
        ))
        assertThat(result).isInstanceOf(XirrResult.Rejected::class.java)
        assertThat((result as XirrResult.Rejected).reason).isEqualTo(XirrRejection.CURRENCY_MISMATCH)
    }

    @Test
    fun `known zero terminal cannot hide a mixed currency request`() {
        val result = XirrCalc.annualRate(XirrInput(
            flows = listOf(DatedCashFlow("in", start, Money.of("-1000"))),
            terminalValuation = DatedValuation(end, Money.zero("USD")),
        ))
        assertThat(result).isInstanceOf(XirrResult.Rejected::class.java)
        assertThat((result as XirrResult.Rejected).reason).isEqualTo(XirrRejection.CURRENCY_MISMATCH)
    }

    @Test
    fun `reserved looking caller ids cannot authorize an external zero flow`() {
        for (id in listOf("wm028-opening-valuation", "wm028-terminal-valuation")) {
            val result = XirrCalc.annualRate(XirrInput(flows = listOf(
                DatedCashFlow("in", start, Money.of("-1000")),
                DatedCashFlow(id, LocalDate(2024, 6, 1), Money.zero()),
                DatedCashFlow("out", end, Money.of("1100")),
            )))
            assertThat(result).isInstanceOf(XirrResult.Rejected::class.java)
            assertThat((result as XirrResult.Rejected).reason).isEqualTo(XirrRejection.ZERO_AMOUNT)
        }
    }
}
