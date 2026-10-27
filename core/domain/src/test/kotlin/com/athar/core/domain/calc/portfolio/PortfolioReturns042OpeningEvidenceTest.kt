package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class PortfolioReturns042OpeningEvidenceTest {
    private val start = LocalDate(2026, 1, 1)
    private val end = LocalDate(2026, 4, 1)

    @Test
    fun openingCapitalMustNotDisappearWhenDatedContributionExists() {
        val ledger = (PortfolioLedger.of("SAR", Money.of("1000"), emptyList(),
            listOf(ExternalContribution("deposit", start, 1, Money.of("1000")))) as Outcome.Success).value
        val accounting = (PortfolioAccountingCalc.account(ledger, emptyList(), end) as Outcome.Success).value
        // Independent Decimal: 1000 opening +1000 deposited =2000. No market gain.
        assertThat(accounting.valuation.total!!.amount.compareTo(BigDecimal("2000"))).isEqualTo(0)
        assertThat(PortfolioReturnsCalc.xirr(ledger, emptyList(), end)).isEqualTo(
            XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION),
        )
    }

    @Test
    fun firstTradeDateIsNotAnObservedOpeningBalanceDate() {
        val security = PortfolioSecurity("DATE-PROBE", "Date probe", AssetClass.EQUITY)
        val ledger = (PortfolioLedger.of("SAR", Money.of("1000"), listOf(security),
            listOf(SecurityBuy("buy", start, 1, security.securityId, BigDecimal("100"),
                Money.of("10"), Money.zero()))) as Outcome.Success).value
        val quotes = listOf(SecurityQuote(security.securityId, end, Money.of("12")))
        val accounting = (PortfolioAccountingCalc.account(ledger, quotes, end) as Outcome.Success).value
        // Independent Decimal: 100 units at12 =1200; the amount is known, its opening date is not.
        assertThat(accounting.valuation.total!!.amount.compareTo(BigDecimal("1200"))).isEqualTo(0)
        assertThat(PortfolioReturnsCalc.xirr(ledger, quotes, end)).isEqualTo(
            XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION),
        )
    }
}
