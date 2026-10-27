package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.*
import com.athar.core.domain.model.financialgraph.*
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.component
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.created
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.draft
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.leg
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.observation
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.rejected
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FinancialGraphAllocationTest {
    @Test
    fun `component and leg precision are validated independently without rounding`() {
        for ((currency, bad) in listOf("SAR" to "10.005", "USD" to "10.005", "JPY" to "10.1", "KWD" to "10.1234")) {
            val obs = observation(currency = currency)
            val result = rejected(draft(observation = obs,
                components = listOf(component(amount = bad, currency = currency)),
                legs = listOf(leg(amount = "-$bad", currency = currency))))
            assertThat(result.issues).containsAtLeast(GraphBuildIssue.COMPONENT_UNSUPPORTED_PRECISION,
                GraphBuildIssue.LEG_UNSUPPORTED_PRECISION)
            assertThat(result.source.components.single().amount).isEqualTo(Money.of(bad, currency))
        }
    }

    @Test
    fun `duplicate identifiers are actual duplicates not distinct fixture names`() {
        val result = rejected(draft(
            components = listOf(component("same", "50.00"), component("same", "50.00")),
            legs = listOf(leg("same-leg", amount = "-50.00"), leg("same-leg", amount = "-50.00"))))
        assertThat(result.issues).containsAtLeast(GraphBuildIssue.DUPLICATE_COMPONENT_ID, GraphBuildIssue.DUPLICATE_LEG_ID)
    }

    @Test
    fun `distinct fees cannot reuse one allocation evidence reference`() {
        val result = rejected(draft(components = listOf(component(amount = "98.00"),
            component("fee1", "1.00", type = ComponentType.FEE, relation = FeeRelation.INCLUDED, feeEvidence = "same-span"),
            component("fee2", "1.00", type = ComponentType.FEE, relation = FeeRelation.INCLUDED, feeEvidence = "same-span"))))
        assertThat(result.issues).contains(GraphBuildIssue.DUPLICATE_FEE_EVIDENCE)
    }

    @Test
    fun `referenced principal cannot be overallocated while total account movement still matches`() {
        val result = rejected(draft(components = listOf(component(amount = "98.00"),
            component("fee", "2.00", type = ComponentType.FEE, relation = FeeRelation.INCLUDED, feeEvidence = "fee-span")),
            legs = listOf(leg("principal-leg", amount = "-99.00", component = "principal"),
                leg("fee-leg", amount = "-1.00", component = "fee"))))
        assertThat(result.issues).contains(GraphBuildIssue.LEG_OVERSHOOT)
    }

    @Test
    fun `a referenced component cannot silently convert the leg currency`() {
        val result = rejected(draft(components = listOf(component()),
            legs = listOf(leg(amount = "-100.00", currency = "USD", component = "principal"))))
        assertThat(result.issues).contains(GraphBuildIssue.LEG_COMPONENT_CURRENCY_MISMATCH)
    }

    @Test
    fun `opposite transfer legs may each reference the same principal without double coverage`() {
        val obs = observation(kind = EconomicKind.OWN_TRANSFER, direction = null)
        val graph = created(draft(observation = obs, components = listOf(component()), legs = listOf(
            leg("out", "bank", "-100.00", component = "principal"),
            leg("in", "wallet", "100.00", component = "principal"))))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.ROUTED)
        assertThat(graph.legs).hasSize(2)
    }

    @Test
    fun `a transfer cannot claim two counterpart sides on the same account`() {
        val obs = observation(kind = EconomicKind.OWN_TRANSFER, direction = null)
        val result = rejected(draft(observation = obs, legs = listOf(
            leg("out", "bank", "-100.00"), leg("in", "bank", "100.00"))))
        assertThat(result.issues).contains(GraphBuildIssue.CONFLICTING_LEG_ACCOUNTS)
    }

    @Test
    fun `transfer fees reduce signed net worth once without requiring equal gross sides`() {
        val obs = observation(kind = EconomicKind.CARD_REPAYMENT)
        val graph = created(draft(observation = obs, components = listOf(component(amount = "98.00"),
            component("fee", "2.00", type = ComponentType.FEE, relation = FeeRelation.INCLUDED, feeEvidence = "fee-span")),
            legs = listOf(leg("bank", "bank", "-100.00"), leg("card", "card", "98.00"))))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.ROUTED)
        val total = graph.legs.map { it.delta.amount }.reduce { a, b -> a + b }
        assertThat(total.compareTo(java.math.BigDecimal("-2.00"))).isEqualTo(0)
    }

    @Test
    fun `weaker foreign amount evidence cannot claim full counterpart booking`() {
        val obs = observation(kind = EconomicKind.OWN_TRANSFER, direction = null)
        val graph = created(draft(observation = obs, legs = listOf(leg(),
            leg("usd", "usd-account", "25.00", "USD").copy(support = AmountSupport.EXPLICIT_TRANSACTION))))
        assertThat(graph.legs).hasSize(2)
        assertThat(graph.routing).isEqualTo(RoutingAllocation.PARTIAL)
    }

    @Test
    fun `inbound fee netting remains typed unsupported instead of inventing a formula`() {
        val obs = observation(kind = EconomicKind.REFUND, direction = MovementDirection.IN)
        val result = rejected(draft(observation = obs, components = listOf(
            component(type = ComponentType.REFUND, amount = "98.00"),
            component("fee", "2.00", type = ComponentType.FEE, relation = FeeRelation.INCLUDED, feeEvidence = "fee-span"))))
        assertThat(result.issues).contains(GraphBuildIssue.COMPONENT_NOT_ALLOWED_FOR_KIND)
    }
}
