package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.anchor
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.cash
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.day
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.request
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.reserve
import com.athar.core.domain.calc.cashforecast.CashForecastFixtures.result
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRejectionReason
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger

class CashForecastGuardTest {
    private fun reasons(input: CashForecastRequest) = (CashForecastCalculator.forecast(input) as CashForecastOutcome.Rejected).reasons

    @Test
    fun `initial reserved currency cannot be silently relabeled`() {
        assertThat(reasons(request(anchors = listOf(anchor().copy(reserved = Money.of("100", "USD"))))))
            .containsExactly(CashForecastRejectionReason.INITIAL_RESERVE_CURRENCY_MISMATCH)
    }

    @Test
    fun `negative reserve input is rejected independently of its movement direction`() {
        assertThat(reasons(request(anchors = listOf(anchor(reserved = "-1")))))
            .containsExactly(CashForecastRejectionReason.NEGATIVE_RESERVE_MAGNITUDE)
        assertThat(reasons(request(reservations = listOf(reserve("negative", "-1")))))
            .containsExactly(CashForecastRejectionReason.NEGATIVE_RESERVE_MAGNITUDE)
    }

    @Test
    fun `posted reservation beyond knowledge is rejected even outside horizon`() {
        val future = reserve("future", date = "2026-03-01").copy(certainty = EffectCertainty.POSTED)
        assertThat(reasons(request(reservations = listOf(future))))
            .containsExactly(CashForecastRejectionReason.POSTED_EFFECT_BEYOND_KNOWLEDGE_ASOF)
    }

    @Test
    fun `horizon before knowledge is typed rejection`() {
        val base = request()
        val input = CashForecastRequest(base.anchors, base.effects, base.reservations, base.knowledgeAsOf,
            day("2026-02-27"), base.attestation)
        assertThat(reasons(input)).containsExactly(CashForecastRejectionReason.HORIZON_END_BEFORE_KNOWLEDGE_ASOF)
    }

    @Test
    fun `ten thousand effects accepted but the next is rejected without truncation`() {
        val effects = (1..10000).map { cash("id-$it", "0", "2026-01-31") }
        assertThat(result(request(effects)).decisionReady).isTrue()
        assertThat(reasons(request(effects + cash("excess"))))
            .containsExactly(CashForecastRejectionReason.EFFECT_LIMIT_EXCEEDED)
    }

    @Test
    fun `extreme decimal scale is rejected before allocation or normalization`() {
        val extreme = cash("extreme").copy(amount = Money.of(BigDecimal(BigInteger.ONE, Int.MIN_VALUE), "SAR"))
        assertThat(reasons(request(effects = listOf(extreme))))
            .containsExactly(CashForecastRejectionReason.ARITHMETIC_UNSUPPORTED)
    }

    @Test
    fun `malformed currency hint is preserved as a typed rejection`() {
        val unknown = cash("unknown", amount = null).copy(currencyHint = "sar")
        assertThat(reasons(request(effects = listOf(unknown))))
            .containsExactly(CashForecastRejectionReason.UNRESOLVABLE_CURRENCY_HINT)
    }

    @Test
    fun `request snapshots all three caller-owned collections`() {
        val anchors = mutableListOf(anchor())
        val effects = mutableListOf(cash("expense"))
        val reservations = mutableListOf(reserve("earmark"))
        val input = request(effects, reservations, anchors)
        anchors.clear()
        effects.clear()
        reservations.clear()
        val partition = result(input).partitions.single()
        assertThat(partition.path.last().cash.amount).isEqualTo(BigDecimal("900"))
        assertThat(partition.path.last().available!!.amount).isEqualTo(BigDecimal("800"))
        assertThat(partition.opening).isSameInstanceAs(partition.path.first())
    }

    @Test
    fun `result path cannot be mutated through a mutable-list cast`() {
        val output = result(request(effects = listOf(cash("expense"))))
        val partition = output.partitions.single()
        assertThrows<Exception> { (partition.path as MutableList<*>).clear() }
        assertThat(partition.path).hasSize(2)
        assertThat(partition.path.last().cash.amount).isEqualTo(BigDecimal("900"))
    }

    @Test
    fun `same economic ID in reservation family does not settle an expected cash effect`() {
        val output = result(request(effects = listOf(cash("bill")), reservations = listOf(reserve("bill"))))
        assertThat(output.partitions.single().headline!!.finalBalance.amount).isEqualTo(BigDecimal("900"))
        assertThat(output.partitions.single().path.last().effectIds).containsExactly("bill", "bill")
    }
}
