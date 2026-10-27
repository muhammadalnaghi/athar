package com.athar.core.data.ledger.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One original-source/analysis variant, not a financial event or economic identity.
 * Digests of original source bytes and the versioned codec snapshot are separate.
 * The later repository validates them and defensively copies bytes at its boundary;
 * these Room rows remain internal. No recertification occurs on DAO reads.
 */
@Entity(
    tableName = "canonical_ledger_observation_snapshot",
    primaryKeys = ["sourceNamespace", "sourceId", "sourcePayloadDigest", "snapshotDigest"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalLedgerSourceObservationEntity::class,
            parentColumns = ["sourceNamespace", "sourceId"],
            childColumns = ["sourceNamespace", "sourceId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
internal data class CanonicalObservationSnapshotEntity(
    val sourceNamespace: String,
    val sourceId: String,
    val sourcePayloadDigest: String,
    val snapshotDigest: String,
    val sourcePayload: ByteArray,
    val snapshotJson: String,
)

/**
 * Durable diagnostic referring to the exact incoming archived variant. It never
 * contributes money and does not replace an accepted event or source payload.
 * IDs/reasons are caller-supplied; admission/revision coordination belongs to the
 * later atomic repository, not this low-level entity.
 */
@Entity(
    tableName = "canonical_ledger_conflict",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalObservationSnapshotEntity::class,
            parentColumns = ["sourceNamespace", "sourceId", "sourcePayloadDigest", "snapshotDigest"],
            childColumns = ["sourceNamespace", "sourceId", "sourcePayloadDigest", "snapshotDigest"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalLedgerEventEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["existingEventId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("sourceNamespace", "sourceId", "sourcePayloadDigest", "snapshotDigest"),
        Index("existingEventId"),
    ],
)
internal data class CanonicalLedgerConflictEntity(
    @PrimaryKey val conflictId: String,
    val sourceNamespace: String,
    val sourceId: String,
    val sourcePayloadDigest: String,
    val snapshotDigest: String,
    val existingEventId: String?,
    val reasonCode: String,
    val detailJson: String?,
)
