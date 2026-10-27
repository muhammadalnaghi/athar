package com.athar.core.data.ledger.admission

import com.athar.core.data.ledger.codec.CertifiedGraphSnapshotDto
import com.athar.core.domain.calc.financial.RecordingDecision
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import java.math.BigDecimal

/**
 * Certified economic facts only. Category metadata, classification issues and
 * the proposed event ID are deliberately absent: they never participate in
 * replay economics. Amounts keep their exact stored string form; equality is
 * numeric per literal currency, never a stored-value rewrite.
 */
internal data class EconomicComponentFact(
    val componentRef: String,
    val type: String,
    val amount: String,
    val currency: String,
    val feeRelation: String?,
    val feeEvidenceRef: String?,
)

internal data class EconomicLegFact(
    val legRef: String,
    val accountRef: String,
    val delta: String,
    val currency: String,
    val componentRef: String?,
)

internal data class EconomicFacts(
    val bookedAmount: String,
    val bookedCurrency: String,
    val bookedBasis: String,
    val originalAmount: String?,
    val originalCurrency: String?,
    val settlementAmount: String?,
    val settlementCurrency: String?,
    val kind: String,
    val kindResolved: Boolean,
    val incomeNature: String,
    val recordingOutcome: String,
    val kindBasis: String?,
    val eventDate: String?,
    val routing: String,
    val composition: String,
    val identityIssuer: String?,
    val identityReference: String?,
    val linkedRepresentedFeeRef: String?,
    val components: List<EconomicComponentFact>,
    val legs: List<EconomicLegFact>,
)

internal object GraphEconomicFacts {
    fun of(graph: FinancialEventGraph): EconomicFacts {
        val recorded = graph.decision as? RecordingDecision.Recorded
        val identity = graph.economicIdentity as? EconomicIdentity.IssuerReference
        return EconomicFacts(
            bookedAmount = graph.bookedAmount.amount.toString(),
            bookedCurrency = graph.bookedAmount.currency,
            bookedBasis = graph.bookedBasis.name,
            originalAmount = graph.originalAmount?.amount?.toString(),
            originalCurrency = graph.originalAmount?.currency,
            settlementAmount = graph.settlementAmount?.amount?.toString(),
            settlementCurrency = graph.settlementAmount?.currency,
            kind = graph.kind.name,
            kindResolved = graph.kindResolved,
            incomeNature = graph.incomeNature.name,
            recordingOutcome = if (recorded != null) "RECORDED" else "CAPTURED_UNRESOLVED_KIND",
            kindBasis = recorded?.kindBasis?.name,
            eventDate = graph.observation.eventDate?.toString(),
            routing = graph.routing.name,
            composition = graph.composition.name,
            identityIssuer = identity?.issuer,
            identityReference = identity?.reference,
            linkedRepresentedFeeRef = graph.source.linkedRepresentedFeeRef,
            components = graph.components
                .map {
                    EconomicComponentFact(
                        it.componentRef, it.type.name, it.amount.amount.toString(),
                        it.amount.currency, it.feeRelation?.name, it.feeEvidenceRef,
                    )
                }
                .sortedBy { it.componentRef },
            legs = graph.legs
                .map { EconomicLegFact(it.legRef, it.accountRef, it.delta.amount.toString(), it.delta.currency, it.componentRef) }
                .sortedBy { it.legRef },
        )
    }

