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
import com.athar.core.data.backup.input.StrictBackupGuardException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicBoolean

/** Real service, crypto, Android reader and Room; synthetic owned databases only. */
@RunWith(AndroidJUnit4::class)
class BackupServiceInputNativeTest {
    private suspend fun rejects(plain: ByteArray, expected: Class<out Throwable>) {
        val db = newDb()
        try {
            seed(db)
            val before = evidence(db)
            var planned = false
            val service = BackupService(db, Clock.System).also { it.restoreTransactionProbe = { planned = true } }
            val error = runCatching { service.import(encrypted(plain), passphrase) }.exceptionOrNull()
            assertThat(error).isInstanceOf(expected)
            assertThat(planned).isFalse()
            assertThat(evidence(db)).isEqualTo(before)
        } finally {
            dispose(db)
        }
    }

    @Test
    fun duplicateRootVersionCannotSilentlyChooseTheLastValue() = runBlocking<Unit> {
        rejects(envelope.replaceFirst("\"version\":4", "\"version\":1,\"version\":4").toByteArray(Charsets.UTF_8), StrictBackupGuardException::class.java)
    }

    @Test
    fun duplicateNestedUnknownKeysCannotBypassTheEnvelopeGuard() = runBlocking<Unit> {
        rejects((envelope.dropLast(1) + ",\"ignored\":{\"note\":1,\"note\":2}}").toByteArray(Charsets.UTF_8), StrictBackupGuardException::class.java)
    }

    @Test
    fun malformedUtf8CannotSilentlyReplaceExistingMoney() = runBlocking<Unit> {
        val plain = (envelope.dropLast(1) + ",\"ignored\":\"").toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray(Charsets.UTF_8)
        rejects(plain, CharacterCodingException::class.java)
    }

    @Test
    fun exportWritesOffTheAndroidMainThread() = runBlocking<Unit> {
        val db = newDb()
        try {
            seed(db)
            val wrote = AtomicBoolean(false)
            val wroteOnMain = AtomicBoolean(false)
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, off: Int, len: Int) {
                    wrote.set(true)
                    if (Looper.myLooper() == Looper.getMainLooper()) wroteOnMain.set(true)
                    super.write(bytes, off, len)
                }
            }
            withContext(Dispatchers.Main) { BackupService(db, Clock.System).export(output, passphrase) }
            assertThat(wrote.get()).isTrue()
            assertThat(wroteOnMain.get()).isFalse()
        } finally {
            dispose(db)
        }
    }
}
