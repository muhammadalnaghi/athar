package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * WM052: optional explicitly dated opening cash at the pure ledger boundary.
 *
 * The final `of(...)` parameter defaults to null, so every legacy caller and the
 * entire undated behavior contract (money, ids, event order, negative-cash,
 * currency, precision) must stay bit-identical. The date is stored verbatim —
 * never inferred and never repaired; date-vs-first-event ordering is validated by
 * the parent document codec before construction.
 */
class PortfolioLedgerOpening052Test {

    private val secA = PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY)
    private val opening = LocalDate(2026, 1, 1)

    private fun buy(id: String, date: LocalDate, seq: Int = 1) = SecurityBuy(
        eventId = id,
        date = date,
        sequence = seq,
        securityRef = "SEC-A",
        quantity = BigDecimal("100"),
        unitPrice = Money.of("10"),
        fee = Money.zero(),
    )

    @Test
    fun `four-argument call keeps legacy undated opening with null date`() {
        val outcome = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.of("1000"),
            securities = listOf(secA),
            events = listOf(buy("buy-1", opening)),
        )
        val ledger = (outcome as Outcome.Success).value
        assertThat(ledger.openingCash).isEqualTo(Money.of("1000"))
        assertThat(ledger.openingCashDate).isNull()
        assertThat(ledger.orderedEvents.map { it.eventId }).containsExactly("buy-1")
    }

    @Test
    fun `explicit opening date is exposed read-only on the ledger`() {
        val outcome = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.of("1000"),
            securities = listOf(secA),
            events = listOf(buy("buy-1", opening)),
            openingCashDate = opening,
        )
        assertThat((outcome as Outcome.Success).value.openingCashDate).isEqualTo(opening)
    }

    @Test
    fun `opening date equal to the first event date is accepted`() {
        val outcome = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.of("1000"),
            securities = listOf(secA),
            events = listOf(buy("buy-1", opening), buy("buy-2", LocalDate(2026, 2, 1))),
            openingCashDate = opening,
        )
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun `zero opening cash with an explicit date stays valid legacy economics`() {
        val outcome = PortfolioLedger.of(
            baseCurrency = "SAR",
            openingCash = Money.zero(),
            securities = listOf(secA),
            events = emptyList(),
            openingCashDate = opening,
        )
        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
    }
}