    /** Decode-side projection: reads the stored DTO, never reruns policy. */
    fun of(stored: CertifiedGraphSnapshotDto): EconomicFacts = EconomicFacts(
        bookedAmount = stored.bookedAmount,
        bookedCurrency = stored.bookedCurrency,
        bookedBasis = stored.bookedBasis,
        originalAmount = stored.certifiedOriginal?.amount,
        originalCurrency = stored.certifiedOriginal?.currency,
        settlementAmount = stored.certifiedSettlement?.amount,
        settlementCurrency = stored.certifiedSettlement?.currency,
        kind = stored.kind,
        kindResolved = stored.kindResolved,
        incomeNature = stored.incomeNature,
        recordingOutcome = stored.recordingOutcome,
        kindBasis = stored.kindBasis,
        eventDate = stored.eventDate,
        routing = stored.routing,
        composition = stored.composition,
        identityIssuer = stored.economicIdentity?.issuer,
        identityReference = stored.economicIdentity?.reference,
        linkedRepresentedFeeRef = stored.linkedRepresentedFeeRef,
        components = stored.components
            .map { EconomicComponentFact(it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef) }
            .sortedBy { it.componentRef },
        legs = stored.legs
            .map { EconomicLegFact(it.legRef, it.accountRef, it.delta, it.currency, it.componentRef) }
            .sortedBy { it.legRef },
    )
}

internal object EconomicComparison {
    private const val MAX_REPORTED_DIFFERENCES = 32

    /** Bounded list of differing certified fact paths; empty means replay. */
    fun differences(stored: EconomicFacts, incoming: EconomicFacts): List<String> {
        val differences = mutableListOf<String>()
        fun differ(path: String, equal: Boolean) {
            if (!equal) differences += path
        }
        fun amountEqual(storedAmount: String?, incomingAmount: String?): Boolean = when {
            storedAmount == null && incomingAmount == null -> true
            storedAmount == null || incomingAmount == null -> false
            else -> try {
                BigDecimal(storedAmount).compareTo(BigDecimal(incomingAmount)) == 0
            } catch (_: NumberFormatException) {
                storedAmount == incomingAmount
            }
        }
        differ(
            "bookedAmount",
            amountEqual(stored.bookedAmount, incoming.bookedAmount) &&
                stored.bookedCurrency == incoming.bookedCurrency,
        )
        differ("bookedBasis", stored.bookedBasis == incoming.bookedBasis)
        differ(
            "certifiedOriginal",
            amountEqual(stored.originalAmount, incoming.originalAmount) &&
                stored.originalCurrency == incoming.originalCurrency,
        )
        differ(
            "certifiedSettlement",
            amountEqual(stored.settlementAmount, incoming.settlementAmount) &&
                stored.settlementCurrency == incoming.settlementCurrency,
        )
        differ("kind", stored.kind == incoming.kind && stored.kindResolved == incoming.kindResolved)
        differ("incomeNature", stored.incomeNature == incoming.incomeNature)
        differ("recordingOutcome", stored.recordingOutcome == incoming.recordingOutcome)
        differ("kindBasis", stored.kindBasis == incoming.kindBasis)
        differ("eventDate", stored.eventDate == incoming.eventDate)
        differ("routing", stored.routing == incoming.routing)
        differ("composition", stored.composition == incoming.composition)
        differ(
            "economicIdentity",
            stored.identityIssuer == incoming.identityIssuer &&
                stored.identityReference == incoming.identityReference,
        )
        differ("linkedRepresentedFeeRef", stored.linkedRepresentedFeeRef == incoming.linkedRepresentedFeeRef)
        if (stored.components.size != incoming.components.size) {
            differences += "components.count"
        } else {
            stored.components.zip(incoming.components).forEachIndexed { index, (s, n) ->
                differ(
                    "components[$index]",
                    s.componentRef == n.componentRef &&
                        s.type == n.type &&
                        s.currency == n.currency &&
                        amountEqual(s.amount, n.amount) &&
                        s.feeRelation == n.feeRelation &&
                        s.feeEvidenceRef == n.feeEvidenceRef,
                )
            }
        }
        if (stored.legs.size != incoming.legs.size) {
            differences += "legs.count"
        } else {
            stored.legs.zip(incoming.legs).forEachIndexed { index, (s, n) ->
                differ(
                    "legs[$index]",
                    s.legRef == n.legRef &&
                        s.accountRef == n.accountRef &&
                        s.currency == n.currency &&
                        amountEqual(s.delta, n.delta) &&
                        s.componentRef == n.componentRef,
                )
            }
        }
        return differences.take(MAX_REPORTED_DIFFERENCES)
    }
}
