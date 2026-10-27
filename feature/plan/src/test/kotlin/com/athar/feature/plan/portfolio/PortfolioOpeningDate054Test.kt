package com.athar.feature.plan.portfolio

import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.core.domain.calc.portfolio.XirrResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PortfolioOpeningDate054Test {
    private val base = PortfolioPlanDocument(
        currency = "KWD",
        openingCashAmount = "1000.500",
        securities = listOf(SecurityInput("ID-LITERAL", "Public synthetic", "EQUITY")),
        events = listOf(EventInput(PortfolioEntryKind.BUY, "event-literal", "2026-01-01", "ID-LITERAL", "10.00", "90.000", "0.500", note = "  unchanged note  ")),
        quotes = listOf(QuoteInput("ID-LITERAL", "2026-04-01", "120.000")),
    )

    @Test
    fun `optional opening date round trips every exact field`() {
        val input = base.copy(openingCashDate = "2025-12-31")
        assertThat(PortfolioPlanCodec.decode(PortfolioPlanCodec.encode(input))).isEqualTo(PortfolioDecodeResult.Success(input))
    }

    @Test
    fun `legacy omission stays omitted and never acquires a first trade date`() {
        val encoded = PortfolioPlanCodec.encode(base)
        assertThat(encoded).doesNotContain("openingCashDate")
        assertThat(PortfolioPlanCodec.decode(encoded)).isEqualTo(PortfolioDecodeResult.Success(base))
        assertThat(PortfolioWorkbench.build(base).xirr)
            .isEqualTo(XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION))
    }

    @Test
    fun `supplied malformed opening date types are refused rather than discarded`() {
        val encoded = PortfolioPlanCodec.encode(base.copy(openingCashDate = "2025-12-31"))
        for (token in listOf("null", "20251231", "true", "[]", "{}")) {
            val malformed = encoded.replace("\"openingCashDate\":\"2025-12-31\"", "\"openingCashDate\":$token")
            assertThat(PortfolioPlanCodec.decode(malformed))
                .isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE))
        }
    }

    @Test
    fun `impossible or non ISO supplied opening dates are refused`() {
        for (value in listOf("2026-02-30", "31/12/2025", "")) {
            assertThat(PortfolioPlanCodec.decode(PortfolioPlanCodec.encode(base.copy(openingCashDate = value))))
                .isEqualTo(PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_DATE))
        }
    }

    @Test
    fun `opening date after a saved event refuses replay without reordering inputs`() {
        val document = base.copy(openingCashDate = "2026-01-02")
        val result = PortfolioWorkbench.build(document)
        assertThat(result.rejection).isEqualTo(PortfolioRejectToken.MALFORMED_OPENING_DATE.name)
        assertThat(result.document).isEqualTo(document)
        assertThat(result.accounting).isNull()
    }

    @Test
    fun `explicit opening date enables returns without adding cash twice`() {
        val result = PortfolioWorkbench.build(base.copy(openingCashDate = "2025-12-31"))
        assertThat(result.rejection).isNull()
        assertThat(result.xirr).isInstanceOf(XirrResult.Available::class.java)
        assertThat(result.accounting!!.cash.amount.toPlainString()).isEqualTo("100.00000")
        assertThat(result.accounting!!.valuation.total!!.amount.compareTo(java.math.BigDecimal("1300"))).isEqualTo(0)
    }
}
