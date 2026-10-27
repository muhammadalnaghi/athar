package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.LedgerRejection
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.PriceStatus
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Dated-quote boundary oracles: the latest quote dated **on or before** asOf is used;
 * quotes dated after asOf are ignored; a holding with no usable quote is reported
 * `Unavailable` (never valued at 0); quotes older than the staleness window are
 * flagged stale but still usable with disclosure. All prices here are user-known
 * historical constants — nothing is fetched or invented.
 */
class PortfolioValuationQuoteTest {

    private val sec = PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY)

    private fun ledgerWithHolding(): PortfolioLedger = when (
        val o = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.zero(),
            securities = listOf(sec),
            events = listOf(
                ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("10000")),
                SecurityBuy(
                    "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                    BigDecimal("10"), Money.of("10.00"), Money.of("0"),
                ),
            ),
        )
    ) {
        is Outcome.Success -> o.value
        is Outcome.Failure -> throw AssertionError("unexpected ${o.error}")
    }

    private fun <T> success(outcome: Outcome<T, LedgerRejection>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> throw AssertionError("unexpected rejection: ${outcome.error}")
    }

    @Test
    fun `quote dated exactly on asOf is used`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("11.00"))),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        val holding = accounting.valuation.holdings.single()
        val priced = holding.status as PriceStatus.Priced
        assertThat(priced.quoteDate).isEqualTo(LocalDate(2026, 3, 31))
        assertThat(priced.unitPrice).isEqualTo(Money.of("11.00"))
        assertThat(holding.marketValue).isEquivalentAccordingToCompareTo(Money.of("110.00"))
        assertThat(accounting.valuation.isPartial).isFalse()
    }

    @Test
    fun `latest quote on or before asOf wins over older ones`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(
                    SecurityQuote("SEC-A", LocalDate(2026, 1, 20), Money.of("9.00")),
                    SecurityQuote("SEC-A", LocalDate(2026, 2, 15), Money.of("10.50")),
                ),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        val priced = accounting.valuation.holdings.single().status as PriceStatus.Priced
        assertThat(priced.quoteDate).isEqualTo(LocalDate(2026, 2, 15))
        assertThat(accounting.valuation.holdings.single().marketValue)
            .isEquivalentAccordingToCompareTo(Money.of("105.00"))
    }

    @Test
    fun `quote dated after asOf is never used`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 4, 2), Money.of("99.00"))),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        // No quote on/before 03-31 → unavailable, NOT the future price, NOT zero
        val holding = accounting.valuation.holdings.single()
        assertThat(holding.status).isEqualTo(PriceStatus.Unavailable)
        assertThat(holding.marketValue).isNull()
        assertThat(accounting.valuation.total).isNull()
        assertThat(accounting.valuation.isPartial).isTrue()
        assertThat(accounting.valuation.unpricedSecurityIds).containsExactly("SEC-A")
        assertThat(accounting.moneyGain.amount).isNull()
    }

    @Test
    fun `unpriced holding contributes nothing to priced value but total stays null`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = emptyList(),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        // cash 9900 (10000 − 10×10.00 cost) is priced; the holding is not
        assertThat(accounting.cash).isEquivalentAccordingToCompareTo(Money.of("9900"))
        assertThat(accounting.valuation.pricedHoldingsValue)
            .isEquivalentAccordingToCompareTo(Money.zero())
        assertThat(accounting.valuation.total).isNull() // never counts the holding as 0
        assertThat(accounting.moneyGain.unavailableBecause).containsExactly("SEC-A")
    }

    @Test
    fun `stale quote is flagged but still usable and disclosed`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 20), Money.of("12.00"))),
                asOf = LocalDate(2026, 3, 31),
                staleAfterDays = 7,
            ),
        )
        val priced = accounting.valuation.holdings.single().status as PriceStatus.Priced
        assertThat(priced.stale).isTrue() // 11 days old > 7
        assertThat(accounting.valuation.total).isNotNull() // still a complete valuation
        assertThat(accounting.valuation.holdings.single().marketValue)
            .isEquivalentAccordingToCompareTo(Money.of("120.00"))
    }

    @Test
    fun `staleness window is configurable`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 20), Money.of("12.00"))),
                asOf = LocalDate(2026, 3, 31),
                staleAfterDays = 30, // 11 days old fits inside 30
            ),
        )
        val priced = accounting.valuation.holdings.single().status as PriceStatus.Priced
        assertThat(priced.stale).isFalse()
    }

    // ---- quote price sign: explicit known-zero vs missing quote vs negative ----

    @Test
    fun `explicit zero quote is a known-zero valuation not unpriced`() {
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("0.00"))),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        val holding = accounting.valuation.holdings.single()
        // Priced at a known 0.00 — distinct from Unavailable, which is never synthesized.
        val priced = holding.status as PriceStatus.Priced
        assertThat(priced.unitPrice).isEquivalentAccordingToCompareTo(Money.of("0.00"))
        assertThat(holding.marketValue).isEquivalentAccordingToCompareTo(Money.zero())
        assertThat(accounting.valuation.isPartial).isFalse()
        assertThat(accounting.valuation.unpricedSecurityIds).isEmpty()
        // cash 9900 + holdings 0.00: complete valuation, money gain computable (−100).
        assertThat(accounting.valuation.total).isEquivalentAccordingToCompareTo(Money.of("9900"))
        assertThat(accounting.moneyGain.amount).isNotNull()
    }

    @Test
    fun `negative quote price is rejected not silently valued`() {
        val rejection = when (
            val o = PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("-1.00"))),
                asOf = LocalDate(2026, 3, 31),
            )
        ) {
            is Outcome.Failure -> o.error
            is Outcome.Success -> throw AssertionError("expected rejection, got ${o.value}")
        }
        assertThat(rejection).isEqualTo(
            LedgerRejection.NegativeQuotePrice("SEC-A", LocalDate(2026, 3, 31), Money.of("-1.00")),
        )
    }

    @Test
    fun `negative staleAfterDays is rejected not flipped arbitrarily`() {
        val rejection = when (
            val o = PortfolioAccountingCalc.account(
                ledger = ledgerWithHolding(),
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 20), Money.of("12.00"))),
                asOf = LocalDate(2026, 3, 31),
                staleAfterDays = -1,
            )
        ) {
            is Outcome.Failure -> o.error
            is Outcome.Success -> throw AssertionError("expected rejection, got ${o.value}")
        }
        assertThat(rejection).isEqualTo(LedgerRejection.NegativeStaleAfterDays(-1))
    }

    @Test
    fun `valuation is partial when only one of two holdings is priced`() {
        val secB = PortfolioSecurity("SEC-B", "صندوق ب", AssetClass.SUKUK)
        val ledger = when (
            val o = PortfolioLedger.of(
                baseCurrency = "SAR",
                openingCash = Money.zero(),
                securities = listOf(sec, secB),
                events = listOf(
                    ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("10000")),
                    SecurityBuy(
                        "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                        BigDecimal("10"), Money.of("10.00"), Money.of("0"),
                    ),
                    SecurityBuy(
                        "b2", LocalDate(2026, 1, 6), 2, "SEC-B",
                        BigDecimal("100"), Money.of("5.00"), Money.of("0"),
                    ),
                ),
            )
        ) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> throw AssertionError("unexpected ${o.error}")
        }
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledger,
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("11.00"))),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        assertThat(accounting.valuation.isPartial).isTrue()
        assertPartialWithUnpriced(accounting, "SEC-B")
        // priced side is real: A = 110.00, cash = 10000 − 10×10 − 100×5 = 9400
        assertThat(accounting.valuation.holdings.first { it.securityId == "SEC-A" }.marketValue)
            .isEquivalentAccordingToCompareTo(Money.of("110.00"))
        assertThat(accounting.cash).isEquivalentAccordingToCompareTo(Money.of("9400"))
    }

    private fun assertPartialWithUnpriced(
        accounting: PortfolioAccounting,
        vararg unpricedIds: String,
    ) {
        assertThat(accounting.valuation.unpricedSecurityIds).containsExactly(*unpricedIds)
        assertThat(accounting.valuation.total).isNull()
        assertThat(accounting.moneyGain.amount).isNull()
        assertThat(accounting.moneyGain.unavailableBecause)
            .containsExactlyElementsIn(unpricedIds)
    }
}
