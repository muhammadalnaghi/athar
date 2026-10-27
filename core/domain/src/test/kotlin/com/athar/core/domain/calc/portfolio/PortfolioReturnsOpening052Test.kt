package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.calc.performance.XirrCalc
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.DatedValuation
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.PortfolioEvent
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * WM052: explicitly dated opening cash in reviewed XIRR economics.
 *
 * Public fixture: 1000 SAR opening on 2026-01-01, BUY 100 units at 10 the same day
 * (no contribution), quote 12 on 2026-04-01 with as-of the same date. Closing total
 * 1200, money gain 200, annual rate (1.2^(365/90) − 1) × 100 = 109.47101751% at the
 * 8dp facade over the reviewed engine. Dated opening must survive alongside later
 * contributions (both capital sources reach the engine); the same fixture WITHOUT a
 * date still withholds; as-of before the opening date never exposes future capital.
 */
class PortfolioReturnsOpening052Test {

    private val openingDate = LocalDate(2026, 1, 1)
    private val contributionDate = LocalDate(2026, 2, 1)
    private val asOf = LocalDate(2026, 4, 1)
    private val security = PortfolioSecurity("OPEN-052", "صندوق ٠٥٢", AssetClass.EQUITY)

    private fun buyOnOpening() = SecurityBuy(
        eventId = "buy-1",
        date = openingDate,
        sequence = 1,
        securityRef = security.securityId,
        quantity = BigDecimal("100"),
        unitPrice = Money.of("10"),
        fee = Money.zero(),
    )

    private fun ledgerOf(
        events: List<PortfolioEvent>,
        base: String = "SAR",
        openingCash: String = "1000",
        openingCashDate: LocalDate? = openingDate,
    ): PortfolioLedger = (
        PortfolioLedger.of(
            baseCurrency = base,
            openingCash = Money.of(openingCash, base),
            securities = listOf(security),
            events = events,
            openingCashDate = openingCashDate,
        ) as Outcome.Success
        ).value

    @Test
    fun `dated opening fixture totals 1200 with gain 200 and reviewed annual rate 109_47101751`() {
        val ledger = ledgerOf(listOf(buyOnOpening()))
        val quotes = listOf(SecurityQuote(security.securityId, asOf, Money.of("12")))
        val accounting = (
            PortfolioAccountingCalc.account(ledger, quotes, asOf) as Outcome.Success
            ).value

        // Independent decimals: 100 units × 12 = 1200; 1200 − 1000 opening = 200 gain.
        assertThat(accounting.valuation.total!!.amount.compareTo(BigDecimal("1200"))).isEqualTo(0)
        assertThat(accounting.moneyGain.amount!!.amount.compareTo(BigDecimal("200"))).isEqualTo(0)

        // Closed form (1.2^(365/90) − 1) × 100, 8dp HALF_EVEN facade over the reviewed engine.
        val xirr = PortfolioReturnsCalc.xirr(ledger, quotes, asOf)
        assertThat(xirr).isEqualTo(XirrResult.Available(BigDecimal("109.47101751")))
    }

    @Test
    fun `dated opening plus later contribution retains both capital sources through the real engine`() {
        val events = listOf(
            ExternalContribution(
                eventId = "contrib-1",
                date = contributionDate,
                sequence = 1,
                amount = Money.of("500"),
            ),
            SecurityBuy(
                eventId = "buy-2",
                date = contributionDate,
                sequence = 2,
                securityRef = security.securityId,
                quantity = BigDecimal("50"),
                unitPrice = Money.of("10"),
                fee = Money.zero(),
            ),
            buyOnOpening().let { it.copy(sequence = 2) },
        )
        val ledger = ledgerOf(events)
        val quotes = listOf(SecurityQuote(security.securityId, asOf, Money.of("12")))
        val accounting = (
            PortfolioAccountingCalc.account(ledger, quotes, asOf) as Outcome.Success
            ).value
        // 1000 opening + 500 contribution − 1500 buys = 0 cash; 150 units × 12 = 1800.
        val terminal = accounting.valuation.total!!
        assertThat(accounting.cash.amount.signum()).isEqualTo(0)
        assertThat(terminal.amount.compareTo(BigDecimal("1800"))).isEqualTo(0)
        assertThat(accounting.moneyGain.amount!!.amount.compareTo(BigDecimal("300"))).isEqualTo(0)

        val viaFacade = PortfolioReturnsCalc.xirr(ledger, quotes, asOf)
        // Direct reviewed-engine call with BOTH capital sources: dated opening valuation
        // plus the dated contribution flow. The bridge must equal this exactly.
        val viaEngine = PortfolioReturns042XirrBridge.translate(
            XirrCalc.annualRate(
                XirrInput(
                    flows = listOf(
                        DatedCashFlow("contrib-1", contributionDate, Money.of("-500")),
                    ),
                    openingValuation = DatedValuation(openingDate, Money.of("1000")),
                    terminalValuation = DatedValuation(asOf, terminal),
                ),
            ),
        )
        assertThat(viaFacade).isEqualTo(viaEngine)
        assertThat(viaFacade).isInstanceOf(XirrResult.Available::class.java)
    }

