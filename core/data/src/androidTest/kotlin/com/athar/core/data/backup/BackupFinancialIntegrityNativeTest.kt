package com.athar.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.common.crypto.AtharCrypto
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.db.atharDatabaseBuilder
import com.athar.core.data.ledger.admission.AdmissionNativeDb
import com.athar.core.data.ledger.admission.UnallocatedAdmissionOutcome
import com.athar.core.data.ledger.admission.nativeGraph
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room instrumentation for the WM028-P semantic-integrity gate on the
 * genuine production path: an actually-admitted UNALLOCATED event (production
 * `UnallocatedAdmissionRepository`, never hand-seeded faux-certified rows)
 * must back up and restore unchanged, while tampered denormalized money,
 * corrupted archive bytes or routed authority refuse before any write with
 * exact full-row/blob evidence — not counts. Synthetic data only; owned
 * per-test database files with robust cleanup. Authored for device execution;
 * not run in this lane.
 */
@RunWith(AndroidJUnit4::class)
class BackupFinancialIntegrityNativeTest {

    private val passphrase = "synthetic-passphrase-028P".toCharArray()

    private fun service(db: AtharDatabase) = BackupService(db, Clock.System)

    private fun newDb(tag: String): AtharDatabase {
        require(tag.matches(Regex("[a-z0-9-]+")))
        return BackupInputNativeFixtures.newDb()
    }

    // --------------------------------------------------- envelope tampering

    private fun plainJsonOf(bytes: ByteArray): String {
        val decrypted = AtharCrypto.decrypt(bytes, passphrase)
        return GZIPInputStream(decrypted.inputStream()).use { it.readBytes() }
            .toString(Charsets.UTF_8)
    }

    private fun encryptedOf(plainJson: String): ByteArray {
        val gzipped = java.io.ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { it.write(plainJson.toByteArray(Charsets.UTF_8)) }
            buffer.toByteArray()
        }
        return AtharCrypto.encrypt(gzipped, passphrase)
    }

    /** Exact-substring rewrite; fails closed when the anchor is absent or ambiguous. */
    private fun rewrite(plainJson: String, anchor: String, replacement: String): String {
        assertThat(plainJson).contains(anchor)
        assertThat(Regex(Regex.escape(anchor)).findAll(plainJson).count()).isEqualTo(1)
        return plainJson.replace(anchor, replacement)
    }

    /** Admits through the real production repository and exports; returns (bytes, pre-export full state). */
    private fun admitAndExport(name: String, seedLegacySentinel: Boolean = false): Pair<ByteArray, Map<String, List<List<Any?>>>> = runBlocking {
        val source = newDb(name)
        try {
            val fixture = AdmissionNativeDb(source)
            if (seedLegacySentinel) fixture.seedLegacySentinel()
            val outcome = fixture.admit()
            assertThat(outcome).isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
            val state = BackupInputNativeFixtures.evidence(source)
            val exported = ByteArrayOutputStream().also { service(source).export(it, passphrase) }.toByteArray()
            exported to state
        } finally {
            BackupInputNativeFixtures.dispose(source)
        }
    }

    private fun refusesBeforeAnyWrite(
        target: AtharDatabase,
        bytes: ByteArray,
        code: BackupValidationCode,
    ) = runBlocking<Unit> {
        BackupInputNativeFixtures.seed(target)
        val outcome = AdmissionNativeDb(target).admit(nativeGraph(
            eventId = "target-event", sourceId = "target-source", amount = "50.00",
        ))
        assertThat(outcome).isInstanceOf(UnallocatedAdmissionOutcome.Admitted::class.java)
        val before = BackupInputNativeFixtures.evidence(target)
        try {
            service(target).import(bytes, passphrase)
            fail("Semantic integrity must refuse this backup before any write")
        } catch (expected: BackupValidationException) {
            assertThat(expected.code).isEqualTo(code)
        }
        assertThat(BackupInputNativeFixtures.evidence(target)).isEqualTo(before)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun admittedUnallocatedEventBacksUpAndRestoresUnchanged() = runBlocking<Unit> {
        val (exported, sourceState) = admitAndExport("028p-source")

        val target = newDb("028p-target")
        try {
            service(target).import(exported, passphrase)

            // Full row/blob evidence equality — excluding only the revision
            // singleton, which the strict-advance policy intentionally moves.
            val restored = BackupInputNativeFixtures.evidence(target)
            assertThat(restored.filterKeys { it != "canonical_ledger_revision" })
                .isEqualTo(sourceState.filterKeys { it != "canonical_ledger_revision" })
            assertThat(restored.getValue("canonical_ledger_revision")).isNotEmpty()

            // Exact original money spelling and routing survive admit → export → import.
            val event = target.canonicalLedgerDao().captureEvents().single()
            assertThat(event.bookedAmount).isEqualTo("200.00")
            assertThat(event.bookedCurrency).isEqualTo("SAR")
            assertThat(event.routing).isEqualTo("UNALLOCATED")
        } finally {
            BackupInputNativeFixtures.dispose(target)
        }
    }

    @Test
    fun tamperedBookedAmountRefusedBeforeAnyWrite() = runBlocking<Unit> {
        val (exported, _) = admitAndExport("028p-amount-source")
        val tampered = encryptedOf(
            rewrite(
                plainJsonOf(exported),
                "\"bookedAmount\":\"200.00\"",
                "\"bookedAmount\":\"9999.00\"",
            ),
        )
        val target = newDb("028p-amount-target")
        try {
            refusesBeforeAnyWrite(target, tampered, BackupValidationCode.CANONICAL_DENORMALIZATION_MISMATCH)
        } finally {
            BackupInputNativeFixtures.dispose(target)
        }
    }

    @Test
    fun corruptedAcceptedArchiveBytesRefusedBeforeAnyWrite() = runBlocking<Unit> {
        val (exported, _) = admitAndExport("028p-archive-source")
        val plain = plainJsonOf(exported)
        // The archived original payload no longer hashes to the observation's
        // accepted payload digest: transport survived, the archive did not.
        val anchor = Regex("\"payloadBase64\":\"[A-Za-z0-9+/=]+\"").find(plain)!!.value
        val tampered = encryptedOf(rewrite(plain, anchor, "\"payloadBase64\":\"dGFtcGVyZWQ=\""))
        val target = newDb("028p-archive-target")
        try {
            refusesBeforeAnyWrite(target, tampered, BackupValidationCode.CANONICAL_SNAPSHOT_BINDING_BROKEN)
        } finally {
            BackupInputNativeFixtures.dispose(target)
        }
    }

    @Test
    fun injectedLegIsUnsupportedRoutedAuthorityAndRefused() = runBlocking<Unit> {
        val (exported, _) = admitAndExport("028p-leg-source", seedLegacySentinel = true)
        val tampered = encryptedOf(
            rewrite(
                plainJsonOf(exported),
                "\"legs\":[]",
                "\"legs\":[{\"eventId\":\"event-a\",\"legRef\":\"leg-x\",\"accountRef\":\"native-cash\"," +
                    "\"delta\":\"-200.00\",\"currency\":\"SAR\",\"componentRef\":null}]",
            ),
        )
        val target = newDb("028p-leg-target")
        try {
            refusesBeforeAnyWrite(target, tampered, BackupValidationCode.CANONICAL_AUTHORITY_UNSUPPORTED)
        } finally {
            BackupInputNativeFixtures.dispose(target)
        }
    }
}
