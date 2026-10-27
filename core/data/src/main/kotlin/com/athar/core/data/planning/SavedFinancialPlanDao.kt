package com.athar.core.data.planning

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * WM028 fast-store DAO. Conflict safety is statement-atomic: the conditional
 * UPDATE/DELETE return affected-row counts, so a stale revision can never
 * partially apply and no read-check-write race exists.
 */
@Dao
internal interface SavedFinancialPlanDao {

    @Query(
        "SELECT * FROM saved_financial_plan WHERE kind = :kind " +
            "ORDER BY updatedAtEpochMillis DESC, id ASC",
    )
    fun observeByKind(kind: String): Flow<List<SavedFinancialPlanEntity>>

    @Query("SELECT * FROM saved_financial_plan WHERE id = :id")
    suspend fun getById(id: String): SavedFinancialPlanEntity?

    /** ABORT: a duplicate id on create-only save surfaces as a conflict, never an overwrite. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SavedFinancialPlanEntity)

    @Query(
        "UPDATE saved_financial_plan SET title = :title, currency = :currency, " +
            "payloadVersion = :payloadVersion, payloadJson = :payloadJson, " +
            "revision = :newRevision, updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE id = :id AND revision = :expectedRevision",
    )
    suspend fun updateIfRevisionMatches(
        id: String,
        title: String,
        currency: String,
        payloadVersion: Int,
        payloadJson: String,
        expectedRevision: Long,
        newRevision: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM saved_financial_plan WHERE id = :id AND revision = :expectedRevision")
    suspend fun deleteIfRevisionMatches(id: String, expectedRevision: Long): Int

    @Query("SELECT * FROM saved_financial_plan ORDER BY updatedAtEpochMillis DESC, id ASC")
    suspend fun all(): List<SavedFinancialPlanEntity>

    @Query("DELETE FROM saved_financial_plan")
    suspend fun clear()

    /** Backup restore only, inside the existing replace transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<SavedFinancialPlanEntity>)

    /** Backup eligibility only: deterministic full-table live-row count, inside the replace transaction. */
    @Query("SELECT COUNT(*) FROM saved_financial_plan")
    suspend fun count(): Long
}
