package com.athar.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.athar.core.data.db.dao.AccountDao
import com.athar.core.data.db.dao.ActivityLogDao
import com.athar.core.data.db.dao.CategoryDao
import com.athar.core.data.db.dao.CategoryRuleDao
import com.athar.core.data.db.dao.InvestmentDao
import com.athar.core.data.db.dao.RecurringRuleDao
import com.athar.core.data.db.dao.SmsMessageDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.dao.TransactionReceiptDao
import com.athar.core.data.db.dao.UserTemplateDao
import com.athar.core.data.db.dao.WishlistDao
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.ActivityLogEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.data.db.entity.InvestmentContributionEntity
import com.athar.core.data.db.entity.InvestmentPoolEntity
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.db.entity.SmsMessageEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.athar.core.data.db.entity.UserTemplateEntity
import com.athar.core.data.db.entity.WishlistEntity

@Database(
    version = 8,
    exportSchema = true,
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        CategoryRuleEntity::class,
        WishlistEntity::class,
        InvestmentPoolEntity::class,
        InvestmentContributionEntity::class,
        SmsMessageEntity::class,
        ActivityLogEntity::class,
        UserTemplateEntity::class,
        RecurringRuleEntity::class,
        TransactionReceiptEntity::class,
    ],
)
@TypeConverters(Converters::class)
internal abstract class AtharDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun wishlistDao(): WishlistDao
    abstract fun investmentDao(): InvestmentDao
    abstract fun smsMessageDao(): SmsMessageDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun userTemplateDao(): UserTemplateDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun transactionReceiptDao(): TransactionReceiptDao

    companion object {
        internal const val NAME: String = "athar.db"

        /**
         * v1 → v2: adds the `activity_log` table (P-08).
         */
        internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS activity_log (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        summary TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_log_timestamp ON activity_log(timestamp)")
            }
        }

        /**
         * v2 → v3: adds the `user_template` table (W-4). User-defined bank-SMS
         * parsing templates with anchor-based field extraction.
         */
        internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_template (
                        id TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        txType TEXT NOT NULL,
                        amountAnchorBefore TEXT NOT NULL,
                        amountAnchorAfter TEXT,
                        merchantAnchorBefore TEXT,
                        merchantAnchorAfter TEXT,
                        counterpartyAnchorBefore TEXT,
                        counterpartyAnchorAfter TEXT,
                        sampleBody TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_template_sender ON user_template(sender)")
            }
        }

        /**
         * v3 → v4: adds the `recurring_rule` table (G-3). Stores user-defined
         * recurring transactions (rent, salary, subscriptions) that materialize
         * into PENDING transactions on their next-run date.
         */
        internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_rule (
                        id TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        merchant TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        amountCurrency TEXT NOT NULL,
                        type TEXT NOT NULL,
                        accountId TEXT NOT NULL,
                        categoryId TEXT,
                        cadence TEXT NOT NULL,
                        dayOfMonth INTEGER,
                        dayOfWeek INTEGER,
                        monthOfYear INTEGER,
                        nextRunDate TEXT NOT NULL,
                        lastRunDate TEXT,
                        isActive INTEGER NOT NULL,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_rule_nextRunDate ON recurring_rule(nextRunDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_rule_isActive ON recurring_rule(isActive)")
            }
        }

        /**
         * v4 → v5: multi-account + net worth (G-4).
         *
         * Extends the `account` table with the columns needed to model real
         * checking/savings/credit/cash/investment buckets and to compute live net
         * worth. Also remaps the legacy `DEBIT`/`CREDIT` enum values to the new
         * `CHECKING`/`CREDIT_CARD` taxonomy.
         */
        internal val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account ADD COLUMN openingBalanceMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE account ADD COLUMN openingBalanceCurrency TEXT NOT NULL DEFAULT 'SAR'")
                db.execSQL("ALTER TABLE account ADD COLUMN notes TEXT")
                db.execSQL("ALTER TABLE account ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE account ADD COLUMN archivedAt INTEGER")
                db.execSQL("ALTER TABLE account ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE account SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("UPDATE account SET type = 'CHECKING'    WHERE type = 'DEBIT'")
                db.execSQL("UPDATE account SET type = 'CREDIT_CARD' WHERE type = 'CREDIT'")
                db.execSQL(
                    "UPDATE account SET type = 'OTHER' " +
                        "WHERE type NOT IN ('CHECKING','SAVINGS','CREDIT_CARD','CASH','INVESTMENT','OTHER')",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_account_archivedAt ON account(archivedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_account_sortOrder ON account(sortOrder)")
            }
        }

        /**
         * v5 → v6: encrypted receipt attachments for manual transactions (G-8).
         *
         * The payload is stored as a BLOB in the SQLCipher database, so receipt photos
         * inherit the same local-at-rest encryption as ledger data.
         */
        internal val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transaction_receipt (
                        id TEXT NOT NULL PRIMARY KEY,
                        transactionId TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        originalName TEXT,
                        sizeBytes INTEGER NOT NULL,
                        payload BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(transactionId) REFERENCES transactions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_transaction_receipt_transactionId
                    ON transaction_receipt(transactionId)
                    """.trimIndent(),
                )
            }
        }

        /**
         * v6 → v7: allows multiple encrypted receipt attachments per transaction (G-8).
         *
         * Existing receipt rows are preserved; the transaction lookup index changes from
         * unique to non-unique so users can keep an itemized receipt plus card slip, invoice
         * pages, or other proof images on one ledger transaction.
         */
        internal val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_transaction_receipt_transactionId")
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_transaction_receipt_transactionId
                    ON transaction_receipt(transactionId)
                    """.trimIndent(),
                )
            }
        }

        /**
         * v7 → v8: nullable categorization provenance on transactions (ENH-009).
         *
         * `categorySource` records which layer suggested the category
         * (RULE_*, CLASSIFIER, CATALOG, USER) so catalog/model suggestions can
         * never silently feed local learning or confidence sweeps. NULL for
         * all legacy rows; the column is additive and never carries financial
         * values.
         */
        internal val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN categorySource TEXT")
            }
        }
    }
}
