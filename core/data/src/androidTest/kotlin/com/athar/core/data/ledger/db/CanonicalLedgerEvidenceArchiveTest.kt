package com.athar.core.data.ledger.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Low-level storage tests, not codec/hash/admission or atomic repository acceptance. */
@RunWith(AndroidJUnit4::class)
class CanonicalLedgerEvidenceArchiveTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AtharDatabase::class.java, emptyList(),
    )

    @Test
    fun migration9To10PreservesExistingRowsWithoutInventingSourceArchives() {
        val name = "athar-wm028-13b2a-migration-9-to-10"
        val old = helper.createDatabase(name, 9)
        insertSentinels(old)
        old.execSQL("INSERT INTO canonical_ledger_revision (id, revision) VALUES ('singleton', 17)")
        val before = preservedTables.associateWith { contents(old, it) }
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 10, true, AtharDatabase.MIGRATION_9_10)
        try {
            assertThat(preservedTables.associateWith { contents(migrated, it) }).isEqualTo(before)
            assertThat(contents(migrated, "canonical_ledger_observation_snapshot")).isEmpty()
            assertThat(contents(migrated, "canonical_ledger_conflict")).isEmpty()
        } finally {
            migrated.close()
        }
    }

    @Test
    fun variantsAndConflictsRoundTripWithIndependentConstraintsAndNoReplacement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "athar-wm028-13b2a-archive-constraints"
        context.deleteDatabase(name)
        val database = atharDatabaseBuilder(context, name).build()
        try {
            val raw = database.openHelper.writableDatabase
            insertSentinels(raw)
            val dao = database.canonicalEvidenceDao()
            val bytesA = byteArrayOf(0, 1, 127, -128, -1)
            val first = CanonicalObservationSnapshotEntity(
                "sms", "obs-a", "payload-a", "snapshot-a", bytesA, "{\"raw\":\"first\"}",
            )
            val second = CanonicalObservationSnapshotEntity(
                "sms", "obs-a", "payload-b", "snapshot-b", byteArrayOf(10, 13, 0), "{\"raw\":\"second\"}",
            )
            dao.insertSnapshot(first)
            dao.insertSnapshot(second)
            val variants = dao.listSnapshots("sms", "obs-a")
            assertThat(variants.map { it.snapshotJson }).containsExactly(first.snapshotJson, second.snapshotJson)
            assertThat(variants.first { it.sourcePayloadDigest == "payload-a" }.sourcePayload.toList())
                .isEqualTo(bytesA.toList())
            assertThat(runCatching { dao.insertSnapshot(first.copy(sourcePayload = byteArrayOf(99))) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(dao.listSnapshots("sms", "obs-a").first { it.sourcePayloadDigest == "payload-a" }.sourcePayload.toList())
                .isEqualTo(bytesA.toList())
            // All other parents are valid: this rejection proves the observation FK.
            assertThat(runCatching { dao.insertSnapshot(first.copy(sourceId = "missing-observation")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)

            val conflict = CanonicalLedgerConflictEntity(
                "conflict-a", "sms", "obs-a", "payload-b", "snapshot-b", "event-a",
                "SOURCE_PAYLOAD_CHANGED", "{\"detail\":\"retained\"}",
            )
            dao.insertConflict(conflict)
            assertThat(dao.getConflict("conflict-a")).isEqualTo(conflict)
            assertThat(runCatching { dao.insertConflict(conflict.copy(reasonCode = "OTHER")) }.exceptionOrNull())
                .isInstanceOf(SQLiteConstraintException::class.java)
            assertThat(dao.getConflict("conflict-a")!!.reasonCode).isEqualTo("SOURCE_PAYLOAD_CHANGED")
            // Existing event is valid, but this exact archived variant is absent.
            assertThat(runCatching {
                dao.insertConflict(conflict.copy(conflictId = "missing-variant", snapshotDigest = "absent"))
            }.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
            // Exact archived variant is valid, isolating the existing-event FK.
            assertThat(runCatching {
                dao.insertConflict(conflict.copy(conflictId = "missing-event", existingEventId = "absent"))
            }.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
            dao.insertConflict(conflict.copy(conflictId = "no-existing-event", existingEventId = null))
            assertThat(dao.listConflicts("sms", "obs-a")).hasSize(2)
            assertThat(runCatching {
                raw.execSQL("DELETE FROM canonical_ledger_observation_snapshot WHERE sourcePayloadDigest = 'payload-b'")
            }.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
            // Neither conflicts nor archived variants create financial events/revisions.
            assertThat(contents(raw, "canonical_ledger_event")).hasSize(1)
            assertThat(database.canonicalLedgerDao().getRevision("singleton")!!.revision).isEqualTo(0L)
        } finally {
            database.close()
        }
    }

    private fun insertSentinels(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO account (
                id, name, type, currency, openingBalanceMinor, openingBalanceCurrency,
                smsSenders, notes, sortOrder, active, archivedAt, createdAt, updatedAt
            ) VALUES ('archive-cash', 'Cash', 'CASH', 'SAR', 12345, 'SAR', '[]', NULL, 0, 1, NULL, 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO transactions (
                id, accountId, type, amountMinor, currency, date, occurredAt, merchant,
                merchantNormalized, categoryId, notes, source, sourceRefId, status,
                confidence, categorySource, createdAt, updatedAt
            ) VALUES (
                'archive-legacy', 'archive-cash', 'EXPENSE', 999, 'SAR', '2026-01-01', NULL,
                'Legacy', 'legacy', NULL, NULL, 'MANUAL', NULL, 'DISMISSED', NULL, NULL, 1, 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO canonical_ledger_event (
                eventId, economicIdentityIssuer, economicIdentityReference, snapshotFormatVersion,
                policyRevision, graphRevision, snapshotJson, bookedAmount, bookedCurrency,
                bookedBasis, kind, kindResolved, incomeNature, recordingOutcome, eventDate, routing, composition
            ) VALUES (
                'event-a', NULL, NULL, 1, 'policy-original', 'graph-original', '{"retained":true}',
                '200.00', 'SAR', 'SETTLEMENT', 'PURCHASE', 1, 'UNCLASSIFIED', 'RECORDED',
                '2026-01-01', 'ROUTED', 'RESOLVED'
            )
            """.trimIndent(),
        )
        db.execSQL("INSERT INTO canonical_ledger_source_observation VALUES ('sms', 'obs-a', 'evidence-original', NULL)")
        db.execSQL("INSERT INTO canonical_ledger_event_source VALUES ('sms', 'obs-a', 'event-a')")
        db.execSQL("INSERT INTO canonical_ledger_component VALUES ('event-a', 'component-a', 'FEE', '1.00', 'SAR', 'UNKNOWN', NULL)")
        db.execSQL("INSERT INTO canonical_ledger_leg VALUES ('event-a', 'leg-a', 'archive-cash', '-200.00', 'SAR', 'component-a')")
    }

    private fun contents(db: SupportSQLiteDatabase, table: String): List<List<String?>> =
        db.query("SELECT * FROM `$table` ORDER BY 1").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { index ->
                        if (cursor.isNull(index)) null else "${cursor.getType(index)}:${cursor.getString(index)}"
                    })
                }
            }
        }

    private companion object {
        val preservedTables = listOf(
            "account", "transactions", "canonical_ledger_event", "canonical_ledger_source_observation",
            "canonical_ledger_event_source", "canonical_ledger_component", "canonical_ledger_leg", "canonical_ledger_revision",
        )
    }
}
