package com.athar.core.data.ledger.admission

import com.athar.core.domain.model.financialgraph.FinancialEventGraph

/**
 * Typed preflight rejection. Bounded preparation runs on the injected
 * dispatcher before database writes; nothing is persisted on rejection.
 */
enum class AdmissionRejectionReason {
    UNSUPPORTED_ROUTING,
    UNSUPPORTED_LEG,
    UNSUPPORTED_LINKED_FEE,
    BLANK_EVIDENCE_REF,
    ORIGINAL_BYTES_BOUND,
    SNAPSHOT_JSON_BOUND,
    RAW_COLLECTION_BOUND,
    MONEY_REPRESENTATION_BOUND,
    RAW_TEXT_BOUND,
    AGGREGATE_TEXT_BOUND,
    SNAPSHOT_UNSUPPORTED,
    UNSUPPORTED_READ_PROJECTION,
}

/** Stable durable-conflict reason codes, persisted verbatim in `reasonCode`. */
enum class AdmissionConflictReason {
    SOURCE_PAYLOAD_CHANGED,
    SOURCE_UNBOUND,
    MISSING_ACCEPTED_ARCHIVE,
    INVALID_ACCEPTED_ARCHIVE,
    ECONOMICS_CHANGED,
    EVENT_ID_COLLISION,
    ECONOMIC_IDENTITY_COLLISION,
    FEE_EVIDENCE_COLLISION,
    EVIDENCE_REF_MISMATCH,
}

data class AdmissionRejection(
    val reason: AdmissionRejectionReason,
    val detail: String,
)

/** Recoverable failure vocabulary, never a fabricated successful admission. */
enum class AdmissionStorageFailure {
    REVISION_MISSING,
    REVISION_EXHAUSTED,
    SQLITE_FAILURE,
    INVARIANT_VIOLATION,
    ARCHIVE_VARIANT_CORRUPT,
}

/** Durable diagnostic record; never contributes money and never mutates an event. */
data class AdmissionConflictRecord(
    val conflictId: String,
    val reason: AdmissionConflictReason,
    val existingEventId: String?,
    val sourceNamespace: String,
    val sourceId: String,
    val sourcePayloadDigest: String,
    val snapshotDigest: String,
    val alreadyRecorded: Boolean,
)

sealed interface UnallocatedAdmissionOutcome {
    /** Full atomic first admission; [revision] is the advanced singleton value. */
    data class Admitted(val eventId: String, val revision: Long) : UnallocatedAdmissionOutcome

    /** Same source payload and same certified economics; no money or revision change. */
    data class Replay(val existingEventId: String, val variantArchived: Boolean) : UnallocatedAdmissionOutcome

    /** Unsupported input rejected by preflight; nothing was persisted. */
    data class Rejected(val rejection: AdmissionRejection) : UnallocatedAdmissionOutcome

    /** Durable typed conflict; incoming exact variant retained, no financial mutation. */
    data class Conflicted(val conflict: AdmissionConflictRecord) : UnallocatedAdmissionOutcome

    /** Recoverable storage failure; the transaction rolled back, nothing mutated. */
    data class StorageFailed(val reason: AdmissionStorageFailure, val detail: String?) : UnallocatedAdmissionOutcome {
        val code: String get() = reason.name
    }
}

/** Admission input. Original bytes are cloned defensively by the repository. */
internal class UnallocatedAdmissionRequest(
    val graph: FinancialEventGraph,
    val originalBytes: ByteArray,
    val evidenceRef: String,
)

/** Fail-closed storage invariant violation; always rolls the transaction back. */
internal class AdmissionStorageException(
    val reason: AdmissionStorageFailure,
    message: String,
) : RuntimeException(message)
