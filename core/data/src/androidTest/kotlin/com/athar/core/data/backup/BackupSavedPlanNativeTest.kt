package com.athar.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.common.crypto.AtharCrypto
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.data.db.entity.InvestmentContributionEntity
import com.athar.core.data.db.entity.InvestmentPoolEntity
import com.athar.core.data.db.entity.SmsMessageEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventEntity
import com.athar.core.data.ledger.db.CanonicalLedgerEventSourceEntity
import com.athar.core.data.ledger.db.CanonicalLedgerComponentEntity
import com.athar.core.data.ledger.db.CanonicalLedgerLegEntity
import com.athar.core.data.ledger.db.CanonicalLedgerSourceObservationEntity
import com.athar.core.data.ledger.db.CanonicalLedgerConflictEntity
import com.athar.core.data.ledger.db.CanonicalObservationSnapshotEntity
import com.athar.core.data.planning.SavedFinancialPlanEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Real encrypted SQLCipher regressions for the complete 21-table v4 envelope on
 * synthetic data only: saved financial plans must survive a genuine
 * capture → AES-256-GCM → authenticated restore round trip byte-exactly
 * (opaque versioned documents, original decimal strings, literal currencies,
 * revisions and epoch-millis times), and a legacy v1–v3 archive must refuse a
 * plan-only live target inside the restore transaction without touching a row.
 */
@RunWith(AndroidJUnit4::class)
class BackupSavedPlanNativeTest {
    private val at = Instant.parse("2026-06-13T00:00:00Z")
    private val passphrase = "synthetic-saved-plan-backup-028".toCharArray()

    @Before
    fun loadSqlCipher() {
        System.loadLibrary("sqlcipher")
    }

