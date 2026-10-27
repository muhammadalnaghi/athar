package com.athar.core.data.ledger.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One posted canonical financial event (WM028-13B1), persisted under its
 * stable caller-supplied [eventId]. Internal persistence only: never exposed
 * to domain/UI, never written outside the later atomic repository seam.
 *
 * Column meanings are frozen to the certified graph snapshot contract
 * (`core:domain` `FinancialEventGraph` / `core:data` `ledger:codec`):
 * - `snapshotJson` is the immutable versioned codec envelope (raw draft plus
 *   certified snapshot, including category metadata). It is write-once
 *   evidence; queryable columns below mirror only the certified facts.
 * - `snapshotFormatVersion` / `policyRevision` / `graphRevision` record the
 *   codec and policy revisions that produced the snapshot.
 * - `bookedAmount`/`bookedCurrency` are exact decimal TEXT with literal
 *   currency — never REAL, never universal minor units, no invented zeros.
 * - `economicIdentityIssuer`/`economicIdentityReference` are the optional
 *   proven issuer reference. Uniqueness is enforced by
 *   [CanonicalLedgerSchema.MIGRATION_8_9] exactly as Room generates it: rows
 *   without a proven economic identity (NULLs) never collide, so
 *   observation-scoped identities stay separate. Similar text, amounts or
 *   dates never establish this identity.
 * - `eventDate` is nullable: a missing date is preserved, never defaulted.
 * - `recordingOutcome` distinguishes RECORDED from CAPTURED_UNRESOLVED_KIND.
 *
 * Inserts use ABORT semantics (see [CanonicalLedgerDao]) — financial rows are
 * never overwritten by conflict replacement.
 */
@Entity(
    tableName = "canonical_ledger_event",
    indices = [
        Index(
            value = ["economicIdentityIssuer", "economicIdentityReference"],
            unique = true,
        ),
    ],
)
internal data class CanonicalLedgerEventEntity(
    @PrimaryKey val eventId: String,
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

/**
 * Canonical-ledger revision metadata singleton (WM028-13B1). Purely
 * metadata — never a financial balance and never derivable from stored legs.
 *
 * Exactly one row (`id` = [CanonicalLedgerSchema.REVISION_SINGLETON_ID]) is
 * seeded consistently for fresh databases (builder `onCreate` callback) and
 * migrated databases (`MIGRATION_8_9` insert), starting at `revision = 0`.
 * There is no fallback or reseeding path: a missing row means the ledger was
 * never initialized, never a silent reset to zero.
 */
@Entity(tableName = "canonical_ledger_revision")
internal data class CanonicalLedgerRevisionEntity(
    @PrimaryKey val id: String,
    val revision: Long,
)
