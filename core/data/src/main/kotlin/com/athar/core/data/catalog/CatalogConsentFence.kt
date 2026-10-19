package com.athar.core.data.catalog

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal data class CatalogConsentFenceSnapshot(
    val disabled: Boolean,
    val certain: Boolean,
)

internal interface CatalogConsentFence {
    suspend fun read(): CatalogConsentFenceSnapshot
    suspend fun disable()
    suspend fun clear()
}

/** A tiny nonfinancial veto independent of DataStore; not a cross-store transaction. */
@Singleton
internal class AtomicFileCatalogConsentFence internal constructor(
    private val directory: File,
) : CatalogConsentFence {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "merchant_catalog"))

    private val baseFile = File(directory, "catalog_disable.fence")
    private val atomicFile = AtomicFile(baseFile)

    override suspend fun read(): CatalogConsentFenceSnapshot = withContext(Dispatchers.IO) {
        LOCK.withLock {
            try {
                readOnDisk()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                CatalogConsentFenceSnapshot(disabled = true, certain = false)
            }
        }
    }

    override suspend fun disable(): Unit = withContext(Dispatchers.IO) {
        LOCK.withLock {
            ensureDirectory()
            var stream: FileOutputStream? = null
            try {
                val started = atomicFile.startWrite()
                stream = started
                started.write(DISABLED_MARKER)
                // AtomicFile.finishWrite can log a sync/rename failure rather than throw.
                started.fd.sync()
                atomicFile.finishWrite(started)
                stream = null
                val stored = readOnDisk()
                if (!stored.disabled || !stored.certain) {
                    throw IOException("Catalog disable fence was not durably verified")
                }
            } catch (failure: Throwable) {
                stream?.let { started ->
                    try {
                        atomicFile.failWrite(started)
                    } catch (_: Exception) {
                        // Keep the original failure; reads of remnants fail closed.
                    }
                }
                throw failure
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        LOCK.withLock {
            ensureDirectory()
            atomicFile.delete()
            markerFiles().forEach { file ->
                if (file.exists() && !file.delete()) {
                    throw IOException("Catalog consent fence could not be cleared")
                }
            }
            val stored = readOnDisk()
            if (stored.disabled || !stored.certain) {
                throw IOException("Catalog consent fence clear was not verified")
            }
        }
    }

    private fun readOnDisk(): CatalogConsentFenceSnapshot {
        ensureDirectory()
        // FileNotFoundException also represents denied access. Prove genuine
        // absence using a successful directory read before allowing downloads.
        val names = directory.list()?.toSet()
            ?: throw IOException("Catalog consent directory is unreadable")
        val hasBaseOrBackup = baseFile.name in names || "${baseFile.name}.bak" in names
        if (!hasBaseOrBackup) {
            return if ("${baseFile.name}.new" in names) {
                CatalogConsentFenceSnapshot(disabled = true, certain = false)
            } else {
                CatalogConsentFenceSnapshot(disabled = false, certain = true)
            }
        }
        return atomicFile.openRead().use { input ->
            val first = input.read()
            val second = input.read()
            if (first == DISABLED_MARKER[0].toInt() && second == -1) {
                CatalogConsentFenceSnapshot(disabled = true, certain = true)
            } else {
                CatalogConsentFenceSnapshot(disabled = true, certain = false)
            }
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Catalog consent directory is unavailable")
        }
    }

    private fun markerFiles(): List<File> = listOf(
        baseFile,
        File(baseFile.path + ".bak"),
        File(baseFile.path + ".new"),
    )

    private companion object {
        val LOCK = Mutex()
        val DISABLED_MARKER = byteArrayOf(0x44)
    }
}
