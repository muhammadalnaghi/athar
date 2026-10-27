package com.athar.core.data.backup

import com.athar.core.data.ledger.admission.AdmissionBounds
import com.athar.core.data.ledger.admission.AdmissionDigests
import com.athar.core.data.ledger.backup.CanonicalBackupAssociation
import com.athar.core.data.ledger.backup.CanonicalBackupComponent
import com.athar.core.data.ledger.backup.CanonicalBackupEvent
import com.athar.core.data.ledger.backup.CanonicalBackupLeg
import com.athar.core.data.ledger.backup.CanonicalBackupObservation
import com.athar.core.data.ledger.backup.CanonicalBackupSection
import com.athar.core.data.ledger.backup.CanonicalBackupSnapshotVariant
import com.athar.core.data.ledger.codec.GraphSnapshotCodec
import com.athar.core.data.ledger.codec.GraphSnapshotDecodeResult
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerLegEntity
import java.util.Base64

/**
 * Semantic-integrity gate for the v4 canonical section: transport preservation
 * (authenticated JSON, valid FKs) is not proof that the restored denormalized
 * rows still ARE the state their accepted snapshot represents. Two deliberate
 * scopes (WM028-P certification + B6 representational roundtrip):
 *
 *  - **Certified scope** — the event's `snapshotJson` decodes via the trusted
 *    [GraphSnapshotCodec], so the certificate can contradict the transported
 *    rows. The gate re-derives, from the exact snapshot bytes, what the
 *    denormalized event, component and leg rows must be and requires
 *    byte-exact equality. Rows claiming routed/legged authority that their own
 *    UNALLOCATED/no-legs certificate does not grant are tamper and refuse with
 *    `CANONICAL_AUTHORITY_UNSUPPORTED`; any diverging row refuses with
 *    `CANONICAL_DENORMALIZATION_MISMATCH`. A decodable certificate that itself
 *    states routed/legged authority is *represented, not certified*: its rows
 *    must still match it exactly, and restoring implies no runtime admission —
 *    the live `AdmissionBaseline.read` / `AdmissionBounds` rules stay
 *    UNALLOCATED-only and are unchanged.
 *  - **Representational scope** — the snapshot does not decode (historical or
 *    foreign snapshot shape, e.g. opaque legacy captures). The certificate
 *    cannot contradict the rows and no financial authority is invented in
 *    either direction; the exact v4 transport contract still fully applies
 *    (strict envelope, FK/resource checks in `BackupRestoreValidator`, strict
 *    revision advance). Non-certifiable is not unauthorized: a representable
 *    historical graph roundtrips instead of being blanket-refused, while
 *    malformed envelopes and broken references stay refused upstream.
 *
 * Reused trusted primitives only: [GraphSnapshotCodec.decode],
 * [AdmissionDigests.sha256Hex], [AdmissionBounds]. Financial strings are never
 * parsed, coerced or re-normalized; digests are computed over the original
 * bytes exactly as transported (`payloadBase64` → decode → SHA-256).
 *
 * Pure and side-effect free: runs inside `BackupRestoreValidator.validateIncoming`
 * before the restore write transaction opens, so a refused restore never touches
 * the database.
 */
internal object BackupFinancialIntegrity {

    /** Mirrors the accepted archive key of `AdmissionBaseline.read`. */
    private data class VariantKey(
        val sourceNamespace: String,
        val sourceId: String,
        val sourcePayloadDigest: String,
        val snapshotDigest: String,
    )

    fun validate(section: CanonicalBackupSection) {
        if (section.events.isEmpty()) return
        val observations = section.observations.associateBy { it.sourceNamespace to it.sourceId }
        val variants = section.snapshots.associateBy {
            VariantKey(it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest)
        }
        val associationsByEvent = section.associations.groupBy { it.eventId }
        val legsByEvent = section.legs.groupBy { it.eventId }
        val componentsByEvent = section.components.groupBy { it.eventId }
        section.events.forEach { event ->
            val eventId = event.eventId
            val associations = associationsByEvent[eventId].orEmpty()
            if (associations.isEmpty()) {
                throw bindingBroken(eventId, "event has no accepted source binding")
            }
            val decoded = GraphSnapshotCodec.decode(event.snapshotJson)
            if (decoded !is GraphSnapshotDecodeResult.Decoded) {
                // Representational scope: an opaque (historical/foreign)
                // snapshot cannot contradict its transported rows, so nothing
                // is certified and no authority is invented. The exact v4
                // transport contract wrapping this gate still applies.
                return@forEach
            }
            associations.forEach { association ->
                verifyAcceptedBinding(event, association, observations, variants, decoded)
            }
            verifyDenormalizedRows(
                event,
                decoded,
                legsByEvent[eventId].orEmpty(),
                componentsByEvent[eventId].orEmpty(),
            )
        }
    }

