package com.athar.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.common.crypto.AtharCrypto
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Synthetic production-Room regressions. No real records, no admission or activation claim. */
@RunWith(AndroidJUnit4::class)
class BackupAuxiliaryTablesNativeTest {
    private val at = Instant.parse("2026-06-13T00:00:00Z")
    private val passphrase = "synthetic-auxiliary-backup-028".toCharArray()
    private fun newDb() = atharDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), "synthetic-backup-aux-${UUID.randomUUID()}.db",
    ).allowMainThreadQueries().build()
    private fun service(db: AtharDatabase) = BackupService(db, Clock.System)

    private suspend fun seed(db: AtharDatabase, suffix: String, logs: Int) {
        val account = "aux-account-$suffix"
        val category = "aux-category-$suffix"
        db.accountDao().upsert(AccountEntity(
            id = account, name = "Cash $suffix", type = "CASH", currency = "SAR",
            openingBalanceMinor = 10000L, openingBalanceCurrency = "SAR", smsSenders = "",
            notes = null, sortOrder = 0, active = true, archivedAt = null, createdAt = at, updatedAt = at,
        ))
        db.categoryDao().upsertAll(listOf(CategoryEntity(
            id = category, name = "Food $suffix", nameAr = "طعام", kind = "EXPENSE",
            icon = null, monthlyTargetMinor = null, currency = "SAR", archived = false, sortOrder = 0,
        )))
        db.transactionDao().upsert(TransactionEntity(
            id = "aux-tx-$suffix", accountId = account, type = "EXPENSE", amountMinor = 125L,
            currency = "SAR", date = LocalDate.parse("2026-06-13"), occurredAt = null,
            merchant = "Raw $suffix", merchantNormalized = "raw", categoryId = category,
            notes = null, source = "MANUAL", sourceRefId = null, status = "CONFIRMED", confidence = null,
            createdAt = at, updatedAt = at, categorySource = "USER",
        ))
        db.transactionReceiptDao().upsert(TransactionReceiptEntity(
            id = "aux-receipt-$suffix", transactionId = "aux-tx-$suffix", mimeType = "image/png",
            originalName = "opaque-$suffix", sizeBytes = 4L, payload = byteArrayOf(0, 1, 2, 3), createdAt = at,
        ))
        BackupAuxiliaryNativeFixtures.seed(db, suffix, account, category, logs)
    }

    private suspend fun preserved(db: AtharDatabase): Map<String, Any> = mapOf(
        "accounts" to db.accountDao().all(),
        "categories" to db.categoryDao().all(),
        "transactions" to db.transactionDao().all(),
        "wishlist" to db.wishlistDao().all(),
        "receipts" to db.transactionReceiptDao().all().map {
            listOf(it.id, it.transactionId, it.mimeType, it.originalName, it.sizeBytes, it.payload.toList(), it.createdAt)
        },
        "revisions" to db.canonicalLedgerDao().captureRevisions(),
    ) + BackupAuxiliaryNativeFixtures.evidence(db)

    private fun dispose(db: AtharDatabase) {
        val name = db.openHelper.databaseName
        db.close()
        if (name != null) {
            check(name.startsWith("synthetic-backup-aux-"))
            check(ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name))
        }
    }

    @Test
    fun legacyArchiveCannotReplaceAuxiliaryDataOrDeleteItsLinkedRows() = runBlocking<Unit> {
        val legacy = """{"version":3,"exportedAt":"2026-06-13T00:00:00Z",
            "accounts":[],"categories":[],"transactions":[],"categoryRules":[],"wishlist":[],
            "investmentPools":[],"investmentContributions":[],"smsAudit":[],"receiptAttachments":[]}"""
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(legacy.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val encrypted = AtharCrypto.encrypt(compressed, passphrase)
        // Isolate every table so a working activity-log count cannot conceal a missing sibling check.
        listOf("all", "logs", "templates", "rules", "plans").forEach { retained ->
            val db = newDb()
            try {
                seed(db, "old", 2)
                if (retained != "all") {
                    if (retained != "logs") db.activityLogDao().clear()
                    if (retained != "templates") db.userTemplateDao().clear()
                    if (retained != "rules") db.recurringRuleDao().clear()
                    if (retained != "plans") db.savedFinancialPlanDao().clear()
                }
                val counts = listOf(
                    db.activityLogDao().count(), db.userTemplateDao().count(),
                    db.recurringRuleDao().count(), db.savedFinancialPlanDao().count(),
                )
                assertThat(counts.count { it > 0L }).isEqualTo(if (retained == "all") 4 else 1)
                assertThat(db.canonicalLedgerDao().countCanonicalLedgerDataRows()).isEqualTo(0L)
                val before = preserved(db)
                val error = runCatching { service(db).import(encrypted, passphrase) }.exceptionOrNull()
                val after = preserved(db)
                assertThat(error).isInstanceOf(BackupValidationException::class.java)
                assertThat((error as BackupValidationException).code).isEqualTo(BackupValidationCode.AUXILIARY_DATA_PRESENT)
                assertThat(after).isEqualTo(before)
            } finally {
                dispose(db)
            }
        }
    }

    @Test
    fun captureRestoresMoreThanOneHundredLogsAndInactiveRulesExactly() = runBlocking<Unit> {
        val source = newDb()
        val target = newDb()
        try {
            seed(source, "source", 105)
            seed(target, "target", 2)
            assertThat(source.activityLogDao().count()).isEqualTo(105L)
            assertThat(source.recurringRuleDao().all().single().isActive).isFalse()
            val bytes = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()
            service(target).import(bytes, passphrase)
            assertThat(BackupAuxiliaryNativeFixtures.evidence(target))
                .isEqualTo(BackupAuxiliaryNativeFixtures.evidence(source))
            assertThat(target.activityLogDao().count()).isEqualTo(105L)
            assertThat(target.recurringRuleDao().all().single().isActive).isFalse()
            assertThat(target.wishlistDao().all()).isEqualTo(source.wishlistDao().all())
        } finally {
            dispose(source)
            dispose(target)
        }
    }
}
