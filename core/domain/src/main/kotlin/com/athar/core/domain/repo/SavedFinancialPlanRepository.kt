package com.athar.core.domain.repo

import kotlinx.coroutines.flow.Flow

/** Durable user-authored planning inputs, never posted money or cached solver results. */
enum class SavedFinancialPlanKind { GOAL, DEBT, CASH, PORTFOLIO }

data class SavedFinancialPlan(
    val id: String,
    val kind: SavedFinancialPlanKind,
    val title: String,
    val currency: String,
    val payloadVersion: Int,
    val payloadJson: String,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * Main-safe, encrypted-Room-backed saved-input boundary.
 *
 * Each feature owns its input document codec and validates its version before use.
 * Exact decimal strings and literal currencies must survive without conversion.
 * Identity, kind and creation time are immutable; returned collections are snapshots.
 */
interface SavedFinancialPlanRepository {
    fun observe(kind: SavedFinancialPlanKind): Flow<List<SavedFinancialPlan>>
    suspend fun get(id: String): SavedFinancialPlan?

    /** Null means create-only; an update must match the current revision exactly. */
    suspend fun save(plan: SavedFinancialPlan, expectedRevision: Long?): SavedFinancialPlan

    /** Explicit user deletion only; a stale revision refuses without changing storage. */
    suspend fun delete(id: String, expectedRevision: Long)
}

/** Safe constant messages only: callers must not expose stored JSON through errors. */
class SavedFinancialPlanConflictException : IllegalStateException("Saved plan changed")
class SavedFinancialPlanValidationException : IllegalArgumentException("Saved plan is unsupported or invalid")
