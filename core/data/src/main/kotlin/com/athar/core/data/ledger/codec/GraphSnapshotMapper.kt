package com.athar.core.data.ledger.codec

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financial.RecordingDecision
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.CategoryAssignment
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import kotlinx.collections.immutable.toPersistentList

/** Encode accepted facts once. This mapper is never invoked by snapshot reads. */
internal object GraphSnapshotMapper {
    fun envelope(graph: FinancialEventGraph): GraphSnapshotEnvelope {
        val raw = graph.source
        val identity = graph.economicIdentity.wireIdentity()
        val draft = RawGraphDraftDto(
            eventId = raw.eventId,
            sourceNamespace = raw.sourceNamespace,
            observation = raw.observation.wireObservation(),
            economicIdentity = identity,
            components = raw.components.map {
                RawComponentDto(
                    it.componentRef, it.type.name, it.amount.amount.toString(), it.amount.currency,
                    it.feeRelation?.name, it.feeEvidenceRef, it.support.name, it.evidenceRef,
                )
            }.toPersistentList(),
            legs = raw.legs.map {
                RawLegDto(
                    it.legRef, it.accountRef, it.delta.amount.toString(), it.delta.currency,
                    it.componentRef, it.support.name, it.evidenceRef,
                )
            }.toPersistentList(),
            category = raw.category.wireCategory(),
            linkedRepresentedFeeRef = raw.linkedRepresentedFeeRef,
        )
        val recorded = graph.decision as? RecordingDecision.Recorded
        val snapshot = CertifiedGraphSnapshotDto(
            eventId = graph.eventId.value,
            observationIdentity = ObservationIdentityDto(
                graph.observationIdentity.namespace, graph.observationIdentity.sourceItemId,
            ),
            economicIdentity = identity,
            bookedAmount = graph.bookedAmount.amount.toString(),
            bookedCurrency = graph.bookedAmount.currency,
            bookedBasis = graph.bookedBasis.name,
            certifiedOriginal = graph.originalAmount?.wireMoney(),
            certifiedSettlement = graph.settlementAmount?.wireMoney(),
            kind = graph.kind.name,
            kindResolved = graph.kindResolved,
            incomeNature = graph.incomeNature.name,
            recordingOutcome = if (recorded != null) "RECORDED" else "CAPTURED_UNRESOLVED_KIND",
            kindBasis = recorded?.kindBasis?.name,
            eventDate = graph.observation.eventDate?.toString(),
            routing = graph.routing.name,
            composition = graph.composition.name,
            category = graph.category.wireCategory(),
            linkedRepresentedFeeRef = raw.linkedRepresentedFeeRef,
            components = graph.components.map {
                CertifiedComponentDto(
                    it.componentRef, it.type.name, it.amount.amount.toString(), it.amount.currency,
                    it.feeRelation?.name, it.feeEvidenceRef,
                )
            }.toPersistentList(),
            legs = graph.legs.map {
                CertifiedLegDto(
                    it.legRef, it.accountRef, it.delta.amount.toString(), it.delta.currency, it.componentRef,
                )
            }.toPersistentList(),
            classificationIssues = graph.classificationIssues.map { it.name }.sorted().toPersistentList(),
        )
        return GraphSnapshotEnvelope(
            GraphSnapshotWireFormat.VERSION, GraphSnapshotWireFormat.POLICY_REVISION,
            GraphSnapshotWireFormat.GRAPH_REVISION, draft, snapshot,
        )
    }

    private fun FinancialObservation.wireObservation(): RawObservationDto = RawObservationDto(
        stableId = stableId.value,
        postingState = posting.state.name,
        postingSupport = posting.support.name,
        economicKind = economicKind?.name,
        direction = direction?.name,
        original = original?.wireSupportedMoney(),
        settlement = settlement?.wireSupportedMoney(),
        routingResolved = routing is RoutingCoverage.Resolved,
        routingAccountRef = (routing as? RoutingCoverage.Resolved)?.accountRef,
        eventDate = eventDate?.toString(),
        legacy = legacy?.let { LegacyFactsDto(it.status, it.type, it.value) },
        userDeclaration = userDeclaration?.let { DeclarationDto(it.kind.name, it.note) },
        incomeNature = incomeNature.name,
    )

    private fun Money.wireMoney() = SnapshotMoneyDto(amount.toString(), currency)

    private fun SupportedMoney.wireSupportedMoney() = SupportedMoneyDto(
        amount.amount.toString(), amount.currency, support.name,
    )

    private fun CategoryAssignment?.wireCategory() = this?.let { CategoryDto(it.categoryId, it.basis.name) }

    private fun EconomicIdentity?.wireIdentity(): EconomicIdentityDto? = when (this) {
        is EconomicIdentity.IssuerReference -> EconomicIdentityDto(issuer, reference)
        null -> null
    }
}
