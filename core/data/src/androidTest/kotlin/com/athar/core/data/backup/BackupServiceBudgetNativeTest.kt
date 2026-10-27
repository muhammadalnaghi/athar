package com.athar.core.data.backup

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.athar.core.data.backup.BackupInputNativeFixtures.dispose
import com.athar.core.data.backup.BackupInputNativeFixtures.encrypted
import com.athar.core.data.backup.BackupInputNativeFixtures.envelope
import com.athar.core.data.backup.BackupInputNativeFixtures.evidence
import com.athar.core.data.backup.BackupInputNativeFixtures.newDb
import com.athar.core.data.backup.BackupInputNativeFixtures.passphrase
import com.athar.core.data.backup.BackupInputNativeFixtures.seed
import com.athar.core.data.backup.input.BackupEnvelopeDecodeException
import com.athar.core.data.backup.input.BackupInputLimits
import com.athar.core.data.backup.input.BackupPayloadBoundException
import com.athar.core.data.backup.input.StrictBackupGuardException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class BackupServiceBudgetNativeTest {
    private class Input(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var readOnMain = false
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            if (Looper.myLooper() == Looper.getMainLooper()) readOnMain = true
            return super.read(bytes, off, len)
        }
        override fun close() { closed = true; super.close() }
    }

    private suspend fun refuses(
        expected: Class<out Throwable>,
        limits: BackupInputLimits = BackupInputLimits.Production,
        invoke: suspend (BackupService) -> Unit,
    ): Throwable {
        val db = newDb()
        try {
            seed(db)
            val before = evidence(db)
            var planned = false
            val service = BackupService(db, Clock.System, limits).also { it.restoreTransactionProbe = { planned = true } }
            val error = runCatching { invoke(service) }.exceptionOrNull()
            assertThat(error).isInstanceOf(expected)
            assertThat(planned).isFalse()
            assertThat(evidence(db)).isEqualTo(before)
            return requireNotNull(error)
        } finally {
            dispose(db)
        }
    }

    @Test
    fun exactCiphertextBoundaryRoundtripsWithoutClosingTheCallerStreamOrReadingOnMain() = runBlocking<Unit> {
        val source = newDb()
        val target = newDb()
        try {
            seed(source)
            val output = ByteArrayOutputStream()
            BackupService(source, Clock.System).export(output, passphrase)
            val bytes = output.toByteArray()
            val input = Input(bytes)
            try {
                val limits = BackupInputLimits.Production.copy(maxCiphertextBytes = bytes.size)
                withContext(Dispatchers.Main) { BackupService(target, Clock.System, limits).import(input, passphrase) }
                assertThat(input.closed).isFalse()
                assertThat(input.readOnMain).isFalse()
                assertThat(input.available()).isEqualTo(0)
                // Whole-table content is keyed data, not SQLite insertion order. In particular,
                // activityLog.all() captures newest-first and legitimately reverses rowid order.
                // Keep raw rowid evidence unchanged for the before/after refusal checks above.
                val expectedRows = evidence(source).filterKeys { it != "canonical_ledger_revision" }
                val actualRows = evidence(target).filterKeys { it != "canonical_ledger_revision" }
                assertThat(actualRows.keys).containsExactlyElementsIn(expectedRows.keys)
                expectedRows.forEach { (table, rows) ->
                    assertThat(actualRows.getValue(table)).containsExactlyElementsIn(rows)
                }
                assertThat(target.canonicalLedgerDao().captureRevisions().single { it.id == "singleton" }.revision).isEqualTo(1L)
            } finally {
                input.close()
            }
            assertThat(input.closed).isTrue()
        } finally {
            dispose(source)
            dispose(target)
        }
    }

    @Test
    fun oversizedStreamConsumesOnlyLimitPlusOneAndLeavesCallerOwnershipIntact() = runBlocking<Unit> {
        val input = Input(ByteArray(32))
        val limits = BackupInputLimits.Production.copy(maxCiphertextBytes = 8)
        refuses(BackupPayloadBoundException::class.java, limits) { it.import(input, passphrase) }
        assertThat(input.available()).isEqualTo(32 - (8 + 1))
        assertThat(input.closed).isFalse()
        input.close()
    }

    @Test
    fun oversizedDirectArraysRejectBeforeMalformedHeaderOrKdfProcessing() = runBlocking<Unit> {
        val limits = BackupInputLimits.Production.copy(maxCiphertextBytes = 8)
        refuses(BackupPayloadBoundException::class.java, limits) { it.import(ByteArray(9), passphrase) }
    }

    @Test
    fun authenticatedExpansionLimitRejectsBeforeAnyReplacement() = runBlocking<Unit> {
        val plain = envelope.toByteArray(Charsets.UTF_8)
        val bytes = encrypted(plain)
        val limits = BackupInputLimits.Production.copy(maxExpandedBytes = plain.size - 1)
        refuses(BackupPayloadBoundException::class.java, limits) { it.import(bytes, passphrase) }
    }

    @Test
    fun authenticatedDepthTokenAndStringBudgetsRejectBeforeAnyReplacement() = runBlocking<Unit> {
        val plain = (envelope.dropLast(1) + ",\"ignored\":\"" + "x".repeat(65) + "\"}").toByteArray(Charsets.UTF_8)
        val bytes = encrypted(plain)
        listOf(
            BackupInputLimits.Production.copy(maxDepth = 1),
            BackupInputLimits.Production.copy(maxTokens = 1L),
            BackupInputLimits.Production.copy(maxStringChars = 64),
        ).forEach { limits ->
            refuses(StrictBackupGuardException::class.java, limits) { it.import(bytes, passphrase) }
        }
    }

    @Test
    fun authenticationFailurePrecedesEvenARejectingJsonBudget() = runBlocking<Unit> {
        val bytes = encrypted(envelope.toByteArray(Charsets.UTF_8))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val limits = BackupInputLimits.Production.copy(maxDepth = 1)
        refuses(AEADBadTagException::class.java, limits) { it.import(bytes, passphrase) }
    }

    @Test
    fun dtoDecodeErrorsDoNotExposePayloadFragmentsOrChainedCauses() = runBlocking<Unit> {
        val marker = "PRIVATE_SYNTHETIC_MARKER"
        val plain = envelope.replaceFirst("\"accounts\":[]", "\"accounts\":null").dropLast(1) + ",\"ignored\":\"$marker\"}"
        val bytes = encrypted(plain.toByteArray(Charsets.UTF_8))
        val error = refuses(BackupEnvelopeDecodeException::class.java) { it.import(bytes, passphrase) }
        assertThat(error.cause).isNull()
        assertThat(error.message).isEqualTo("Backup structure is invalid or incomplete")
        assertThat(error.message).doesNotContain(marker)
    }

    @Test
    fun exportCannotWriteAnArtifactItsOwnExpandedPolicyWouldRefuse() = runBlocking<Unit> {
        val output = ByteArrayOutputStream()
        val limits = BackupInputLimits.Production.copy(maxExpandedBytes = 1)
        refuses(BackupPayloadBoundException::class.java, limits) { it.export(output, passphrase) }
        assertThat(output.size()).isEqualTo(0)
    }
}
