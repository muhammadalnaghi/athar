package com.athar.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athar.core.data.db.entity.UserTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserTemplateDao {
    @Query("SELECT * FROM user_template ORDER BY createdAt DESC")
    suspend fun all(): List<UserTemplateEntity>

    @Query("SELECT COUNT(*) FROM user_template")
    suspend fun count(): Long

    @Query("DELETE FROM user_template")
    suspend fun clear()

    @Query("SELECT * FROM user_template ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserTemplateEntity>>

    @Query("SELECT * FROM user_template WHERE id = :id LIMIT 1")
    suspend fun get(id: String): UserTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserTemplateEntity)

    @Query("DELETE FROM user_template WHERE id = :id")
    suspend fun delete(id: String)
}
