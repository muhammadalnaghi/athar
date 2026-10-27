package com.athar.feature.plan.portfolio

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.portfolio.MoneyGain
import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.core.domain.calc.portfolio.TwrResult
import com.athar.core.domain.calc.portfolio.XirrResult
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * Evidence-model contract: counts and dated span derived from stored inputs only,
 * honest empty state (nothing fabricated), and exact propagation of the engines'
 * undefined/unavailable states including the security ids they reported.
 */
class PortfolioPerformanceEvidence040Test {

    private fun event(kind: PortfolioEntryKind, date: String) =
        EventInput(kind = kind, eventId = "e-${kind.name}-$date", date = date)

    @Test
    fun `derives counts and dated span from stored inputs`() {
        val events = listOf(
            event(PortfolioEntryKind.BUY, "2026-01-05"),
            event(PortfolioEntryKind.BUY, "2026-03-02"),
            event(PortfolioEntryKind.CASH_DIVIDEND, "2026-02-10"),
        )
        val quotes = listOf(
            QuoteInput(securityId = "S1", date = "2026-03-31", unitPrice = "10.5"),
            QuoteInput(securityId = "S2", date = "2026-02-20", unitPrice = "4"),
        )
        val evidence = PerformanceEvidence040.of(
            events = events,
            quotes = quotes,
            asOf = LocalDate.parse("2026-03-31"),
            xirr = XirrResult.Available(BigDecimal("12.5")),
            twr = TwrResult.Available(BigDecimal("9.1"), 3),
            moneyGain = MoneyGain(Money.of(BigDecimal("150.25"), "SAR"), emptyList()),
        )

        assertThat(evidence.hasDatedInputs).isTrue()
        assertThat(evidence.eventCountByKind)
            .containsExactly(PortfolioEntryKind.BUY, 2, PortfolioEntryKind.CASH_DIVIDEND, 1)
            .inOrder()
        assertThat(evidence.quoteCount).isEqualTo(2)
        assertThat(evidence.earliestDate).isEqualTo(LocalDate.parse("2026-01-05"))
        assertThat(evidence.latestDate).isEqualTo(LocalDate.parse("2026-03-31"))
        assertThat(evidence.asOf).isEqualTo(LocalDate.parse("2026-03-31"))
        assertThat(evidence.xirr).isEqualTo(ReturnFlow040.Defined)
        assertThat(evidence.twr).isEqualTo(ReturnFlow040.Defined)
        assertThat(evidence.moneyGain).isEqualTo(MoneyGainFlow040.Available)
    }

    @Test
    fun `empty inputs stay honest - no span, undefined flows, no replay`() {
        val evidence = PerformanceEvidence040.of(
            events = emptyList(),
            quotes = emptyList(),
            asOf = null,
            xirr = XirrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS),
            twr = TwrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS),
            moneyGain = null,
        )

        assertThat(evidence.hasDatedInputs).isFalse()
        assertThat(evidence.eventCountByKind).isEmpty()
        assertThat(evidence.quoteCount).isEqualTo(0)
        assertThat(evidence.earliestDate).isNull()
        assertThat(evidence.latestDate).isNull()
        assertThat(evidence.asOf).isNull()
        assertThat(evidence.xirr)
            .isEqualTo(ReturnFlow040.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS, emptyList()))
        assertThat(evidence.twr)
            .isEqualTo(ReturnFlow040.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS, emptyList()))
        assertThat(evidence.moneyGain).isEqualTo(MoneyGainFlow040.NotReplayable)
    }

    @Test
    fun `missing boundary valuation propagates unpriced securities and unavailable gain`() {
        val unpriced = listOf("S1", "S9")
        val evidence = PerformanceEvidence040.of(
            events = listOf(event(PortfolioEntryKind.BUY, "2026-01-05")),
            quotes = emptyList(),
            asOf = LocalDate.parse("2026-02-01"),
            xirr = XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION, unpriced),
            twr = TwrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION, unpriced),
            moneyGain = MoneyGain(null, unpriced),
        )

        assertThat(evidence.xirr)
            .isEqualTo(ReturnFlow040.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION, unpriced))
        assertThat(evidence.twr)
            .isEqualTo(ReturnFlow040.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION, unpriced))
        assertThat(evidence.moneyGain).isEqualTo(MoneyGainFlow040.Unavailable(unpriced))
        assertThat(evidence.hasDatedInputs).isTrue()
    }

    @Test
    fun `quotes alone carry the span without events`() {
        val evidence = PerformanceEvidence040.of(
            events = emptyList(),
            quotes = listOf(QuoteInput(securityId = "S1", date = "2026-05-01", unitPrice = "7")),
            asOf = LocalDate.parse("2026-05-01"),
            xirr = XirrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS),
            twr = TwrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS),
            moneyGain = MoneyGain(Money.of(BigDecimal.ZERO, "SAR"), emptyList()),
        )

        assertThat(evidence.hasDatedInputs).isTrue()
        assertThat(evidence.eventCountByKind).isEmpty()
        assertThat(evidence.quoteCount).isEqualTo(1)
        assertThat(evidence.earliestDate).isEqualTo(LocalDate.parse("2026-05-01"))
        assertThat(evidence.latestDate).isEqualTo(LocalDate.parse("2026-05-01"))
        assertThat(evidence.moneyGain).isEqualTo(MoneyGainFlow040.Available)
    }
}