    @Test
    fun `undated same fixture still withholds opening capital`() {
        val ledger = ledgerOf(listOf(buyOnOpening()), openingCashDate = null)
        val quotes = listOf(SecurityQuote(security.securityId, asOf, Money.of("12")))
        assertThat(PortfolioReturnsCalc.xirr(ledger, quotes, asOf)).isEqualTo(
            XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION),
        )
    }

    @Test
    fun `as-of before the explicit opening date never exposes future capital`() {
        val before = LocalDate(2025, 12, 15)
        val ledger = ledgerOf(listOf(buyOnOpening()))
        // The bridge withholds before any economics run: dated opening capital that
        // does not exist yet at the as-of date can never surface as an invested amount
        // or a rate. (Date-vs-event ordering is pre-validated by the parent codec.)
        assertThat(PortfolioReturnsCalc.xirr(ledger, emptyList(), before)).isEqualTo(
            XirrResult.Undefined(ReturnUndefinedReason.AS_OF_BEFORE_OPENING_DATE),
        )
    }

    @Test
    fun `literal USD base is carried unconverted through the same economics`() {
        val ledger = ledgerOf(
            listOf(buyOnOpening().copy(unitPrice = Money.of("10", "USD"), fee = Money.of("0", "USD"))),
            base = "USD",
        )
        val quotes = listOf(SecurityQuote(security.securityId, asOf, Money.of("12", "USD")))
        assertThat(PortfolioReturnsCalc.xirr(ledger, quotes, asOf)).isEqualTo(
            XirrResult.Available(BigDecimal("109.47101751")),
        )
    }

    @Test
    fun `engine reserved valuation id in an event id cannot collide or misattribute`() {
        // The reviewed engine folds the opening valuation under its own reserved id
        // ("wm028-opening-valuation"); this bridge invents no flow id and does no
        // id-based filtering, so even a literal collision must delegate purely.
        val events = listOf(
            ExternalContribution(
                eventId = "wm028-opening-valuation",
                date = contributionDate,
                sequence = 1,
                amount = Money.of("500"),
            ),
            buyOnOpening(),
        )
        val ledger = ledgerOf(events)
        val quotes = listOf(SecurityQuote(security.securityId, asOf, Money.of("12")))
        val accounting = (
            PortfolioAccountingCalc.account(ledger, quotes, asOf) as Outcome.Success
            ).value
        val viaFacade = PortfolioReturnsCalc.xirr(ledger, quotes, asOf)
        val viaEngine = PortfolioReturns042XirrBridge.translate(
            XirrCalc.annualRate(
                XirrInput(
                    flows = listOf(
                        DatedCashFlow("wm028-opening-valuation", contributionDate, Money.of("-500")),
                    ),
                    openingValuation = DatedValuation(openingDate, Money.of("1000")),
                    terminalValuation = DatedValuation(asOf, accounting.valuation.total!!),
                ),
            ),
        )
        assertThat(viaFacade).isEqualTo(viaEngine)
    }

    @Test
    fun `zero opening cash keeps the legacy no dated flows result`() {
        val ledger = ledgerOf(emptyList(), openingCash = "0")
        assertThat(PortfolioReturnsCalc.xirr(ledger, emptyList(), asOf)).isEqualTo(
            XirrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS),
        )
    }

    @Test
    fun `twr stays honestly unsupported without per-flow boundary valuations`() {
        val datedOpeningOnly = ledgerOf(listOf(buyOnOpening()))
        assertThat(PortfolioReturnsCalc.timeWeighted(datedOpeningOnly, emptyList(), asOf)).isEqualTo(
            TwrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION),
        )
        val withFlow = ledgerOf(
            listOf(
                buyOnOpening(),
                ExternalContribution("contrib-1", contributionDate, 1, Money.of("500")),
            ),
        )
        assertThat(PortfolioReturnsCalc.timeWeighted(withFlow, emptyList(), asOf)).isEqualTo(
            TwrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION),
        )
    }
}
