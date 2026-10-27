package com.athar.core.data.backup

import com.athar.core.data.ledger.backup.CanonicalBackupRevision
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Focused exact JVM coverage of the restore-epoch advance policy (triage B3):
 * every revision id affected by a v4 whole replace must restore strictly
 * greater than BOTH the live value and the incoming value
 * (`max(current, incoming) + 1`), with fail-closed overflow and negatives.
 * Pure policy only — no Room, no Android runtime.
 */
class BackupRevisionAdvanceTest {

    private fun rev(id: String, value: Long) = CanonicalBackupRevision(id, value)

    @Test
    fun `equal counters still strictly advance by one`() {
        val rows = BackupRevisionAdvance.advance(
            incoming = listOf(rev("singleton", 5L)),
            local = listOf(rev("singleton", 5L)),
            requireSingleton = true,
        )
        // The old max-only policy rewrote 5 — reusing the live epoch for a
        // different graph. The strict policy must land one past both values.
        assertThat(rows.single().id).isEqualTo("singleton")
        assertThat(rows.single().revision).isEqualTo(6L)
    }

    @Test
    fun `local greater advances past local`() {
        val rows = BackupRevisionAdvance.advance(
            incoming = listOf(rev("singleton", 3L)),
            local = listOf(rev("singleton", 7L)),
            requireSingleton = true,
        )
        assertThat(rows.single().revision).isEqualTo(8L)
    }

    @Test
    fun `incoming greater advances past incoming`() {
        val rows = BackupRevisionAdvance.advance(
            incoming = listOf(rev("singleton", 9L)),
            local = listOf(rev("singleton", 2L)),
            requireSingleton = true,
        )
        assertThat(rows.single().revision).isEqualTo(10L)
    }

    @Test
    fun `affected id without a live row restores at incoming plus one`() {
        // A missing live row protects no epoch (the table seeds and increments
        // only `singleton`), so the restored counter is incoming + 1.
        val rows = BackupRevisionAdvance.advance(
            incoming = listOf(rev("singleton", 4L)),
            local = emptyList(),
            requireSingleton = true,
        )
        assertThat(rows.single().revision).isEqualTo(5L)
    }

    @Test
    fun `local-only metadata row is preserved exactly and rows stay sorted`() {
        val rows = BackupRevisionAdvance.advance(
            incoming = listOf(rev("singleton", 3L)),
            local = listOf(rev("singleton", 3L), rev("extra", 9L)),
            requireSingleton = true,
        )
        assertThat(rows.map { it.id }).containsExactly("extra", "singleton").inOrder()
        assertThat(rows.single { it.id == "extra" }.revision).isEqualTo(9L)
        assertThat(rows.single { it.id == "singleton" }.revision).isEqualTo(4L)
    }

    @Test
    fun `every affected id is strictly greater than both current and incoming`() {
        val incoming = listOf(rev("singleton", 100L), rev("per-source", 40L))
        val local = listOf(rev("singleton", 90L), rev("per-source", 60L), rev("extra", 7L))
        val rows = BackupRevisionAdvance.advance(incoming, local, requireSingleton = true)
        val byId = rows.associateBy { it.id }
        incoming.forEach { row ->
            val restored = byId.getValue(row.id).revision
            val current = local.first { it.id == row.id }.revision
            assertThat(restored).isGreaterThan(row.revision)
            assertThat(restored).isGreaterThan(current)
        }
        assertThat(byId.getValue("extra").revision).isEqualTo(7L)
    }

    @Test
    fun `negative incoming revision fails closed`() {
        val error = assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = listOf(rev("singleton", -1L)),
                local = emptyList(),
                requireSingleton = true,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
    }

    @Test
    fun `incoming max value fails closed before any advance`() {
        val error = assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = listOf(rev("singleton", Long.MAX_VALUE)),
                local = listOf(rev("singleton", 1L)),
                requireSingleton = true,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
    }

    @Test
    fun `live max value with smaller incoming also fails closed`() {
        val error = assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = listOf(rev("singleton", 1L)),
                local = listOf(rev("singleton", Long.MAX_VALUE)),
                requireSingleton = true,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
    }

    @Test
    fun `duplicate incoming revision ids fail closed`() {
        val error = assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = listOf(rev("singleton", 1L), rev("singleton", 2L)),
                local = emptyList(),
                requireSingleton = true,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.DUPLICATE_BACKUP_KEY)
    }

    @Test
    fun `canonical rows without the singleton counter fail closed`() {
        val error = assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = listOf(rev("per-source", 1L)),
                local = emptyList(),
                requireSingleton = true,
            )
        }
        assertThat(error.code).isEqualTo(BackupValidationCode.INVALID_REVISION)
    }

    @Test
    fun `singleton is not required when the payload carries no canonical data rows`() {
        val rows = BackupRevisionAdvance.advance(
            incoming = listOf(rev("extra", 1L)),
            local = emptyList(),
            requireSingleton = false,
        )
        assertThat(rows.single { it.id == "extra" }.revision).isEqualTo(2L)
    }
}
