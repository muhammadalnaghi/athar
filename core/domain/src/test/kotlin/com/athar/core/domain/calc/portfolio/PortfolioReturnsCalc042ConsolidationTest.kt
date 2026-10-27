package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.ExternalWithdrawal
import com.athar.core.domain.model.portfolio.PortfolioEvent
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.athar.core.domain.model.portfolio.SecuritySell
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * WM042 consolidation regressions for [PortfolioReturnsCalc] (spec:
 * `docs/specs/WM042-portfolio-return-facade.md`).
 *
 * Every expected number is an INDEPENDENT oracle computed with Python `decimal`
 * (precision 60): XIRR solves `-S + E*(1+r)^(-90/365) = 0`, i.e. `r = (E/S)^(365/90) - 1`,
 * for the 2026-01-01 → 2026-04-01 window (90 days). No expected value was taken from
 * any implementation output.
 *
 * RED evidence (old legacy facade, run before the repair — see
 * `build/qa/wealth-042/`): [xirr_nonConventionalMultipleSignChanges_isUndefinedNotFirstRoot],
 * [xirr_totalLossKnownZeroTerminal_isUndefinedNoConvergence],
 * [twr_flowlessDocument_isUndefinedNoDatedFlows] and
 * [twr_externalFlowBoundariesAreUncertifiableFromDailyDocument] fail with assertion
 * errors on the legacy implementation (it returned `Available` / `NO_DATED_FLOWS`),
 * while the compatibility anchors ([xirr_conventionalSingleRoot_returnsEngineCertifiedPercent],
 * [xirr_literalUsdLedger_currencyIsNeverConvertedOrRelabeled],
 * [xirr_noDatedInputs_isUndefinedNoDatedFlows],
 * [xirr_explicitZeroQuoteIsValuedNotMissing],
 * [xirr_missingBoundaryValuation_surfacesUnpricedSecurities]) already pass there.
 */
class PortfolioReturnsCalc042ConsolidationTest {

