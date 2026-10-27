package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financial.BookedBasis
import com.athar.core.domain.calc.financial.FinancialRecordingPolicy
import com.athar.core.domain.model.financial.*
import com.athar.core.domain.model.financialgraph.*
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.component
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.created
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.draft
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.leg
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.observation
import com.athar.core.domain.calc.financialgraph.GraphTestFixtures.rejected
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FinancialGraphTrustTest {
    @Test
    fun `both actual currencies and complete policy observation are retained`() {
        val obs = observation(amount = "10.00", currency = "USD").copy(
            settlement = SupportedMoney(Money.of("37.50"), AmountSupport.EXPLICIT_BOOKED),
        )
        val raw = draft(observation = obs)
        val graph = created(raw)
        assertThat(graph.source).isSameInstanceAs(raw)
        assertThat(graph.observation).isSameInstanceAs(obs)
        assertThat(graph.decision).isEqualTo(FinancialRecordingPolicy.decide(obs))
        assertThat(graph.bookedBasis).isEqualTo(BookedBasis.SETTLEMENT)
        assertThat(graph.bookedAmount).isEqualTo(Money.of("37.50"))
        assertThat(graph.originalAmount).isEqualTo(Money.of("10.00", "USD"))
        assertThat(graph.settlementAmount).isEqualTo(Money.of("37.50"))
    }

    @Test
    fun `invalid or heuristic secondary money remains raw but never certified`() {
        for (secondary in listOf(
            SupportedMoney(Money.of("10.005", "USD"), AmountSupport.EXPLICIT_TRANSACTION),
            SupportedMoney(Money.of("10.00", "USD"), AmountSupport.HEURISTIC_ONLY),
        )) {
            val obs = observation().copy(original = secondary,
                settlement = SupportedMoney(Money.of("37.50"), AmountSupport.EXPLICIT_BOOKED))
            val graph = created(draft(observation = obs))
            assertThat(graph.observation.original).isEqualTo(secondary)
            assertThat(graph.originalAmount).isNull()
            assertThat(graph.bookedAmount).isEqualTo(Money.of("37.50"))
        }
    }

    @Test
    fun `blank optional category cannot reject known money or become validated metadata`() {
        val assignment = CategoryAssignment(" ", CategoryBasis.USER_DECLARED)
        val graph = created(draft(category = assignment))
        assertThat(graph.bookedAmount).isEqualTo(Money.of("100.00"))
        assertThat(graph.category).isNull()
        assertThat(graph.source.category).isSameInstanceAs(assignment)
        assertThat(graph.classificationIssues).containsExactly(GraphBuildIssue.BLANK_CATEGORY_ID)
    }

    @Test
    fun `unknown fee retains raw facts without certifying or inventing a split`() {
        val fee = component("fee", "2.00", type = ComponentType.FEE, relation = FeeRelation.UNKNOWN)
        val raw = draft(components = listOf(component(), fee), legs = listOf(leg()))
        val graph = created(raw)
        assertThat(graph.bookedAmount).isEqualTo(Money.of("100.00"))
        assertThat(graph.source.components).containsExactly(component(), fee).inOrder()
        assertThat(graph.components).isEmpty()
        assertThat(graph.composition).isEqualTo(CompositionResolution.UNRESOLVED_FEE_RELATION)
        assertThat(graph.legs.single().delta).isEqualTo(Money.of("-100.00"))
    }

    @Test
    fun `absent composition and routing are not manufactured from a known booking`() {
        val graph = created(draft())
        assertThat(graph.components).isEmpty()
        assertThat(graph.legs).isEmpty()
        assertThat(graph.composition).isEqualTo(CompositionResolution.UNSPECIFIED)
        assertThat(graph.routing).isEqualTo(RoutingAllocation.UNALLOCATED)
    }

    @Test
    fun `unknown inflow never inherits earned income classification or a guessed kind`() {
        val obs = observation(kind = null, direction = MovementDirection.IN).copy(incomeNature = IncomeNature.EARNED)
        val graph = created(draft(observation = obs))
        assertThat(graph.kind).isEqualTo(EconomicKind.UNRESOLVED_POSTED_MOVEMENT)
        assertThat(graph.kindResolved).isFalse()
        assertThat(graph.incomeNature).isEqualTo(IncomeNature.UNCLASSIFIED)
        assertThat(graph.observation.incomeNature).isEqualTo(IncomeNature.EARNED)
    }

    @Test
    fun `raw input defaults cannot certify component or leg amounts`() {
        val c = ComponentSpec("c", ComponentType.PRINCIPAL, Money.of("100.00"))
        val l = LegSpec("l", "bank", Money.of("-100.00"))
        val raw = draft(components = listOf(c), legs = listOf(l))
        val rejected = rejected(raw)
        assertThat(rejected.source).isSameInstanceAs(raw)
        assertThat(rejected.decision).isEqualTo(FinancialRecordingPolicy.decide(raw.observation))
        assertThat(rejected.issues).containsAtLeast(
            GraphBuildIssue.UNSUPPORTED_COMPONENT_AMOUNT, GraphBuildIssue.UNSUPPORTED_LEG_AMOUNT,
        )
    }

    @Test
    fun `explicit support without a source reference cannot certify an allocation`() {
        val result = rejected(draft(components = listOf(component().copy(evidenceRef = " ")),
            legs = listOf(leg().copy(evidenceRef = null))))
        assertThat(result.issues).containsAtLeast(
            GraphBuildIssue.COMPONENT_EVIDENCE_REQUIRED, GraphBuildIssue.LEG_EVIDENCE_REQUIRED,
        )
    }

    @Test
    fun `caller aliases and exposed collections cannot invalidate a graph or raw evidence`() {
        val components = mutableListOf(component())
        val legs = mutableListOf(leg())
        val raw = draft(components = components, legs = legs)
        val graph = created(raw)
        components.clear(); legs.clear()
        assertThat(raw.components).hasSize(1)
        assertThat(graph.legs).hasSize(1)
        assertThrows(RuntimeException::class.java) { (raw.components as MutableList<ComponentSpec>).clear() }
        assertThrows(RuntimeException::class.java) { (raw.legs as MutableList<LegSpec>).clear() }
        assertThrows(RuntimeException::class.java) { (graph.components as MutableList<FinancialComponent>).clear() }
        assertThrows(RuntimeException::class.java) { (graph.legs as MutableList<AccountLeg>).clear() }
        assertThat(graph.components).hasSize(1)
        assertThat(graph.legs).hasSize(1)
        assertThat(FinancialEventGraph::class.java.methods.map { it.name }).doesNotContain("copy")
    }

    @Test
    fun `not eligible outcome also retains the complete raw draft`() {
        val obs = observation().copy(legacy = LegacyFacts("DISMISSED", "EXPENSE", "100.00"))
        val raw = draft(observation = obs, components = listOf(ComponentSpec("bad", ComponentType.FEE, Money.zero())))
        val result = FinancialGraphFactory.build(raw) as FinancialGraphOutcome.NotEligible
        assertThat(result.source).isSameInstanceAs(raw)
        assertThat(result.decision.observation).isSameInstanceAs(obs)
        assertThat(result.reason).isEqualTo(NoGraphReason.LEGACY_PRESERVED)
    }
}
