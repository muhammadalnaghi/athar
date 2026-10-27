package com.athar.core.data.backup.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/**
 * Adversarial JVM suite for [BoundedBackupPayload]. This object is pure
 * `java.io`/`java.util.zip`/`java.nio`, so every behavior — budgets, the
 * limit+1 consumption contract, stream ownership, gzip bombs, truncation, CRC,
 * strict UTF-8 and concatenated members — is exercised for real on the JVM.
 */
class BoundedBackupPayloadTest {

    // -- ciphertext bounds ----------------------------------------------------

    @Test
    fun `reads ciphertext within the exact budget`() {
        val payload = repeatingBytes(300)
        val read = BoundedBackupPayload.readCiphertext(ByteArrayInputStream(payload), maxBytes = 300)
        assertThat(read).isEqualTo(payload)
    }

    @Test
    fun `rejects ciphertext one byte over budget after consuming at most limit plus one`() {
        val budget = 100
        val oversized = repeatingBytes(1000)
        val stream = CountingInputStream(ByteArrayInputStream(oversized))
        val failure = assertThrows<BackupPayloadBoundException> {
            BoundedBackupPayload.readCiphertext(stream, maxBytes = budget)
        }
        assertThat(failure).hasMessageThat().contains("100")
        // Exactly one byte past the budget is pulled to prove overflow; nothing more.
        assertThat(stream.totalServed).isEqualTo(budget + 1)
    }

    @Test
    fun `retains no ciphertext when the stream is far larger than the budget`() {
        val stream = CountingInputStream(ByteArrayInputStream(repeatingBytes(8192 * 4)))
        assertThrows<BackupPayloadBoundException> {
            BoundedBackupPayload.readCiphertext(stream, maxBytes = 128)
        }
        assertThat(stream.totalServed).isAtMost(128 + 1)
    }

    @Test
    fun `returns an empty payload for an empty stream within budget`() {
        val read = BoundedBackupPayload.readCiphertext(ByteArrayInputStream(ByteArray(0)), maxBytes = 16)
        assertThat(read).isEmpty()
    }

    @Test
    fun `rejects nonpositive ciphertext budgets`() {
        val stream = ByteArrayInputStream(repeatingBytes(4))
        assertThat(assertThrows<IllegalArgumentException> {
            BoundedBackupPayload.readCiphertext(stream, maxBytes = 0)
        }).hasMessageThat().contains("positive")
        assertThat(assertThrows<IllegalArgumentException> {
            BoundedBackupPayload.readCiphertext(ByteArrayInputStream(repeatingBytes(4)), maxBytes = -1)
        }).hasMessageThat().contains("positive")
    }

    @Test
    fun `reads a short stream under a maximum-int budget without arithmetic overflow`() {
        val payload = repeatingBytes(8)
        val read = BoundedBackupPayload.readCiphertext(
            ByteArrayInputStream(payload),
            maxBytes = Int.MAX_VALUE,
        )
        assertThat(read).isEqualTo(payload)
    }

    @Test
    fun `rejects a stream that answers a positive-length request with a zero-length read`() {
        val stream = ZeroReadInputStream(ByteArrayInputStream(repeatingBytes(4)))
        val failure = assertThrows<IOException> {
            BoundedBackupPayload.readCiphertext(stream, maxBytes = 64)
        }
        assertThat(failure).hasMessageThat().contains("zero-length read")
    }

    // -- caller stream ownership ----------------------------------------------

    @Test
    fun `leaves the caller stream open on success`() {
        val stream = CloseTrackingInputStream(ByteArrayInputStream(repeatingBytes(10)))
        BoundedBackupPayload.readCiphertext(stream, maxBytes = 10)
        assertThat(stream.closed).isFalse()
    }

    @Test
    fun `leaves the caller stream open on budget rejection`() {
        val stream = CloseTrackingInputStream(ByteArrayInputStream(repeatingBytes(64)))
        assertThrows<BackupPayloadBoundException> {
            BoundedBackupPayload.readCiphertext(stream, maxBytes = 8)
        }
        assertThat(stream.closed).isFalse()
    }

    // -- gzip expansion bounds -------------------------------------------------

    @Test
    fun `decodes gzip within the exact expanded budget`() {
        val text = "أثر — 2026 transactions \"quoted\" \\ backslash\nnewlines kept"
        val decoded = BoundedBackupPayload.decodeAuthenticatedGzip(gzip(text.toByteArray()), maxExpandedBytes = text.toByteArray().size)
        assertThat(decoded).isEqualTo(text)
    }

