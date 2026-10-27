package com.athar.core.domain.repo

import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted local backup contract.
 *
 * Wire format and crypto details live in `core:data` — feature modules consume this
 * interface only. Tests can fake it without pulling Room.
 */
interface BackupRepository {
    suspend fun export(output: OutputStream, passphrase: CharArray)
    suspend fun import(bytes: ByteArray, passphrase: CharArray)

    /** Main-safe bounded import. The caller retains ownership and closes [input]. No unbounded fallback. */
    suspend fun import(input: InputStream, passphrase: CharArray)
}
