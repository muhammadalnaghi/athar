package com.athar.core.data.ledger.api

import com.athar.core.common.money.Money
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.LegacyFacts
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.LegSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * WM063: pure checks of the card-repayment expected-kind/type seam over REAL
 * FinancialGraphFactory graphs and REAL TransactionEntity rows. Room-level
 * atomicity/replay/rollback is proven by the dedicated native test; policy
 * semantics stay owned by the domain factory.
 */
class CardRepaymentProjectionPredicateTest {
    private val date = LocalDate.parse("2026-03-14")
    private val instant = Instant.fromEpochMilliseconds(7)
    private val session = FinancialRecordingSession(Any())

    private fun build(
        kind: EconomicKind = EconomicKind.CARD_REPAYMENT,
        direction: MovementDirection? = MovementDirection.OUT,
        posting: PostingEvidence = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
        eventDate: LocalDate? = date,
        legacy: LegacyFacts? = null,
        namespace: String = "sms",
        amount: String = "500.00",
        components: List<ComponentSpec>? = null,
        legs: List<LegSpec> = emptyList(),
    ): FinancialGraphOutcome {
        val money = Money.of(amount, "SAR")
        val observation = FinancialObservation(
            stableId = StableObservationId("repayment-source-a"),
            posting = posting,
            economicKind = kind,
            direction = direction,
            original = SupportedMoney(money, AmountSupport.EXPLICIT_BOOKED),
            settlement = null,
            routing = RoutingCoverage.Unresolved,
            eventDate = eventDate,
            legacy = legacy,
            userDeclaration = null,
        )
        return FinancialGraphFactory.build(
            FinancialGraphDraft(
                eventId = "repayment-event-a",
                observation = observation,
                sourceNamespace = namespace,
                components = components
                    ?: listOf(
                        ComponentSpec(
                            componentRef = "principal",
                            type = ComponentType.PRINCIPAL,
                            amount = money,
                            support = AmountSupport.EXPLICIT_TRANSACTION,
                            evidenceRef = "repayment:principal",
                        ),
                    ),
                legs = legs,
            ),
        )
    }

    private fun created(outcome: FinancialGraphOutcome): FinancialEventGraph {
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return (outcome as FinancialGraphOutcome.Created).graph
    }

    private fun transferRow(): TransactionEntity = TransactionEntity(
        id = "fresh-transfer",
        accountId = "account-a",
        type = "TRANSFER",
        amountMinor = 50000,
        currency = "SAR",
        date = date,
        occurredAt = null,
        merchant = "",
        merchantNormalized = "",
        categoryId = null,
        notes = null,
        source = "SMS",
        sourceRefId = "repayment-source-a",
        status = "PENDING",
        confidence = null,
        createdAt = instant,
        updatedAt = instant,
    )

    private fun projection(
        row: TransactionEntity = transferRow(),
        createdAt: Instant = instant,
        confirmedAt: Instant = instant,
    ) = NewPurchaseProjection(row.id, createdAt, confirmedAt, session)

    @Test
    fun `valid repayment matches exactly its fresh pending transfer row and the purchase api refuses it`() {
        val graph = created(build())
        val row = transferRow()
        assertThat(isPostedCardRepayment(graph)).isTrue()
        assertThat(matchesNewCardRepayment(graph, row, projection())).isTrue()
        assertThat(isPostedPurchase(graph)).isFalse()
        assertThat(matchesNewPurchase(graph, row, projection())).isFalse()
        val notificationGraph = created(build(namespace = "notification"))
        assertThat(matchesNewCardRepayment(notificationGraph, row.copy(source = "NOTIFICATION"), projection())).isTrue()
    }

    @Test
    fun `purchase projection behavior is unchanged and never confirms a transfer row`() {
        val purchaseGraph = created(build(kind = EconomicKind.PURCHASE))
        assertThat(isPostedPurchase(purchaseGraph)).isTrue()
        assertThat(matchesNewPurchase(purchaseGraph, transferRow().copy(type = "EXPENSE"), projection())).isTrue()
        assertThat(isPostedCardRepayment(purchaseGraph)).isFalse()
        assertThat(matchesNewCardRepayment(purchaseGraph, transferRow(), projection())).isFalse()
    }

