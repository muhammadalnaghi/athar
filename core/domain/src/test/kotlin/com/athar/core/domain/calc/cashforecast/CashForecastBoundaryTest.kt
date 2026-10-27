package com.athar.core.domain.calc.cashforecast

import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.anchor
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.cash
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.day
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.request
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.reserve
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.result
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.ForecastEffectReference
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.core.domain.model.cashforecast.ReservationDirection
import com.athar.core.domain.model.cashforecast.SettlementIncompatibility
import com.athar.core.domain.model.cashforecast.SubjectKind
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CashForecastBoundaryTest {
    @Test
    fun `missing cash date discloses unknown placement without fabricating an amount`() {
        val output = result(request(effects = listOf(cash("missing", date = null), cash("known"))))
        val partition = output.partitions.single()
        assertThat(partition.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(partition.path.last().cash.amount).isEqualTo(BigDecimal("900"))
        assertThat(partition.headline).isNull()
        assertThat(output.disclosures).contains(CashForecastDisclosure.UnavailableDate(SubjectKind.CASH_EFFECT, "missing", "SAR"))
    }

    @Test
    fun `missing reserve amount retains known opening then makes reserve state unknown`() {
        val output = result(request(anchors = listOf(anchor(reserved = "100")), reservations = listOf(reserve("unknown", amount = null))))
        val partition = output.partitions.single()
        assertThat(partition.opening!!.reserved!!.amount).isEqualTo(BigDecimal("100"))
        assertThat(partition.path.last().date).isEqualTo(day("2026-02-05"))
        assertThat(partition.path.last().cash.amount).isEqualTo(BigDecimal("1000"))
        assertThat(partition.path.last().reserved).isNull()
        assertThat(partition.path.last().available).isNull()
        assertThat(partition.headline).isNull()
    }

    @Test
    fun `unknown reservation date does not corrupt observed opening or invent placement`() {
        val output = result(request(anchors = listOf(anchor(reserved = "100")), effects = listOf(cash("known")),
            reservations = listOf(reserve("undated", date = null))))
        val partition = output.partitions.single()
        assertThat(partition.opening!!.reserved!!.amount).isEqualTo(BigDecimal("100"))
        assertThat(partition.path.last().reserved).isNull()
        assertThat(partition.path.last().effectIds).containsExactly("known")
        assertThat(partition.status).isEqualTo(PartitionStatus.PARTIAL)
    }

    @Test
    fun `same-day increases and releases net before reserve underflow evaluation`() {
        val output = result(request(anchors = listOf(anchor(reserved = "100")), reservations = listOf(
            reserve("a-release", "150", direction = ReservationDirection.RELEASE), reserve("z-increase", "100"))))
        val partition = output.partitions.single()
        assertThat(partition.path.last().reserved!!.amount).isEqualTo(BigDecimal("50"))
        assertThat(partition.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(output.disclosures.filterIsInstance<CashForecastDisclosure.ReserveUnderflowUnknown>()).isEmpty()
    }

    @Test
    fun `reserve uncertainty cannot be silently recovered by a later increase`() {
        val output = result(request(anchors = listOf(anchor(reserved = "100")), reservations = listOf(
            reserve("release", "150", direction = ReservationDirection.RELEASE), reserve("later", "500", "2026-02-10"))))
        val partition = output.partitions.single()
        assertThat(partition.path.last().reserved).isNull()
        assertThat(partition.headline).isNull()
        assertThat(partition.path.map { it.cash.amount }.distinct()).containsExactly(BigDecimal("1000"))
    }

    @Test
    fun `cash and reserve with the same ID keep independently typed provenance`() {
        val partition = result(request(effects = listOf(cash("same")), reservations = listOf(reserve("same")))).partitions.single()
        assertThat(partition.path.last().supportingEffects).containsExactly(
            ForecastEffectReference(SubjectKind.CASH_EFFECT, "same"), ForecastEffectReference(SubjectKind.RESERVATION, "same")).inOrder()
        assertThat(partition.path.last().cash.amount).isEqualTo(BigDecimal("900"))
        assertThat(partition.path.last().available!!.amount).isEqualTo(BigDecimal("800"))
    }

    @Test
    fun `conflicting cross-currency identity demotes every represented currency`() {
        val output = result(request(anchors = listOf(anchor(), anchor("USD")),
            effects = listOf(cash("conflict"), cash("conflict", currency = "USD"))))
        assertThat(output.partitions.map { it.status }).containsExactly(PartitionStatus.PARTIAL, PartitionStatus.PARTIAL)
        assertThat(output.partitions.map { it.path.last().cash.amount }).containsExactly(BigDecimal("1000"), BigDecimal("1000"))
        assertThat(output.partitions.map { it.headline }).containsExactly(null, null)
    }

    @Test
    fun `conflicting claimant variants cannot revive the referenced expectation`() {
        val output = result(request(effects = listOf(cash("expected", "-200"),
            cash("posted", "-100", certainty = EffectCertainty.POSTED, link = "expected"),
            cash("posted", "-300", certainty = EffectCertainty.POSTED, link = "expected"))))
        val partition = output.partitions.single()
        assertThat(partition.path.last().cash.amount).isEqualTo(BigDecimal("1000"))
        assertThat(partition.headline).isNull()
        assertThat(output.disclosures.filterIsInstance<CashForecastDisclosure.IncompatibleSettlementLink>()).containsExactly(
            CashForecastDisclosure.IncompatibleSettlementLink("expected", "posted", SettlementIncompatibility.CONFLICTING_IDENTITY))
    }

    @Test
    fun `missing linked date cannot certify settlement or synthesize cash`() {
        val output = result(request(effects = listOf(cash("expected", "-200"),
            cash("posted", "-200", date = null, certainty = EffectCertainty.POSTED, link = "expected"))))
        assertThat(output.partitions.single().path.last().cash.amount).isEqualTo(BigDecimal("1000"))
        assertThat(output.disclosures.filterIsInstance<CashForecastDisclosure.VerifiedSettlement>()).isEmpty()
        assertThat(output.disclosures.filterIsInstance<CashForecastDisclosure.IncompatibleSettlementLink>()).containsExactly(
            CashForecastDisclosure.IncompatibleSettlementLink("expected", "posted", SettlementIncompatibility.DATE_UNAVAILABLE))
    }

    @Test
    fun `numeric trailing zeros do not create a false identity conflict`() {
        val output = result(request(effects = listOf(cash("duplicate", "-100.0"), cash("duplicate", "-100.00"))))
        assertThat(output.partitions.single().headline!!.finalBalance.amount).isEqualTo(BigDecimal("900.00"))
        assertThat(output.disclosures.filterIsInstance<CashForecastDisclosure.DuplicateCollapsed>()).hasSize(1)
        assertThat(output.decisionReady).isTrue()
    }

    @Test
    fun `input ordering does not affect states diagnostics or provenance`() {
        val effects = listOf(cash("z", "-100.0"), cash("z", "-100.00"), cash("a", "50", "2026-02-10"))
        val reservations = listOf(reserve("z", "100"), reserve("a", "50", "2026-02-10"))
        assertThat(result(request(effects, reservations))).isEqualTo(result(request(effects.reversed(), reservations.reversed())))
    }

    @Test
    fun `minimum ties select the earliest dated minimum`() {
        val output = result(request(effects = listOf(cash("first", "-100"), cash("restore", "100", "2026-02-06"),
            cash("tie", "-100", "2026-02-07"))))
        assertThat(output.partitions.single().headline!!.minimumAvailableDate).isEqualTo(day("2026-02-05"))
        assertThat(output.partitions.single().headline!!.minimumAvailable.amount).isEqualTo(BigDecimal("900"))
    }
}
