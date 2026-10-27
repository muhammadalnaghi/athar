package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashAnchor
import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CompletenessAttestation
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Behavior-first oracle tests for the dated cash-path forecast core:
 * dated low point vs final balance, same-day netting, opening-state inclusion,
 * flat path, leap/month-end dating, exact large and three-decimal money, window bounds.
 */
class CashForecastCalculatorTest {

    private fun anchor(date: String, cash: String, currency: String = "SAR", reserved: String? = "0") =
        CashAnchor(
            asOf = LocalDate.parse(date),
            cash = Money.of(BigDecimal(cash), currency),
            reserved = reserved?.let { Money.of(BigDecimal(it), currency) },
        )

    private fun effect(
        id: String,
        amount: String,
        date: String,
        currency: String = "SAR",
        certainty: EffectCertainty = EffectCertainty.EXPECTED,
        settles: String? = null,
    ) = CashEffect(id, Money.of(BigDecimal(amount), currency), LocalDate.parse(date), certainty, settles)

    private fun request(
        anchors: List<CashAnchor>,
        effects: List<CashEffect> = emptyList(),
        knowledgeAsOf: String = "2026-02-28",
        horizonEnd: String = "2026-02-28",
    ) = CashForecastRequest(
        anchors = anchors,
        effects = effects,
        reservations = emptyList(),
        knowledgeAsOf = LocalDate.parse(knowledgeAsOf),
        horizonEnd = LocalDate.parse(horizonEnd),
        attestation = CompletenessAttestation(scopeComplete = true, anchorFresh = true),
    )

    private fun forecast(request: CashForecastRequest): CashForecastOutcome.Forecast =
        CashForecastCalculator.forecast(request) as CashForecastOutcome.Forecast

