package com.athar.feature.settings

import com.athar.core.domain.repo.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Owns URI streams and temporary password arrays, never buffers an encrypted file in the UI. */
internal object BackupFileTransfer {
    suspend fun import(
        repository: BackupRepository,
        passphrase: CharArray,
        openInput: () -> InputStream?,
    ) {
        try {
            withContext(Dispatchers.IO) {
                val input = openInput() ?: throw IOException("Backup input is unavailable")
                input.use { repository.import(it, passphrase) }
            }
        } finally {
            passphrase.fill(0.toChar())
        }
    }

    suspend fun export(
        repository: BackupRepository,
        passphrase: CharArray,
        openOutput: () -> OutputStream?,
    ) {
        try {
            withContext(Dispatchers.IO) {
                val output = openOutput() ?: throw IOException("Backup output is unavailable")
                output.use { repository.export(it, passphrase) }
            }
        } finally {
            passphrase.fill(0.toChar())
        }
    }
}
