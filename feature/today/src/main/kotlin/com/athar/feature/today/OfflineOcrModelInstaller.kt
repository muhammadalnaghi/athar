package com.athar.feature.today

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

internal class OfflineOcrModelInstaller(
    private val context: Context,
    private val finishWrite: (AtomicFile, FileOutputStream) -> Unit = { file, stream -> file.finishWrite(stream) },
) {

    suspend fun ensureInstalled(): File = withContext(Dispatchers.IO) {
        val activeContext = currentCoroutineContext()
        INSTALL_LOCK.withLock {
            activeContext.ensureActive()
            installIfNeeded(activeContext)
        }
    }

    private fun installIfNeeded(activeContext: CoroutineContext): File {
        activeContext.ensureActive()
        val dataDirectory = context.filesDir.resolve(DATA_DIRECTORY)
        val tessdataDirectory = dataDirectory.resolve(TESSDATA_DIRECTORY)
        val modelFile = tessdataDirectory.resolve(MODEL_FILE_NAME)
        val atomicFile = AtomicFile(modelFile)

        if (!dataDirectory.isDirectory && !dataDirectory.mkdirs() && !dataDirectory.isDirectory) {
            throw IOException("Unable to create offline OCR data directory")
        }
        if (!tessdataDirectory.isDirectory && !tessdataDirectory.mkdirs() && !tessdataDirectory.isDirectory) {
            throw IOException("Unable to create offline OCR tessdata directory")
        }

        if (hasExpectedDigest(atomicFile, activeContext)) {
            return dataDirectory
        }

        var output: FileOutputStream? = null
        var finished = false
        try {
            val started = atomicFile.startWrite()
            output = started
            copyVerifiedModel(started, activeContext)
            started.fd.sync()
            activeContext.ensureActive()
            finishWrite(atomicFile, started)
            finished = true
            if (!hasExpectedDigest(atomicFile, activeContext)) {
                throw IOException("Installed offline OCR model digest mismatch")
            }
        } catch (throwable: Throwable) {
            if (!finished) {
                output?.let { stream ->
                    try {
                        atomicFile.failWrite(stream)
                    } catch (_: IOException) {
                        // Preserve the original failure while AtomicFile attempts rollback.
                    }
                }
            }
            throw throwable
        }

        activeContext.ensureActive()
        return dataDirectory
    }

    private fun copyVerifiedModel(
        output: FileOutputStream,
        activeContext: CoroutineContext,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L

        context.assets.open(ASSET_PATH).use { input ->
            while (true) {
                activeContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                total += read
                if (total > MAX_MODEL_BYTES) {
                    throw IOException("Offline OCR model exceeds size bound")
                }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }

        if (digest.digest().toHex() != EXPECTED_MODEL_SHA256) {
            throw IOException("Bundled offline OCR model digest mismatch")
        }
    }

    private fun hasExpectedDigest(
        atomicFile: AtomicFile,
        activeContext: CoroutineContext,
    ): Boolean {
        val actualDigest = try {
            atomicFile.openRead().use { input ->
                sha256(input, activeContext)
            }
        } catch (_: IOException) {
            return false
        }
        return actualDigest == EXPECTED_MODEL_SHA256
    }

    private fun sha256(
        input: InputStream,
        activeContext: CoroutineContext,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L

        while (true) {
            activeContext.ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            total += read
            if (total > MAX_MODEL_BYTES) {
                throw IOException("Offline OCR model exceeds size bound")
            }
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    companion object {
        private val INSTALL_LOCK = Mutex()

        internal const val LANGUAGE = "eng"
        internal const val DATA_DIRECTORY = "ocr"
        internal const val MODEL_RELATIVE_PATH = "tessdata/eng.traineddata"
        internal const val EXPECTED_MODEL_SHA256 =
            "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"

        private const val TESSDATA_DIRECTORY = "tessdata"
        private const val MODEL_FILE_NAME = "eng.traineddata"
        private const val ASSET_PATH = "ocr/tessdata/eng.traineddata"
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val MAX_MODEL_BYTES = 8L * 1024L * 1024L
    }
}
