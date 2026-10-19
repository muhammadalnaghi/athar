package com.athar.core.data.backup

import com.athar.core.data.mapper.toDomain
import com.athar.core.domain.model.CategorySource
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * Provenance field round-trips through the backup format. Legacy rows (null),
 * explicit user edits (USER), and unknown future provenance values must all
 * restore without failing — unknown parses back to null, never a crash.
 */
class BackupTransactionProvenanceTest {

    @Test
    fun `legacy rows without provenance restore as null`() {
        val backup = BackupTransaction(
            id = "tx-1", accountId = "cash", type = "EXPENSE", amountMinor = 10_00,
            currency = "SAR", date = LocalDate.parse("2026-06-13"), occurredAt = null,
            merchant = "Jarir", merchantNormalized = "jarir", categoryId = "cat-education",
            notes = null, source = "MANUAL", sourceRefId = null,
            status = "CONFIRMED", confidence = null,
            createdAt = Instant.parse("2026-06-13T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-13T00:00:00Z"),
        )

        val entity = backup.toEntity()

        assertThat(entity.categorySource).isNull()
        assertThat(entity.toDomain().categorySource).isNull()
    }

    @Test
    fun `catalog and user provenance round-trip in both directions`() {
        val entity = transactionEntity(categorySource = "CATALOG")

        val backup = entity.toBackup()
        assertThat(backup.categorySource).isEqualTo("CATALOG")
        assertThat(backup.toEntity().toDomain().categorySource).isEqualTo(CategorySource.CATALOG)

        val userEntity = transactionEntity(categorySource = "USER")
        assertThat(userEntity.toBackup().toEntity().toDomain().categorySource)
            .isEqualTo(CategorySource.USER)
    }

    @Test
    fun `unknown future provenance parses to null instead of failing the restore`() {
        val entity = transactionEntity(categorySource = "QUANTUM_ENGINE")

        // Restore path: backup -> entity succeeds.
        val restored = entity.toBackup().toEntity()
        assertThat(restored.categorySource).isEqualTo("QUANTUM_ENGINE")

        // Domain mapping degrades to null rather than crashing on the unknown name.
        assertThat(restored.toDomain().categorySource).isNull()
    }

    private fun transactionEntity(categorySource: String?) = com.athar.core.data.db.entity.TransactionEntity(
        id = "tx-1",
        accountId = "cash",
        type = "EXPENSE",
        amountMinor = 10_00,
        currency = "SAR",
        date = LocalDate.parse("2026-06-13"),
        occurredAt = null,
        merchant = "Jarir",
        merchantNormalized = "jarir",
        categoryId = "cat-education",
        notes = null,
        source = "MANUAL",
        sourceRefId = null,
        status = "CONFIRMED",
        confidence = null,
        createdAt = Instant.parse("2026-06-13T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T00:00:00Z"),
        categorySource = categorySource,
    )
}
