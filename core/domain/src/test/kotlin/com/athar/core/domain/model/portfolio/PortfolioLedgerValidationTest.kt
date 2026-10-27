package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Structural-oracle tests for [PortfolioLedger]: duplicate ids, ambiguous ordering,
 * currency boundary, positive-quantity/price/amount guards, unknown securities,
 * deterministic (date, sequence) ordering. Companion to the economic tests in
 * `calc/portfolio` — this file only proves the ledger is *well-formed*.
 */
class PortfolioLedgerValidationTest {

    private val secA = PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY)

    private fun buy(
        id: String,
        date: LocalDate,
        seq: Int = 1,
        qty: String = "10",
        price: String = "10.00",
        fee: String = "0.00",
        currency: String = "SAR",
    ) = SecurityBuy(
        eventId = id, date = date, sequence = seq,
        securityRef = "SEC-A",
        quantity = BigDecimal(qty),
        unitPrice = Money.of(price, currency),
        fee = Money.of(fee, currency),
    )

    private fun contribution(id: String, date: LocalDate, seq: Int = 1, amount: String = "1000") =
        ExternalContribution(
            eventId = id, date = date, sequence = seq,
            amount = Money.of(amount),
        )

    private fun reinvest(
        id: String,
        date: LocalDate,
        seq: Int = 1,
        amount: String,
        price: String,
        qty: String,
    ) = DividendReinvestment(
        eventId = id, date = date, sequence = seq,
        securityRef = "SEC-A",
        amount = Money.of(amount),
        unitPrice = Money.of(price),
        quantity = BigDecimal(qty),
    )

    private fun ledgerOf(
        events: List<PortfolioEvent>,
        baseCurrency: String = "SAR",
        openingCash: Money = Money.zero(),
        securities: List<PortfolioSecurity> = listOf(secA),
    ): Outcome<PortfolioLedger, LedgerRejection> =
        PortfolioLedger.of(
            baseCurrency = baseCurrency,
            openingCash = openingCash,
            securities = securities,
            events = events,
        )

    @Suppress("UNUSED_EXPRESSION")
    private fun expectFailure(outcome: Outcome<PortfolioLedger, LedgerRejection>): LedgerRejection =
        when (outcome) {
            is Outcome.Failure -> outcome.error
            is Outcome.Success -> throw AssertionError("expected rejection, got ${outcome.value}")
        }

    // ---- happy path + deterministic ordering ----

    @Test
    fun `events are reordered by date then sequence regardless of input order`() {
        val ledger = ledgerOf(
            events = listOf(
                buy("e3", LocalDate(2026, 3, 1), seq = 2),
                buy("e1", LocalDate(2026, 1, 6), seq = 1),
                buy("e2", LocalDate(2026, 3, 1), seq = 1),
            ),
        )
        val ledger2 = when (ledger) {
            is Outcome.Success -> ledger.value
            is Outcome.Failure -> throw AssertionError("unexpected ${ledger.error}")
        }
        assertThat(ledger2.orderedEvents.map { it.eventId })
            .containsExactly("e1", "e2", "e3")
            .inOrder()
    }

    @Test
    fun `opening cash currency must match base currency`() {
        val rejection = expectFailure(
            ledgerOf(
                events = emptyList(),
                baseCurrency = "SAR",
                openingCash = Money.of("100", "USD"),
            ),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.CrossCurrencyEvent::class.java)
    }

    @Test
    fun `negative opening cash is rejected as unsupported synthetic negative cash`() {
        val rejection = expectFailure(
            ledgerOf(events = emptyList(), openingCash = Money.of("-50")),
        )
        assertThat(rejection)
            .isEqualTo(LedgerRejection.NonPositiveAmount("openingCash", Money.of("-50")))
    }

    @Test
    fun `zero opening cash is valid`() {
        val outcome = ledgerOf(events = emptyList(), openingCash = Money.zero())
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }

    // ---- id and ordering rejections ----

    @Test
    fun `duplicate event id is rejected`() {
        val rejection = expectFailure(
            ledgerOf(
                events = listOf(
                    contribution("dup", LocalDate(2026, 1, 5)),
                    buy("dup", LocalDate(2026, 1, 6)),
                ),
            ),
        )
        assertThat(rejection).isEqualTo(LedgerRejection.DuplicateEventId("dup"))
    }

    @Test
    fun `blank event id is rejected`() {
        val rejection = expectFailure(
            ledgerOf(events = listOf(contribution("  ", LocalDate(2026, 1, 5)))),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.InvalidBlankEventId::class.java)
    }

    // ---- security identity validation ----

    @Test
    fun `duplicate security id with conflicting asset classes is rejected`() {
        val rejection = expectFailure(
            ledgerOf(
                events = emptyList(),
                securities = listOf(
                    PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY),
                    PortfolioSecurity("SEC-A", "صندوق ب", AssetClass.SUKUK),
                ),
            ),
        )
        assertThat(rejection).isEqualTo(LedgerRejection.DuplicateSecurityId("SEC-A"))
    }

    @Test
    fun `identical duplicate security ids are also rejected not silently repaired`() {
        val rejection = expectFailure(
            ledgerOf(
                events = emptyList(),
                securities = listOf(
                    PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY),
                    PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY),
                ),
            ),
        )
        assertThat(rejection).isEqualTo(LedgerRejection.DuplicateSecurityId("SEC-A"))
    }

    @Test
    fun `blank and whitespace security ids are rejected`() {
        val emptyId = expectFailure(
            ledgerOf(events = emptyList(), securities = listOf(PortfolioSecurity("", "أ", null))),
        )
        assertThat(emptyId).isEqualTo(LedgerRejection.InvalidBlankSecurityId(""))
        val whitespaceId = expectFailure(
            ledgerOf(events = emptyList(), securities = listOf(PortfolioSecurity("   ", "أ", null))),
        )
        assertThat(whitespaceId).isEqualTo(LedgerRejection.InvalidBlankSecurityId("   "))
    }

    @Test
    fun `nonblank security ids are preserved exactly without trimming`() {
        val padded = " SEC-A "
        val outcome = ledgerOf(
            events = listOf(
                SecurityBuy(
                    eventId = "e1", date = LocalDate(2026, 1, 6), sequence = 1,
                    securityRef = padded,
                    quantity = BigDecimal("10"),
                    unitPrice = Money.of("10.00"),
                    fee = Money.of("0.00"),
                ),
            ),
            securities = listOf(PortfolioSecurity(padded, "صندوق أ", AssetClass.EQUITY)),
        )
        val ledger = when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected ${outcome.error}")
        }
        assertThat(ledger.securities.keys).containsExactly(padded)
    }

    @Test
    fun `same date and same sequence is ambiguous and rejected`() {
        val rejection = expectFailure(
            ledgerOf(
                events = listOf(
                    contribution("a", LocalDate(2026, 1, 5), seq = 7),
                    contribution("b", LocalDate(2026, 1, 5), seq = 7),
                ),
            ),
        )
        assertThat(rejection)
            .isEqualTo(LedgerRejection.AmbiguousOrdering(LocalDate(2026, 1, 5), 7))
    }

    @Test
    fun `same date with different sequences is accepted`() {
        val outcome = ledgerOf(
            events = listOf(
                contribution("a", LocalDate(2026, 1, 5), seq = 7),
                contribution("b", LocalDate(2026, 1, 5), seq = 8),
            ),
        )
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }

    // ---- currency boundary ----

    @Test
    fun `buy priced in another currency is rejected not relabeled`() {
        val rejection = expectFailure(
            ledgerOf(events = listOf(buy("e1", LocalDate(2026, 1, 6), currency = "USD"))),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.CrossCurrencyEvent::class.java)
        val cross = rejection as LedgerRejection.CrossCurrencyEvent
        assertThat(cross.baseCurrency).isEqualTo("SAR")
        assertThat(cross.eventCurrencies).containsExactly("USD")
    }

    @Test
    fun `fee in a different currency than base is rejected`() {
        val rejection = expectFailure(
            ledgerOf(
                events = listOf(
                    SecurityBuy(
                        eventId = "e1", date = LocalDate(2026, 1, 6), sequence = 1,
                        securityRef = "SEC-A",
                        quantity = BigDecimal("10"),
                        unitPrice = Money.of("10.00"),
                        fee = Money.of("1.00", "USD"),
                    ),
                ),
            ),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.CrossCurrencyEvent::class.java)
    }

    // ---- positivity guards ----

    @Test
    fun `nonpositive buy quantity is rejected`() {
        val rejection = expectFailure(
            ledgerOf(events = listOf(buy("e1", LocalDate(2026, 1, 6), qty = "0"))),
        )
        assertThat(rejection)
            .isEqualTo(LedgerRejection.NonPositiveQuantity("e1", BigDecimal("0")))
    }

    @Test
    fun `negative buy quantity is rejected`() {
        val rejection = expectFailure(
            ledgerOf(events = listOf(buy("e1", LocalDate(2026, 1, 6), qty = "-5"))),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.NonPositiveQuantity::class.java)
    }

    @Test
    fun `nonpositive unit price is rejected`() {
        val rejection = expectFailure(
            ledgerOf(events = listOf(buy("e1", LocalDate(2026, 1, 6), price = "0.00"))),
        )
        assertThat(rejection).isEqualTo(
            LedgerRejection.NonPositiveUnitPrice("e1", Money.of("0.00")),
        )
    }

    @Test
    fun `nonpositive contribution amount is rejected`() {
        val rejection = expectFailure(
            ledgerOf(events = listOf(contribution("e1", LocalDate(2026, 1, 5), amount = "0"))),
        )
        assertThat(rejection).isInstanceOf(LedgerRejection.NonPositiveAmount::class.java)
    }

    @Test
    fun `zero fee is allowed`() {
        val outcome = ledgerOf(
            events = listOf(buy("e1", LocalDate(2026, 1, 6), fee = "0.00")),
        )
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }

    // ---- reinvestment consideration consistency ----

    @Test
    fun `reinvestment amount far from quantity times unit price is rejected`() {
        val rejection = expectFailure(
            ledgerOf(
                events = listOf(
                    reinvest("r1", LocalDate(2026, 2, 1), amount = "50", price = "25", qty = "100"),
                ),
            ),
        )
        assertThat(rejection)
            .isInstanceOf(LedgerRejection.InconsistentReinvestmentConsideration::class.java)
        val inconsistent = rejection as LedgerRejection.InconsistentReinvestmentConsideration
        assertThat(inconsistent.declaredAmount).isEqualTo(Money.of("50"))
        assertThat(inconsistent.expectedAmount).isEqualTo(Money.of("2500.00"))
    }

    @Test
    fun `reinvestment with exact amount quantity times unit price is accepted`() {
        val outcome = ledgerOf(
            events = listOf(
                reinvest("r1", LocalDate(2026, 2, 1), amount = "2500", price = "25", qty = "100"),
            ),
        )
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun `fractional share reinvestment rounded to settlement scale is accepted`() {
        // 0.3333 × 9.99 = 3.329667 → 3.33 at settlement scale 2, HALF_EVEN (the Money contract).
        val outcome = ledgerOf(
            events = listOf(
                reinvest("r1", LocalDate(2026, 2, 1), amount = "3.33", price = "9.99", qty = "0.3333"),
            ),
        )
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun `reinvestment amount one halala below rounded product is rejected no tolerance`() {
        val rejection = expectFailure(
            ledgerOf(
                events = listOf(
                    reinvest("r1", LocalDate(2026, 2, 1), amount = "3.32", price = "9.99", qty = "0.3333"),
                ),
            ),
        )
        assertThat(rejection)
            .isInstanceOf(LedgerRejection.InconsistentReinvestmentConsideration::class.java)
    }

    // ---- security references ----

    @Test
    fun `event referencing unknown security is rejected`() {
        val withKnownSecurity = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.zero(),
            securities = listOf(secA),
            events = listOf(buy("e1", LocalDate(2026, 1, 6))),
        )
        val withMissingSecurity = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.zero(),
            securities = listOf(PortfolioSecurity("SEC-B", "صندوق ب", AssetClass.SUKUK)),
            events = listOf(buy("e1", LocalDate(2026, 1, 6))),
        )
        assertThat(withKnownSecurity).isInstanceOf(Outcome.Success::class.java)
        val rejection = expectFailure(withMissingSecurity)
        assertThat(rejection).isEqualTo(LedgerRejection.UnknownSecurity("e1", "SEC-A"))
    }

    @Test
    fun `ledger keeps securities keyed by id`() {
        val ledger = when (val outcome = ledgerOf(events = listOf(buy("e1", LocalDate(2026, 1, 6))))) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> throw AssertionError("unexpected ${outcome.error}")
        }
        assertThat(ledger.securities.keys).containsExactly("SEC-A")
        assertThat(ledger.baseCurrency).isEqualTo("SAR")
        assertThat(ledger.openingCash).isEqualTo(Money.zero())
    }
}
