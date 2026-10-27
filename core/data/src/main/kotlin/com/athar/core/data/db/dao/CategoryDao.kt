package com.athar.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.athar.core.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface CategoryDao {

    @Query(
        """
        SELECT * FROM category
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:includeArchived = 1 OR archived = 0)
        ORDER BY sortOrder ASC, name ASC
        """,
    )
    fun observeAll(kind: String?, includeArchived: Boolean): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Query("SELECT * FROM category ORDER BY sortOrder ASC")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("DELETE FROM category")
    suspend fun clear()

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE category SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE category SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)
}