    private fun newDb(role: String) = atharDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), "synthetic-backup-plan-$role-${UUID.randomUUID()}.db",
    ).openHelperFactory(SupportOpenHelperFactory(ByteArray(32) { (it + 1).toByte() }))
        .allowMainThreadQueries()
        .build()
        .also(::assertEncryptedDatabase)

    private fun assertEncryptedDatabase(db: AtharDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA cipher_version").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isNotEmpty()
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath(requireNotNull(db.openHelper.databaseName))
        val header = ByteArray(16)
        java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
        assertThat(header).isNotEqualTo("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
    }

    private fun service(db: AtharDatabase) = BackupService(db, Clock.System)

    private fun plan(id: String, kind: String, currency: String, revision: Long) = SavedFinancialPlanEntity(
        id = id, kind = kind, title = " خطة $id\r\n ", currency = currency,
        payloadVersion = 4,
        payloadJson = " \r\n{\"doc\":{\"amount\":\"001.2300\",\"rate\":\"1E+3\",\"opaque\":true},\"v\":4}\t ",
        revision = revision, createdAtEpochMillis = 1_700_000_000_000L, updatedAtEpochMillis = 1_700_000_001_000L,
    )

    private suspend fun seedLegacy(db: AtharDatabase, suffix: String) {
        db.accountDao().upsert(AccountEntity(
            id = "acc-$suffix", name = "Cash $suffix", type = "CASH", currency = "SAR",
            openingBalanceMinor = 1L, openingBalanceCurrency = "SAR", smsSenders = "",
            notes = null, sortOrder = 0, active = true, archivedAt = null, createdAt = at, updatedAt = at,
        ))
        db.categoryDao().upsertAll(listOf(CategoryEntity(
            id = "cat-$suffix", name = "Food $suffix", nameAr = "طعام", kind = "EXPENSE",
            icon = null, monthlyTargetMinor = null, currency = "SAR", archived = false, sortOrder = 0,
        )))
        db.transactionDao().upsert(TransactionEntity(
            id = "tx-$suffix", accountId = "acc-$suffix", type = "EXPENSE", amountMinor = 2300L,
            currency = "SAR", date = LocalDate.parse("2026-06-13"), occurredAt = null,
            merchant = "Raw $suffix", merchantNormalized = "raw", categoryId = "cat-$suffix",
            notes = null, source = "MANUAL", sourceRefId = null, status = "CONFIRMED", confidence = null,
            createdAt = at, updatedAt = at, categorySource = null,
        ))
        db.categoryRuleDao().upsert(CategoryRuleEntity(
            id = "rule-$suffix", pattern = "store", patternType = "CONTAINS",
            categoryId = "cat-$suffix", priority = 1, learnedFromUser = false, createdAt = at,
        ))
        db.investmentDao().upsertPool(InvestmentPoolEntity(
            id = "pool-$suffix", name = "Pool", period = "MONTHLY", totalReturnMinor = 0L, currency = "SAR",
        ))
        db.investmentDao().upsertContribution(InvestmentContributionEntity(
            id = "con-$suffix", poolId = "pool-$suffix", ownerName = "Owner", amountMinor = 500L, currency = "SAR",
        ))
        db.smsMessageDao().upsert(SmsMessageEntity(
            id = "sms-$suffix", sender = "BANK", body = "synthetic", receivedAt = at,
            parsedTransactionId = null, parseStatus = "UNPARSED", parseError = null,
        ))
        db.transactionReceiptDao().upsert(TransactionReceiptEntity(
            id = "receipt-$suffix", transactionId = "tx-$suffix", mimeType = "image/png",
            originalName = null, sizeBytes = 4L, payload = byteArrayOf(1, 2, 3, 4), createdAt = at,
        ))
    }

    /** Opaque historical canonical graph: transport representation, not certified admission. */
    private suspend fun seedCanonical(db: AtharDatabase, accountRef: String) {
        val dao = db.canonicalLedgerDao()
        dao.insertEvent(CanonicalLedgerEventEntity(
            eventId = "sp-event-1", economicIdentityIssuer = "issuer", economicIdentityReference = "ref",
            snapshotFormatVersion = 1L, policyRevision = "p", graphRevision = "g",
            snapshotJson = " \r\n{\"opaque\":\"0001.2300\"}\t ", bookedAmount = "0001.2300",
            bookedCurrency = "SAR", bookedBasis = "ORIGINAL", kind = "FUTURE_KIND",
            kindResolved = false, incomeNature = "UNCLASSIFIED",
            recordingOutcome = "CAPTURED_UNRESOLVED_KIND", eventDate = "2026-01-02",
            routing = "UNALLOCATED", composition = "FUTURE_COMPOSITION",
        ))
        dao.insertObservation(CanonicalLedgerSourceObservationEntity("synthetic-sms", "sp-src", "ev", "pd"))
        dao.insertEventSource(CanonicalLedgerEventSourceEntity("synthetic-sms", "sp-src", "sp-event-1"))
        dao.insertComponent(CanonicalLedgerComponentEntity("sp-event-1", "cmp", "FUTURE_COMPONENT", "1E+3", "KWD", "UNKNOWN", "fee"))
        dao.insertLeg(CanonicalLedgerLegEntity("sp-event-1", "leg", accountRef, "-0001.2300", "KWD", "cmp"))
        dao.insertSnapshotVariant(CanonicalObservationSnapshotEntity(
            "synthetic-sms", "sp-src", "pd", "sd",
            byteArrayOf(3, 1, 4, 1, 5), " \r\n{\"opaque\":\"0001.2300\"}\t ",
        ))
        dao.insertConflict(CanonicalLedgerConflictEntity(
            "sp-conflict", "synthetic-sms", "sp-src", "pd", "sd", "sp-event-1", "FUTURE_REASON", " {\"d\":1}\r\n ",
        ))
        // The schema-seeded singleton (0) is live state: advance it by update, never a second row.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE canonical_ledger_revision SET revision = 9 WHERE id = 'singleton'",
        )
    }

    /** Include every field of every table; rejection checks also include the live epoch. */
    private suspend fun evidence(db: AtharDatabase): Map<String, List<List<Any?>>> =
        BackupNativeStateEvidence.capture(db)

    private fun savedPlanOnlyEnvelope(version: Int): ByteArray {
        val legacy = """{"version":$version,"exportedAt":"2026-06-13T00:00:00Z",
            "accounts":[],"categories":[],"transactions":[],"categoryRules":[],"wishlist":[],
            "investmentPools":[],"investmentContributions":[],"smsAudit":[],"receiptAttachments":[]}"""
        val gzipped = ByteArrayOutputStream().use { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(legacy.toByteArray(Charsets.UTF_8)) }
            out.toByteArray()
        }
        return AtharCrypto.encrypt(gzipped, passphrase)
    }

    private fun dispose(db: AtharDatabase) {
        val name = db.openHelper.databaseName
        db.close()
        if (name != null) {
            check(name.startsWith("synthetic-backup-plan-"))
            check(ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name))
        }
    }

    @Test
    fun planOnlyLegacyTargetRefusesInsideRestoreTransactionAndKeepsPlansByteExact() = runBlocking<Unit> {
        val db = newDb("legacy-refusal")
        try {
            db.savedFinancialPlanDao().insertAll(listOf(
                plan("only-plan-1", "GOAL", "KWD", 3L),
                plan("only-plan-2", "DEBT", "USD", Long.MAX_VALUE),
            ))
            assertThat(db.savedFinancialPlanDao().count()).isEqualTo(2L)
            assertThat(db.canonicalLedgerDao().countCanonicalLedgerDataRows()).isEqualTo(0L)
            assertThat(db.activityLogDao().count()).isEqualTo(0L)
            assertThat(db.userTemplateDao().count()).isEqualTo(0L)
            assertThat(db.recurringRuleDao().count()).isEqualTo(0L)
            val before = evidence(db)
            (1..3).forEach { version ->
                val error = runCatching { service(db).import(savedPlanOnlyEnvelope(version), passphrase) }
                    .exceptionOrNull()
                assertThat(error).isInstanceOf(BackupValidationException::class.java)
                assertThat((error as BackupValidationException).code)
                    .isEqualTo(BackupValidationCode.AUXILIARY_DATA_PRESENT)
                assertThat(evidence(db)).isEqualTo(before)
                assertThat(db.savedFinancialPlanDao().count()).isEqualTo(2L)
            }
        } finally {
            dispose(db)
        }
    }

    @Test
    fun encryptedCaptureRestoreRoundTripsSavedPlansExactlyAmongAllTwentyOneTables() = runBlocking<Unit> {
        val source = newDb("source")
        val target = newDb("target")
        try {
            seedLegacy(source, "s")
            seedCanonical(source, "acc-s")
            BackupAuxiliaryNativeFixtures.seed(source, "s", "acc-s", "cat-s")
            source.savedFinancialPlanDao().insertAll(listOf(
                plan("sp-goal", "GOAL", "KWD", 1L),
                plan("sp-cash", "CASH", "USD", Long.MAX_VALUE),
                plan("sp-portfolio", "PORTFOLIO", "SAR", 0L),
            ))
            val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

            // Conflicting pre-existing target state proves whole replace, not merge.
            seedLegacy(target, "t")
            BackupAuxiliaryNativeFixtures.seed(target, "t", "acc-t", "cat-t")
            target.savedFinancialPlanDao().insertAll(listOf(
                plan("stale-plan", "GOAL", "SAR", 99L),
                plan("sp-goal", "GOAL", "SAR", 12345L),
            ))
            service(target).import(bytes, passphrase)

            val sourceState = evidence(source)
            val targetState = evidence(target)
            assertThat(targetState.keys).containsExactlyElementsIn(sourceState.keys)
            assertThat(sourceState).hasSize(21)
            assertThat(sourceState.values.all { it.isNotEmpty() }).isTrue()
            // The singleton is intentionally advanced, not restored byte-for-byte.
            // Every other field in all 21 tables must be exactly preserved.
            assertThat(targetState - "canonicalRevisions").isEqualTo(sourceState - "canonicalRevisions")
            val sourceOtherRevisions = sourceState.getValue("canonicalRevisions").filter { it[0] != "singleton" }
            val targetOtherRevisions = targetState.getValue("canonicalRevisions").filter { it[0] != "singleton" }
            assertThat(targetOtherRevisions).isEqualTo(sourceOtherRevisions)
            // The imported singleton strictly advances past both sides and never rolls back.
            val targetSingleton = target.canonicalLedgerDao().captureRevisions().single { it.id == "singleton" }
            val sourceSingleton = source.canonicalLedgerDao().captureRevisions().single { it.id == "singleton" }
            assertThat(targetSingleton.revision).isGreaterThan(sourceSingleton.revision)
            // Explicit saved-plan field evidence, not just structural equality.
            val restored = target.savedFinancialPlanDao().all().sortedBy { it.id }
            assertThat(restored.map { it.id })
                .containsExactly("aux-plan-s", "sp-cash", "sp-goal", "sp-portfolio")
            val goal = restored.first { it.id == "sp-goal" }
            assertThat(goal.currency).isEqualTo("KWD")
            assertThat(goal.revision).isEqualTo(1L)
            assertThat(goal.payloadJson.toByteArray())
                .isEqualTo(" \r\n{\"doc\":{\"amount\":\"001.2300\",\"rate\":\"1E+3\",\"opaque\":true},\"v\":4}\t ".toByteArray())
            assertThat(goal.createdAtEpochMillis).isEqualTo(1_700_000_000_000L)
            assertThat(goal.updatedAtEpochMillis).isEqualTo(1_700_000_001_000L)
            assertThat(target.savedFinancialPlanDao().getById("stale-plan")).isNull()
        } finally {
            dispose(source)
            dispose(target)
        }
    }

    @Test
    fun completeV4ReplaceOverPlanOnlyTargetSubstitutesPlansWithoutStaleRows() = runBlocking<Unit> {
        val source = newDb("v4-source")
        val target = newDb("v4-target")
        try {
            seedLegacy(source, "vs")
            BackupAuxiliaryNativeFixtures.seed(source, "vs", "acc-vs", "cat-vs")
            source.savedFinancialPlanDao().insert(plan("fresh-plan", "GOAL", "KWD", 8L))
            val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()

            target.savedFinancialPlanDao().insertAll(listOf(
                plan("old-plan", "CASH", "USD", 1L),
                plan("fresh-plan", "DEBT", "USD", 77L),
            ))
            service(target).import(bytes, passphrase)

            val ids = target.savedFinancialPlanDao().all().map { it.id }.sorted()
            assertThat(ids).containsExactly("aux-plan-vs", "fresh-plan")
            val fresh = target.savedFinancialPlanDao().getById("fresh-plan")!!
            assertThat(fresh.revision).isEqualTo(8L)
            assertThat(fresh.currency).isEqualTo("KWD")
            assertThat(target.canonicalLedgerDao().countCanonicalLedgerDataRows()).isEqualTo(0L)
        } finally {
            dispose(source)
            dispose(target)
        }
    }
}
