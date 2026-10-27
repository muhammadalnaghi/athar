package com.athar.core.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WM028-13B0/B1/B2a regression: an unsupported downgrade against the PRODUCTION Room
 * configuration must be refused without erasing anything.
 *
 * Sandbox discipline: a uniquely named throwaway database is created through
 * MigrationTestHelper at the current schema (10) — real, current Room
 * schema, not a test string builder — then its user_version is bumped to a
 * hypothetical future 11. `athar.db` and any real installation data are never
 * touched; the only delete here is this sandbox name at test start for rerun
 * isolation.
 */
@RunWith(AndroidJUnit4::class)
class AtharDatabaseDowngradeRefusalTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtharDatabase::class.java,
        emptyList(),
    )

    @Test
    fun productionConfigurationRefusesDowngradeAndPreservesSentinelRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sandboxName = "athar-wm028-13b2a-downgrade-sandbox"
        context.deleteDatabase(sandboxName)

        val sentinel = helper.createDatabase(sandboxName, 10)
        sentinel.execSQL(
            """
            INSERT INTO account (
                id, name, type, currency, openingBalanceMinor, openingBalanceCurrency,
                smsSenders, notes, sortOrder, active, archivedAt, createdAt, updatedAt
            ) VALUES (
                'downgrade-sentinel', 'Sandbox Cash', 'CASH', 'SAR', 4242, 'SAR',
                '[]', NULL, 0, 1, NULL, 1, 1
            )
            """.trimIndent(),
        )
        // Simulate a database file left behind by a hypothetical future schema 11.
        sentinel.execSQL("PRAGMA user_version = 11")
        sentinel.close()

        // Open through the exact production configuration (migrations through10, no
        // destructive fallback). Room must refuse the 11 → 10 downgrade.
        val productionDatabase = atharDatabaseBuilder(context, sandboxName).build()
        val refusal = runCatching { productionDatabase.openHelper.writableDatabase }.exceptionOrNull()
        productionDatabase.close()

        assertThat(refusal).isInstanceOf(IllegalStateException::class.java)
        assertThat(refusal).hasMessageThat().contains("migration")
        assertThat(refusal).hasMessageThat().contains("11 to 10")

        // Nothing was dropped or rewritten: the sentinel row and the bumped
        // user_version both survive, inspected with plain SQLite afterwards.
        val rawDatabase = SQLiteDatabase.openDatabase(
            context.getDatabasePath(sandboxName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            rawDatabase.rawQuery(
                "SELECT openingBalanceMinor FROM account WHERE id = 'downgrade-sentinel'",
                null,
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(4242L)
            }
            rawDatabase.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(11)
            }
        } finally {
            rawDatabase.close()
        }
    }
}
