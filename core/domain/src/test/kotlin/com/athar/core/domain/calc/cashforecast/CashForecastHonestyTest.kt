package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashAnchor
import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRejectionReason
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CompletenessAttestation
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.core.domain.model.cashforecast.ReservationDirection
import com.athar.core.domain.model.cashforecast.ReservationEffect
import com.athar.core.domain.model.cashforecast.SubjectKind
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Honesty guarantees: unknown is not zero, withheld partitions, typed rejections,
 * attestation/freshness labelling, bounded inputs, mixed-currency independence.
 */
class CashForecastHonestyTest {

    private fun anchor(date: String, cash: String, currency: String = "SAR", reserved: String? = "0") =
        CashAnchor(
            asOf = LocalDate.parse(date),
            cash = Money.of(BigDecimal(cash), currency),
            reserved = reserved?.let { Money.of(BigDecimal(it), currency) },
        )

    private fun effect(id: String, amount: String, date: String, currency: String = "SAR", certainty: EffectCertainty = EffectCertainty.EXPECTED) =
        CashEffect(id, Money.of(BigDecimal(amount), currency), LocalDate.parse(date), certainty, null)

    private fun request(
        anchors: List<CashAnchor>,
        effects: List<CashEffect> = emptyList(),
        reservations: List<ReservationEffect> = emptyList(),
        scopeComplete: Boolean = true,
        anchorFresh: Boolean = true,
    ) = CashForecastRequest(
        anchors = anchors,
        effects = effects,
        reservations = reservations,
        knowledgeAsOf = LocalDate.parse("2026-02-28"),
        horizonEnd = LocalDate.parse("2026-02-28"),
        attestation = CompletenessAttestation(scopeComplete, anchorFresh),
    )

    private fun forecast(request: CashForecastRequest) =
        (CashForecastCalculator.forecast(request) as CashForecastOutcome.Forecast).result

