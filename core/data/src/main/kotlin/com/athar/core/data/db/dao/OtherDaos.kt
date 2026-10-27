package com.athar.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.athar.core.data.db.entity.AccountBalanceRow
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.data.db.entity.InvestmentContributionEntity
import com.athar.core.data.db.entity.InvestmentPoolEntity
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.db.entity.SmsMessageEntity
import com.athar.core.data.db.entity.WishlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AccountDao {
    @Query("SELECT * FROM account WHERE archivedAt IS NULL ORDER BY sortOrder ASC, name ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account ORDER BY archivedAt IS NOT NULL ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM account ORDER BY name")
    suspend fun all(): List<AccountEntity>

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("UPDATE account SET archivedAt = :archivedAt, active = :active, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archivedAt: kotlinx.datetime.Instant?, active: Boolean, now: kotlinx.datetime.Instant)

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :id")
    suspend fun countTransactions(id: String): Int

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM account")
    suspend fun clear()
}

@Dao
internal interface CategoryRuleDao {
    @Query("SELECT * FROM category_rule ORDER BY priority DESC, createdAt DESC")
    fun observeAll(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rule WHERE priority > 0 ORDER BY priority DESC")
    suspend fun all(): List<CategoryRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rule WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM category_rule")
    suspend fun clear()

    @Query("DELETE FROM category_rule WHERE learnedFromUser = 0")
    suspend fun clearSystemRules()

    @Query("SELECT COUNT(*) FROM category_rule WHERE learnedFromUser = 0 AND id NOT LIKE 'auto-local-%'")
    suspend fun countSystemRules(): Int

    @Query("DELETE FROM category_rule WHERE learnedFromUser = 0 AND id NOT LIKE 'auto-local-%'")
    suspend fun clearSeedRules()

    @Query("DELETE FROM category_rule WHERE id LIKE 'auto-local-%' AND pattern = :pattern AND patternType = :patternType")
    suspend fun deleteAutoLearnedForPattern(pattern: String, patternType: String): Int
}

@Dao
internal interface WishlistDao {
    @Query("SELECT * FROM wishlist_item ORDER BY startYear ASC, startMonth ASC")
    fun observeAll(): Flow<List<WishlistEntity>>

    @Query("SELECT * FROM wishlist_item ORDER BY startYear ASC, startMonth ASC")
    suspend fun all(): List<WishlistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WishlistEntity)

    @Query("DELETE FROM wishlist_item WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM wishlist_item")
    suspend fun clear()
}

@Dao
internal interface InvestmentDao {
    @Query("SELECT * FROM investment_pool")
    fun observePools(): Flow<List<InvestmentPoolEntity>>

    @Query("SELECT * FROM investment_contribution")
    fun observeContributions(): Flow<List<InvestmentContributionEntity>>

    @Query("SELECT * FROM investment_pool")
    suspend fun allPools(): List<InvestmentPoolEntity>

    @Query("SELECT * FROM investment_contribution")
    suspend fun allContributions(): List<InvestmentContributionEntity>

    // REPLACE would delete this parent and cascade all contributor records.
    @Upsert
    suspend fun upsertPool(pool: InvestmentPoolEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContribution(contribution: InvestmentContributionEntity)

    @Query("DELETE FROM investment_pool")
    suspend fun clearPools()

    @Query("DELETE FROM investment_contribution")
    suspend fun clearContributions()

    @Query("DELETE FROM investment_pool WHERE id = :id")
    suspend fun deletePool(id: String)

    @Query("DELETE FROM investment_contribution WHERE id = :id")
    suspend fun deleteContribution(id: String)
}

@Dao
internal interface SmsMessageDao {
    @Query("SELECT * FROM sms_message ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_message WHERE parseStatus = :status ORDER BY receivedAt DESC")
    fun observeByStatus(status: String): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_message ORDER BY receivedAt DESC")
    suspend fun all(): List<SmsMessageEntity>

    @Query("SELECT * FROM sms_message ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SmsMessageEntity>

    @Query("SELECT parseStatus AS status, COUNT(*) AS count FROM sms_message GROUP BY parseStatus")
    suspend fun statusCounts(): List<SmsStatusCount>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreOnDup(entity: SmsMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SmsMessageEntity)

    @Query("UPDATE sms_message SET parseStatus = :status, parsedTransactionId = :txId, parseError = :error WHERE id = :id")
    suspend fun updateParse(id: String, status: String, txId: String?, error: String?)

    @Query("DELETE FROM sms_message")
    suspend fun clear()
}

internal data class SmsStatusCount(
    val status: String,
    val count: Int,
)

@Dao
internal interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rule ORDER BY isActive DESC, nextRunDate ASC")
    suspend fun all(): List<RecurringRuleEntity>

    @Query("SELECT COUNT(*) FROM recurring_rule")
    suspend fun count(): Long

    @Query("SELECT * FROM recurring_rule ORDER BY isActive DESC, nextRunDate ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rule WHERE isActive = 1 ORDER BY nextRunDate ASC")
    fun observeActive(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun get(id: String): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_rule WHERE isActive = 1 AND nextRunDate <= :today")
    suspend fun dueOn(today: String): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecurringRuleEntity)

    @Query("UPDATE recurring_rule SET isActive = :active, updatedAt = :ts WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, ts: Long)

    @Query("DELETE FROM recurring_rule WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recurring_rule")
    suspend fun clear()
}
