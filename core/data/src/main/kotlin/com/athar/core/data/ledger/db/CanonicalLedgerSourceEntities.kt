package com.athar.core.data.ledger.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One observed source item behind canonical financial events (WM028-13B1),
 * keyed by its source-observation identity `(sourceNamespace, sourceId)` —
 * the same identity the codec preserves inside the raw draft.
 *
 * `evidenceRef`/`payloadDigest` are caller-supplied original-source identity
 * for the observed evidence/payload. They are never derived categorization
 * output: hashing a category, merchant normalization or any downstream
 * product must never stand in for original-source identity.
 *
 * An observation may exist without an event (captured but not yet posted);
 * the binding to an event lives in [CanonicalLedgerEventSourceEntity].
 */
@Entity(
    tableName = "canonical_ledger_source_observation",
    primaryKeys = ["sourceNamespace", "sourceId"],
)
internal data class CanonicalLedgerSourceObservationEntity(
    val sourceNamespace: String,
    val sourceId: String,
    val evidenceRef: String?,
    val payloadDigest: String?,
)

/**
 * Association binding one source observation to the single canonical event
 * it produced (WM028-13B1). The composite primary key over the observation
 * identity enforces "each source attaches at most one event"; there is no
 * uniqueness over `eventId`, so multiple proven observations may attach to
 * one event.
 *
 * Attaching an observation that was never stored, or an event that does not
 * exist, is rejected by foreign keys — never repaired or auto-created.
 */
@Entity(
    tableName = "canonical_ledger_event_source",
    primaryKeys = ["sourceNamespace", "sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalLedgerSourceObservationEntity::class,
            parentColumns = ["sourceNamespace", "sourceId"],
            childColumns = ["sourceNamespace", "sourceId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalLedgerEventEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("eventId"),
    ],
)
internal data class CanonicalLedgerEventSourceEntity(
    val sourceNamespace: String,
    val sourceId: String,
    val eventId: String,
)
