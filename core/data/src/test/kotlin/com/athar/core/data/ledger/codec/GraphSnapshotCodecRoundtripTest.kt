package com.athar.core.data.ledger.codec

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.ExplicitFinancialDeclaration
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.CategoryAssignment
import com.athar.core.domain.model.financialgraph.CategoryBasis
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.CompositionResolution
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.LegSpec
import com.athar.core.domain.model.financialgraph.RoutingAllocation
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for the lossless graph snapshot codec. Every graph is
 * produced by the REAL FinancialGraphFactory + actual recording policy —
 * never a forged certificate.
 */
class GraphSnapshotCodecRoundtripTest {

    private fun observation(
        amount: String = "100.00",
        currency: String = "SAR",
        kind: EconomicKind? = EconomicKind.PURCHASE,
        direction: MovementDirection? = MovementDirection.OUT,
        settlement: SupportedMoney? = null,
        eventDate: String? = null,
        declaration: ExplicitFinancialDeclaration? = null,
    ) = FinancialObservation(
        stableId = StableObservationId("obs-1"),
        posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
        economicKind = kind,
        direction = direction,
        original = SupportedMoney(Money.of(amount, currency), AmountSupport.EXPLICIT_BOOKED),
        settlement = settlement,
        routing = RoutingCoverage.Unresolved,
        eventDate = eventDate?.let(LocalDate::parse),
        legacy = null,
        userDeclaration = declaration,
    )

    private fun principal(amount: String, currency: String) = ComponentSpec(
        componentRef = "principal",
        type = ComponentType.PRINCIPAL,
        amount = Money.of(amount, currency),
        support = AmountSupport.EXPLICIT_TRANSACTION,
        evidenceRef = "source:principal:amount",
    )

    private fun leg(ref: String, account: String, delta: String, currency: String, component: String? = null) = LegSpec(
        legRef = ref,
        accountRef = account,
        delta = Money.of(delta, currency),
        componentRef = component,
        support = AmountSupport.EXPLICIT_BOOKED,
        evidenceRef = "source:$ref:amount",
    )

    private fun created(draft: FinancialGraphDraft): FinancialEventGraph {
        val outcome = FinancialGraphFactory.build(draft)
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return (outcome as FinancialGraphOutcome.Created).graph
    }

    private fun decoded(encoded: String): GraphSnapshotDecodeResult.Decoded {
        val result = GraphSnapshotCodec.decode(encoded)
        assertThat(result).isInstanceOf(GraphSnapshotDecodeResult.Decoded::class.java)
        return result as GraphSnapshotDecodeResult.Decoded
    }

    @Test
    fun `round trips SAR USD JPY KWD significant decimals exactly`() {
        for ((amount, currency) in listOf("1500.55" to "SAR", "99.99" to "USD", "1000" to "JPY", "10.123" to "KWD")) {
            val graph = created(
                FinancialGraphDraft(
                    eventId = "event-$currency",
                    observation = observation(amount, currency),
                    sourceNamespace = "sms",
                    components = listOf(principal(amount, currency)),
                    legs = listOf(leg("bank-leg", "bank", "-$amount", currency, component = "principal")),
                ),
            )
            val decoded = decoded(GraphSnapshotCodec.encode(graph))
            val snapshot = decoded.certifiedSnapshot
            assertThat(snapshot.eventId).isEqualTo("event-$currency")
            assertThat(snapshot.bookedAmount).isEqualTo(amount)
            assertThat(snapshot.bookedCurrency).isEqualTo(currency)
            assertThat(snapshot.bookedBasis).isEqualTo("ORIGINAL")
            assertThat(snapshot.kind).isEqualTo("PURCHASE")
            assertThat(snapshot.kindResolved).isTrue()
            assertThat(snapshot.components.single().amount).isEqualTo(amount)
            assertThat(snapshot.components.single().currency).isEqualTo(currency)
            assertThat(snapshot.legs.single().delta).isEqualTo("-$amount")
            assertThat(snapshot.legs.single().currency).isEqualTo(currency)
            val raw = decoded.rawDraft
            assertThat(raw.observation.original?.amount).isEqualTo(amount)
            assertThat(raw.observation.original?.currency).isEqualTo(currency)
            assertThat(raw.components.single().support).isEqualTo("EXPLICIT_TRANSACTION")
            assertThat(raw.components.single().evidenceRef).isEqualTo("source:principal:amount")
            assertThat(raw.legs.single().delta).isEqualTo("-$amount")
            assertThat(raw.legs.single().support).isEqualTo("EXPLICIT_BOOKED")
        }
    }