    @Test
    fun `unknown initial reserves are unknown not zero`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000", reserved = null)),
                reservations = listOf(
                    ReservationEffect(
                        "r1", Money.of(BigDecimal("50"), "SAR"),
                        ReservationDirection.INCREASE, LocalDate.parse("2026-02-05"), EffectCertainty.EXPECTED,
                    ),
                ),
            ),
        )
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(sar.path.last().cash.amount).isEqualTo(BigDecimal("1000")) // cash still known
        assertThat(sar.path.last().reserved).isNull()
        assertThat(sar.path.last().available).isNull()
        assertThat(sar.headline).isNull() // no confident available headline without reserve facts
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.UnknownInitialReserves>())
            .containsExactly(CashForecastDisclosure.UnknownInitialReserves("SAR"))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `missing anchor for an effect currency withholds that partition only`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(effect("usd-spend", "-10", "2026-02-05", currency = "USD")),
            ),
        )
        val sar = result.partitions.single { it.currency == "SAR" }
        val usd = result.partitions.single { it.currency == "USD" }

        assertThat(sar.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(sar.headline).isNotNull() // unaffected partition keeps its own headline
        assertThat(usd.status).isEqualTo(PartitionStatus.WITHHELD)
        assertThat(usd.path).isEmpty()
        assertThat(usd.headline).isNull()
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.MissingAnchor>())
            .containsExactly(CashForecastDisclosure.MissingAnchor("USD"))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `unsupported currency withholds partition without quantizing`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(effect("xyz", "-10", "2026-02-05", currency = "XYZ")),
            ),
        )
        val xyz = result.partitions.single { it.currency == "XYZ" }

        assertThat(xyz.status).isEqualTo(PartitionStatus.WITHHELD)
        assertThat(xyz.path).isEmpty()
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.UnsupportedCurrency>())
            .containsExactly(CashForecastDisclosure.UnsupportedCurrency("XYZ"))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `precision beyond currency minor units withholds partition without quantizing`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(effect("sub-halala", "10.123", "2026-02-05")),
            ),
        )
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.status).isEqualTo(PartitionStatus.WITHHELD) // never silently rounded to 10.12
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.PrecisionExceeded>())
            .containsExactly(CashForecastDisclosure.PrecisionExceeded("sub-halala", "SAR", 3, 2))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `posted evidence beyond knowledge-as-of rejects the whole request`() {
        val outcome = CashForecastCalculator.forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(effect("future-posted", "-100", "2026-03-01", certainty = EffectCertainty.POSTED)),
            ),
        ) as CashForecastOutcome.Rejected

        assertThat(outcome.reasons).containsExactly(CashForecastRejectionReason.POSTED_EFFECT_BEYOND_KNOWLEDGE_ASOF)
        assertThat(outcome.offendingIds).containsExactly("future-posted")
    }

    @Test
    fun `incomplete or stale attestation labels known-only path partial`() {
        for (attestation in listOf(true to false, false to true)) {
            val result = forecast(
                request(
                    anchors = listOf(anchor("2026-01-31", "1000")),
                    effects = listOf(effect("spend", "-100", "2026-02-05")),
                    scopeComplete = attestation.first,
                    anchorFresh = attestation.second,
                ),
            )
            val sar = result.partitions.single { it.currency == "SAR" }

            assertThat(sar.status).isEqualTo(PartitionStatus.PARTIAL)
            assertThat(sar.headline).isNull()
            assertThat(sar.headlineFinalBalanceForKnownPath()).isEqualTo(BigDecimal("900"))
            assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.AttestationIncomplete>()).hasSize(1)
            assertThat(result.decisionReady).isFalse()
        }
    }

    @Test
    fun `blank identity and duplicate anchor currency reject rather than guess`() {
        val blank = CashForecastCalculator.forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000")),
                effects = listOf(effect("", "-100", "2026-02-05")),
            ),
        ) as CashForecastOutcome.Rejected
        assertThat(blank.reasons).containsExactly(CashForecastRejectionReason.BLANK_EFFECT_IDENTITY)

        val dupAnchor = CashForecastCalculator.forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000"), anchor("2026-01-30", "900")),
                effects = emptyList(),
            ),
        ) as CashForecastOutcome.Rejected
        assertThat(dupAnchor.reasons).containsExactly(CashForecastRejectionReason.DUPLICATE_ANCHOR_CURRENCY)
    }

    @Test
    fun `contradictory reservation id withholds both occurrences from available`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000", reserved = "100")),
                reservations = listOf(
                    ReservationEffect(
                        "r1", Money.of(BigDecimal("200"), "SAR"),
                        ReservationDirection.INCREASE, LocalDate.parse("2026-02-05"), EffectCertainty.EXPECTED,
                    ),
                    ReservationEffect(
                        "r1", Money.of(BigDecimal("500"), "SAR"),
                        ReservationDirection.INCREASE, LocalDate.parse("2026-02-05"), EffectCertainty.EXPECTED,
                    ),
                ),
            ),
        )
        val sar = result.partitions.single { it.currency == "SAR" }

        assertThat(sar.path).hasSize(1) // no day produced from withheld reservation
        assertThat(sar.path.last().reserved!!.amount).isEqualTo(BigDecimal("100"))
        assertThat(sar.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.ContradictoryWithheld>())
            .containsExactly(CashForecastDisclosure.ContradictoryWithheld(SubjectKind.RESERVATION, "r1", 2))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `mixed currency partitions are computed independently and never merged`() {
        val result = forecast(
            request(
                anchors = listOf(anchor("2026-01-31", "1000"), anchor("2026-01-31", "100", currency = "USD")),
                effects = listOf(
                    effect("sar-spend", "-100", "2026-02-05"),
                    effect("usd-gain", "50", "2026-02-05", currency = "USD"),
                ),
            ),
        )

        assertThat(result.partitions).hasSize(2)
        val sar = result.partitions.single { it.currency == "SAR" }
        val usd = result.partitions.single { it.currency == "USD" }
        assertThat(sar.headline!!.finalBalance.amount).isEqualTo(BigDecimal("900"))
        assertThat(usd.headline!!.finalBalance.amount).isEqualTo(BigDecimal("150"))
        assertThat(sar.path.last().effectIds).containsExactly("sar-spend")
        assertThat(usd.path.last().effectIds).containsExactly("usd-gain")
        assertThat(result.decisionReady).isTrue()
    }

    @Test
    fun `empty input yields no partitions and never a fake settled zero`() {
        val result = forecast(request(anchors = emptyList()))

        assertThat(result.partitions).isEmpty()
        assertThat(result.decisionReady).isFalse()
    }
}

/** Known-only end balance read straight from the path, used when the headline is withheld. */
private fun com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast.headlineFinalBalanceForKnownPath() =
    path.last().cash.amount
