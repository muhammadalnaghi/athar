package com.athar.core.data.backup.input

import java.io.IOException

/** Trusted caller policy, not payload-controlled configuration. */
internal data class BackupInputLimits(
    val maxCiphertextBytes: Int,
    val maxExpandedBytes: Int,
    val maxDepth: Int,
    val maxTokens: Long,
    val maxStringChars: Int,
) {
    init {
        require(maxCiphertextBytes > 0) { "ciphertext byte budget must be positive" }
        require(maxExpandedBytes > 0) { "expanded byte budget must be positive" }
        require(maxDepth > 0) { "depth budget must be positive" }
        require(maxTokens > 0L) { "token budget must be positive" }
        require(maxStringChars > 0) { "string char budget must be positive" }
    }

    companion object {
        val Production = BackupInputLimits(
            maxCiphertextBytes = 16 * 1024 * 1024,
            maxExpandedBytes = 32 * 1024 * 1024,
            maxDepth = 32,
            maxTokens = 2_000_000L,
            maxStringChars = 8 * 1024 * 1024,
        )
    }
}

/** No chained decoder exception: serialization errors can contain financial payload fragments. */
internal class BackupEnvelopeDecodeException : IOException("Backup structure is invalid or incomplete")
