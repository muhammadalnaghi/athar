package com.athar.core.data.ledger.backup

import kotlinx.serialization.Serializable

/** Trusted row transport only; no financial certification or external-backup validation. */
@Serializable
internal data class CanonicalBackupSection(
    val events: List<CanonicalBackupEvent>,
    val observations: List<CanonicalBackupObservation>,
    val associations: List<CanonicalBackupAssociation>,
    val components: List<CanonicalBackupComponent>,
    val legs: List<CanonicalBackupLeg>,
    val revisions: List<CanonicalBackupRevision>,
    val snapshots: List<CanonicalBackupSnapshotVariant>,
    val conflicts: List<CanonicalBackupConflict>,
)

@Serializable
internal data class CanonicalBackupEvent(
    val eventId: String,
    val economicIdentityIssuer: String?,
    val economicIdentityReference: String?,
    val snapshotFormatVersion: Long,
    val policyRevision: String,
    val graphRevision: String,
    val snapshotJson: String,
    val bookedAmount: String,
    val bookedCurrency: String,
    val bookedBasis: String,
    val kind: String,
    val kindResolved: Boolean,
    val incomeNature: String,
    val recordingOutcome: String,
    val eventDate: String?,
    val routing: String,
    val composition: String,
)

@Serializable
internal data class CanonicalBackupObservation(
    val sourceNamespace: String,
    val sourceId: String,
    val evidenceRef: String?,
    val payloadDigest: String?,
)

@Serializable
internal data class CanonicalBackupAssociation(
    val sourceNamespace: String,
    val sourceId: String,
    val eventId: String,
)

@Serializable
internal data class CanonicalBackupComponent(
    val eventId: String,
    val componentRef: String,
    val type: String,
    val amount: String,
    val currency: String,
    val feeRelation: String?,
    val feeEvidenceRef: String?,
)

@Serializable
internal data class CanonicalBackupLeg(
    val eventId: String,
    val legRef: String,
    val accountRef: String,
    val delta: String,
    val currency: String,
    val componentRef: String?,
)

@Serializable
internal data class CanonicalBackupRevision(
    val id: String,
    val revision: Long,
)

@Serializable
internal data class CanonicalBackupSnapshotVariant(
    val sourceNamespace: String,
    val sourceId: String,
    val sourcePayloadDigest: String,
    val snapshotDigest: String,
    val payloadBase64: String,
    val snapshotJson: String,
)

@Serializable
internal data class CanonicalBackupConflict(
    val conflictId: String,
    val sourceNamespace: String,
    val sourceId: String,
    val sourcePayloadDigest: String,
    val snapshotDigest: String,
    val existingEventId: String?,
    val reasonCode: String,
    val detailJson: String?,
)
