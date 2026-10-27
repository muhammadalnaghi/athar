package com.athar.core.domain.calc.financialgraph

import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.created
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.draft
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.leg
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.observation
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.rejected
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.GraphBuildIssue
import com.athar.core.domain.model.financialgraph.NoGraphReason
import com.athar.core.domain.model.financialgraph.RoutingAllocation
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FinancialGraphBorrowingTest {
    private val borrowing = observation(kind = EconomicKind.BORROWING, direction = MovementDirection.IN)
    private val bank = leg("bank-leg", "bank-account", "100.00")
    private val liability = leg("liability-leg", "loan-account", "-100.00")

    @Test
    fun `complete borrowing increases asset and liability once with no net wealth creation`() {
        val graph = created(draft(observation = borrowing, legs = listOf(bank, liability)))
        assertThat(graph.kind).isEqualTo(EconomicKind.BORROWING)
        assertThat(graph.routing).isEqualTo(RoutingAllocation.ROUTED)
        assertThat(graph.legs).hasSize(2)
        val net = graph.legs.fold(BigDecimal.ZERO) { total, next -> total.add(next.delta.amount) }
        assertThat(net.compareTo(BigDecimal.ZERO)).isEqualTo(0)
        assertThat(graph.components).isEmpty()
    }

    @Test
    fun `received bank money alone cannot certify complete borrowing coverage`() {
        val graph = created(draft(observation = borrowing, legs = listOf(bank)))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.PARTIAL)
        assertThat(graph.legs).hasSize(1)
        assertThat(graph.legs.single().accountRef).isEqualTo("bank-account")
    }

    @Test
    fun `liability alone stays partial without manufacturing bank cash`() {
        val graph = created(draft(observation = borrowing, legs = listOf(liability)))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.PARTIAL)
        assertThat(graph.legs).hasSize(1)
        assertThat(graph.legs.single().accountRef).isEqualTo("loan-account")
    }

    @Test
    fun `known borrowing with no routing remains unallocated and retains money`() {
        val graph = created(draft(observation = borrowing))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.UNALLOCATED)
        assertThat(graph.bookedAmount).isEqualTo(borrowing.original!!.amount)
        assertThat(graph.legs).isEmpty()
    }

    @Test
    fun `paired legs never override policy rejection of direction OUT borrowing`() {
        val raw = draft(observation = borrowing.copy(direction = MovementDirection.OUT), legs = listOf(bank, liability))
        val result = FinancialGraphFactory.build(raw)
        assertThat(result).isInstanceOf(FinancialGraphOutcome.NotEligible::class.java)
        assertThat((result as FinancialGraphOutcome.NotEligible).reason).isEqualTo(NoGraphReason.KIND_DIRECTION_CONFLICT)
        assertThat(result.source).isSameInstanceAs(raw)
    }

    @Test
    fun `unequal supplied borrowing counterparts remain a genuine financial conflict`() {
        val result = rejected(draft(observation = borrowing, legs = listOf(bank, leg("loan", "loan-account", "-90.00"))))
        assertThat(result.issues).contains(GraphBuildIssue.CONFLICTING_LEG_AMOUNTS)
    }
}