    /**
     * Exact accepted-archive binding, identical to `AdmissionBaseline.read`:
     * the bound observation must carry a payload digest, and the variant keyed
     * by (source, payload digest, sha256(event.snapshotJson)) must exist, hold
     * original bytes that re-hash to the payload digest, and contain exactly
     * the event's snapshot JSON (re-hashing to the variant key).
     */
    private fun verifyAcceptedBinding(
        event: CanonicalBackupEvent,
        association: CanonicalBackupAssociation,
        observations: Map<Pair<String, String>, CanonicalBackupObservation>,
        variants: Map<VariantKey, CanonicalBackupSnapshotVariant>,
        decoded: GraphSnapshotDecodeResult.Decoded,
    ) {
        val source = association.sourceNamespace to association.sourceId
        val observation = observations[source]
            ?: throw bindingBroken(event.eventId, "bound observation ${association.sourceNamespace}/${association.sourceId} is missing")
        val payloadDigest = observation.payloadDigest
            ?: throw bindingBroken(event.eventId, "bound observation ${association.sourceNamespace}/${association.sourceId} has no payload digest")
        val snapshotJson = event.snapshotJson
        if (snapshotJson.length > AdmissionBounds.MAX_SNAPSHOT_JSON_BYTES ||
            snapshotJson.toByteArray(Charsets.UTF_8).size > AdmissionBounds.MAX_SNAPSHOT_JSON_BYTES
        ) {
            throw bindingBroken(event.eventId, "snapshot exceeds the accepted archive bound")
        }
        val snapshotDigest = AdmissionDigests.sha256Hex(snapshotJson)
        val variant = variants[VariantKey(association.sourceNamespace, association.sourceId, payloadDigest, snapshotDigest)]
            ?: throw bindingBroken(event.eventId, "accepted archive ${association.sourceNamespace}/${association.sourceId} is missing")
        val maxEncodedPayloadChars = ((AdmissionBounds.MAX_ORIGINAL_BYTES + 2) / 3) * 4
        if (variant.payloadBase64.length > maxEncodedPayloadChars) {
            throw bindingBroken(event.eventId, "accepted original payload exceeds the admission bound")
        }
        val payload = runCatching { Base64.getDecoder().decode(variant.payloadBase64) }.getOrNull()
            ?: throw bindingBroken(event.eventId, "accepted archive ${association.sourceNamespace}/${association.sourceId} payload is not transportable base64")
        if (payload.size > AdmissionBounds.MAX_ORIGINAL_BYTES ||
            AdmissionDigests.sha256Hex(payload) != payloadDigest ||
            variant.snapshotJson != snapshotJson ||
            AdmissionDigests.sha256Hex(variant.snapshotJson) != snapshotDigest
        ) {
            throw bindingBroken(event.eventId, "accepted archive ${association.sourceNamespace}/${association.sourceId} does not contain its exact variant")
        }
        val decodedIdentity = decoded.certifiedSnapshot.observationIdentity
        if (decodedIdentity.namespace != observation.sourceNamespace ||
            decodedIdentity.sourceItemId != observation.sourceId
        ) {
            throw bindingBroken(event.eventId, "accepted snapshot identity does not match its source binding")
        }
    }

