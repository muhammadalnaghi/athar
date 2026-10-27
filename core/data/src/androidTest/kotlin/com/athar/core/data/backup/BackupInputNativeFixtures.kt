package com.athar.core.data.backup

import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import com.athar.core.common.crypto.AtharCrypto
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Shared real-Room synthetic setup and raw21table evidence, never production data. */
internal object BackupInputNativeFixtures {
    val at = Instant.parse("2026-06-13T00:00:00Z")
    val passphrase get() = "synthetic-input-boundary-028".toCharArray()
    val envelope = """{"version":4,"exportedAt":"2026-06-13T00:00:00Z",
        "accounts":[],"categories":[],"transactions":[],"categoryRules":[],"wishlist":[],
        "investmentPools":[],"investmentContributions":[],"smsAudit":[],"receiptAttachments":[],
        "canonicalLedger":{"events":[],"observations":[],"associations":[],"components":[],
        "legs":[],"revisions":[],"snapshots":[],"conflicts":[]},
        "auxiliaryTables":{"activityLog":[],"userTemplates":[],"recurringRules":[],"savedPlans":[]}}""".trimIndent()

    fun newDb() = atharDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), "synthetic-backup-input-${UUID.randomUUID()}.db",
    ).allowMainThreadQueries().build()

    suspend fun seed(db: AtharDatabase) {
        db.accountDao().upsert(AccountEntity(
            id = "a", name = "Synthetic cash", type = "CASH", currency = "SAR", smsSenders = "",
            active = true, createdAt = at, openingBalanceMinor = 123L, openingBalanceCurrency = "SAR",
            notes = null, sortOrder = 0, archivedAt = null, updatedAt = at,
        ))
        db.categoryDao().upsertAll(listOf(CategoryEntity(
            "c", "Food", "طعام", "EXPENSE", null, null, "SAR", false, 0,
        )))
        db.transactionDao().upsert(TransactionEntity(
            id = "t", accountId = "a", type = "EXPENSE", amountMinor = 125L, currency = "SAR",
            date = LocalDate.parse("2026-06-13"), occurredAt = null, merchant = "Synthetic", merchantNormalized = "synthetic",
            categoryId = "c", notes = "preserve", source = "MANUAL", sourceRefId = null, status = "CONFIRMED",
            confidence = null, createdAt = at, updatedAt = at, categorySource = "USER",
        ))
        db.transactionReceiptDao().upsert(TransactionReceiptEntity(
            "receipt", "t", "image/png", "synthetic", 4L, byteArrayOf(0, 1, -1, 3), at,
        ))
        BackupAuxiliaryNativeFixtures.seed(db, "boundary", "a", "c", 3)
    }

    /** Independent raw SQLite contents, including blob bytes; not DTO equality. */
    fun evidence(db: AtharDatabase): Map<String, List<List<Any?>>> {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(tables).hasSize(21)
        return tables.associateWith { table ->
            check(table.matches(Regex("[a-z_]+")))
            sql.query("SELECT * FROM \"$table\" ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index) // Only dimensionless confidence uses REAL.
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                            else -> cursor.getString(index)
                        }
                    })
                }
            }
        }
    }

    fun encrypted(plain: ByteArray): ByteArray {
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        return AtharCrypto.encrypt(gzip, passphrase)
    }

    fun dispose(db: AtharDatabase) {
        val name = requireNotNull(db.openHelper.databaseName)
        check(name.startsWith("synthetic-backup-input-"))
        db.close()
        check(ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name))
    }

}