    @Test
    fun `rejects expansion one byte over the expanded budget`() {
        val raw = "0123456789".toByteArray()
        val compressed = gzip(raw)
        val failure = assertThrows<BackupPayloadBoundException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(compressed, maxExpandedBytes = raw.size - 1)
        }
        assertThat(failure).hasMessageThat().contains("9")
    }

    @Test
    fun `stops a gzip bomb within the expanded budget`() {
        val bomb = gzip(ByteArray(1024 * 1024)) // ~1KB compressed
        assertThat(bomb.size).isLessThan(8192)
        assertThrows<BackupPayloadBoundException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(bomb, maxExpandedBytes = 1024)
        }
    }

    @Test
    fun `rejects nonpositive expanded budgets`() {
        assertThat(assertThrows<IllegalArgumentException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(gzip("x".toByteArray()), maxExpandedBytes = 0)
        }).hasMessageThat().contains("positive")
        assertThat(assertThrows<IllegalArgumentException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(gzip("x".toByteArray()), maxExpandedBytes = -3)
        }).hasMessageThat().contains("positive")
    }

    @Test
    fun `accepts gzip within a maximum-int expanded budget without arithmetic overflow`() {
        val raw = "أثر".toByteArray()
        val decoded = BoundedBackupPayload.decodeAuthenticatedGzip(gzip(raw), maxExpandedBytes = Int.MAX_VALUE)
        assertThat(decoded).isEqualTo("أثر")
    }

    // -- gzip integrity propagation --------------------------------------------

    @Test
    fun `propagates truncated gzip as EOF rather than returning a partial payload`() {
        val compressed = gzip("أثر backup".toByteArray())
        val truncated = compressed.copyOfRange(0, compressed.size - 4)
        assertThrows<EOFException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(truncated, maxExpandedBytes = 1024)
        }
    }

    @Test
    fun `propagates CRC corruption as a zip failure`() {
        val compressed = gzip("أثر backup".toByteArray())
        compressed[compressed.size - 5] = (compressed[compressed.size - 5].toInt() xor 0x01).toByte()
        assertThrows<ZipException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(compressed, maxExpandedBytes = 1024)
        }
    }

    @Test
    fun `rejects empty gzip input as EOF`() {
        assertThrows<EOFException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(ByteArray(0), maxExpandedBytes = 16)
        }
    }

    // -- strict UTF-8 ------------------------------------------------------------

    @Test
    fun `rejects malformed UTF-8 inside an otherwise valid gzip stream`() {
        val malformed = byteArrayOf(0x61, 0xFF.toByte(), 0x62) // 'a', invalid byte, 'b'
        assertThrows<CharacterCodingException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(gzip(malformed), maxExpandedBytes = 16)
        }
    }

    @Test
    fun `decodes concatenated gzip members and bounds them as one payload`() {
        val first = gzip("أثر-".toByteArray())
        val second = gzip("one".toByteArray())
        val concatenated = first + second
        val decoded = BoundedBackupPayload.decodeAuthenticatedGzip(concatenated, maxExpandedBytes = concatenated.size * 4)
        assertThat(decoded).isEqualTo("أثر-one")
        // The bound applies across members, not per member.
        assertThrows<BackupPayloadBoundException> {
            BoundedBackupPayload.decodeAuthenticatedGzip(concatenated, maxExpandedBytes = "أثر-".toByteArray().size)
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun repeatingBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        var value = 0x2A
        for (i in out.indices) {
            value = (value * 31 + i) and 0xFF
            out[i] = value.toByte()
        }
        return out
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(raw) }
        return buffer.toByteArray()
    }

    /** Counts total bytes actually served by the delegate. */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var totalServed = 0
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) totalServed += 1
            return value
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(buffer, off, len)
            if (n > 0) totalServed += n
            return n
        }

        override fun close() = delegate.close()
    }

    private class CloseTrackingInputStream(private val delegate: InputStream) : InputStream() {
        var closed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, off: Int, len: Int): Int = delegate.read(buffer, off, len)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    /** Contract-violating stream: replies 0 to array reads while data remains. */
    private class ZeroReadInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, off: Int, len: Int): Int = 0

        override fun close() = delegate.close()
    }
}
