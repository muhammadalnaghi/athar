package com.athar.feature.settings

import com.athar.core.domain.repo.BackupRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class BackupFileTransferTest {
    private class Input : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var closed = false
        var closeThread: Thread? = null
        override fun close() { closed = true; closeThread = Thread.currentThread(); super.close() }
    }
    private class Output : ByteArrayOutputStream() {
        var closed = false
        var closeThread: Thread? = null
        override fun close() { closed = true; closeThread = Thread.currentThread(); super.close() }
    }
    private class Repository(
        val onImport: suspend (InputStream, CharArray) -> Unit = { _, _ -> },
        val onExport: suspend (OutputStream, CharArray) -> Unit = { _, _ -> },
    ) : BackupRepository {
        var imports = 0
        var exports = 0
        override suspend fun import(input: InputStream, passphrase: CharArray) { imports++; onImport(input, passphrase) }
        override suspend fun export(output: OutputStream, passphrase: CharArray) { exports++; onExport(output, passphrase) }
        override suspend fun import(bytes: ByteArray, passphrase: CharArray) {
            throw AssertionError("URI transfer must not buffer into the byte-array overload")
        }
    }
    private fun password() = "synthetic-transfer".toCharArray()
    private fun assertCleared(password: CharArray) { assertThat(password.all { it == 0.toChar() }).isTrue() }

    @Test
    fun `stream import opens reads and closes off the caller thread`() {
        val caller = Thread.currentThread()
        val input = Input()
        val passphrase = password()
        val repository = Repository(onImport = { stream, secret ->
            assertThat(Thread.currentThread()).isNotSameInstanceAs(caller)
            assertThat(stream).isSameInstanceAs(input)
            assertThat(input.closed).isFalse()
            assertThat(secret.any { it != 0.toChar() }).isTrue()
            assertThat(stream.readBytes().toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        })
        runBlocking {
            BackupFileTransfer.import(repository, passphrase) {
                assertThat(Thread.currentThread()).isNotSameInstanceAs(caller)
                input
            }
        }
        assertThat(repository.imports).isEqualTo(1)
        assertThat(input.closed).isTrue()
        assertThat(input.closeThread).isNotSameInstanceAs(caller)
        assertCleared(passphrase)
    }

    @Test
    fun `failed import closes its stream and clears the temporary password`() {
        val input = Input()
        val passphrase = password()
        val repository = Repository(onImport = { _, _ -> throw IOException("synthetic failure") })
        assertThrows<IOException> { runBlocking { BackupFileTransfer.import(repository, passphrase) { input } } }
        assertThat(input.closed).isTrue()
        assertCleared(passphrase)
    }

    @Test
    fun `missing input does not call the repository and clears the temporary password`() {
        val passphrase = password()
        val repository = Repository()
        assertThrows<IOException> { runBlocking { BackupFileTransfer.import(repository, passphrase) { null } } }
        assertThat(repository.imports).isEqualTo(0)
        assertCleared(passphrase)
    }

    @Test
    fun `cancelled import propagates cancellation and closes its stream`() {
        val input = Input()
        val passphrase = password()
        val repository = Repository(onImport = { _, _ -> throw CancellationException("synthetic cancellation") })
        assertThrows<CancellationException> { runBlocking { BackupFileTransfer.import(repository, passphrase) { input } } }
        assertThat(input.closed).isTrue()
        assertCleared(passphrase)
    }

    @Test
    fun `export opens writes and closes off the caller thread`() {
        val caller = Thread.currentThread()
        val output = Output()
        val passphrase = password()
        val repository = Repository(onExport = { stream, secret ->
            assertThat(Thread.currentThread()).isNotSameInstanceAs(caller)
            assertThat(stream).isSameInstanceAs(output)
            assertThat(secret.any { it != 0.toChar() }).isTrue()
            stream.write(byteArrayOf(3, 2, 1))
        })
        runBlocking {
            BackupFileTransfer.export(repository, passphrase) {
                assertThat(Thread.currentThread()).isNotSameInstanceAs(caller)
                output
            }
        }
        assertThat(repository.exports).isEqualTo(1)
        assertThat(output.toByteArray().toList()).containsExactly(3.toByte(), 2.toByte(), 1.toByte()).inOrder()
        assertThat(output.closed).isTrue()
        assertThat(output.closeThread).isNotSameInstanceAs(caller)
        assertCleared(passphrase)
    }

    @Test
    fun `failed export closes its stream and clears the temporary password`() {
        val output = Output()
        val passphrase = password()
        val repository = Repository(onExport = { _, _ -> throw IOException("synthetic failure") })
        assertThrows<IOException> { runBlocking { BackupFileTransfer.export(repository, passphrase) { output } } }
        assertThat(output.closed).isTrue()
        assertCleared(passphrase)
    }

    @Test
    fun `missing output does not call the repository and clears the temporary password`() {
        val passphrase = password()
        val repository = Repository()
        assertThrows<IOException> { runBlocking { BackupFileTransfer.export(repository, passphrase) { null } } }
        assertThat(repository.exports).isEqualTo(0)
        assertCleared(passphrase)
    }
}
