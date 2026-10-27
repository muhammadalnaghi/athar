package com.athar.core.data.backup

import com.athar.core.data.ledger.backup.CanonicalBackupRevision
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackupEmptyRestoreRevisionTest {
    @Test
    fun `fresh empty whole replace still creates a live epoch`() {
        val result = BackupRevisionAdvance.advance(emptyList(), emptyList(), requireSingleton = false)
        assertThat(result.single { it.id == "singleton" }.revision).isEqualTo(1L)
    }

    @Test
    fun `negative live epoch is refused rather than silently repaired`() {
        assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = emptyList(),
                local = listOf(CanonicalBackupRevision(id = "singleton", revision = -1L)),
                requireSingleton = false,
            )
        }
    }

    @Test
    fun `empty whole replace advances existing live singleton instead of preserving stale epoch`() {
        val result = BackupRevisionAdvance.advance(
            incoming = emptyList(),
            local = listOf(CanonicalBackupRevision(id = "singleton", revision = 5L)),
            requireSingleton = false,
        )
        assertThat(result.single { it.id == "singleton" }.revision).isEqualTo(6L)
    }

    @Test
    fun `empty whole replace cannot clear old money at an exhausted live epoch`() {
        assertThrows(BackupValidationException::class.java) {
            BackupRevisionAdvance.advance(
                incoming = emptyList(),
                local = listOf(CanonicalBackupRevision(id = "singleton", revision = Long.MAX_VALUE)),
                requireSingleton = false,
            )
        }
    }
}
