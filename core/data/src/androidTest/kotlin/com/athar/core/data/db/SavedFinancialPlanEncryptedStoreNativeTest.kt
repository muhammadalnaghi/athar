package com.athar.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.data.planning.JsonReaderSavedFinancialPlanPayloadGuard
import com.athar.core.data.planning.RoomSavedFinancialPlanTransactionRunner
import com.athar.core.data.planning.SavedFinancialPlanRepositoryImpl
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WM028 fast-store native SOURCE: the saved-plan boundary on a REAL encrypted
 * Room store. The database is created at v10 and migrated 10 -> 11 through the
 * shipped [AtharDatabase.MIGRATION_10_11] with every statement flowing through
 * the real SQLCipher helper factory, then the production builder opens the same
 * migrated file so the repository (real Room transaction runner included)
 * saves, CAS-updates, reopens and reloads exact decimal strings and literal
 * currencies. Parent executes this source on a device; it is a compile+run
 * contract, not a JVM substitute.
 */
@RunWith(AndroidJUnit4::class)
class SavedFinancialPlanEncryptedStoreNativeTest {

    /** Test-local raw key; production key handling (Keystore) is not touched. */
    private val key = ByteArray(32) { (it + 1).toByte() }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AtharDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(key),
    )

    @Before
    fun loadSqlCipher() {
        // sqlcipher-android 4.6.x ships no auto System.loadLibrary (see DataModule).
        System.loadLibrary("sqlcipher")
    }

    @Test
    fun encrypted10To11MigrationPreservesIncumbentRowsAndPersistsStrictDecimalsAcrossReopen() {
        val name = "athar-wm028-saved-plan-encrypted-mig"
        val document = """{"target":"17500.000","monthlyAmount":"340.750","note":"ر.س"}"""
        val updatedDocument = """{"target":"18250.250","monthlyAmount":"410.125","note":"ر.س"}"""
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. A real v10 database, encrypted through the same factory production uses.
        val v10 = helper.createDatabase(name, 10)
        v10.execSQL(
            """
            INSERT INTO account (
                id, name, type, currency, openingBalanceMinor, openingBalanceCurrency,
                smsSenders, notes, sortOrder, active, archivedAt, createdAt, updatedAt
            ) VALUES ('cash-10', 'Cash', 'CASH', 'SAR', 12500, 'SAR', '[]', NULL, 0, 1, NULL, 10, 10)
            """.trimIndent(),
        )
        v10.close()

        // 2. Real 10 -> 11 migration, validated against the exported v11 schema.
        val migrated = helper.runMigrationsAndValidate(
            name,
            11,
            true,
            AtharDatabase.MIGRATION_10_11,
        )
        migrated.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='saved_financial_plan'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        migrated.close()

        // 3. Production-shaped encrypted open of the SAME migrated file, and a
        //    real repository save through the real Room transaction runner.
        val created = runBlocking {
            val db = atharDatabaseBuilder(context, name)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
            try {
                val repo = SavedFinancialPlanRepositoryImpl(
                    db.savedFinancialPlanDao(),
                    Clock.System,
                    RoomSavedFinancialPlanTransactionRunner(db),
                    JsonReaderSavedFinancialPlanPayloadGuard,
                )
                repo.save(
                    SavedFinancialPlan(
                        id = "goal-native",
                        kind = SavedFinancialPlanKind.GOAL,
                        title = "صندوق الطوارئ",
                        currency = "SAR",
                        payloadVersion = 1,
                        payloadJson = document,
                        revision = 0,
                        createdAtEpochMillis = 0,
                        updatedAtEpochMillis = 0,
                    ),
                    expectedRevision = null,
                )
            } finally {
                db.close()
            }
        }
        assertThat(created.revision).isEqualTo(1)

        runBlocking {
            val db = atharDatabaseBuilder(context, name)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
            try {
                val repo = SavedFinancialPlanRepositoryImpl(
                    db.savedFinancialPlanDao(),
                    Clock.System,
                    RoomSavedFinancialPlanTransactionRunner(db),
                    JsonReaderSavedFinancialPlanPayloadGuard,
                )
                val dao = db.savedFinancialPlanDao()

                // Duplicate create-only save is a typed conflict; storage unchanged.
                val duplicate = runCatching {
                    repo.save(
                        SavedFinancialPlan(
                            id = "goal-native",
                            kind = SavedFinancialPlanKind.GOAL,
                            title = "مكرر",
                            currency = "SAR",
                            payloadVersion = 1,
                            payloadJson = document,
                            revision = 0,
                            createdAtEpochMillis = 0,
                            updatedAtEpochMillis = 0,
                        ),
                        expectedRevision = null,
                    )
                }
                assertThat(duplicate.exceptionOrNull())
                    .isInstanceOf(SavedFinancialPlanConflictException::class.java)

                // Exact-revision CAS update bumps to revision 2.
                val updated = repo.save(
                    SavedFinancialPlan(
                        id = "goal-native",
                        kind = SavedFinancialPlanKind.GOAL,
                        title = "صندوق الطوارئ",
                        currency = "SAR",
                        payloadVersion = 1,
                        payloadJson = updatedDocument,
                        revision = 0,
                        createdAtEpochMillis = 0,
                        updatedAtEpochMillis = 0,
                    ),
                    expectedRevision = created.revision,
                )
                assertThat(updated.revision).isEqualTo(2)
                assertThat(updated.payloadJson).isEqualTo(updatedDocument)

                // The raw ABORT insert (the repository race backstop signature)
                // really raises the SQLite constraint failure on this store.
                val abort = runCatching { dao.insert(dao.getById("goal-native")!!) }
                assertThat(abort.exceptionOrNull())
                    .isInstanceOf(SQLiteConstraintException::class.java)
            } finally {
                db.close()
            }
        }

        // 4. Reopen a third time: persisted truth survives process recreation.
        runBlocking {
            val reopened = atharDatabaseBuilder(context, name)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
            try {
                val reloaded = reopened.savedFinancialPlanDao().getById("goal-native")
                assertThat(reloaded).isNotNull()
                assertThat(reloaded!!.revision).isEqualTo(2)
                assertThat(reloaded.payloadJson).isEqualTo(updatedDocument)
                assertThat(reloaded.currency).isEqualTo("SAR")
                assertThat(reloaded.kind).isEqualTo("GOAL")

                // The incumbent v10 row survived migration and every save.
                reopened.openHelper.writableDatabase.query(
                    "SELECT openingBalanceMinor, currency FROM account WHERE id = 'cash-10'",
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getLong(0)).isEqualTo(12500L)
                    assertThat(cursor.getString(1)).isEqualTo("SAR")
                }
            } finally {
                reopened.close()
            }
        }
    }

    /**
     * A REAL concurrent create race on the production encrypted Room store:
     * two create-only saves of the same id are launched simultaneously, so
     * both run through the production [RoomSavedFinancialPlanTransactionRunner]
     * (real androidx.room.withTransaction) and SQLite itself serializes the
     * writers. Exactly one wins with revision 1, the loser is the typed
     * conflict, and exactly one row exists — the loser never overwrites.
     */
    @Test
    fun concurrentCreateOnEncryptedRoomResolvesExactlyOneWinnerWithoutOverwrite() {
        val name = "athar-wm028-saved-plan-encrypted-race"
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        helper.createDatabase(name, 10).close()
        helper.runMigrationsAndValidate(name, 11, true, AtharDatabase.MIGRATION_10_11).close()

        runBlocking {
            val db = atharDatabaseBuilder(context, name)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
            try {
                val repo = SavedFinancialPlanRepositoryImpl(
                    db.savedFinancialPlanDao(),
                    Clock.System,
                    RoomSavedFinancialPlanTransactionRunner(db),
                    JsonReaderSavedFinancialPlanPayloadGuard,
                )
                val outcomes = coroutineScope {
                    val first = async { runCatching { repo.save(racePlan("first"), expectedRevision = null) } }
                    val second = async { runCatching { repo.save(racePlan("second"), expectedRevision = null) } }
                    listOf(first.await(), second.await())
                }

                val winners = outcomes.filter { it.isSuccess }
                val losers = outcomes.filter { it.isFailure }
                assertThat(winners).hasSize(1)
                assertThat(losers).hasSize(1)
                assertThat(losers.single().exceptionOrNull())
                    .isInstanceOf(SavedFinancialPlanConflictException::class.java)
                val winner = winners.single().getOrThrow()
                assertThat(winner.revision).isEqualTo(1)

                // Exactly one row, holding exactly the winner's write.
                db.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM saved_financial_plan",
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(1)
                }
                val stored = db.savedFinancialPlanDao().getById("race-native")!!
                assertThat(stored.title).isEqualTo(winner.title)
                assertThat(stored.revision).isEqualTo(1)
            } finally {
                db.close()
            }
        }
    }

    /**
     * The PRODUCTION raw-payload guard ([JsonReaderSavedFinancialPlanPayloadGuard],
     * the real android.util.JsonReader token walk) refusing malformed roots,
     * duplicate keys and budget overruns on-device — every refusal BEFORE any
     * statement runs, proven by the row count staying at zero, with a positive
     * control so the refusals are not vacuous.
     */
    @Test
    fun productionGuardRefusesMalformedRootsDuplicateKeysAndBudgetsOnDeviceWithoutWrites() {
        val name = "athar-wm028-saved-plan-encrypted-guard"
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        helper.createDatabase(name, 10).close()
        helper.runMigrationsAndValidate(name, 11, true, AtharDatabase.MIGRATION_10_11).close()

        val deepOverBudget = buildString {
            repeat(13) { append("""{"a":""") }
            append("1")
            repeat(13) { append("}") }
        }
        val memberFlood = buildString {
            append("""{"k0":"1"""")
            for (i in 1 until SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS + 1) {
                append(""","k$i":"1"""")
            }
            append("}")
        }
        val textFlood = """{"note":"${"x".repeat(SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_STRING_CHARS + 1)}"}"""

        val refusals = linkedMapOf(
            "arrayRoot" to """["12.500"]""",
            "stringRoot" to """"42"""",
            "numberRoot" to "7",
            "nullRoot" to "null",
            // NBSP is not Char.isWhitespace, so this passes the cheap blank
            // check and genuinely exercises the guard's EOF root peek.
            "emptyDocument" to "\u00A0",
            "trailingContent" to """{"a":"1"} {"b":"2"}""",
            "duplicateKeys" to """{"amount":"1.000","amount":"2.000"}""",
            "depthOverBudget" to deepOverBudget,
            "membersOverBudget" to memberFlood,
            "textOverBudget" to textFlood,
            "malformedSyntax" to "{not-json",
        )

        runBlocking {
            val db = atharDatabaseBuilder(context, name)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .build()
            try {
                val repo = SavedFinancialPlanRepositoryImpl(
                    db.savedFinancialPlanDao(),
                    Clock.System,
                    RoomSavedFinancialPlanTransactionRunner(db),
                    JsonReaderSavedFinancialPlanPayloadGuard,
                )
                refusals.forEach { (case, payload) ->
                    val outcome = runCatching {
                        repo.save(racePlan(case, payloadJson = payload), expectedRevision = null)
                    }
                    assertThat(outcome.exceptionOrNull())
                        .isInstanceOf(SavedFinancialPlanValidationException::class.java)
                }

                // No refusal ever reached storage.
                db.openHelper.writableDatabase.query(
                    "SELECT COUNT(*) FROM saved_financial_plan",
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getInt(0)).isEqualTo(0)
                }

                // Positive control: a well-formed object document still saves.
                val saved = repo.save(racePlan("control"), expectedRevision = null)
                assertThat(saved.revision).isEqualTo(1)
            } finally {
                db.close()
            }
        }
    }

    private fun racePlan(
        title: String,
        id: String = "race-native",
        payloadJson: String = """{"target":"17500.000","note":"ر.س"}""",
    ): SavedFinancialPlan = SavedFinancialPlan(
        id = id,
        kind = SavedFinancialPlanKind.GOAL,
        title = title,
        currency = "SAR",
        payloadVersion = 1,
        payloadJson = payloadJson,
        revision = 0,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )
}