    @Test
    fun `edited confirmed dismissed or mismatched rows cannot be promoted`() {
        val graph = created(build())
        val row = transferRow()
        val variants = listOf(
            row.copy(type = "EXPENSE"),
            row.copy(type = "INCOME"),
            row.copy(source = "MANUAL"),
            row.copy(sourceRefId = "other-source"),
            row.copy(amountMinor = 49999),
            row.copy(currency = "USD"),
            row.copy(date = LocalDate.parse("2026-03-15")),
            row.copy(categorySource = "USER"),
            row.copy(status = "DISMISSED"),
            row.copy(status = "CONFIRMED"),
            row.copy(updatedAt = Instant.fromEpochMilliseconds(9)),
        )
        for (variant in variants) assertThat(matchesNewCardRepayment(graph, variant, projection())).isFalse()
    }

    @Test
    fun `missing or stale projection rows refuse`() {
        val graph = created(build())
        val row = transferRow()
        assertThat(matchesNewCardRepayment(graph, null, projection())).isFalse()
        assertThat(matchesNewCardRepayment(graph, row.copy(id = "other-row"), projection())).isFalse()
        assertThat(matchesNewCardRepayment(graph, row, projection(createdAt = Instant.fromEpochMilliseconds(6)))).isFalse()
        assertThat(
            matchesNewCardRepayment(graph, row, projection(confirmedAt = Instant.fromEpochMilliseconds(6))),
        ).isFalse()
    }

    @Test
    fun `unsupported graph shapes stay refused`() {
        val includedFeeComposite = listOf(
            ComponentSpec(
                componentRef = "principal",
                type = ComponentType.PRINCIPAL,
                amount = Money.of("499.00", "SAR"),
                support = AmountSupport.EXPLICIT_TRANSACTION,
                evidenceRef = "repayment:principal",
            ),
            ComponentSpec(
                componentRef = "fee",
                type = ComponentType.FEE,
                amount = Money.of("1.00", "SAR"),
                feeRelation = FeeRelation.INCLUDED,
                feeEvidenceRef = "repayment:fee",
                support = AmountSupport.EXPLICIT_TRANSACTION,
                evidenceRef = "repayment:fee:amount",
            ),
        )
        val unresolvedFeeComposite = listOf(
            ComponentSpec(
                componentRef = "principal",
                type = ComponentType.PRINCIPAL,
                amount = Money.of("500.00", "SAR"),
                support = AmountSupport.EXPLICIT_TRANSACTION,
                evidenceRef = "repayment:principal",
            ),
            ComponentSpec(
                componentRef = "fee",
                type = ComponentType.FEE,
                amount = Money.of("1.00", "SAR"),
                feeRelation = FeeRelation.UNKNOWN,
                support = AmountSupport.EXPLICIT_TRANSACTION,
                evidenceRef = "repayment:fee:amount",
            ),
        )
        val routedLegs = listOf(
            LegSpec("leg-out", "bank", Money.of("-500.00", "SAR"), null, AmountSupport.EXPLICIT_BOOKED, "repayment:leg:out"),
            LegSpec("leg-in", "card", Money.of("500.00", "SAR"), null, AmountSupport.EXPLICIT_BOOKED, "repayment:leg:in"),
        )
        val row = transferRow()
        val unsupported = listOf(
            build(eventDate = null),
            build(namespace = "import"),
            build(components = includedFeeComposite),
            build(components = unresolvedFeeComposite),
            build(legs = routedLegs),
        )
        for (outcome in unsupported) {
            val graph = created(outcome)
            assertThat(isPostedCardRepayment(graph)).isFalse()
            assertThat(matchesNewCardRepayment(graph, row, projection())).isFalse()
        }
    }

    @Test
    fun `heuristic hold reversed legacy and zero money never leave the factory`() {
        val unreachable = listOf(
            build(posting = PostingEvidence(PostingState.AUTHORIZED, PostingSupport.EXPLICIT_POSTED)),
            build(posting = PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY)),
            build(direction = MovementDirection.IN),
            build(legacy = LegacyFacts("DISMISSED", "EXPENSE", "1")),
            build(amount = "0.00"),
        )
        for (outcome in unreachable) assertThat(outcome).isInstanceOf(FinancialGraphOutcome.NotEligible::class.java)
    }
}
