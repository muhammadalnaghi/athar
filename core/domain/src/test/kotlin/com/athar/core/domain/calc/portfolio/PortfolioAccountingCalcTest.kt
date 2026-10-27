package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.CashDividend
import com.athar.core.domain.model.portfolio.DividendDestination
import com.athar.core.domain.model.portfolio.DividendReinvestment
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.ExternalWithdrawal
import com.athar.core.domain.model.portfolio.LedgerRejection
import com.athar.core.domain.model.portfolio.PortfolioEvent
import com.athar.core.domain.model.portfolio.PortfolioFee
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.PriceStatus
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.athar.core.domain.model.portfolio.SecuritySell
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Numerical oracle tests for [PortfolioAccountingCalc]. Every expected number below is
 * hand-computed; see the arithmetic in each test. The master scenario exercises:
 * contribution → buy → additional buy → second security → cash dividend (both destinations)
 * → reinvestment → partial sell → portfolio fee → withdrawal, then reconciles money gain
 * two independent ways (flows identity and components identity).
 */
class PortfolioAccountingCalcTest {

    // ---- master scenario oracle -------------------------------------------------

    // SEC-A: buy 100 @10.00 fee 5.00 → basis 1005.00
    //        buy  50 @12.00 fee 3.00 → basis 1608.00, qty 150
    //        reinvest 50.00 @25.00 (2 units) → basis 1658.00, qty 152
    //        sell 76 @15.00 fee 2.50 → proceeds 1137.50, released 1658*(76/152)=829.00,
    //                                  realized 308.50, remaining qty 76 basis 829.00
    // SEC-B: buy 200 @5.00 fee 2.00 → basis 1002.00, qty 200
    // Cash: 10000 −1005 −603 −1002 +25 +1137.50 −7 −4000 = 4545.50
    // Quotes as-of 2026-03-31 (staleAfterDays 7): A=11.00 (2026-03-31, fresh), B=5.50 (2026-02-28, stale)
    // MV = 76*11 + 200*5.50 = 836 + 1100 = 1936.00; total = 4545.50 + 1936 = 6481.50
    // Money gain (flows):    6481.50 − 0 − 10000 + 4000 + 15 = 496.50
    // Money gain (comps):    308.50 + (7 + 98) + 25 + 15 + 50 − 7 = 496.50
    private val secA = PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY)
    private val secB = PortfolioSecurity("SEC-B", "صندوق ب", AssetClass.SUKUK)

    private val masterEvents: List<PortfolioEvent> = listOf(
        ExternalContribution("e1", LocalDate(2026, 1, 5), 1, Money.of("10000")),
        SecurityBuy(
            "e2", LocalDate(2026, 1, 6), 1, "SEC-A",
            BigDecimal("100"), Money.of("10.00"), Money.of("5.00"),
        ),
        SecurityBuy(
            "e3", LocalDate(2026, 1, 10), 1, "SEC-A",
            BigDecimal("50"), Money.of("12.00"), Money.of("3.00"),
        ),
        SecurityBuy(
            "e3b", LocalDate(2026, 1, 12), 1, "SEC-B",
            BigDecimal("200"), Money.of("5.00"), Money.of("2.00"),
        ),
        CashDividend(
            "e4", LocalDate(2026, 2, 1), 1, "SEC-A",
            Money.of("25"), DividendDestination.PORTFOLIO_CASH,
        ),
        CashDividend(
            "e5", LocalDate(2026, 2, 1), 2, "SEC-A",
            Money.of("15"), DividendDestination.EXTERNAL_ACCOUNT,
        ),
        DividendReinvestment(
            "e6", LocalDate(2026, 2, 15), 1, "SEC-A",
            Money.of("50.00"), Money.of("25.00"), BigDecimal("2"),
        ),
        SecuritySell(
            "e7", LocalDate(2026, 3, 1), 1, "SEC-A",
            BigDecimal("76"), Money.of("15.00"), Money.of("2.50"),
        ),
        PortfolioFee("e8", LocalDate(2026, 3, 5), 1, Money.of("7"), "custody"),
        ExternalWithdrawal("e9", LocalDate(2026, 3, 10), 1, Money.of("4000")),
    )

    private val masterQuotes = listOf(
        SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("11.00")),
        SecurityQuote("SEC-B", LocalDate(2026, 2, 28), Money.of("5.50")),
    )

    private fun ledger(
        events: List<PortfolioEvent> = masterEvents,
        openingCash: Money = Money.zero(),
        securities: List<PortfolioSecurity> = listOf(secA, secB),
    ): PortfolioLedger = when (val o = PortfolioLedger.of("SAR", openingCash, securities, events)) {
        is Outcome.Success -> o.value
        is Outcome.Failure -> throw AssertionError("ledger rejected: ${o.error}")
    }

    private fun <T> success(outcome: Outcome<T, LedgerRejection>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> throw AssertionError("unexpected rejection: ${outcome.error}")
    }

    private fun failure(outcome: Outcome<*, LedgerRejection>): LedgerRejection = when (outcome) {
        is Outcome.Failure -> outcome.error
        is Outcome.Success -> throw AssertionError("expected rejection, got ${outcome.value}")
    }

    private fun assertMoney(expected: String, actual: Money?) {
        assertThat(actual).isNotNull()
        assertThat(actual!!.currency).isEqualTo("SAR")
        assertThat(actual.amount.stripTrailingZeros())
            .isEquivalentAccordingToCompareTo(BigDecimal(expected).stripTrailingZeros())
    }

    @Test
    fun `master scenario produces exact cash, holdings, valuation and money gain`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledger(),
                quotes = masterQuotes,
                asOf = LocalDate(2026, 3, 31),
            ),
        )

        assertThat(accounting.baseCurrency).isEqualTo("SAR")
        assertThat(accounting.valuation.isPartial).isFalse()

        // Cash ledger oracle
        assertMoney("4545.50", accounting.cash)

        // Holdings + weighted-average cost
        assertThat(accounting.holdings.map { it.securityId }).containsExactly("SEC-A", "SEC-B")
        val a = accounting.holdings.first { it.securityId == "SEC-A" }
        val b = accounting.holdings.first { it.securityId == "SEC-B" }
        assertThat(a.quantity).isEquivalentAccordingToCompareTo(BigDecimal("76"))
        assertMoney("829.00", a.costBasis)
        // 829/76 = 10.9078947368… → 8dp HALF_EVEN → 10.90789474
        assertMoney("10.90789474", a.averageCostPerUnit)
        assertThat(b.quantity).isEquivalentAccordingToCompareTo(BigDecimal("200"))
        assertMoney("1002.00", b.costBasis)
        assertMoney("5.01", b.averageCostPerUnit)

        // Valuation from dated quotes (never fetched/invented)
        assertMoney("1936.00", accounting.valuation.pricedHoldingsValue)
        assertMoney("6481.50", accounting.valuation.total)
        val valA = accounting.valuation.holdings.first { it.securityId == "SEC-A" }
        val valB = accounting.valuation.holdings.first { it.securityId == "SEC-B" }
        assertThat(valA.status).isInstanceOf(PriceStatus.Priced::class.java)
        val pricedA = valA.status as PriceStatus.Priced
        assertThat(pricedA.stale).isFalse()
        assertThat(pricedA.quoteDate).isEqualTo(LocalDate(2026, 3, 31))
        val pricedB = valB.status as PriceStatus.Priced
        assertThat(pricedB.stale).isTrue() // quote is 31 days old, window is 7
        assertMoney("836.00", valA.marketValue)
        assertMoney("1100.00", valB.marketValue)

        // Realized gain on the partial sell
        assertThat(accounting.realizedGains).hasSize(1)
        val realized = accounting.realizedGains.single()
        assertThat(realized.securityId).isEqualTo("SEC-A")
        assertThat(realized.eventId).isEqualTo("e7")
        assertThat(realized.date).isEqualTo(LocalDate(2026, 3, 1))
        assertThat(realized.quantity).isEquivalentAccordingToCompareTo(BigDecimal("76"))
        assertMoney("1137.50", realized.proceeds)
        assertMoney("829.00", realized.costOfSoldUnits)
        assertMoney("308.50", realized.gain)

        // Unrealized gains at as-of quotes
        assertMoney("7.00", accounting.unrealizedGains.first { it.securityId == "SEC-A" }.gain)
        assertMoney("98.00", accounting.unrealizedGains.first { it.securityId == "SEC-B" }.gain)

        // Flow totals
        assertMoney("10000", accounting.contributions)
        assertMoney("4000", accounting.withdrawals)
        assertMoney("25", accounting.dividendsToCash)
        assertMoney("15", accounting.dividendsToExternal)
        assertMoney("50.00", accounting.dividendsReinvested)
        assertMoney("7", accounting.portfolioFees)
        assertMoney("12.50", accounting.tradeFees)

        // Economic money gain — NOT IRR/TWR/annualized (typed as MoneyGain)
        assertMoney("496.50", accounting.moneyGain.amount)
        assertThat(accounting.moneyGain.unavailableBecause).isEmpty()
    }

    @Test
    fun `money gain reconciles through the flows identity`() {
        val accounting = success(
            PortfolioAccountingCalc.account(ledger(), masterQuotes, LocalDate(2026, 3, 31)),
        )
        // closing − opening − contributions + withdrawals + external dividends
        val flowsGain = accounting.valuation.total!! -
            accounting.openingCash -
            accounting.contributions +
            accounting.withdrawals +
            accounting.dividendsToExternal
        assertThat(flowsGain.amount.stripTrailingZeros())
            .isEquivalentAccordingToCompareTo(BigDecimal("496.50"))
        assertThat(flowsGain.amount)
            .isEquivalentAccordingToCompareTo(accounting.moneyGain.amount!!.amount)
    }

    @Test
    fun `money gain reconciles through the components identity`() {
        val accounting = success(
            PortfolioAccountingCalc.account(ledger(), masterQuotes, LocalDate(2026, 3, 31)),
        )
        // realized + unrealized + all dividends (cash, external, reinvested) − portfolio fees
        val unrealizedTotal = accounting.unrealizedGains
            .fold(Money.zero()) { acc, g -> acc + g.gain }
        val components = accounting.realizedGains.fold(Money.zero()) { acc, g -> acc + g.gain } +
            unrealizedTotal +
            accounting.dividendsToCash +
            accounting.dividendsToExternal +
            accounting.dividendsReinvested -
            accounting.portfolioFees
        assertThat(components.amount)
            .isEquivalentAccordingToCompareTo(accounting.moneyGain.amount!!.amount)
        assertThat(components.amount.stripTrailingZeros())
            .isEquivalentAccordingToCompareTo(BigDecimal("496.50"))
    }

    @Test
    fun `external contribution is not reported as money gain`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger(events = listOf(ExternalContribution("e1", LocalDate(2026, 1, 5), 1, Money.of("10000")))),
                quotes = emptyList(),
                asOf = LocalDate(2026, 1, 31),
            ),
        )
        assertMoney("10000", accounting.cash)
        assertMoney("0", accounting.moneyGain.amount) // gain is zero, not the deposit
    }

    // ---- sells: partial vs full liquidation ----

    @Test
    fun `full liquidation realizes the whole basis and leaves no holding`() {
        val events = listOf(
            ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("1000")),
            SecurityBuy(
                "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                BigDecimal("100"), Money.of("10.00"), Money.of("0"),
            ),
            SecuritySell(
                "s1", LocalDate(2026, 2, 1), 1, "SEC-A",
                BigDecimal("100"), Money.of("12.00"), Money.of("0"),
            ),
        )
        val accounting = success(
            PortfolioAccountingCalc.account(ledger(events = events), emptyList(), LocalDate(2026, 3, 1)),
        )
        assertThat(accounting.holdings).isEmpty()
        assertThat(accounting.valuation.holdings).isEmpty()
        assertMoney("1200.00", accounting.cash)
        assertMoney("200.00", accounting.realizedGains.single().gain)
        assertThat(accounting.unrealizedGains).isEmpty()
        assertMoney("1200.00", accounting.valuation.total)
        assertThat(accounting.valuation.isPartial).isFalse() // no holdings → nothing unpriced
        assertMoney("200.00", accounting.moneyGain.amount) // 1200 − 1000
    }

    // ---- economic rejections during the fold ----

    @Test
    fun `selling more than held is rejected as unsupported short sale`() {
        val events = listOf(
            ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("1000")),
            SecurityBuy(
                "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                BigDecimal("10"), Money.of("10.00"), Money.of("0"),
            ),
            SecuritySell(
                "s1", LocalDate(2026, 1, 7), 1, "SEC-A",
                BigDecimal("11"), Money.of("10.00"), Money.of("0"),
            ),
        )
        val rejection = failure(
            PortfolioAccountingCalc.account(ledger(events = events), emptyList(), LocalDate(2026, 3, 1)),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.UnsupportedShortSale::class.java)
        val short = rejection as LedgerRejection.UnsupportedShortSale
        assertThat(short.heldQuantity).isEquivalentAccordingToCompareTo(BigDecimal("10"))
        assertThat(short.requestedQuantity).isEquivalentAccordingToCompareTo(BigDecimal("11"))
    }

    @Test
    fun `buy exceeding cash is rejected as unsupported negative cash`() {
        val events = listOf(
            ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("100")),
            SecurityBuy(
                "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                BigDecimal("20"), Money.of("10.00"), Money.of("0"),
            ),
        )
        val rejection = failure(
            PortfolioAccountingCalc.account(ledger(events = events), emptyList(), LocalDate(2026, 3, 1)),
        )
        val insufficient = rejection as LedgerRejection.InsufficientCash
        assertMoney("200.00", insufficient.required)
        assertMoney("100", insufficient.available)
    }

    @Test
    fun `withdrawal exceeding cash is rejected`() {
        val events = listOf(
            ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("100")),
            ExternalWithdrawal("w1", LocalDate(2026, 1, 6), 1, Money.of("200")),
        )
        val rejection = failure(
            PortfolioAccountingCalc.account(ledger(events = events), emptyList(), LocalDate(2026, 3, 1)),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.InsufficientCash::class.java)
    }

    @Test
    fun `portfolio fee exceeding cash is rejected not silently repaired`() {
        val events = listOf(
            ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("5")),
            PortfolioFee("f1", LocalDate(2026, 1, 6), 1, Money.of("6"), "custody"),
        )
        val rejection = failure(
            PortfolioAccountingCalc.account(ledger(events = events), emptyList(), LocalDate(2026, 3, 1)),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.InsufficientCash::class.java)
    }

    // ---- as-of semantics ----

    @Test
    fun `events strictly after asOf are excluded from cash and holdings`() {
        val accounting = success(
            PortfolioAccountingCalc.account(ledger(), masterQuotes, LocalDate(2026, 2, 1)),
        )
        // applied: e1..e4 only → cash 10000 − 1005 − 603 − 1002 + 25 = 7415
        assertMoney("7415", accounting.cash)
        val a = accounting.holdings.first { it.securityId == "SEC-A" }
        assertThat(a.quantity).isEquivalentAccordingToCompareTo(BigDecimal("150"))
        assertMoney("1608.00", a.costBasis)
        assertThat(accounting.realizedGains).isEmpty() // sell on 03-01 not yet applied
        // No quotes for A or B on/before 02-01 → both unavailable (not 0), valuation partial
        assertThat(accounting.valuation.isPartial).isTrue()
        assertThat(accounting.valuation.total).isNull()
        assertThat(accounting.valuation.unpricedSecurityIds).containsExactly("SEC-A", "SEC-B")
        assertThat(accounting.moneyGain.amount).isNull()
        assertThat(accounting.moneyGain.unavailableBecause).containsExactly("SEC-A", "SEC-B")
    }

    // ---- quote ambiguity ----

    @Test
    fun `two quotes for the same security on the same date are ambiguous and rejected`() {
        val rejection = failure(
            PortfolioAccountingCalc.account(
                ledger(),
                listOf(
                    SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("11.00")),
                    SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("12.00")),
                    SecurityQuote("SEC-B", LocalDate(2026, 2, 28), Money.of("5.50")),
                ),
                LocalDate(2026, 3, 31),
            ),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.AmbiguousQuotes::class.java)
        val ambiguous = rejection as LedgerRejection.AmbiguousQuotes
        assertThat(ambiguous.securityId).isEqualTo("SEC-A")
        assertThat(ambiguous.date).isEqualTo(LocalDate(2026, 3, 31))
    }
}