    @Test
    fun `preserves paired transfer and borrowing signed legs exactly`() {
        val transfer = created(
            FinancialGraphDraft(
                eventId = "event-transfer",
                observation = observation(
                    "200.00", "SAR", EconomicKind.OWN_TRANSFER, direction = null,
                    declaration = ExplicitFinancialDeclaration(EconomicKind.OWN_TRANSFER, "moved between my accounts"),
                ),
                sourceNamespace = "sms",
                economicIdentity = EconomicIdentity.IssuerReference("ISSUER-1", "REF-9"),
                components = listOf(principal("200.00", "SAR")),
                legs = listOf(
                    leg("out-leg", "bank", "-200.00", "SAR", component = "principal"),
                    leg("in-leg", "card", "200.00", "SAR", component = "principal"),
                ),
                category = CategoryAssignment("cat-transfers", CategoryBasis.USER_DECLARED),
                linkedRepresentedFeeRef = "fee-event-0",
            ),
        )
        val decoded = decoded(GraphSnapshotCodec.encode(transfer))
        assertThat(decoded.certifiedSnapshot.routing).isEqualTo(RoutingAllocation.ROUTED.name)
        assertThat(decoded.certifiedSnapshot.legs.map { it.delta }).containsExactly("-200.00", "200.00").inOrder()
        assertThat(decoded.rawDraft.legs.map { it.delta }).containsExactly("-200.00", "200.00").inOrder()
        assertThat(decoded.rawDraft.legs.map { it.accountRef }).containsExactly("bank", "card").inOrder()
        assertThat(decoded.rawDraft.category?.categoryId).isEqualTo("cat-transfers")
        assertThat(decoded.rawDraft.category?.basis).isEqualTo("USER_DECLARED")
        assertThat(decoded.rawDraft.linkedRepresentedFeeRef).isEqualTo("fee-event-0")
        assertThat(decoded.rawDraft.observation.userDeclaration?.kind).isEqualTo("OWN_TRANSFER")
        assertThat(decoded.rawDraft.observation.userDeclaration?.note).isEqualTo("moved between my accounts")
        assertThat(decoded.rawDraft.economicIdentity?.issuer).isEqualTo("ISSUER-1")
        assertThat(decoded.certifiedSnapshot.economicIdentity?.reference).isEqualTo("REF-9")

        val borrowing = created(
            FinancialGraphDraft(
                eventId = "event-borrowing",
                observation = observation("1000.00", "USD", EconomicKind.BORROWING, direction = MovementDirection.IN),
                sourceNamespace = "sms",
                components = listOf(principal("1000.00", "USD")),
                legs = listOf(
                    leg("asset-leg", "bank", "1000.00", "USD", component = "principal"),
                    leg("liability-leg", "loan", "-1000.00", "USD"),
                ),
            ),
        )
        val decodedBorrowing = decoded(GraphSnapshotCodec.encode(borrowing))
        assertThat(decodedBorrowing.certifiedSnapshot.kind).isEqualTo("BORROWING")
        assertThat(decodedBorrowing.certifiedSnapshot.legs.map { it.accountRef to it.delta })
            .containsExactly("bank" to "1000.00", "loan" to "-1000.00")
            .inOrder()
        assertThat(GraphSnapshotCodec.encode(borrowing)).isEqualTo(GraphSnapshotCodec.encode(borrowing))
    }

    @Test
    fun `retains UNKNOWN fee relation raw without certified split`() {
        val graph = created(
            FinancialGraphDraft(
                eventId = "event-unknown-fee",
                observation = observation("100.00", "SAR"),
                sourceNamespace = "sms",
                components = listOf(
                    principal("100.00", "SAR"),
                    ComponentSpec(
                        componentRef = "fee-1",
                        type = ComponentType.FEE,
                        amount = Money.of("5.00", "SAR"),
                        feeRelation = FeeRelation.UNKNOWN,
                        support = AmountSupport.EXPLICIT_TRANSACTION,
                        evidenceRef = "source:fee-1:amount",
                    ),
                ),
                legs = listOf(leg("bank-leg", "bank", "-100.00", "SAR")),
            ),
        )
        val decoded = decoded(GraphSnapshotCodec.encode(graph))
        assertThat(decoded.certifiedSnapshot.composition).isEqualTo(CompositionResolution.UNRESOLVED_FEE_RELATION.name)
        assertThat(decoded.certifiedSnapshot.components).isEmpty()
        assertThat(decoded.rawDraft.components).hasSize(2)
        val rawFee = decoded.rawDraft.components.last()
        assertThat(rawFee.type).isEqualTo("FEE")
        assertThat(rawFee.feeRelation).isEqualTo("UNKNOWN")
        assertThat(rawFee.amount).isEqualTo("5.00")
        // Known booked account movement is fully routed; unknown economic fee split is separate.
        assertThat(decoded.certifiedSnapshot.routing).isEqualTo(RoutingAllocation.ROUTED.name)
    }

