package com.athar.core.data.ledger.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Low-level internal DAO for the WM028-13B1 canonical ledger tables.
 *
 * Deliberately raw: it moves rows in and out of the six canonical-ledger
 * tables and exposes nothing to domain/UI, performs no merging, no
 * idempotency decisions and no account currency/ownership validation —
 * those belong to the later atomic repository seam. Every insert uses
 * [OnConflictStrategy.ABORT]: financial rows are never silently replaced,
 * and constraint violations surface as SQLite exceptions for the caller.
 */
@Dao
internal interface CanonicalLedgerDao {

    // ---------------------------------------------------------------- events

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: CanonicalLedgerEventEntity)

    @Query("SELECT * FROM canonical_ledger_event WHERE eventId = :eventId LIMIT 1")
    suspend fun getEvent(eventId: String): CanonicalLedgerEventEntity?

    @Query(
        """
        SELECT * FROM canonical_ledger_event
        WHERE bookedCurrency = :currency
        ORDER BY eventId
        """,
    )
    suspend fun listEventsByCurrency(currency: String): List<CanonicalLedgerEventEntity>

    @Query(
        """
        SELECT * FROM canonical_ledger_event
        WHERE kind = :kind
        ORDER BY eventId
        """,
    )
    suspend fun listEventsByKind(kind: String): List<CanonicalLedgerEventEntity>

    @Query(
        """
        SELECT * FROM canonical_ledger_event
        WHERE eventDate BETWEEN :fromDate AND :toDate
        ORDER BY eventDate, eventId
        """,
    )
    suspend fun listEventsByDateRange(fromDate: String, toDate: String): List<CanonicalLedgerEventEntity>

    // ---------------------------------------------------- source observations

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertObservation(observation: CanonicalLedgerSourceObservationEntity)

    @Query(
        """
        SELECT * FROM canonical_ledger_source_observation
        WHERE sourceNamespace = :sourceNamespace AND sourceId = :sourceId
        LIMIT 1
        """,
    )
    suspend fun getObservation(sourceNamespace: String, sourceId: String): CanonicalLedgerSourceObservationEntity?

    // ---------------------------------------------- observation↔event binding

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEventSource(association: CanonicalLedgerEventSourceEntity)

    @Query(
        """
        SELECT * FROM canonical_ledger_event_source
        WHERE sourceNamespace = :sourceNamespace AND sourceId = :sourceId
        LIMIT 1
        """,
    )
    suspend fun getEventSource(sourceNamespace: String, sourceId: String): CanonicalLedgerEventSourceEntity?

    // ------------------------------------------------------------ components

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertComponent(component: CanonicalLedgerComponentEntity)

    @Query(
        """
        SELECT * FROM canonical_ledger_component
        WHERE eventId = :eventId
        ORDER BY componentRef
        """,
    )
    suspend fun listComponentsForEvent(eventId: String): List<CanonicalLedgerComponentEntity>

    // ------------------------------------------------------------------ legs

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLeg(leg: CanonicalLedgerLegEntity)

    @Query(
        """
        SELECT * FROM canonical_ledger_leg
        WHERE eventId = :eventId
        ORDER BY legRef
        """,
    )
    suspend fun listLegsForEvent(eventId: String): List<CanonicalLedgerLegEntity>

    // ------------------------------------------------- revision metadata only

    @Query("SELECT * FROM canonical_ledger_revision WHERE id = :id LIMIT 1")
    suspend fun getRevision(id: String): CanonicalLedgerRevisionEntity?

    @Query("UPDATE canonical_ledger_revision SET revision = revision + 1 WHERE id = :id")
    suspend fun incrementRevision(id: String): Int

    // ------------------------------------------- WM028-13B2b additive queries

    @Query(
        """
        SELECT * FROM canonical_ledger_event
        WHERE economicIdentityIssuer = :issuer AND economicIdentityReference = :reference
        LIMIT 1
        """,
    )
    suspend fun getEventByIdentity(issuer: String, reference: String): CanonicalLedgerEventEntity?

    @Query(
        """
        SELECT * FROM canonical_ledger_component
        WHERE feeEvidenceRef IN (:feeEvidenceRefs)
        ORDER BY eventId, componentRef
        """,
    )
    suspend fun listComponentsByFeeEvidenceRefs(feeEvidenceRefs: List<String>): List<CanonicalLedgerComponentEntity>

    /** Compare-and-increment; the returned rowcount must prove the advance. */
    @Query(
        """
        UPDATE canonical_ledger_revision
        SET revision = revision + 1
        WHERE id = :id AND revision = :expectedRevision
        """,
    )
    suspend fun incrementRevisionIfExpected(id: String, expectedRevision: Long): Int

    // -------------------- WM028-13C3 backup capture / clear (additive, backup lane only)

    @Query("SELECT * FROM canonical_ledger_event ORDER BY eventId")
    suspend fun captureEvents(): List<CanonicalLedgerEventEntity>

    @Query("SELECT * FROM canonical_ledger_source_observation ORDER BY sourceNamespace, sourceId")
    suspend fun captureObservations(): List<CanonicalLedgerSourceObservationEntity>

    @Query("SELECT * FROM canonical_ledger_event_source ORDER BY sourceNamespace, sourceId")
    suspend fun captureAssociations(): List<CanonicalLedgerEventSourceEntity>

    @Query("SELECT * FROM canonical_ledger_component ORDER BY eventId, componentRef")
    suspend fun captureComponents(): List<CanonicalLedgerComponentEntity>

    @Query("SELECT * FROM canonical_ledger_leg ORDER BY eventId, legRef")
    suspend fun captureLegs(): List<CanonicalLedgerLegEntity>

    @Query("SELECT * FROM canonical_ledger_revision ORDER BY id")
    suspend fun captureRevisions(): List<CanonicalLedgerRevisionEntity>

    @Query(
        """
        SELECT * FROM canonical_ledger_observation_snapshot
        ORDER BY sourceNamespace, sourceId, sourcePayloadDigest, snapshotDigest
        """,
    )
    suspend fun captureSnapshotVariants(): List<CanonicalObservationSnapshotEntity>

    @Query("SELECT * FROM canonical_ledger_conflict ORDER BY conflictId")
    suspend fun captureConflicts(): List<CanonicalLedgerConflictEntity>

    /**
     * Rowcount over the seven canonical data tables. The revision counter table
     * is deliberately excluded: the seeded `singleton` row is metadata, so a
     * legacy restore stays acceptable on a fresh database while still refusing
     * when real canonical rows would be silently stranded.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM canonical_ledger_event) +
            (SELECT COUNT(*) FROM canonical_ledger_source_observation) +
            (SELECT COUNT(*) FROM canonical_ledger_event_source) +
            (SELECT COUNT(*) FROM canonical_ledger_component) +
            (SELECT COUNT(*) FROM canonical_ledger_leg) +
            (SELECT COUNT(*) FROM canonical_ledger_observation_snapshot) +
            (SELECT COUNT(*) FROM canonical_ledger_conflict)
        """,
    )
    suspend fun countCanonicalLedgerDataRows(): Long

    /** Child-first clears for a whole-replace restore; order respects the NO_ACTION FK graph. */
    @Query("DELETE FROM canonical_ledger_conflict")
    suspend fun clearConflicts(): Int

    @Query("DELETE FROM canonical_ledger_observation_snapshot")
    suspend fun clearSnapshotVariants(): Int

    @Query("DELETE FROM canonical_ledger_event_source")
    suspend fun clearAssociations(): Int

    @Query("DELETE FROM canonical_ledger_component")
    suspend fun clearComponents(): Int

    @Query("DELETE FROM canonical_ledger_leg")
    suspend fun clearLegs(): Int

    @Query("DELETE FROM canonical_ledger_event")
    suspend fun clearEvents(): Int

    @Query("DELETE FROM canonical_ledger_source_observation")
    suspend fun clearObservations(): Int

    @Query("DELETE FROM canonical_ledger_revision")
    suspend fun clearRevisions(): Int

    /** Restore-side ABORT inserts for tables that had no plain insert path; duplicates roll back. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevisionExact(revision: CanonicalLedgerRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshotVariant(snapshot: CanonicalObservationSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConflict(conflict: CanonicalLedgerConflictEntity)
}
