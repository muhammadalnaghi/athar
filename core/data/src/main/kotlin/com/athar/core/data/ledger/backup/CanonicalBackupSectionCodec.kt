package com.athar.core.data.ledger.backup

import kotlinx.serialization.json.Json

/**
 * Lossless JSON transport for trusted, caller-private row captures only.
 * No outer backup version/crypto, I/O, FK validation, policy, or restore occurs.
 * External backup bytes require a separate bounded/strict authenticated decoder.
 */
internal object CanonicalBackupSectionCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(rows: CanonicalBackupRows): String =
        json.encodeToString(CanonicalBackupSection.serializer(), rows.toSection())

    fun decode(encoded: String): CanonicalBackupRows =
        json.decodeFromString(CanonicalBackupSection.serializer(), encoded).toRows()
}
