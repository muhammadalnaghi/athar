package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashAnchor
import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CompletenessAttestation
import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.core.domain.model.cashforecast.ReservationDirection
import com.athar.core.domain.model.cashforecast.ReservationEffect
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Exact dated currency conservation, including a genuine one-fils gap. No FX assumptions. */
class CashForecastCurrencyPrecisionTest {
    private val openingDate = LocalDate(2026, 1, 31)
    private val movementDate = LocalDate(2026, 2, 5)
    private val knowledgeDate = LocalDate(2026, 2, 28)

    private fun money(value: String, currency: String) = Money.of(value, currency)

    private fun anchor(cash: String, reserved: String, currency: String) =
        CashAnchor(openingDate, money(cash, currency), money(reserved, currency))

    private fun cash(id: String, value: String, currency: String) =
        CashEffect(id, money(value, currency), movementDate, EffectCertainty.POSTED)

    private fun forecast(
        anchors: List<CashAnchor>,
        effects: List<CashEffect>,
        reservations: List<ReservationEffect> = emptyList(),
    ) = CashForecastCalculator.forecast(
        CashForecastRequest(
            anchors, effects, reservations, knowledgeDate, knowledgeDate,
            CompletenessAttestation(scopeComplete = true, anchorFresh = true),
        ),
    ).let { outcome ->
        assertThat(outcome).isInstanceOf(CashForecastOutcome.Forecast::class.java)
        (outcome as CashForecastOutcome.Forecast).result
    }

    private fun amount(actual: Money, value: String, currency: String) {
        assertThat(actual.currency).isEqualTo(currency)
        assertThat(actual.amount).isEquivalentAccordingToCompareTo(BigDecimal(value))
    }

    private fun confident(partition: CurrencyPartitionForecast) {
        assertThat(partition.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(partition.headline).isNotNull()
    }

    @Test
    fun `posted KWD inflow retains all fils separately from initial reserves`() {
        val result = forecast(
            listOf(anchor("10.123", "0.001", "KWD")),
            listOf(cash("posted-inflow", "0.500", "KWD")),
        )
        val partition = result.partitions.single()
        confident(partition)
        assertThat(result.decisionReady).isTrue()
        val last = partition.path.last()
        amount(last.cash, "10.623", "KWD")
        amount(requireNotNull(last.reserved), "0.001", "KWD")
        amount(requireNotNull(last.available), "10.622", "KWD")
        val headline = requireNotNull(partition.headline)
        amount(headline.minimumAvailable, "10.122", "KWD")
        assertThat(headline.minimumAvailableDate).isEqualTo(openingDate)
        assertThat(headline.shortfall).isNull()
        assertThat(last.effectIds).containsExactly("posted-inflow")
    }

    @Test
    fun `a genuine one fils deficit is not rounded away or moved to another date`() {
        val result = forecast(
            listOf(anchor("0.001", "0", "KWD")),
            listOf(cash("posted-debit", "-0.002", "KWD")),
        )
        val partition = result.partitions.single()
        confident(partition)
        val headline = requireNotNull(partition.headline)
        amount(headline.minimumAvailable, "-0.001", "KWD")
        amount(requireNotNull(headline.shortfall), "0.001", "KWD")
        amount(headline.finalBalance, "-0.001", "KWD")
        assertThat(headline.firstNegativeAvailableDate).isEqualTo(movementDate)
        assertThat(headline.minimumAvailableDate).isEqualTo(movementDate)
        assertThat(partition.path.last().effectIds).containsExactly("posted-debit")
    }

    @Test
    fun `same day fils release and increase do not fabricate reserve underflow`() {
        val reservations = listOf(
            ReservationEffect(
                "a-release", money("0.001", "KWD"), ReservationDirection.RELEASE,
                movementDate, EffectCertainty.POSTED,
            ),
            ReservationEffect(
                "z-increase", money("0.001", "KWD"), ReservationDirection.INCREASE,
                movementDate, EffectCertainty.POSTED,
            ),
        )
        for (ordered in listOf(reservations, reservations.reversed())) {
            val result = forecast(
                listOf(anchor("0.002", "0", "KWD")),
                listOf(cash("posted-debit", "-0.001", "KWD")), ordered,
            )
            val partition = result.partitions.single()
            confident(partition)
            assertThat(result.decisionReady).isTrue()
            val last = partition.path.last()
            amount(last.cash, "0.001", "KWD")
            amount(requireNotNull(last.reserved), "0", "KWD")
            amount(requireNotNull(last.available), "0.001", "KWD")
            assertThat(requireNotNull(partition.headline).shortfall).isNull()
            assertThat(last.effectIds).containsExactly("a-release", "posted-debit", "z-increase")
        }
    }

    @Test
    fun `KWD and JPY remain independent exact partitions without a combined currency`() {
        val result = forecast(
            listOf(anchor("10.123", "0", "KWD"), anchor("1001", "0", "JPY")),
            listOf(cash("kwd-credit", "0.001", "KWD"), cash("jpy-debit", "-1", "JPY")),
        )
        assertThat(result.partitions.map { it.currency }).containsExactly("JPY", "KWD").inOrder()
        assertThat(result.decisionReady).isTrue()
        val byCurrency = result.partitions.associateBy { it.currency }
        val kwd = byCurrency.getValue("KWD")
        val jpy = byCurrency.getValue("JPY")
        confident(kwd)
        confident(jpy)
        amount(requireNotNull(kwd.headline).finalBalance, "10.124", "KWD")
        amount(requireNotNull(jpy.headline).finalBalance, "1000", "JPY")
        assertThat(kwd.path.last().effectIds).containsExactly("kwd-credit")
        assertThat(jpy.path.last().effectIds).containsExactly("jpy-debit")
    }
}
