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
import com.athar.core.domain.model.cashforecast.ReservationDirection
import com.athar.core.domain.model.cashforecast.ReservationEffect
import com.athar.core.domain.model.cashforecast.SettlementIncompatibility
import com.athar.core.domain.model.cashforecast.SubjectKind
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Missing facts and settlement/reserve uncertainty regressions. */
class CashForecastMissingFactsTest {

    private fun sarAnchor(cash: String = "1000", reserved: String = "0") =
        CashAnchor(LocalDate.parse("2026-01-31"), Money.of(BigDecimal(cash), "SAR"), Money.of(BigDecimal(reserved), "SAR"))

    private fun effect(
        id: String,
        amount: String,
        date: String,
        certainty: EffectCertainty,
        settles: String? = null,
        currency: String = "SAR",
    ) = CashEffect(id, Money.of(BigDecimal(amount), currency), LocalDate.parse(date), certainty, settles)

    private fun request(
        effects: List<CashEffect>,
        reservations: List<ReservationEffect> = emptyList(),
        reserved: String = "0",
    ) = CashForecastRequest(
        anchors = listOf(sarAnchor(reserved = reserved)),
        effects = effects,
        reservations = reservations,
        knowledgeAsOf = LocalDate.parse("2026-02-28"),
        horizonEnd = LocalDate.parse("2026-02-28"),
        attestation = CompletenessAttestation(true, true),
    )

    private fun reservation(id: String, amount: String, date: String, direction: ReservationDirection) =
        ReservationEffect(id, Money.of(BigDecimal(amount), "SAR"), direction, LocalDate.parse(date), EffectCertainty.EXPECTED)

    private fun forecast(request: CashForecastRequest) =
        (CashForecastCalculator.forecast(request) as CashForecastOutcome.Forecast).result

    private fun sar(result: com.athar.core.domain.model.cashforecast.CashForecastResult) =
        result.partitions.single { it.currency == "SAR" }

    @Test
    fun `missing amount with currency hint keeps known path but withholds headline`() {
        val result = forecast(
            request(
                effects = listOf(
                    CashEffect("m1", null, LocalDate.parse("2026-02-05"), EffectCertainty.EXPECTED, null, "SAR"),
                    effect("known", "-100", "2026-02-10", EffectCertainty.EXPECTED),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(sarP.path.last().cash.amount).isEqualTo(BigDecimal("900")) // known effect still applied
        assertThat(sarP.headline).isNull()
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.UnavailableAmount>())
            .containsExactly(CashForecastDisclosure.UnavailableAmount(SubjectKind.CASH_EFFECT, "m1", "SAR"))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `amountless fact without any literal currency is structurally rejected`() {
        val outcome = CashForecastCalculator.forecast(
            request(effects = listOf(CashEffect("m2", null, LocalDate.parse("2026-02-05"), EffectCertainty.EXPECTED, null, null))),
        ) as CashForecastOutcome.Rejected

        assertThat(outcome.reasons).containsExactly(
            com.athar.core.domain.model.cashforecast.CashForecastRejectionReason.UNAVAILABLE_AMOUNT_WITHOUT_CURRENCY,
        )
    }

    @Test
    fun `currency hint contradicting observed money is rejected`() {
        val outcome = CashForecastCalculator.forecast(
            request(effects = listOf(effect("h1", "-100", "2026-02-05", EffectCertainty.EXPECTED).copy(currencyHint = "USD"))),
        ) as CashForecastOutcome.Rejected

        assertThat(outcome.reasons).containsExactly(
            com.athar.core.domain.model.cashforecast.CashForecastRejectionReason.CURRENCY_HINT_CONTRADICTION,
        )
    }

    @Test
    fun `multiple distinct posted claimants are ambiguous never aggregated`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("post-1", "-120", "2026-02-10", EffectCertainty.POSTED, settles = "exp-1"),
                    effect("post-2", "-80", "2026-02-11", EffectCertainty.POSTED, settles = "exp-1"),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.path.last().cash.amount).isEqualTo(BigDecimal("800")) // posted facts kept: 1000 - 120 - 80
        assertThat(sarP.headline).isNull() // expectation withheld: aggregation not guessed
        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.AmbiguousSettlementClaimants>())
            .containsExactly(CashForecastDisclosure.AmbiguousSettlementClaimants("exp-1", listOf("post-1", "post-2").toPersistentList()))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `release beyond known reserves underflows to unknown never negative`() {
        val result = forecast(
            request(
                effects = emptyList(),
                reservations = listOf(reservation("r-over", "150", "2026-02-05", ReservationDirection.RELEASE)),
                reserved = "100",
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(sarP.path.last().cash.amount).isEqualTo(BigDecimal("1000")) // cash retained
        assertThat(sarP.path.last().reserved).isNull() // underflow: unknown, never negative
        assertThat(sarP.path.last().available).isNull()
        assertThat(sarP.headline).isNull()
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.ReserveUnderflowUnknown>())
            .containsExactly(CashForecastDisclosure.ReserveUnderflowUnknown("SAR", "r-over"))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `expected effect claiming to settle another expectation is a typed diagnostic`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("exp-2", "-100", "2026-02-10", EffectCertainty.EXPECTED, settles = "exp-1"),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.headline).isNull()
        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.IncompatibleSettlementLink>())
            .containsExactly(
                CashForecastDisclosure.IncompatibleSettlementLink("exp-1", "exp-2", SettlementIncompatibility.CLAIMANT_NOT_POSTED),
            )
        assertThat(result.decisionReady).isFalse()
    }
}
