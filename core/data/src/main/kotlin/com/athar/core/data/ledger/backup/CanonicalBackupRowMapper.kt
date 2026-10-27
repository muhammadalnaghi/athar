package com.athar.core.data.ledger.backup

import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventSourceEntity
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerLegEntity
import com.athar.core.data.ledger.db.CanonicalLedgerRevisionEntity
import com.athar.core.data.ledger.db.CanonicalObservationSnapshotEntity
import com.athar.core.data.ledger.db.CanonicalLedgerConflictEntity
import java.util.Base64

/** Caller-private capture rows; this transport neither reads nor changes the database. */
internal data class CanonicalBackupRows(
    val events: List<CanonicalLedgerEventEntity>,
    val observations: List<CanonicalLedgerSourceObservationEntity>,
    val associations: List<CanonicalLedgerEventSourceEntity>,
    val components: List<CanonicalLedgerComponentEntity>,
    val legs: List<CanonicalLedgerLegEntity>,
    val revisions: List<CanonicalLedgerRevisionEntity>,
    val snapshots: List<CanonicalObservationSnapshotEntity>,
    val conflicts: List<CanonicalLedgerConflictEntity>,
)

internal fun CanonicalBackupRows.toSection(): CanonicalBackupSection = CanonicalBackupSection(
    events = events.map { row ->
        CanonicalBackupEvent(
            eventId = row.eventId,
            economicIdentityIssuer = row.economicIdentityIssuer,
            economicIdentityReference = row.economicIdentityReference,
            snapshotFormatVersion = row.snapshotFormatVersion,
            policyRevision = row.policyRevision,
            graphRevision = row.graphRevision,
            snapshotJson = row.snapshotJson,
            bookedAmount = row.bookedAmount,
            bookedCurrency = row.bookedCurrency,
            bookedBasis = row.bookedBasis,
            kind = row.kind,
            kindResolved = row.kindResolved,
            incomeNature = row.incomeNature,
            recordingOutcome = row.recordingOutcome,
            eventDate = row.eventDate,
            routing = row.routing,
            composition = row.composition,
        )
    },
    observations = observations.map { row ->
        CanonicalBackupObservation(
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            evidenceRef = row.evidenceRef,
            payloadDigest = row.payloadDigest,
        )
    },
    associations = associations.map { row ->
        CanonicalBackupAssociation(
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            eventId = row.eventId,
        )
    },
    components = components.map { row ->
        CanonicalBackupComponent(
            eventId = row.eventId,
            componentRef = row.componentRef,
            type = row.type,
            amount = row.amount,
            currency = row.currency,
            feeRelation = row.feeRelation,
            feeEvidenceRef = row.feeEvidenceRef,
        )
    },
    legs = legs.map { row ->
        CanonicalBackupLeg(
            eventId = row.eventId,
            legRef = row.legRef,
            accountRef = row.accountRef,
            delta = row.delta,
            currency = row.currency,
            componentRef = row.componentRef,
        )
    },
    revisions = revisions.map { row ->
        CanonicalBackupRevision(
            id = row.id,
            revision = row.revision,
        )
    },
    snapshots = snapshots.map { row ->
        CanonicalBackupSnapshotVariant(
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            sourcePayloadDigest = row.sourcePayloadDigest,
            snapshotDigest = row.snapshotDigest,
            payloadBase64 = Base64.getEncoder().encodeToString(row.sourcePayload),
            snapshotJson = row.snapshotJson,
        )
    },
    conflicts = conflicts.map { row ->
        CanonicalBackupConflict(
            conflictId = row.conflictId,
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            sourcePayloadDigest = row.sourcePayloadDigest,
            snapshotDigest = row.snapshotDigest,
            existingEventId = row.existingEventId,
            reasonCode = row.reasonCode,
            detailJson = row.detailJson,
        )
    },
)

internal fun CanonicalBackupSection.toRows(): CanonicalBackupRows = CanonicalBackupRows(
    events = events.map { row ->
        CanonicalLedgerEventEntity(
            eventId = row.eventId,
            economicIdentityIssuer = row.economicIdentityIssuer,
            economicIdentityReference = row.economicIdentityReference,
            snapshotFormatVersion = row.snapshotFormatVersion,
            policyRevision = row.policyRevision,
            graphRevision = row.graphRevision,
            snapshotJson = row.snapshotJson,
            bookedAmount = row.bookedAmount,
            bookedCurrency = row.bookedCurrency,
            bookedBasis = row.bookedBasis,
            kind = row.kind,
            kindResolved = row.kindResolved,
            incomeNature = row.incomeNature,
            recordingOutcome = row.recordingOutcome,
            eventDate = row.eventDate,
            routing = row.routing,
            composition = row.composition,
        )
    },
    observations = observations.map { row ->
        CanonicalLedgerSourceObservationEntity(
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            evidenceRef = row.evidenceRef,
            payloadDigest = row.payloadDigest,
        )
    },
    associations = associations.map { row ->
        CanonicalLedgerEventSourceEntity(
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            eventId = row.eventId,
        )
    },
    components = components.map { row ->
        CanonicalLedgerComponentEntity(
            eventId = row.eventId,
            componentRef = row.componentRef,
            type = row.type,
            amount = row.amount,
            currency = row.currency,
            feeRelation = row.feeRelation,
            feeEvidenceRef = row.feeEvidenceRef,
        )
    },
    legs = legs.map { row ->
        CanonicalLedgerLegEntity(
            eventId = row.eventId,
            legRef = row.legRef,
            accountRef = row.accountRef,
            delta = row.delta,
            currency = row.currency,
            componentRef = row.componentRef,
        )
    },
    revisions = revisions.map { row ->
        CanonicalLedgerRevisionEntity(
            id = row.id,
            revision = row.revision,
        )
    },
    snapshots = snapshots.map { row ->
        CanonicalObservationSnapshotEntity(
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            sourcePayloadDigest = row.sourcePayloadDigest,
            snapshotDigest = row.snapshotDigest,
            sourcePayload = Base64.getDecoder().decode(row.payloadBase64),
            snapshotJson = row.snapshotJson,
        )
    },
    conflicts = conflicts.map { row ->
        CanonicalLedgerConflictEntity(
            conflictId = row.conflictId,
            sourceNamespace = row.sourceNamespace,
            sourceId = row.sourceId,
            sourcePayloadDigest = row.sourcePayloadDigest,
            snapshotDigest = row.snapshotDigest,
            existingEventId = row.existingEventId,
            reasonCode = row.reasonCode,
            detailJson = row.detailJson,
        )
    },
)
