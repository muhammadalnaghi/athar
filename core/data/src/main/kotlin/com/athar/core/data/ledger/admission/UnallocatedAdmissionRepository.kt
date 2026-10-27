package com.athar.core.data.ledger.admission

import android.database.SQLException
import androidx.room.withTransaction
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.ledger.db.CanonicalEvidenceDao
import com.athar.core.data.ledger.db.CanonicalLedgerConflictEntity
import com.athar.core.data.ledger.db.CanonicalLedgerDao
import com.athar.core.data.ledger.db.CanonicalLedgerEventSourceEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSchema
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.data.ledger.db.CanonicalObservationSnapshotEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Main-safe atomic UNALLOCATED-only admission boundary (WM028-13B2b).
 *
 * One Room transaction stores the observation, the exact original-bytes/
 * snapshot variant, the accepted event, the source association and the
 * certified components, then advances the existing revision singleton
 * exactly once via compare-and-increment. Any unexpected failure rolls the
 * whole write back. Replays archive metadata-only variants without money or
 * revision movement; changed payloads or economics and all cross-source
 * identity/fee/evidence collisions become durable typed conflicts. There is
 * no REPLACE, no reseeding, no second-source attachment and no ownership
 * inference in this slice.
 */
internal class UnallocatedAdmissionRepository(
    private val database: AtharDatabase,
    private val ledgerDao: CanonicalLedgerDao,
    private val evidenceDao: CanonicalEvidenceDao,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun admit(request: UnallocatedAdmissionRequest): UnallocatedAdmissionOutcome {
        // Only a bounded input snapshot is taken before suspension. Codec/hash
        // work belongs to the injected dispatcher, never the caller's main thread.
        if (request.originalBytes.size > AdmissionBounds.MAX_ORIGINAL_BYTES) {
            return UnallocatedAdmissionOutcome.Rejected(
                AdmissionRejection(AdmissionRejectionReason.ORIGINAL_BYTES_BOUND, "original bytes exceed bound"),
            )
        }
        val bytes = request.originalBytes.copyOf()
        return try {
            withContext(ioDispatcher) {
                val prepared = when (val preparation = AdmissionPreparation.prepare(request.graph, bytes, request.evidenceRef)) {
                    is AdmissionPreparation.Preparation.Ready -> preparation.prepared
                    is AdmissionPreparation.Preparation.Unprepared ->
                        return@withContext UnallocatedAdmissionOutcome.Rejected(preparation.rejection)
                }
                database.withTransaction { admitWithinTransaction(prepared) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AdmissionStorageException) {
            UnallocatedAdmissionOutcome.StorageFailed(failure.reason, failure.message)
        } catch (failure: SQLException) {
            UnallocatedAdmissionOutcome.StorageFailed(AdmissionStorageFailure.SQLITE_FAILURE, failure.message)
        } catch (failure: IllegalStateException) {
            UnallocatedAdmissionOutcome.StorageFailed(AdmissionStorageFailure.INVARIANT_VIOLATION, failure.message)
        }
    }

    private suspend fun admitWithinTransaction(prepared: PreparedAdmission): UnallocatedAdmissionOutcome {
        val observation = ledgerDao.getObservation(prepared.namespace, prepared.sourceItemId)
        return if (observation == null) admitNewSource(prepared) else admitKnownSource(prepared, observation)
    }

    private suspend fun admitNewSource(prepared: PreparedAdmission): UnallocatedAdmissionOutcome {
        findCollision(prepared)?.let { return durableConflict(prepared, it.first, it.second) }
        val currentRevision = requiredRevision()
        insertObservationAndVariant(prepared)
        ledgerDao.insertEvent(prepared.toEventEntity())
        ledgerDao.insertEventSource(
            CanonicalLedgerEventSourceEntity(prepared.namespace, prepared.sourceItemId, prepared.eventId),
        )
        prepared.componentEntities().forEach { ledgerDao.insertComponent(it) }
        val updated = ledgerDao.incrementRevisionIfExpected(CanonicalLedgerSchema.REVISION_SINGLETON_ID, currentRevision)
        check(updated == 1) { "revision compare-and-increment moved $updated rows (expected 1)" }
        return UnallocatedAdmissionOutcome.Admitted(prepared.eventId, currentRevision + 1)
    }

    /** Reused for unbound diagnostic observations so identical collisions stay idempotent. */
    private suspend fun findCollision(prepared: PreparedAdmission): Pair<AdmissionConflictReason, String>? {
        val collidingEvent = ledgerDao.getEvent(prepared.eventId)
        if (collidingEvent != null) {
            return AdmissionConflictReason.EVENT_ID_COLLISION to collidingEvent.eventId
        }
        val issuer = prepared.identityIssuer
        val reference = prepared.identityReference
        if (issuer != null && reference != null) {
            val identityEvent = ledgerDao.getEventByIdentity(issuer, reference)
            if (identityEvent != null) {
                return AdmissionConflictReason.ECONOMIC_IDENTITY_COLLISION to identityEvent.eventId
            }
        }
        // Stay below the minimum supported Android SQLite bind-variable limit.
        for (refs in prepared.feeEvidenceRefs.chunked(900)) {
            val collidingComponent = ledgerDao.listComponentsByFeeEvidenceRefs(refs).firstOrNull()
            if (collidingComponent != null) {
                // Fee-evidence reuse is ambiguity, never proof of equality.
                return AdmissionConflictReason.FEE_EVIDENCE_COLLISION to collidingComponent.eventId
            }
        }
        return null
    }

    private suspend fun admitKnownSource(
        prepared: PreparedAdmission,
        observation: CanonicalLedgerSourceObservationEntity,
    ): UnallocatedAdmissionOutcome {
        val binding = ledgerDao.getEventSource(prepared.namespace, prepared.sourceItemId)
        if (binding == null) {
            findCollision(prepared)?.let { return durableConflict(prepared, it.first, it.second) }
            return durableConflict(prepared, AdmissionConflictReason.SOURCE_UNBOUND, null)
        }
        val storedFacts = when (val baseline = AdmissionBaseline.read(ledgerDao, evidenceDao, observation, binding.eventId)) {
            is AdmissionBaselineRead.Present -> baseline.facts
            is AdmissionBaselineRead.Unavailable -> return durableConflict(prepared, baseline.reason, binding.eventId)
        }
        if (prepared.payloadDigest != observation.payloadDigest) {
            return durableConflict(prepared, AdmissionConflictReason.SOURCE_PAYLOAD_CHANGED, binding.eventId)
        }
        val differences = EconomicComparison.differences(storedFacts, prepared.facts)
        val archived = archiveVariantIfNew(prepared)
        if (differences.isNotEmpty()) {
            return durableConflict(
                prepared,
                AdmissionConflictReason.ECONOMICS_CHANGED,
                binding.eventId,
                extraDetail = mapOf("differences" to differences.joinToString(",")),
            )
        }
        val storedEvidenceRef = observation.evidenceRef
        if (storedEvidenceRef == null || storedEvidenceRef != prepared.evidenceRef) {
            // Write-once accepted source row keeps its ref; the differing
            // incoming reference stays recoverable as a metadata conflict.
            return durableConflict(
                prepared,
                AdmissionConflictReason.EVIDENCE_REF_MISMATCH,
                binding.eventId,
                extraDetail = mapOf(
                    "incomingEvidenceRef" to prepared.evidenceRef,
                    "storedEvidenceRef" to (storedEvidenceRef ?: ""),
                ),
            )
        }
        return UnallocatedAdmissionOutcome.Replay(binding.eventId, archived)
    }

    private suspend fun requiredRevision(): Long {
        val revision = ledgerDao.getRevision(CanonicalLedgerSchema.REVISION_SINGLETON_ID)
            ?: throw AdmissionStorageException(
                AdmissionStorageFailure.REVISION_MISSING,
                "canonical ledger revision singleton is absent",
            )
        if (revision.revision == Long.MAX_VALUE) {
            throw AdmissionStorageException(AdmissionStorageFailure.REVISION_EXHAUSTED, "revision counter exhausted")
        }
        return revision.revision
    }

    private suspend fun insertObservationAndVariant(prepared: PreparedAdmission) {
        ledgerDao.insertObservation(
            CanonicalLedgerSourceObservationEntity(
                sourceNamespace = prepared.namespace,
                sourceId = prepared.sourceItemId,
                evidenceRef = prepared.evidenceRef,
                payloadDigest = prepared.payloadDigest,
            ),
        )
        archiveVariantIfNew(prepared)
    }

    private suspend fun archiveVariantIfNew(prepared: PreparedAdmission): Boolean {
        val existing = evidenceDao.getSnapshot(
            prepared.namespace, prepared.sourceItemId, prepared.payloadDigest, prepared.snapshotDigest,
        )
        if (existing != null) {
            if (!existing.sourcePayload.contentEquals(prepared.originalBytes) || existing.snapshotJson != prepared.snapshotJson) {
                throw AdmissionStorageException(
                    AdmissionStorageFailure.ARCHIVE_VARIANT_CORRUPT,
                    "existing archive key does not contain its exact original variant",
                )
            }
            return false
        }
        evidenceDao.insertSnapshot(
            CanonicalObservationSnapshotEntity(
                sourceNamespace = prepared.namespace,
                sourceId = prepared.sourceItemId,
                sourcePayloadDigest = prepared.payloadDigest,
                snapshotDigest = prepared.snapshotDigest,
                sourcePayload = prepared.originalBytes,
                snapshotJson = prepared.snapshotJson,
            ),
        )
        return true
    }

    private suspend fun durableConflict(
        prepared: PreparedAdmission,
        reason: AdmissionConflictReason,
        existingEventId: String?,
        extraDetail: Map<String, String> = emptyMap(),
    ): UnallocatedAdmissionOutcome {
        // New-source collisions need their observation FK parent too; neither
        // this parent nor its archive associates the source with financial money.
        if (ledgerDao.getObservation(prepared.namespace, prepared.sourceItemId) == null) {
            insertObservationAndVariant(prepared)
        } else {
            archiveVariantIfNew(prepared)
        }
        val detail = buildMap {
            put("incomingEvidenceRef", prepared.evidenceRef)
            existingEventId?.let { put("existingEventId", it) }
            putAll(extraDetail)
        }
        val conflictId = AdmissionDigests.canonicalTupleId(
            listOf(
                reason.name,
                prepared.namespace,
                prepared.sourceItemId,
                prepared.payloadDigest,
                prepared.snapshotDigest,
                existingEventId ?: "",
                prepared.evidenceRef,
            ),
        )
        val alreadyRecorded = evidenceDao.getConflict(conflictId) != null
        if (!alreadyRecorded) {
            evidenceDao.insertConflict(
                CanonicalLedgerConflictEntity(
                    conflictId = conflictId,
                    sourceNamespace = prepared.namespace,
                    sourceId = prepared.sourceItemId,
                    sourcePayloadDigest = prepared.payloadDigest,
                    snapshotDigest = prepared.snapshotDigest,
                    existingEventId = existingEventId,
                    reasonCode = reason.name,
                    detailJson = AdmissionDigests.detailJson(detail),
                ),
            )
        }
        return UnallocatedAdmissionOutcome.Conflicted(
            AdmissionConflictRecord(
                conflictId = conflictId,
                reason = reason,
                existingEventId = existingEventId,
                sourceNamespace = prepared.namespace,
                sourceId = prepared.sourceItemId,
                sourcePayloadDigest = prepared.payloadDigest,
                snapshotDigest = prepared.snapshotDigest,
                alreadyRecorded = alreadyRecorded,
            ),
        )
    }
}
