package com.athar.core.data.ledger.admission

import com.athar.core.data.ledger.codec.GraphSnapshotCodec
import com.athar.core.data.ledger.codec.GraphSnapshotDecodeResult
import com.athar.core.data.ledger.db.CanonicalEvidenceDao
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerDao
import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity

internal sealed interface AdmissionBaselineRead {
    data class Present(val facts: EconomicFacts) : AdmissionBaselineRead
    data class Unavailable(val reason: AdmissionConflictReason) : AdmissionBaselineRead
}

/** Read-only binding to the accepted event, not to an arbitrary archived analysis. */
internal object AdmissionBaseline {
    suspend fun read(
        ledger: CanonicalLedgerDao,
        evidence: CanonicalEvidenceDao,
        observation: CanonicalLedgerSourceObservationEntity,
        eventId: String,
    ): AdmissionBaselineRead {
        val event = checkNotNull(ledger.getEvent(eventId)) { "bound canonical event is missing" }
        val payloadDigest = observation.payloadDigest
            ?: return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.MISSING_ACCEPTED_ARCHIVE)
        if (event.snapshotJson.length > AdmissionBounds.MAX_SNAPSHOT_JSON_BYTES ||
            event.snapshotJson.toByteArray(Charsets.UTF_8).size > AdmissionBounds.MAX_SNAPSHOT_JSON_BYTES
        ) return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
        val snapshotDigest = AdmissionDigests.sha256Hex(event.snapshotJson)
        val archive = evidence.getSnapshot(
            observation.sourceNamespace, observation.sourceId, payloadDigest, snapshotDigest,
        ) ?: return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.MISSING_ACCEPTED_ARCHIVE)
        if (archive.sourcePayload.size > AdmissionBounds.MAX_ORIGINAL_BYTES ||
            AdmissionDigests.sha256Hex(archive.sourcePayload) != payloadDigest ||
            archive.snapshotJson != event.snapshotJson ||
            AdmissionDigests.sha256Hex(archive.snapshotJson) != snapshotDigest
        ) return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
        val decoded = GraphSnapshotCodec.decode(archive.snapshotJson)
        if (decoded !is GraphSnapshotDecodeResult.Decoded) {
            return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
        }
        val certified = decoded.certifiedSnapshot
        if (certified.observationIdentity.namespace != observation.sourceNamespace ||
            certified.observationIdentity.sourceItemId != observation.sourceId ||
            certified.routing != "UNALLOCATED" || certified.legs.isNotEmpty() ||
            decoded.rawDraft.legs.isNotEmpty() || decoded.rawDraft.linkedRepresentedFeeRef != null ||
            certified.linkedRepresentedFeeRef != null
        ) return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
        val expectedEvent = CanonicalLedgerEventEntity(
            eventId = certified.eventId,
            economicIdentityIssuer = certified.economicIdentity?.issuer,
            economicIdentityReference = certified.economicIdentity?.reference,
            snapshotFormatVersion = decoded.formatVersion.toLong(),
            policyRevision = decoded.policyRevision,
            graphRevision = decoded.graphRevision,
            snapshotJson = archive.snapshotJson,
            bookedAmount = certified.bookedAmount,
            bookedCurrency = certified.bookedCurrency,
            bookedBasis = certified.bookedBasis,
            kind = certified.kind,
            kindResolved = certified.kindResolved,
            incomeNature = certified.incomeNature,
            recordingOutcome = certified.recordingOutcome,
            eventDate = certified.eventDate,
            routing = certified.routing,
            composition = certified.composition,
        )
        val expectedComponents = certified.components.map {
            CanonicalLedgerComponentEntity(
                certified.eventId, it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef,
            )
        }.sortedBy { it.componentRef }
        if (event != expectedEvent || ledger.listComponentsForEvent(eventId) != expectedComponents ||
            ledger.listLegsForEvent(eventId).isNotEmpty()
        ) return AdmissionBaselineRead.Unavailable(AdmissionConflictReason.INVALID_ACCEPTED_ARCHIVE)
        return AdmissionBaselineRead.Present(GraphEconomicFacts.of(certified))
    }
}
