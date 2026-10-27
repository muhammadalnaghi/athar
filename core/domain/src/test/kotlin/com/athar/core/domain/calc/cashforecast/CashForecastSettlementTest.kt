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

/**
 * Settlement-link semantics, reservations vs cash, and effect-identity discipline:
 * verified/unverified settlement, duplicate vs distinct IDs, contradictory IDs.
 */
class CashForecastSettlementTest {

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
    fun `verified settlement link suppresses expectation exactly once`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("post-1", "-200", "2026-02-10", EffectCertainty.POSTED, settles = "exp-1"),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.headline!!.finalBalance.amount).isEqualTo(BigDecimal("800")) // not 600: no double count
        assertThat(sarP.path.last().effectIds).containsExactly("post-1")
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.VerifiedSettlement>())
            .containsExactly(CashForecastDisclosure.VerifiedSettlement("exp-1", "post-1"))
        assertThat(sarP.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(result.decisionReady).isTrue()
    }

    @Test
    fun `historical linked posted effect already inside anchor suppresses expectation without reapplication`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("post-1", "-200", "2026-01-15", EffectCertainty.POSTED, settles = "exp-1"),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.path).hasSize(1) // only opening: posted is pre-anchor, expectation suppressed
        assertThat(sarP.headline!!.finalBalance.amount).isEqualTo(BigDecimal("1000"))
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.VerifiedSettlement>()).hasSize(1)
        assertThat(sarP.status).isEqualTo(PartitionStatus.CONFIDENT)
    }

    @Test
    fun `unverified expectation without settlement link still applies`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("post-1", "-200", "2026-02-10", EffectCertainty.POSTED),
                ),
            ),
        )

        assertThat(sar(result).headline!!.finalBalance.amount).isEqualTo(BigDecimal("600"))
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.VerifiedSettlement>()).isEmpty()
        assertThat(sar(result).status).isEqualTo(PartitionStatus.CONFIDENT)
    }

    @Test
    fun `incompatible settlement link withholds expectation and discloses`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("post-1", "-200", "2026-02-10", EffectCertainty.POSTED, settles = "exp-1", currency = "USD"),
                ),
            ),
        )
        val sarP = result.partitions.single { it.currency == "SAR" }

        assertThat(sarP.headline).isNull() // PARTIAL: known-only path, no decision headline
        assertThat(sarP.path.last().cash.amount).isEqualTo(BigDecimal("1000")) // expectation withheld, not paid-fabricated
        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.IncompatibleSettlementLink>())
            .containsExactly(
                CashForecastDisclosure.IncompatibleSettlementLink("exp-1", "post-1", SettlementIncompatibility.CURRENCY_MISMATCH),
            )
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `shortfall settlement link is never silently treated as fully paid`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("exp-1", "-200", "2026-02-15", EffectCertainty.EXPECTED),
                    effect("post-1", "-60", "2026-02-10", EffectCertainty.POSTED, settles = "exp-1"),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.headline).isNull() // PARTIAL withholds the headline
        assertThat(sarP.path.last().cash.amount).isEqualTo(BigDecimal("940")) // posted fact applies
        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL) // expectation withheld: caller must supply remaining amount
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.IncompatibleSettlementLink>())
            .containsExactly(
                CashForecastDisclosure.IncompatibleSettlementLink("exp-1", "post-1", SettlementIncompatibility.INSUFFICIENT_AMOUNT),
            )
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `settlement reference to unknown expectation is disclosed`() {
        val result = forecast(
            request(
                effects = listOf(effect("post-1", "-200", "2026-02-10", EffectCertainty.POSTED, settles = "ghost")),
            ),
        )

        assertThat(sar(result).headline).isNull() // PARTIAL withholds the headline
        assertThat(sar(result).path.last().cash.amount).isEqualTo(BigDecimal("800")) // posted fact applies
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.UnresolvedSettlementReference>())
            .containsExactly(CashForecastDisclosure.UnresolvedSettlementReference("post-1", "ghost"))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `exact duplicate effect id collapses to one occurrence`() {
        val dup = effect("dup", "-100", "2026-02-05", EffectCertainty.EXPECTED)
        val result = forecast(request(effects = listOf(dup, dup.copy())))

        assertThat(sar(result).headline!!.finalBalance.amount).isEqualTo(BigDecimal("900"))
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.DuplicateCollapsed>())
            .containsExactly(CashForecastDisclosure.DuplicateCollapsed(SubjectKind.CASH_EFFECT, "dup", 2))
        assertThat(sar(result).status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(result.decisionReady).isTrue()
    }

    @Test
    fun `same-looking movements with distinct ids remain distinct`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("a", "-100", "2026-02-05", EffectCertainty.EXPECTED),
                    effect("b", "-100", "2026-02-05", EffectCertainty.EXPECTED),
                ),
            ),
        )

        assertThat(sar(result).headline!!.finalBalance.amount).isEqualTo(BigDecimal("800"))
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.DuplicateCollapsed>()).isEmpty()
        assertThat(sar(result).status).isEqualTo(PartitionStatus.CONFIDENT)
    }

    @Test
    fun `contradictory duplicate id withholds all its occurrences`() {
        val result = forecast(
            request(
                effects = listOf(
                    effect("x", "-100", "2026-02-05", EffectCertainty.EXPECTED),
                    effect("x", "-300", "2026-02-05", EffectCertainty.EXPECTED),
                ),
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.headline).isNull() // PARTIAL withholds the headline
        assertThat(sarP.path.last().cash.amount).isEqualTo(BigDecimal("1000")) // neither occurrence applied
        assertThat(sarP.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(result.disclosures.filterIsInstance<CashForecastDisclosure.ContradictoryWithheld>())
            .containsExactly(CashForecastDisclosure.ContradictoryWithheld(SubjectKind.CASH_EFFECT, "x", 2))
        assertThat(result.decisionReady).isFalse()
    }

    @Test
    fun `reservation increases and releases move available but never bank cash`() {
        val result = forecast(
            request(
                effects = emptyList(),
                reservations = listOf(
                    reservation("r-inc", "200", "2026-02-05", ReservationDirection.INCREASE),
                    reservation("r-rel", "100", "2026-02-10", ReservationDirection.RELEASE),
                ),
                reserved = "100",
            ),
        )
        val sarP = sar(result)

        assertThat(sarP.path.map { it.cash.amount }).containsExactly(BigDecimal("1000"), BigDecimal("1000"), BigDecimal("1000")).inOrder()
        assertThat(sarP.path.map { it.reserved!!.amount })
            .containsExactly(BigDecimal("100"), BigDecimal("300"), BigDecimal("200")).inOrder()
        assertThat(sarP.path.map { it.available!!.amount })
            .containsExactly(BigDecimal("900"), BigDecimal("700"), BigDecimal("800")).inOrder()
        assertThat(sarP.headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("700"))
        assertThat(sarP.headline!!.minimumAvailableDate).isEqualTo(LocalDate.parse("2026-02-05"))
        assertThat(sarP.headline!!.finalBalance.amount).isEqualTo(BigDecimal("1000"))
        assertThat(sarP.status).isEqualTo(PartitionStatus.CONFIDENT)
    }

    @Test
    fun `goal earmark reservation is not a duplicate expense`() {
        val result = forecast(
            request(
                effects = listOf(effect("bill", "-300", "2026-02-05", EffectCertainty.EXPECTED)),
                reservations = listOf(reservation("goal-1", "300", "2026-02-05", ReservationDirection.INCREASE)),
                reserved = "100",
            ),
        )
        val day = sar(result).path.last()

        assertThat(day.cash.amount).isEqualTo(BigDecimal("700")) // bill leaves cash exactly once
        assertThat(day.reserved!!.amount).isEqualTo(BigDecimal("400")) // earmark lands in reserves exactly once
        assertThat(day.available!!.amount).isEqualTo(BigDecimal("300"))
        assertThat(sar(result).status).isEqualTo(PartitionStatus.CONFIDENT)
    }

}