    @Test
    fun `dated low point differs from final balance`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(effect("spend", "-900", "2026-02-05"), effect("income", "500", "2026-02-10")),
                horizonEnd = "2026-02-28",
            ),
        ).result
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(sar.headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("100"))
        assertThat(sar.headline!!.minimumAvailableDate).isEqualTo(LocalDate.parse("2026-02-05"))
        assertThat(sar.headline!!.finalBalance.amount).isEqualTo(BigDecimal("600"))
        assertThat(sar.headline!!.firstNegativeAvailableDate).isNull()
        assertThat(sar.headline!!.shortfall).isNull()
    }

    @Test
    fun `same-day effects are netted once before evaluating the day`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "500")),
                effects = listOf(
                    effect("a", "300", "2026-02-05"),
                    effect("b", "-500", "2026-02-05"),
                    effect("c", "100", "2026-02-05"),
                ),
                horizonEnd = "2026-02-28",
            ),
        ).result
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.path).hasSize(2) // opening + one netted day, no intraday fabrications
        val day = sar.path.last()
        assertThat(day.date).isEqualTo(LocalDate.parse("2026-02-05"))
        assertThat(day.cash.amount).isEqualTo(BigDecimal("400"))
        assertThat(day.effectIds).containsExactly("a", "b", "c").inOrder()
        assertThat(sar.headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("400"))
        assertThat(sar.headline!!.minimumAvailableDate).isEqualTo(LocalDate.parse("2026-02-05"))
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.IntradayMinimaUnknown>()).hasSize(1)
    }

    @Test
    fun `opening state is included in minima and first negative date`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "-50")), // known overdraft is valid
                effects = listOf(effect("topup", "100", "2026-02-01")),
                horizonEnd = "2026-02-28",
            ),
        ).result
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(sar.opening!!.available!!.amount).isEqualTo(BigDecimal("-50"))
        assertThat(sar.headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("-50"))
        assertThat(sar.headline!!.minimumAvailableDate).isEqualTo(LocalDate.parse("2026-01-31"))
        assertThat(sar.headline!!.firstNegativeAvailableDate).isEqualTo(LocalDate.parse("2026-01-31"))
        assertThat(sar.headline!!.shortfall!!.amount).isEqualTo(BigDecimal("50"))
        assertThat(sar.headline!!.finalBalance.amount).isEqualTo(BigDecimal("50"))
    }

    @Test
    fun `empty known-complete future effects produce a flat confident path`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "250")),
                horizonEnd = "2026-02-28",
            ),
        ).result
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(sar.path).hasSize(1)
        assertThat(sar.headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("250"))
        assertThat(sar.headline!!.minimumAvailableDate).isEqualTo(LocalDate.parse("2026-01-31"))
        assertThat(sar.headline!!.finalBalance.amount).isEqualTo(BigDecimal("250"))
        assertThat(result.decisionReady).isTrue()
    }

    @Test
    fun `anchor dated beyond knowledge is typed rejected`() {
        val outcome = CashForecastCalculator.forecast(
            request(anchors = listOf(anchor("2026-03-31", "250"))),
        ) as CashForecastOutcome.Rejected

        assertThat(outcome.reasons).containsExactly(
            com.athar.core.domain.model.cashforecast.CashForecastRejectionReason.ANCHOR_AFTER_KNOWLEDGE_ASOF,
        )
    }

    @Test
    fun `leap day and month end dates are honored exactly`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2028-02-28", "100")),
                effects = listOf(effect("leap", "-40", "2028-02-29"), effect("month-end", "60", "2028-03-31")),
                knowledgeAsOf = "2028-02-28",
                horizonEnd = "2028-03-31",
            ),
        ).result
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.opening!!.date.toString()).isEqualTo("2028-02-28")
        assertThat(sar.path.map { it.date.toString() })
            .containsExactly("2028-02-28", "2028-02-29", "2028-03-31").inOrder() // opening included
        assertThat(sar.path[0].cash.amount).isEqualTo(BigDecimal("100"))
        assertThat(sar.path[1].cash.amount).isEqualTo(BigDecimal("60"))
        assertThat(sar.path[2].cash.amount).isEqualTo(BigDecimal("120"))
        assertThat(sar.headline!!.finalBalance.amount).isEqualTo(BigDecimal("120"))
    }

    @Test
    fun `three-decimal and large money stay exact without quantization`() {
        val kwd = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1234.560", currency = "KWD", reserved = "100.000")),
                effects = listOf(effect("fee", "-0.001", "2026-02-01", currency = "KWD")),
                horizonEnd = "2026-02-28",
            ),
        ).result.partitions.single { it.currency == "KWD" }
        assertThat(kwd.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(kwd.path.last().cash.amount).isEqualTo(BigDecimal("1234.559"))
        assertThat(kwd.path.last().reserved!!.amount).isEqualTo(BigDecimal("100.000"))
        assertThat(kwd.path.last().available!!.amount).isEqualTo(BigDecimal("1134.559"))
        assertThat(kwd.headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("1134.559"))

        val large = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "9999999999.99")),
                effects = listOf(effect("cent", "0.01", "2026-02-01")),
                horizonEnd = "2026-02-28",
            ),
        ).result.partitions.single { it.currency == "SAR" }
        assertThat(large.path.last().cash.amount).isEqualTo(BigDecimal("10000000000.00"))
    }

    @Test
    fun `only effects strictly after anchor and through inclusive horizon are applied`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(
                    effect("on-anchor", "-100", "2026-01-31"),
                    effect("at-horizon", "-50", "2026-02-28"),
                    effect("beyond-horizon", "-70", "2026-03-01"),
                ),
                horizonEnd = "2026-02-28",
            ),
        ).result
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.path).hasSize(2)
        assertThat(sar.path.last().effectIds).containsExactly("at-horizon")
        assertThat(sar.headline!!.finalBalance.amount).isEqualTo(BigDecimal("950"))
    }

    @Test
    fun `horizon bound rejects beyond limit and accepts exactly at limit`() {
        val knowledge = LocalDate.parse("2026-01-01")
        val atLimit = CashForecastRequest(
            anchors = listOf(anchor("2026-01-01", "10")),
            effects = emptyList(),
            reservations = emptyList(),
            knowledgeAsOf = knowledge,
            horizonEnd = knowledge.plus(3650, DateTimeUnit.DAY),
            attestation = CompletenessAttestation(true, true),
        )
        val beyond = CashForecastRequest(
            anchors = listOf(anchor("2026-01-01", "10")),
            effects = emptyList(),
            reservations = emptyList(),
            knowledgeAsOf = knowledge,
            horizonEnd = knowledge.plus(3651, DateTimeUnit.DAY),
            attestation = CompletenessAttestation(true, true),
        )

        assertThat(CashForecastCalculator.forecast(atLimit)).isInstanceOf(CashForecastOutcome.Forecast::class.java)
        val rejected = CashForecastCalculator.forecast(beyond) as CashForecastOutcome.Rejected
        assertThat(rejected.reasons).containsExactly(
            com.athar.core.domain.model.cashforecast.CashForecastRejectionReason.HORIZON_EXCEEDS_LIMIT,
        )
    }
}