    @Test
    fun `invalid heuristic secondary stays raw only and never certified`() {
        val graph = created(
            FinancialGraphDraft(
                eventId = "event-heuristic",
                observation = observation(
                    "100.00", "SAR",
                    settlement = SupportedMoney(Money.of("12.3456789", "USD"), AmountSupport.HEURISTIC_ONLY),
                    eventDate = "2026-10-19",
                ),
                sourceNamespace = "sms",
                components = listOf(principal("100.00", "SAR")),
                legs = listOf(leg("bank-leg", "bank", "-100.00", "SAR", component = "principal")),
            ),
        )
        val decoded = decoded(GraphSnapshotCodec.encode(graph))
        assertThat(decoded.certifiedSnapshot.certifiedSettlement).isNull()
        assertThat(decoded.certifiedSnapshot.certifiedOriginal?.amount).isEqualTo("100.00")
        assertThat(decoded.certifiedSnapshot.certifiedOriginal?.currency).isEqualTo("SAR")
        assertThat(decoded.rawDraft.observation.settlement?.amount).isEqualTo("12.3456789")
        assertThat(decoded.rawDraft.observation.settlement?.currency).isEqualTo("USD")
        assertThat(decoded.rawDraft.observation.settlement?.support).isEqualTo("HEURISTIC_ONLY")
        assertThat(decoded.rawDraft.observation.eventDate).isEqualTo("2026-10-19")
    }

    @Test
    fun `missing category date and routing survive as explicit absences`() {
        val graph = created(FinancialGraphDraft("event-absent", observation(), "sms"))
        val decoded = decoded(GraphSnapshotCodec.encode(graph))
        assertThat(decoded.rawDraft.category).isNull()
        assertThat(decoded.rawDraft.observation.eventDate).isNull()
        assertThat(decoded.rawDraft.observation.routingResolved).isFalse()
        assertThat(decoded.rawDraft.observation.routingAccountRef).isNull()
        assertThat(decoded.rawDraft.observation.legacy).isNull()
        assertThat(decoded.rawDraft.observation.direction).isEqualTo("OUT")
        assertThat(decoded.rawDraft.linkedRepresentedFeeRef).isNull()
        assertThat(decoded.certifiedSnapshot.routing).isEqualTo(RoutingAllocation.UNALLOCATED.name)
        assertThat(decoded.certifiedSnapshot.components).isEmpty()
        assertThat(decoded.certifiedSnapshot.legs).isEmpty()
        assertThat(decoded.certifiedSnapshot.classificationIssues).isEmpty()
        assertThat(decoded.certifiedSnapshot.composition).isEqualTo(CompositionResolution.UNSPECIFIED.name)
    }

    @Test
    fun `blank category becomes a preserved classification issue deterministically`() {
        val graph = created(
            FinancialGraphDraft(
                "event-issue",
                observation(),
                "sms",
                category = CategoryAssignment("   ", CategoryBasis.RULE_DERIVED),
            ),
        )
        val decoded = decoded(GraphSnapshotCodec.encode(graph))
        assertThat(decoded.certifiedSnapshot.classificationIssues).containsExactly("BLANK_CATEGORY_ID")
        assertThat(decoded.rawDraft.category?.categoryId).isEqualTo("   ")
        assertThat(GraphSnapshotCodec.encode(graph)).isEqualTo(GraphSnapshotCodec.encode(graph))
    }

    @Test
    fun `decode is stable and returns preserved dtos never a new certificate`() {
        val graph = created(
            FinancialGraphDraft(
                eventId = "event-stable",
                observation = observation("45.50", "SAR"),
                sourceNamespace = "sms",
                components = listOf(principal("45.50", "SAR")),
                legs = listOf(leg("bank-leg", "bank", "-45.50", "SAR", component = "principal")),
            ),
        )
        val encoded = GraphSnapshotCodec.encode(graph)
        val first = decoded(encoded)
        val second = decoded(encoded)
        assertThat(second).isEqualTo(first)
        assertThat(first.certifiedSnapshot.kindResolved).isTrue()
        assertThat(first.certifiedSnapshot.kind).isEqualTo("PURCHASE")
        assertThat(first.certifiedSnapshot.bookedBasis).isEqualTo("ORIGINAL")
        assertThat(first.certifiedSnapshot.incomeNature).isEqualTo("UNCLASSIFIED")
        assertThat(first.rawDraft.observation.stableId).isEqualTo("obs-1")
        assertThat(first.rawDraft.observation.postingState).isEqualTo("POSTED")
        assertThat(first.rawDraft.observation.postingSupport).isEqualTo("EXPLICIT_POSTED")
        assertThat(first.rawDraft.observation.incomeNature).isEqualTo("UNCLASSIFIED")
        assertThat(first.rawDraft.sourceNamespace).isEqualTo("sms")
        assertThat(first.certifiedSnapshot.observationIdentity.sourceItemId).isEqualTo("obs-1")
        assertThat(first.certifiedSnapshot.observationIdentity.namespace).isEqualTo("sms")
    }
}
