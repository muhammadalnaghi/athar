package com.athar.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athar.core.data.db.entity.AccountBalanceRow
import com.athar.core.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

@Dao
internal interface TransactionDao {

    @Query(
        """
        SELECT * FROM transactions
        WHERE date >= :start AND date < :endExclusive
          AND (:status IS NULL OR status = :status)
        ORDER BY date DESC, createdAt DESC
        """,
    )
    fun observeByPeriod(start: LocalDate, endExclusive: LocalDate, status: String?): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY date DESC, createdAt DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status = 'CONFIRMED' AND date >= :since ORDER BY date DESC")
    fun observeConfirmedSince(since: LocalDate): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT categoryId AS categoryId, COUNT(*) AS count
        FROM transactions
        WHERE status = 'CONFIRMED'
          AND merchantNormalized = :merchantNormalized
          AND categoryId IS NOT NULL
          AND categoryId != ''
          AND type != 'TRANSFER'
          AND (sourceRefId IS NULL OR sourceRefId NOT LIKE 'reconcile-%')
          AND (categorySource IS NULL OR categorySource NOT IN ('CLASSIFIER', 'CATALOG'))
        GROUP BY categoryId
        ORDER BY count DESC, categoryId ASC
        """,
    )
    suspend fun confirmedCategoryCountsForMerchant(merchantNormalized: String): List<CategoryUsageCount>

    @Query("SELECT id FROM transactions LIMIT 1")
    suspend fun firstId(): String?

    @Query("DELETE FROM transactions")
    suspend fun clear()

    @Query("DELETE FROM transactions WHERE status = 'PENDING'")
    suspend fun clearPending(): Int

    @Query(
        """
        UPDATE transactions SET status = 'CONFIRMED', updatedAt = :now
        WHERE status = 'PENDING' AND confidence >= :minConfidence
          AND (categorySource IS NULL OR categorySource NOT IN ('CLASSIFIER', 'CATALOG'))
        """
    )
    suspend fun confirmAllConfident(minConfidence: Float, now: kotlinx.datetime.Instant): Int

    @Query("UPDATE transactions SET status = 'DISMISSED', updatedAt = :now WHERE status = 'PENDING' AND confidence < :maxConfidence")
    suspend fun dismissAllLowConfidence(maxConfidence: Float, now: kotlinx.datetime.Instant): Int

    @Query("UPDATE transactions SET status = 'DISMISSED', updatedAt = :now WHERE status = 'PENDING'")
    suspend fun dismissAllPending(now: kotlinx.datetime.Instant): Int

    @Query("UPDATE transactions SET status = 'PENDING', updatedAt = :now WHERE status = 'DISMISSED'")
    suspend fun recoverDismissedToPending(now: kotlinx.datetime.Instant): Int

    /**
     * Apply [categoryId] to every transaction whose merchantNormalized contains [pattern]
     * (case-insensitive LIKE) AND whose current status is PENDING or DISMISSED — those are
     * the rows the user hasn't explicitly approved yet. Status is moved to CONFIRMED because
     * the user has just *just* explicitly chosen this category for this merchant; any future
     * matches would auto-confirm under the same rule.
     *
     * CONFIRMED rows are NOT touched — the user may have intentionally chosen a different
     * category for some of them earlier (e.g., a Hemmah charge that was actually a gift).
     */
    @Query(
        """
        UPDATE transactions
        SET categoryId = :categoryId,
            categorySource = 'USER',
            status = 'CONFIRMED',
            updatedAt = :now
        WHERE status IN ('PENDING', 'DISMISSED')
          AND merchantNormalized LIKE '%' || :pattern || '%'
        """,
    )
    suspend fun applyCategoryToMatching(pattern: String, categoryId: String, now: kotlinx.datetime.Instant): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE transactions SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: kotlinx.datetime.Instant)

    /**
     * Per-account, per-currency net of CONFIRMED transactions in signed minor units
     * (INCOME positive, EXPENSE negative). TRANSFER rows are excluded — they're net-zero
     * across both legs and v1 doesn't model the second leg yet.
     */
    @Query(
        """
        SELECT accountId AS accountId,
               currency AS currency,
               SUM(CASE WHEN type = 'EXPENSE' THEN -amountMinor ELSE amountMinor END) AS sumMinor
        FROM transactions
        WHERE status = 'CONFIRMED' AND type != 'TRANSFER'
        GROUP BY accountId, currency
        """,
    )
    fun observeBalancesByAccount(): Flow<List<AccountBalanceRow>>
}

internal data class CategoryUsageCount(
    val categoryId: String,
    val count: Int,
)

/**
 * Single source of truth for "engine-derived" category provenance that must
 * never feed local rule learning or confidence bulk-sweeps: the engine cannot
 * teach itself through its own confirmed output. Kept as constants so the DAO
 * queries above and the learner gate stay in lockstep (pinned by test).
 */
internal object EngineProvenanceExclusion {
    val SOURCE_NAMES = listOf("CLASSIFIER", "CATALOG")
    const val SQL_CONDITION =
        "(categorySource IS NULL OR categorySource NOT IN ('CLASSIFIER', 'CATALOG'))"
}
