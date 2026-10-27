package com.athar.core.data.ledger.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.data.db.AtharDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WM028-13B1 regression: the real 8→9 migration must add the canonical ledger
 * without touching any pre-existing row — legacy transaction amounts,
 * merchants, statuses and categorization provenance survive verbatim, the new
 * tables start empty, and the revision metadata singleton is initialized at 0.
 *
 * Sandbox discipline: uniquely named throwaway database only; `athar.db` and
 * real installation data are never touched. Validation runs against the
 * KSP-exported schema 9 (never hand-authored).
 */
@RunWith(AndroidJUnit4::class)
class CanonicalLedgerSchemaMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtharDatabase::class.java,
        emptyList(),
    )

    @Test
    fun migration8To9PreservesLegacyRowsStatusesProvenanceAndInitializesRevision() {
        val databaseName = "athar-wm028-13b1-migration-8-to-9"
        val oldDatabase = helper.createDatabase(databaseName, 8)
        oldDatabase.execSQL(
            """
            INSERT INTO account (
                id, name, type, currency, openingBalanceMinor, openingBalanceCurrency,
                smsSenders, notes, sortOrder, active, archivedAt, createdAt, updatedAt
            ) VALUES ('legacy-cash', 'Cash', 'CASH', 'SAR', 0, 'SAR', '[]', NULL, 0, 1, NULL, 1, 1)
            """.trimIndent(),
        )
        insertLegacyTransaction(oldDatabase, "legacy-tx-pending", 1500, "Pending Shop", "PENDING", "NULL")
        insertLegacyTransaction(oldDatabase, "legacy-tx-confirmed", 25000, "Confirmed Shop", "CONFIRMED", "'RULE_EXACT'")
        insertLegacyTransaction(oldDatabase, "legacy-tx-dismissed", 999, "Dismissed Shop", "DISMISSED", "NULL")
        oldDatabase.close()

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            AtharDatabase.MIGRATION_8_9,
        )
        try {
            assertLegacyTransaction(migrated, "legacy-tx-pending", 1500, "Pending Shop", "PENDING", true)
            assertLegacyTransaction(migrated, "legacy-tx-confirmed", 25000, "Confirmed Shop", "CONFIRMED", false)
            assertLegacyTransaction(migrated, "legacy-tx-dismissed", 999, "Dismissed Shop", "DISMISSED", true)

            // New canonical-ledger tables start empty — the migration never
            // invents financial history.
            assertThat(count(migrated, "canonical_ledger_event")).isEqualTo(0)
            assertThat(count(migrated, "canonical_ledger_source_observation")).isEqualTo(0)
            assertThat(count(migrated, "canonical_ledger_event_source")).isEqualTo(0)
            assertThat(count(migrated, "canonical_ledger_component")).isEqualTo(0)
            assertThat(count(migrated, "canonical_ledger_leg")).isEqualTo(0)

            // Revision metadata singleton: exactly one row at revision 0.
            migrated.query(
                "SELECT id, revision FROM canonical_ledger_revision",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo(CanonicalLedgerSchema.REVISION_SINGLETON_ID)
                assertThat(cursor.getLong(1)).isEqualTo(0L)
                assertThat(cursor.moveToNext()).isFalse()
            }
        } finally {
            migrated.close()
        }
    }

    private fun insertLegacyTransaction(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        amountMinor: Long,
        merchant: String,
        status: String,
        categorySourceSqlLiteral: String,
    ) {
        db.execSQL(
            """
            INSERT INTO transactions (
                id, accountId, type, amountMinor, currency, date, occurredAt, merchant,
                merchantNormalized, categoryId, notes, source, sourceRefId, status,
                confidence, categorySource, createdAt, updatedAt
            ) VALUES (
                '$id', 'legacy-cash', 'EXPENSE', $amountMinor, 'SAR', '2026-01-01', NULL,
                '$merchant', '${merchant.lowercase()}', NULL, NULL, 'MANUAL', NULL,
                '$status', NULL, $categorySourceSqlLiteral, 1, 1
            )
            """.trimIndent(),
        )
    }

    private fun assertLegacyTransaction(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        amountMinor: Long,
        merchant: String,
        status: String,
        categorySourceNull: Boolean,
    ) {
        db.query(
            """
            SELECT amountMinor, merchant, status, categorySource
            FROM transactions WHERE id = '$id'
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(amountMinor)
            assertThat(cursor.getString(1)).isEqualTo(merchant)
            assertThat(cursor.getString(2)).isEqualTo(status)
            if (categorySourceNull) {
                assertThat(cursor.isNull(3)).isTrue()
            } else {
                assertThat(cursor.getString(3)).isEqualTo("RULE_EXACT")
            }
        }
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Long {
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            return cursor.getLong(0)
        }
    }
}
