package com.athar.core.data.backup.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackupInputLimitsTest {
    @Test
    fun `each resource budget must be strictly positive`() {
        val valid = BackupInputLimits.Production
        listOf(0, -1).forEach { invalid ->
            assertThrows<IllegalArgumentException> { valid.copy(maxCiphertextBytes = invalid) }
            assertThrows<IllegalArgumentException> { valid.copy(maxExpandedBytes = invalid) }
            assertThrows<IllegalArgumentException> { valid.copy(maxDepth = invalid) }
            assertThrows<IllegalArgumentException> { valid.copy(maxTokens = invalid.toLong()) }
            assertThrows<IllegalArgumentException> { valid.copy(maxStringChars = invalid) }
        }
    }

    @Test
    fun `production string budget accommodates the existing five MiB receipt limit`() {
        val receiptBytes = 5L * 1024L * 1024L
        val base64Characters = ((receiptBytes + 2L) / 3L) * 4L
        val policy = BackupInputLimits.Production
        assertThat(policy.maxStringChars.toLong()).isAtLeast(base64Characters)
        assertThat(policy.maxExpandedBytes).isEqualTo(32 * 1024 * 1024)
        assertThat(policy.maxCiphertextBytes).isEqualTo(16 * 1024 * 1024)
        assertThat(policy.maxDepth).isEqualTo(32)
        assertThat(policy.maxTokens).isEqualTo(2_000_000L)
    }
}
