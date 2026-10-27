package com.athar.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athar.core.data.db.entity.ActivityLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ActivityLogDao {
    @Query("SELECT COUNT(*) FROM activity_log")
    suspend fun count(): Long

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC")
    suspend fun all(): List<ActivityLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ActivityLogEntity)

    @Query("DELETE FROM activity_log")
    suspend fun clear()
}
