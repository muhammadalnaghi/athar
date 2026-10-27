package com.athar.core.data.ledger.admission

import com.athar.core.common.money.Money
import com.athar.core.data.ledger.codec.GraphSnapshotCodec
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.LegSpec
import com.athar.core.domain.model.financialgraph.RoutingAllocation
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Rejection contract tested with actual public factory graphs, not forged certificates. */
class AdmissionBoundsTest {
    @Test
    fun `fully routed graph is rejected without inferring account ownership`() {
        val graph = graph(legs = listOf(leg("-1")))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.ROUTED)
        assertThat(graph.legs).hasSize(1)
        reject(graph, AdmissionRejectionReason.UNSUPPORTED_ROUTING)
    }

    @Test
    fun `partially routed graph and its raw and certified legs cannot enter unallocated store`() {
        val graph = graph(legs = listOf(leg("-0.50")))
        assertThat(graph.routing).isEqualTo(RoutingAllocation.PARTIAL)
        assertThat(graph.source.legs).hasSize(1)
        assertThat(graph.legs).hasSize(1)
        // Valid legged graphs are PARTIAL/ROUTED, so the first routing gate
        // rejects them. Do not forge an impossible certificate for a later guard.
        reject(graph, AdmissionRejectionReason.UNSUPPORTED_ROUTING)
    }

    @Test
    fun `linked represented fee is rejected even with no account legs`() {
        val graph = graph(linkedFee = "synthetic-other-fee-event")
        assertThat(graph.routing).isEqualTo(RoutingAllocation.UNALLOCATED)
        reject(graph, AdmissionRejectionReason.UNSUPPORTED_LINKED_FEE)
    }

    @Test
    fun `money with more than 256 significant digits is rejected before encoding`() {
        val graph = graph(amount = "9".repeat(257))
        assertThat(graph.bookedAmount.amount.precision()).isEqualTo(257)
        reject(graph, AdmissionRejectionReason.MONEY_REPRESENTATION_BOUND)
    }

    @Test
    fun `unsupported raw secondary money is still bounded at either scale extreme`() {
        for (amount in listOf("1E+1025", "1E-1025")) {
            val graph = graph(heuristicSecondary = Money.of(amount, "SAR"))
            assertThat(graph.bookedAmount.amount.toString()).isEqualTo("1")
            assertThat(graph.source.observation.original!!.support).isEqualTo(AmountSupport.HEURISTIC_ONLY)
            reject(graph, AdmissionRejectionReason.MONEY_REPRESENTATION_BOUND)
        }
    }

    @Test
    fun `raw component collection above 1000 is rejected before allocation by encoder`() {
        val graph = graph(componentCount = 1_001)
        assertThat(graph.source.components).hasSize(1_001)
        reject(graph, AdmissionRejectionReason.RAW_COLLECTION_BOUND)
    }

    @Test
    fun `individually valid raw evidence strings cannot exceed aggregate UTF8 budget`() {
        val graph = graph(componentCount = 40, componentEvidence = "e".repeat(4_096))
        assertThat(graph.source.components.all { it.evidenceRef!!.length == 4_096 }).isTrue()
        reject(graph, AdmissionRejectionReason.AGGREGATE_TEXT_BOUND)
    }

    @Test
    fun `bounded raw collection can still exceed exact encoded snapshot byte budget`() {
        val graph = graph(componentCount = 1_000)
        assertThat(GraphSnapshotCodec.encode(graph).toByteArray(Charsets.UTF_8).size)
            .isGreaterThan(AdmissionBounds.MAX_SNAPSHOT_JSON_BYTES)
        reject(graph, AdmissionRejectionReason.SNAPSHOT_JSON_BOUND)
    }

    private fun reject(graph: FinancialEventGraph, reason: AdmissionRejectionReason) {
        val result = AdmissionPreparation.prepare(graph, byteArrayOf(0, -1), "synthetic:bounds:evidence")
        assertThat(result).isInstanceOf(AdmissionPreparation.Preparation.Unprepared::class.java)
        assertThat((result as AdmissionPreparation.Preparation.Unprepared).rejection.reason).isEqualTo(reason)
    }

    private fun leg(delta: String) = LegSpec(
        "leg", "synthetic-account", Money.of(delta, "SAR"),
        support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "synthetic:leg:evidence",
    )

    private fun graph(
        amount: String? = null,
        componentCount: Int = 1,
        componentEvidence: String = "synthetic:component",
        legs: List<LegSpec> = emptyList(),
        linkedFee: String? = null,
        heuristicSecondary: Money? = null,
    ): FinancialEventGraph {
        val booked = Money.of(amount ?: componentCount.toString(), "SAR")
        val componentMoney = if (componentCount == 1) booked else Money.of("1", "SAR")
        val observation = FinancialObservation(
            stableId = StableObservationId("synthetic-bounds-observation"),
            posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
            economicKind = EconomicKind.PURCHASE,
            direction = MovementDirection.OUT,
            original = SupportedMoney(heuristicSecondary ?: booked,
                if (heuristicSecondary == null) AmountSupport.EXPLICIT_BOOKED else AmountSupport.HEURISTIC_ONLY),
            settlement = if (heuristicSecondary == null) null else SupportedMoney(booked, AmountSupport.EXPLICIT_BOOKED),
            routing = RoutingCoverage.Unresolved,
            eventDate = null,
            legacy = null,
            userDeclaration = null,
        )
        val result = FinancialGraphFactory.build(FinancialGraphDraft(
            eventId = "synthetic-bounds-event", observation = observation, sourceNamespace = "synthetic-bounds",
            components = List(componentCount) { index ->
                ComponentSpec("principal-$index", ComponentType.PRINCIPAL, componentMoney,
                    support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = componentEvidence)
            },
            legs = legs, linkedRepresentedFeeRef = linkedFee,
        ))
        assertThat(result).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return (result as FinancialGraphOutcome.Created).graph
    }
}