    /**
     * The denormalization check for certifiable snapshots: re-derive the
     * event, component and leg entities from the certified snapshot exactly as
     * the admission path wrote them and require exact equality against the
     * transported rows. Collections compare sorted by ref, matching the
     * baseline's unordered-set semantics. Rows whose authority exceeds what
     * their own certificate grants refuse before any row comparison; a
     * certificate that itself states routed/legged authority is represented
     * exactly, never certified for runtime admission.
     */
    private fun verifyDenormalizedRows(
        event: CanonicalBackupEvent,
        decoded: GraphSnapshotDecodeResult.Decoded,
        backupLegs: List<CanonicalBackupLeg>,
        backupComponents: List<CanonicalBackupComponent>,
    ) {
        val certified = decoded.certifiedSnapshot
        val certificateIsUnallocated = certified.routing == ADMISSION_ROUTING &&
            certified.legs.isEmpty() &&
            decoded.rawDraft.legs.isEmpty() &&
            decoded.rawDraft.linkedRepresentedFeeRef == null &&
            certified.linkedRepresentedFeeRef == null
        val transportsRoutedAuthority = event.routing != ADMISSION_ROUTING || backupLegs.isNotEmpty()
        if (certificateIsUnallocated && transportsRoutedAuthority) {
            throw unsupported(event.eventId)
        }
        if (!certificateIsUnallocated && certified.legs.isEmpty() && decoded.rawDraft.legs.isNotEmpty()) {
            throw bindingBroken(event.eventId, "snapshot does not certify an UNALLOCATED event")
        }
        val expectedEvent = CanonicalLedgerEventEntity(
            eventId = certified.eventId,
            economicIdentityIssuer = certified.economicIdentity?.issuer,
            economicIdentityReference = certified.economicIdentity?.reference,
            snapshotFormatVersion = decoded.formatVersion.toLong(),
            policyRevision = decoded.policyRevision,
            graphRevision = decoded.graphRevision,
            snapshotJson = event.snapshotJson,
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
        val actualEvent = CanonicalLedgerEventEntity(
            eventId = event.eventId,
            economicIdentityIssuer = event.economicIdentityIssuer,
            economicIdentityReference = event.economicIdentityReference,
            snapshotFormatVersion = event.snapshotFormatVersion,
            policyRevision = event.policyRevision,
            graphRevision = event.graphRevision,
            snapshotJson = event.snapshotJson,
            bookedAmount = event.bookedAmount,
            bookedCurrency = event.bookedCurrency,
            bookedBasis = event.bookedBasis,
            kind = event.kind,
            kindResolved = event.kindResolved,
            incomeNature = event.incomeNature,
            recordingOutcome = event.recordingOutcome,
            eventDate = event.eventDate,
            routing = event.routing,
            composition = event.composition,
        )
        if (actualEvent != expectedEvent) {
            throw denormalizationMismatch(event.eventId, "event row diverges from its certified snapshot")
        }
        val expectedComponents = certified.components
            .map {
                CanonicalLedgerComponentEntity(
                    certified.eventId, it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef,
                )
            }
            .sortedBy { it.componentRef }
        val actualComponents = backupComponents
            .map {
                CanonicalLedgerComponentEntity(
                    it.eventId, it.componentRef, it.type, it.amount, it.currency, it.feeRelation, it.feeEvidenceRef,
                )
            }
            .sortedBy { it.componentRef }
        if (actualComponents != expectedComponents) {
            throw denormalizationMismatch(event.eventId, "component rows diverge from the certified snapshot")
        }
        val expectedLegs = certified.legs
            .map {
                CanonicalLedgerLegEntity(
                    certified.eventId, it.legRef, it.accountRef, it.delta, it.currency, it.componentRef,
                )
            }
            .sortedBy { it.legRef }
        val actualLegs = backupLegs
            .map {
                CanonicalLedgerLegEntity(
                    it.eventId, it.legRef, it.accountRef, it.delta, it.currency, it.componentRef,
                )
            }
            .sortedBy { it.legRef }
        if (actualLegs != expectedLegs) {
            throw denormalizationMismatch(event.eventId, "leg rows diverge from the certified snapshot")
        }
    }

    private fun unsupported(eventId: String) = BackupValidationException(
        BackupValidationCode.CANONICAL_AUTHORITY_UNSUPPORTED,
        "Event $eventId carries routed/legged financial authority outside the supported certified scope",
    )

    private fun bindingBroken(eventId: String, detail: String) = BackupValidationException(
        BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN,
        "Event $eventId accepted-snapshot binding broken: $detail",
    )

    private fun denormalizationMismatch(eventId: String, detail: String) = BackupValidationException(
        BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH,
        "Event $eventId denormalized rows diverge from the accepted snapshot: $detail",
    )

    private const val ADMISSION_ROUTING = "UNALLOCATED"
}