    private val secA = PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY)
    private val secB = PortfolioSecurity("SEC-B", "صندوق ب", AssetClass.SUKUK)

    private val d0 = LocalDate(2026, 1, 1)
    private val d1 = LocalDate(2026, 2, 1)
    private val d2 = LocalDate(2026, 3, 1)
    private val asOf = LocalDate(2026, 4, 1)

    private fun ledger(
        base: String = "SAR",
        openingCash: Money = Money.zero(base),
        securities: List<PortfolioSecurity> = emptyList(),
        events: List<PortfolioEvent> = emptyList(),
    ): PortfolioLedger = when (val o = PortfolioLedger.of(base, openingCash, securities, events)) {
        is Outcome.Success -> o.value
        is Outcome.Failure -> throw AssertionError("ledger rejected: ${o.error}")
    }

    private fun assertUndefinedNoConvergence(result: XirrResult) {
        assertThat(result).isInstanceOf(XirrResult.Undefined::class.java)
        val undefined = result as XirrResult.Undefined
        assertThat(undefined.reason).isEqualTo(ReturnUndefinedReason.NO_CONVERGENCE)
        assertThat(undefined.unpricedSecurityIds).isEmpty()
    }

    // ---- compatibility anchors (pass on the legacy implementation too) ---------

    @Test
    fun `xirr conventional single root returns engine certified percent`() {
        // Contribution 1000 on d0, all converted to 100 units of SEC-A @10.00,
        // quote 12.00 at asOf → terminal 1200. Oracle: (1.2)^(365/90) − 1 = +109.47101751%.
        val ledger = ledger(
            securities = listOf(secA),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("100"), Money.of("10.00"), Money.zero()),
            ),
        )
        val result = PortfolioReturnsCalc.xirr(ledger, listOf(SecurityQuote("SEC-A", asOf, Money.of("12.00"))), asOf)

        assertThat(result).isInstanceOf(XirrResult.Available::class.java)
        val available = result as XirrResult.Available
        assertThat(available.annualRatePercent.compareTo(BigDecimal("109.47101751"))).isEqualTo(0)
    }

    @Test
    fun `xirr literal USD ledger currency is never converted or relabeled`() {
        // Same numbers as the SAR anchor, denominated in USD: the percent must be
        // numerically identical (no FX relabeling) and the underlying valuation keeps
        // the literal USD currency.
        val ledger = ledger(
            base = "USD",
            securities = listOf(secA),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000", "USD")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("100"), Money.of("10.00", "USD"), Money.zero("USD")),
            ),
        )
        val quotes = listOf(SecurityQuote("SEC-A", asOf, Money.of("12.00", "USD")))
        val result = PortfolioReturnsCalc.xirr(ledger, quotes, asOf)

        assertThat(result).isInstanceOf(XirrResult.Available::class.java)
        val available = result as XirrResult.Available
        assertThat(available.annualRatePercent.compareTo(BigDecimal("109.47101751"))).isEqualTo(0)

        val accounting = when (val o = PortfolioAccountingCalc.account(ledger, quotes, asOf)) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> throw AssertionError("unexpected rejection: ${o.error}")
        }
        assertThat(accounting.valuation.total!!.currency).isEqualTo("USD")
    }

    @Test
    fun `xirr no dated inputs is undefined no dated flows`() {
        val ledger = ledger()
        val result = PortfolioReturnsCalc.xirr(ledger, emptyList(), asOf)
        assertThat(result).isInstanceOf(XirrResult.Undefined::class.java)
        val undefined = result as XirrResult.Undefined
        assertThat(undefined.reason).isEqualTo(ReturnUndefinedReason.NO_DATED_FLOWS)
    }

    @Test
    fun `xirr explicit zero quote is valued not missing`() {
        // 1000 in d0; SEC-A 50@10.00, SEC-B 100@2.50; cash 250. At asOf: A=12.00,
        // B explicitly 0.00 (known worthless — a REAL observation, not a missing quote).
        // Terminal = 250 + 600 + 0 = 850. Oracle: (0.85)^(365/90) − 1 = −48.26856424%.
        val ledger = ledger(
            securities = listOf(secA, secB),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("50"), Money.of("10.00"), Money.zero()),
                SecurityBuy("e3", d0, 3, "SEC-B", BigDecimal("100"), Money.of("2.50"), Money.zero()),
            ),
        )
        val quotes = listOf(
            SecurityQuote("SEC-A", asOf, Money.of("12.00")),
            SecurityQuote("SEC-B", asOf, Money.zero()),
        )
        val result = PortfolioReturnsCalc.xirr(ledger, quotes, asOf)

        assertThat(result).isInstanceOf(XirrResult.Available::class.java)
        val available = result as XirrResult.Available
        assertThat(available.annualRatePercent.compareTo(BigDecimal("-48.26856424"))).isEqualTo(0)
    }

    @Test
    fun `xirr missing boundary valuation surfaces unpriced securities`() {
        // 1000 converted to 100 units of SEC-A on d0; NO quote exists at or before asOf,
        // so the terminal valuation is genuinely partial — undefined with the unpriced
        // id surfaced, never completed with an invented price.
        val ledger = ledger(
            securities = listOf(secA),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("100"), Money.of("10.00"), Money.zero()),
            ),
        )
        val result = PortfolioReturnsCalc.xirr(ledger, emptyList(), asOf)
        assertThat(result).isInstanceOf(XirrResult.Undefined::class.java)
        val undefined = result as XirrResult.Undefined
        assertThat(undefined.reason).isEqualTo(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION)
        assertThat(undefined.unpricedSecurityIds).containsExactly("SEC-A")
    }

    // ---- WM042 honesty regressions (RED on the legacy implementation) ----------

    @Test
    fun `xirr non conventional multiple sign changes is undefined not first root`() {
        // Sign pattern of dated external flows (incl. folded terminal): −1000@d0,
        // +1900@d1, −800@d2, +150@asOf → three sign changes (non-conventional).
        // The legacy grid took the FIRST bracket and claimed `Available` for one of
        // several roots; the reviewed engine certifies ambiguity, and the facade must
        // map it to an honest Undefined — never pick a rate.
        val ledger = ledger(
            securities = listOf(secA, secB),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("100"), Money.of("10.00"), Money.zero()),
                SecuritySell("e3", d1, 1, "SEC-A", BigDecimal("100"), Money.of("20.00"), Money.zero()),
                ExternalWithdrawal("e4", d1, 2, Money.of("1900")),
                ExternalContribution("e5", d2, 1, Money.of("800")),
                SecurityBuy("e6", d2, 2, "SEC-B", BigDecimal("80"), Money.of("10.00"), Money.zero()),
            ),
        )
        val quotes = listOf(
            SecurityQuote("SEC-A", asOf, Money.of("20.00")),
            SecurityQuote("SEC-B", asOf, Money.of("0.625")),
        )
        assertUndefinedNoConvergence(PortfolioReturnsCalc.xirr(ledger, quotes, asOf))
    }

    @Test
    fun `xirr total loss known zero terminal is undefined no convergence`() {
        // 1000 in, SEC-A 100@10.00, quote explicitly 0.00 at asOf: genuine known-zero
        // terminal (valued, NOT missing). A same-sign series after folding the zero
        // terminal has no rate in any bounded window; the legacy `NO_DATED_FLOWS` claim
        // was false (dated flows exist) — an honest no-single-rate state is required.
        val ledger = ledger(
            securities = listOf(secA),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("100"), Money.of("10.00"), Money.zero()),
            ),
        )
        val result = PortfolioReturnsCalc.xirr(ledger, listOf(SecurityQuote("SEC-A", asOf, Money.zero())), asOf)
        assertUndefinedNoConvergence(result)
    }

    @Test
    fun `twr flowless document is undefined no dated flows`() {
        // Opening cash only: the document does not date the opening observation, so no
        // certified dated TWR chain exists. Legacy linked the undated seed to the as-of
        // valuation and claimed Available — removed as a fabrication.
        val ledger = ledger(openingCash = Money.of("1000"))
        val result = PortfolioReturnsCalc.timeWeighted(ledger, emptyList(), asOf)
        assertThat(result).isInstanceOf(TwrResult.Undefined::class.java)
        val undefined = result as TwrResult.Undefined
        assertThat(undefined.reason).isEqualTo(ReturnUndefinedReason.NO_DATED_FLOWS)
    }

    @Test
    fun `twr external flow boundaries are uncertifiable from daily document`() {
        // The ledger carries daily dated quotes only; it cannot supply the observed
        // before/after-flow boundary values the reviewed TWR contract requires
        // (`valueAfter = valueBefore + externalAmount`). Legacy fabricated a chain from
        // same-day valuations; the facade must return a typed unavailable state.
        val ledger = ledger(
            securities = listOf(secA),
            events = listOf(
                ExternalContribution("e1", d0, 1, Money.of("1000")),
                SecurityBuy("e2", d0, 2, "SEC-A", BigDecimal("100"), Money.of("10.00"), Money.zero()),
            ),
        )
        val quotes = listOf(SecurityQuote("SEC-A", asOf, Money.of("12.00")))
        val result = PortfolioReturnsCalc.timeWeighted(ledger, quotes, asOf)
        assertThat(result).isInstanceOf(TwrResult.Undefined::class.java)
        val undefined = result as TwrResult.Undefined
        assertThat(undefined.reason).isEqualTo(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION)
        assertThat(undefined.unpricedSecurityIds).isEmpty()
    }
}
