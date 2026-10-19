package com.athar.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtharDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtharDatabase::class.java,
        emptyList(),
    )

    @Test
    fun migration7To8PreservesLegacyTransactionAndAddsNullableProvenance() {
        val databaseName = "athar-v7-to-v8-preservation"
        val oldDatabase = helper.createDatabase(databaseName, 7)
        oldDatabase.execSQL(
            """
            INSERT INTO account (
                id, name, type, currency, openingBalanceMinor, openingBalanceCurrency,
                smsSenders, notes, sortOrder, active, archivedAt, createdAt, updatedAt
            ) VALUES ('cash', 'Cash', 'CASH', 'SAR', 0, 'SAR', '[]', NULL, 0, 1, NULL, 1, 1)
            """.trimIndent(),
        )
        oldDatabase.execSQL(
            """
            INSERT INTO transactions (
                id, accountId, type, amountMinor, currency, date, occurredAt, merchant,
                merchantNormalized, categoryId, notes, source, sourceRefId, status,
                confidence, createdAt, updatedAt
            ) VALUES (
                'legacy-tx', 'cash', 'EXPENSE', 1234, 'SAR', '2026-01-01', NULL,
                'Legacy Shop', 'legacy shop', NULL, NULL, 'MANUAL', NULL,
                'CONFIRMED', NULL, 1, 1
            )
            """.trimIndent(),
        )
        oldDatabase.close()

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            AtharDatabase.MIGRATION_7_8,
        )
        val cursor = migrated.query(
            "SELECT amountMinor, merchant, categorySource FROM transactions WHERE id = 'legacy-tx'",
        )
        try {
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1234L)
            assertThat(cursor.getString(1)).isEqualTo("Legacy Shop")
            assertThat(cursor.isNull(2)).isTrue()
        } finally {
            cursor.close()
            migrated.close()
        }
    }
}
