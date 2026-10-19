package com.athar.core.data.catalog

import java.security.MessageDigest

/**
 * Build-time trust anchor for the signed shared merchant catalog (ENH-009).
 *
 * Values come from the production public-key record at
 * `plans/execution/002-catalog-public-key.json` (public SPKI only — the
 * signing key is a non-exportable CNG key that never enters this repo).
 * Key rotation requires a trusted Obtainium-delivered app update.
 */
internal object ProductionCatalogTrust {
    const val KEY_ID: String = "p256-3eee06efb538aeb5"
    const val PUBLIC_KEY_SPKI_BASE64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEShvxvjsw61qy1avTYsxN7FbRXmBlfRP04m34a7BFi5I39psg3824yQ5ITxuGZ2+EFqUftscWO/w7zcjf8YKFA=="
}

/** Exact context string prepended to payload bytes before signing/verifying. */
internal val CONTEXT_PREFIX: ByteArray = "ATHAR-MERCHANT-CATALOG/v1\n".toByteArray(Charsets.US_ASCII)

/** Hash helpers over exact payload bytes (never canonicalized/re-serialized). */
internal object CatalogDigest {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
