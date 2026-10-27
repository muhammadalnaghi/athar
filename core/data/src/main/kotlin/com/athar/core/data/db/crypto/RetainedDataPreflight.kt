package com.athar.core.data.db.crypto

import java.io.File

/**
 * WM028-13B0 preflight guard at the wrapped-key creation boundary.
 *
 * The wrapped DB key (`db_key.bin`) must only be minted on a genuinely empty
 * installation. If SQLite artifacts from an earlier life exist, a replacement key
 * would make them permanently unreadable (decryption under a fresh key can never
 * succeed), so the boundary must fail closed and leave every file byte-identical
 * for explicit recovery. Existing data is never deleted, truncated or rewritten
 * here; recovery is a separate, explicit operation.
 *
 * Master Brief §5.3 (Database continuity) / WM028-13B0.
 */
internal object RetainedDataPreflight {

    /**
     * Raised instead of creating a replacement key when retained database
     * artifacts exist. [artifacts] lists the exact files that were preserved.
     */
    class RetainedDatabaseWithoutKeyException internal constructor(
        val artifacts: List<File>,
    ) : IllegalStateException(
        "Retained database artifacts exist without a wrapped key; refusing to create " +
            "a replacement key. Recover the data explicitly instead. Preserved: " +
            artifacts.joinToString(", ") { it.path },
    )

    /** SQLite sidecars that may carry retained pages belonging to [databaseFile]. */
    fun sidecarFiles(databaseFile: File): List<File> = listOf(
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal"),
    )

    /**
     * All retained artifacts (main file plus sidecars) that currently exist.
     * The guard is existence-based, not size-based: a zero-length or truncated
     * file may still hold recoverable pages, so any presence blocks key creation.
     */
    fun retainedArtifacts(databaseFile: File): List<File> =
        (listOf(databaseFile) + sidecarFiles(databaseFile)).filter { it.exists() }

    /**
     * Throws [RetainedDatabaseWithoutKeyException] if any database artifact exists.
     * [DbKeyManager.getOrCreateDbKey] calls this before generating, wrapping or
     * writing anything, so callers cannot bypass the guard.
     */
    fun requireNoRetainedDatabaseArtifacts(databaseFile: File) {
        val retained = retainedArtifacts(databaseFile)
        if (retained.isNotEmpty()) {
            throw RetainedDatabaseWithoutKeyException(retained)
        }
    }
}
