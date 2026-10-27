package com.athar.core.data.ledger.admission

import com.athar.core.data.ledger.codec.GraphSnapshotCodec
import com.athar.core.data.ledger.codec.GraphSnapshotWireFormat
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FinancialEventGraph

/**
 * Bounded preparation runs on the repository's injected dispatcher. Original
 * bytes are privately owned; database phases receive defensive copies.
 */
internal class PreparedAdmission(
    val namespace: String,
    val sourceItemId: String,
    val eventId: String,
    val evidenceRef: String,
    originalBytes: ByteArray,
    val payloadDigest: String,
    val snapshotJson: String,
    val snapshotDigest: String,
    val feeEvidenceRefs: List<String>,
    val facts: EconomicFacts,
    private val graph: FinancialEventGraph,
) {
    private val retainedOriginalBytes = originalBytes.copyOf()
    val originalBytes: ByteArray get() = retainedOriginalBytes.copyOf()

    val identityIssuer: String?
        get() = (graph.economicIdentity as? EconomicIdentity.IssuerReference)?.issuer

    val identityReference: String?
        get() = (graph.economicIdentity as? EconomicIdentity.IssuerReference)?.reference

    fun toEventEntity(): CanonicalLedgerEventEntity = CanonicalLedgerEventEntity(
        eventId = eventId,
        economicIdentityIssuer = identityIssuer,
        economicIdentityReference = identityReference,
        snapshotFormatVersion = GraphSnapshotWireFormat.VERSION.toLong(),
        policyRevision = GraphSnapshotWireFormat.POLICY_REVISION,
        graphRevision = GraphSnapshotWireFormat.GRAPH_REVISION,
        snapshotJson = snapshotJson,
        bookedAmount = facts.bookedAmount,
        bookedCurrency = facts.bookedCurrency,
        bookedBasis = facts.bookedBasis,
        kind = facts.kind,
        kindResolved = facts.kindResolved,
        incomeNature = facts.incomeNature,
        recordingOutcome = facts.recordingOutcome,
        eventDate = facts.eventDate,
        routing = facts.routing,
        composition = facts.composition,
    )

    fun componentEntities(): List<CanonicalLedgerComponentEntity> = graph.components.map {
        CanonicalLedgerComponentEntity(
            eventId = eventId,
            componentRef = it.componentRef,
            type = it.type.name,
            amount = it.amount.amount.toString(),
            currency = it.amount.currency,
            feeRelation = it.feeRelation?.name,
            feeEvidenceRef = it.feeEvidenceRef,
        )
    }
}

/**
 * Pure preflight pipeline: mode gates and bounds first, then exact codec
 * encoding and the two independent SHA-256 digests. No suspension happens.
 */
internal object AdmissionPreparation {
    sealed interface Preparation {
        data class Ready(val prepared: PreparedAdmission) : Preparation
        data class Unprepared(val rejection: AdmissionRejection) : Preparation
    }

    fun prepare(
        graph: FinancialEventGraph,
        originalBytes: ByteArray,
        evidenceRef: String,
    ): Preparation {
        AdmissionBounds.unsupportedModeRejection(graph)?.let { return Preparation.Unprepared(it) }
        if (evidenceRef.length > AdmissionBounds.MAX_TEXT_FIELD_CHARS) {
            return Preparation.Unprepared(
                AdmissionRejection(AdmissionRejectionReason.RAW_TEXT_BOUND, "evidenceRef length=${evidenceRef.length}"),
            )
        }
        if (evidenceRef.isBlank()) {
            return Preparation.Unprepared(
                AdmissionRejection(AdmissionRejectionReason.BLANK_EVIDENCE_REF, "evidenceRef is blank"),
            )
        }
        if (originalBytes.size > AdmissionBounds.MAX_ORIGINAL_BYTES) {
            return Preparation.Unprepared(
                AdmissionRejection(
                    AdmissionRejectionReason.ORIGINAL_BYTES_BOUND,
                    "bytes=${originalBytes.size}",
                ),
            )
        }
        AdmissionBounds.rawCollectionRejection(graph)?.let { return Preparation.Unprepared(it) }
        AdmissionBounds.moneyRejection(graph)?.let { return Preparation.Unprepared(it) }
        AdmissionBounds.textRejection(graph, evidenceRef)?.let { return Preparation.Unprepared(it) }
        val snapshotJson = try {
            GraphSnapshotCodec.encode(graph)
        } catch (_: IllegalArgumentException) {
            return Preparation.Unprepared(
                AdmissionRejection(AdmissionRejectionReason.SNAPSHOT_UNSUPPORTED, "codec rejected graph shape"),
            )
        }
        if (snapshotJson.toByteArray(Charsets.UTF_8).size > AdmissionBounds.MAX_SNAPSHOT_JSON_BYTES) {
            return Preparation.Unprepared(
                AdmissionRejection(
                    AdmissionRejectionReason.SNAPSHOT_JSON_BOUND,
                    "bytes=${snapshotJson.toByteArray(Charsets.UTF_8).size}",
                ),
            )
        }
        val identity = graph.observationIdentity
        return Preparation.Ready(
            PreparedAdmission(
                namespace = identity.namespace,
                sourceItemId = identity.sourceItemId,
                eventId = graph.eventId.value,
                evidenceRef = evidenceRef,
                originalBytes = originalBytes,
                payloadDigest = AdmissionDigests.sha256Hex(originalBytes),
                snapshotJson = snapshotJson,
                snapshotDigest = AdmissionDigests.sha256Hex(snapshotJson),
                feeEvidenceRefs = feeEvidenceRefs(graph),
                facts = GraphEconomicFacts.of(graph),
                graph = graph,
            ),
        )
    }

    private fun feeEvidenceRefs(graph: FinancialEventGraph): List<String> =
        (graph.components.mapNotNull { it.feeEvidenceRef } + graph.source.components.mapNotNull { it.feeEvidenceRef })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
}
