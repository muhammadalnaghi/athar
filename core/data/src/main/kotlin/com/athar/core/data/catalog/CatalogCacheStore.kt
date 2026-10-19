package com.athar.core.data.catalog

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Cache seam for signed public envelopes; only verified public bytes are written. */
internal interface CatalogCacheStore {
    /** Null means absent/oversized invalid data; actual storage read failures throw. */
    suspend fun readCurrent(): ByteArray?
    suspend fun readPrevious(): ByteArray?
    /** Writes signed [next], retaining only repository-authenticated [trustedPrevious] bytes. */
    suspend fun promoteAuthenticated(next: ByteArray, trustedPrevious: ByteArray?)
    suspend fun clear()
}

/**
 * Main-safe, bounded AtomicFile storage for current and previous public envelopes.
 * Directory creation and all file operations happen on Dispatchers.IO. Recovery
 * reads never promote a previous file over a corrupt current file, so a good
 * previous envelope cannot be overwritten by bad bytes during startup.
 */
@Singleton
internal class AtomicFileCatalogCacheStore @Inject constructor(
    @ApplicationContext context: Context,
) : CatalogCacheStore {

    private val directory = File(context.filesDir, "merchant_catalog")
    private val currentBase = File(directory, "catalog_current.bin")
    private val previousBase = File(directory, "catalog_previous.bin")
    private val currentFile = AtomicFile(currentBase)
    private val previousFile = AtomicFile(previousBase)

    override suspend fun readCurrent(): ByteArray? = withContext(Dispatchers.IO) {
        ensureDirectory()
        readAtomicBlocking(currentFile)
    }

    override suspend fun readPrevious(): ByteArray? = withContext(Dispatchers.IO) {
        ensureDirectory()
        readAtomicBlocking(previousFile)
    }

    override suspend fun promoteAuthenticated(
        next: ByteArray,
        trustedPrevious: ByteArray?,
    ) = withContext(Dispatchers.IO) {
        if (next.size > CatalogEnvelopeParser.MAX_ENVELOPE_BYTES) {
            throw IOException("catalog cache write exceeds envelope bound")
        }
        if (trustedPrevious != null && trustedPrevious.size > CatalogEnvelopeParser.MAX_ENVELOPE_BYTES) {
            throw IOException("trusted catalog cache bytes exceed envelope bound")
        }
        ensureDirectory()
        // Never read raw current here: it may be corrupt or divergent. Preserve
        // only bytes authenticated by the repository, then write the new signed
        // envelope. AtomicFile remains the per-file durability primitive.
        trustedPrevious?.let { writeAtomic(previousFile, it) }
        writeAtomic(currentFile, next)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        ensureDirectory()
        listOf(currentFile, previousFile).forEach { file ->
            runCatching { file.delete() }
                .getOrElse { throw IOException("catalog cache clear failed", it) }
            remnants(file).forEach(::deleteIfPresent)
        }
        val remaining = listOf(currentBase, previousBase)
            .flatMap { base -> listOf(base, File(base.path + ".bak"), File(base.path + ".new")) }
            .filter(File::exists)
        if (remaining.isNotEmpty()) {
            throw IOException("catalog cache clear left AtomicFile remnants")
        }
    }

    private fun ensureDirectory() {
        if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("catalog cache path is not a directory")
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("catalog cache directory could not be created")
        }
    }

    private fun readAtomicBlocking(file: AtomicFile): ByteArray? {
        try {
            file.openRead().use { input ->
                val out = ByteArrayOutputStream(CatalogEnvelopeParser.MAX_ENVELOPE_BYTES)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val remaining = CatalogEnvelopeParser.MAX_ENVELOPE_BYTES - total
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                    if (read == -1) break
                    if (read > remaining) return null
                    out.write(buffer, 0, read)
                    total += read
                }
                return out.toByteArray()
            }
        } catch (absent: FileNotFoundException) {
            val names = directory.list()?.toSet()
                ?: throw IOException("catalog cache directory is unreadable", absent)
            if (file.baseFile.name !in names && "${file.baseFile.name}.bak" !in names) return null
            throw IOException("existing catalog cache is unreadable", absent)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw failure
        } catch (failure: SecurityException) {
            throw IOException("catalog cache access denied", failure)
        }
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
            val stored = readAtomicBlocking(file)
                ?: throw IOException("catalog cache commit could not be read back")
            if (!stored.contentEquals(bytes)) throw IOException("catalog cache commit differs from verified bytes")
        } catch (failure: Throwable) {
            stream?.let { runCatching { file.failWrite(it) } }
            if (failure is CancellationException) throw failure
            throw IOException("catalog cache write failed", failure)
        }
    }

    private fun remnants(file: AtomicFile): List<File> = listOf(
        file.baseFile,
        File(file.baseFile.path + ".bak"),
        File(file.baseFile.path + ".new"),
    )

    private fun deleteIfPresent(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("catalog cache clear failed for ${file.name}")
        }
    }
}
