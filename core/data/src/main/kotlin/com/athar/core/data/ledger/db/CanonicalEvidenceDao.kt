package com.athar.core.data.ledger.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Low-level archival only; not an atomic financial admission/replay API. */
@Dao
internal interface CanonicalEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: CanonicalObservationSnapshotEntity)

    @Query(
        """
        SELECT * FROM canonical_ledger_observation_snapshot
        WHERE sourceNamespace = :sourceNamespace AND sourceId = :sourceId
        ORDER BY sourcePayloadDigest, snapshotDigest
        """,
    )
    suspend fun listSnapshots(sourceNamespace: String, sourceId: String): List<CanonicalObservationSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConflict(conflict: CanonicalLedgerConflictEntity)

    @Query("SELECT * FROM canonical_ledger_conflict WHERE conflictId = :conflictId LIMIT 1")
    suspend fun getConflict(conflictId: String): CanonicalLedgerConflictEntity?

    @Query(
        """
        SELECT * FROM canonical_ledger_conflict
        WHERE sourceNamespace = :sourceNamespace AND sourceId = :sourceId
        ORDER BY conflictId
        """,
    )
    suspend fun listConflicts(sourceNamespace: String, sourceId: String): List<CanonicalLedgerConflictEntity>

    // ------------------------------------------- WM028-13B2b additive queries

    /** Point query for one exact archived variant; never loads all BLOBs. */
    @Query(
        """
        SELECT * FROM canonical_ledger_observation_snapshot
        WHERE sourceNamespace = :sourceNamespace AND sourceId = :sourceId
          AND sourcePayloadDigest = :sourcePayloadDigest AND snapshotDigest = :snapshotDigest
        LIMIT 1
        """,
    )
    suspend fun getSnapshot(
        sourceNamespace: String,
        sourceId: String,
        sourcePayloadDigest: String,
        snapshotDigest: String,
    ): CanonicalObservationSnapshotEntity?

    /** Accepted-baseline lookup scoped to one payload digest (PK prefix). */
    @Query(
        """
        SELECT * FROM canonical_ledger_observation_snapshot
        WHERE sourceNamespace = :sourceNamespace AND sourceId = :sourceId
          AND sourcePayloadDigest = :sourcePayloadDigest
        ORDER BY snapshotDigest
        """,
    )
    suspend fun listSnapshotsByPayload(
        sourceNamespace: String,
        sourceId: String,
        sourcePayloadDigest: String,
    ): List<CanonicalObservationSnapshotEntity>
}
