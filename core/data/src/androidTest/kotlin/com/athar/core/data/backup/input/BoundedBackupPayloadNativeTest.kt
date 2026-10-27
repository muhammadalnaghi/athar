package com.athar.core.data.backup.input

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream

/**
 * On-device sanity mirror for [BoundedBackupPayload]: the JVM suite owns the
 * full adversarial matrix (pure JDK), this proves the same bounds hold under
 * the Android runtime the production restore path actually uses.
 */
@RunWith(AndroidJUnit4::class)
class BoundedBackupPayloadNativeTest {

    @Test
    fun boundsCiphertextOnDeviceAndKeepsTheCallerStreamOpen() {
        val payload = "أثر ciphertext 0123456789".toByteArray(Charsets.UTF_8)
        val stream = TrackingStream(ByteArrayInputStream(payload))
        assertThat(BoundedBackupPayload.readCiphertext(stream, maxBytes = payload.size))
            .isEqualTo(payload)
        assertThat(stream.closed).isFalse()
        // One byte over the budget rejects without closing the caller's stream,
        // and consumption stops at exactly limit+1 bytes.
        val oversized = payload + byteArrayOf(1, 2, 3, 4, 5)
        val over = TrackingStream(ByteArrayInputStream(oversized))
        val failure = runCatching {
            BoundedBackupPayload.readCiphertext(over, maxBytes = payload.size)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(BackupPayloadBoundException::class.java)
        assertThat(over.closed).isFalse()
        assertThat(over.served).isEqualTo(payload.size + 1)
        assertThat(over.read()).isEqualTo(2) // Probe consumed tail byte1; exactly bytes2..5 remained.
    }

    @Test
    fun stopsAGzipBombOnDeviceWithinTheExpandedBudget() {
        val bomb = gzip(ByteArray(512 * 1024))
        assertThat(bomb.size).isLessThan(8192)
        val failure = runCatching {
            BoundedBackupPayload.decodeAuthenticatedGzip(bomb, maxExpandedBytes = 1024)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(BackupPayloadBoundException::class.java)
    }

    @Test
    fun propagatesCRCCorruptionAndMalformedUTF8OnDevice() {
        val compressed = gzip("أثر backup".toByteArray())
        val corrupt = compressed.copyOf(compressed.size).also {
            it[it.size - 5] = (it[it.size - 5].toInt() xor 0x01).toByte()
        }
        val crcFailure = runCatching {
            BoundedBackupPayload.decodeAuthenticatedGzip(corrupt, maxExpandedBytes = 4096)
        }.exceptionOrNull()
        assertThat(crcFailure).isInstanceOf(java.util.zip.ZipException::class.java)

        val malformed = gzip(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        val utf8Failure = runCatching {
            BoundedBackupPayload.decodeAuthenticatedGzip(malformed, maxExpandedBytes = 64)
        }.exceptionOrNull()
        assertThat(utf8Failure).isInstanceOf(java.nio.charset.CharacterCodingException::class.java)
    }

    @Test
    fun decodesConcatenatedMembersAsOneBoundedPayloadOnDevice() {
        val concatenated = gzip("أثر-".toByteArray()) + gzip("one".toByteArray())
        val decoded = BoundedBackupPayload.decodeAuthenticatedGzip(
            concatenated,
            maxExpandedBytes = concatenated.size * 4,
        )
        assertThat(decoded).isEqualTo("أثر-one")
        val bound = "أثر-".toByteArray().size
        val overBound = runCatching {
            BoundedBackupPayload.decodeAuthenticatedGzip(concatenated, maxExpandedBytes = bound)
        }.exceptionOrNull()
        assertThat(overBound).isInstanceOf(BackupPayloadBoundException::class.java)
    }

    private class TrackingStream(private val delegate: InputStream) : InputStream() {
        var closed = false
            private set
        var served = 0
            private set
        override fun read(): Int = delegate.read().also { if (it >= 0) served++ }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            delegate.read(bytes, offset, length).also { if (it > 0) served += it }
        override fun close() { closed = true; delegate.close() }
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(raw) }
        return buffer.toByteArray()
    }
}
