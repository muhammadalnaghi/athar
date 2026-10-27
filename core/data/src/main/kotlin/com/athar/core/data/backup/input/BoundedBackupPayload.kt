package com.athar.core.data.backup.input

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/**
 * Resource-bounded decoding of encrypted backup payloads.
 *
 * This object is a guard only: it bounds how many ciphertext bytes are pulled
 * off the caller's stream and how many bytes gzip may expand, then decodes the
 * expanded bytes as strict UTF-8 (mirroring the catalog engine's decoder
 * policy). It does not authenticate the payload — authentication here means
 * the upstream existing AES-GCM backup container (the tag verified when the
 * caller decrypts), not a signature feature introduced by C1 row transport —
 * and it does not choose production policy such as where streams come from or
 * which budgets apply.
 *
 * The name [decodeAuthenticatedGzip] is the frozen lane contract name: its
 * input is expected to be bytes the upstream AES-GCM decryption has already
 * authenticated, and callers must still authenticate before trusting the
 * decoded text.
 */
internal object BoundedBackupPayload {

    /**
     * Reads ciphertext from [stream] without ever reading more than one byte
     * past [maxBytes], so an oversized artifact is detected while total stream
     * consumption stays within `maxBytes + 1` and no over-bound bytes are
     * retained in the result. The caller's stream is intentionally left open:
     * ownership stays with the caller on both success and rejection.
     */
    fun readCiphertext(stream: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "ciphertext byte budget must be positive" }
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            // Ask for at most one byte beyond the remaining budget; a full
            // reply therefore overshoots by at most one byte, which is thrown
            // away with the exception rather than retained. The remaining
            // budget is Long so the limit+1 probe cannot wrap Int arithmetic
            // when maxBytes is Int.MAX_VALUE.
            val remaining = maxBytes - total
            val want = if (remaining >= chunk.size) chunk.size else (remaining + 1).toInt()
            val read = stream.read(chunk, 0, want)
            if (read < 0) break
            if (read == 0) {
                // want is always positive, so a zero reply breaks the
                // InputStream contract; draining would spin forever, so the
                // stream is rejected instead of trusted.
                throw IOException("ciphertext stream returned a zero-length read")
            }
            total += read
            if (total > maxBytes) {
                // Bound validation happens before any byte is retained: the
                // over-bound detection byte is never handed to the output
                // buffer, so a rejection leaves zero over-budget bytes in
                // memory.
                throw BackupPayloadBoundException(
                    "ciphertext exceeds the budget of $maxBytes bytes",
                )
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Decompresses [input] with the standard JDK [GZIPInputStream] — including
     * implicitly concatenated gzip members — while counting expanded bytes
     * chunk-wise against [maxExpandedBytes]. Decompression stops and throws as
     * soon as the budget is exceeded, so a small compressed bomb can never
     * expand unbounded and no over-bound bytes are retained. Reading every
     * member to EOF is also what forces the JDK to verify each member's CRC:
     * truncated or corrupt streams surface as the JDK's own EOF/Zip exceptions,
     * which propagate untouched. The expanded bytes are then decoded as strict
     * UTF-8 (malformed input reports rather than replaces), so no partial or
     * lossily-decoded payload is ever returned.
     */
    fun decodeAuthenticatedGzip(input: ByteArray, maxExpandedBytes: Int): String {
        require(maxExpandedBytes > 0) { "expanded byte budget must be positive" }
        GZIPInputStream(input.inputStream()).use { gzip ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = gzip.read(chunk)
                if (read < 0) break
                // Long accounting keeps the expansion bound overflow-free even
                // when the expanded budget is Int.MAX_VALUE.
                total += read
                if (total > maxExpandedBytes) {
                    throw BackupPayloadBoundException(
                        "gzip expansion exceeds the budget of $maxExpandedBytes bytes",
                    )
                }
                out.write(chunk, 0, read)
            }
            return strictUtf8(out.toByteArray())
        }
    }

    private fun strictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private const val READ_CHUNK_BYTES = 8192
}

/** Raised only when a payload exceeds an explicit caller-provided budget. */
internal class BackupPayloadBoundException(message: String) : IOException(message)
