package com.athar.core.domain.repo

import com.athar.core.common.time.Period
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import kotlinx.coroutines.flow.Flow

/**
 * The public surface of the transaction data domain.
 *
 * Per the modularization skill (dimension 2: data ownership), data modules expose
 * repositories as their only public API. Feature modules depend on this interface
 * via `core:domain`; the implementation lives in `core:data` and is bound by Hilt.
 */
interface TransactionRepository {
    fun observeByPeriod(period: Period, status: TxStatus? = null): Flow<List<Transaction>>
    fun observePending(): Flow<List<Transaction>>
    /** Every transaction in the DB, newest first. Used by the All-Transactions history view. */
    fun observeAll(): Flow<List<Transaction>>
    suspend fun get(id: String): Transaction?
    suspend fun upsert(transaction: Transaction)

    /**
     * Atomically insert a new source observation or return its existing unchanged row id.
     * Replay never rewrites financial facts, user decisions, timestamps or receipt links.
     * A null source reference remains an independent observation. Generic edits use [upsert].
     * The returned stable id, not the caller UUID, is the audit transaction link.
     */
    suspend fun upsertIngest(transaction: Transaction): String
    suspend fun delete(id: String)
    suspend fun setStatus(id: String, status: TxStatus)

    /**
     * Bulk delete every transaction with status = PENDING. Used by Settings → "Re-scan"
     * to wipe the polluted tray accumulated before stricter spam-filter rules landed.
     * Confirmed transactions are untouched.
     */
    suspend fun clearPending(): Int

    /** Confirm every PENDING transaction whose confidence is >= [minConfidence]. */
    suspend fun confirmAllConfident(minConfidence: Float): Int

    /** Dismiss every PENDING transaction whose confidence is < [maxConfidence]. */
    suspend fun dismissAllLowConfidence(maxConfidence: Float): Int

    /** Dismiss every PENDING transaction outright. */
    suspend fun dismissAllPending(): Int

    /**
     * Move every DISMISSED transaction back to PENDING so the user can review them.
     * Used to recover transactions that older builds auto-dismissed for low confidence —
     * the user may have lost real spending records that way.
     */
    suspend fun recoverDismissedToPending(): Int

    /**
     * Fill an unassigned compatible category on PENDING or CONFIRMED transactions
     * whose merchant contains the explicit literal pattern. Preserve financial status,
     * existing categories and dismissed observations. Archived/missing categories do
     * not apply. Returns the number of category assignments, never postings.
     */
    suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int
}
