package com.athar.core.data.ledger.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.athar.core.data.db.entity.AccountEntity

/**
 * One certified economic component of a canonical event (WM028-13B1),
 * event-scoped by composite primary key `(eventId, componentRef)`.
 *
 * `type` mirrors the domain `ComponentType`; `feeRelation` is nullable —
 * NULL means no fee component, while a stored `UNKNOWN` is retained as-is
 * and never conflated with "no fee". `amount`/`currency` are exact decimal
 * TEXT with literal currency; components carry economic amounts, never
 * account movements.
 */
@Entity(
    tableName = "canonical_ledger_component",
    primaryKeys = ["eventId", "componentRef"],
    foreignKeys = [
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
internal data class CanonicalLedgerComponentEntity(
    val eventId: String,
    val componentRef: String,
    val type: String,
    val amount: String,
    val currency: String,
    val feeRelation: String?,
    val feeEvidenceRef: String?,
)

/**
 * One signed-net-worth account leg of a canonical event (WM028-13B1),
 * event-scoped by composite primary key `(eventId, legRef)`.
 *
 * `delta` is a signed exact decimal TEXT with its literal `currency` —
 * positive increases the account's signed position, negative decreases it;
 * no FX rate or counterpart amount is ever synthesized. `accountRef` is a
 * foreign key into `account(id)` with NO ACTION deletion: deleting an
 * account must fail loudly, never silently erase financial history.
 * `(eventId, componentRef)` optionally attributes the leg to a same-event
 * component — a dangling or cross-event attribution is rejected, never
 * repaired.
 */
@Entity(
    tableName = "canonical_ledger_leg",
    primaryKeys = ["eventId", "legRef"],
    foreignKeys = [
        ForeignKey(
            entity = CanonicalLedgerEventEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalLedgerComponentEntity::class,
            parentColumns = ["eventId", "componentRef"],
            childColumns = ["eventId", "componentRef"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountRef"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("accountRef"),
        Index("eventId", "componentRef"),
    ],
)
internal data class CanonicalLedgerLegEntity(
    val eventId: String,
    val legRef: String,
    val accountRef: String,
    val delta: String,
    val currency: String,
    val componentRef: String?,
)
