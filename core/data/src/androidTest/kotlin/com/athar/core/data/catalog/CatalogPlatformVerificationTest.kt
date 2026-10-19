package com.athar.catalog

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.data.catalog.CatalogEnvelopeParser
import com.athar.core.data.catalog.CatalogRejection
import com.athar.core.data.catalog.CatalogVerification
import com.athar.core.data.catalog.JcaCatalogSignatureVerifier
import com.athar.core.data.catalog.ProductionCatalogTrust
import com.athar.core.data.catalog.AtomicFileCatalogCacheStore
import com.athar.core.data.catalog.AtomicFileCatalogConsentFence
import com.athar.core.data.catalog.CatalogConsentFenceSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL platform verification for the shared catalog (parent preflight F01/F06):
 * the actual android.util.JsonReader guard and the actual AtomicFile behavior —
 * not fakes, not no-op seams. Parent runs this on the disposable QA emulator.
 */
@RunWith(AndroidJUnit4::class)
class CatalogPlatformVerificationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realUnreadableCacheIsAnErrorRatherThanAnAbsentCatalog() = kotlinx.coroutines.runBlocking {
        val store = AtomicFileCatalogCacheStore(context)
        val current = java.io.File(context.filesDir, "merchant_catalog/catalog_current.bin")
        store.clear()
        current.mkdirs()
        try {
            val failure = runCatching { store.readCurrent() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        } finally {
            current.deleteRecursively()
        }
        val fixture = javaClass.getResourceAsStream("/catalog/catalog-v1.json")!!.readBytes()
        try {
            store.promoteAuthenticated(fixture, trustedPrevious = null)
            assertThat(store.readCurrent()).isEqualTo(fixture)
        } finally {
            store.clear()
        }
    }

    @Test
    fun realDisableFenceSurvivesRecreationAndCacheReset() = kotlinx.coroutines.runBlocking {
        val first = AtomicFileCatalogConsentFence(context)
        first.clear()
        try {
            assertThat(first.read()).isEqualTo(CatalogConsentFenceSnapshot(disabled = false, certain = true))
            first.disable()
            AtomicFileCatalogCacheStore(context).clear()
            val recreated = AtomicFileCatalogConsentFence(context)
            assertThat(recreated.read()).isEqualTo(CatalogConsentFenceSnapshot(disabled = true, certain = true))
            recreated.clear()
            assertThat(AtomicFileCatalogConsentFence(context).read())
                .isEqualTo(CatalogConsentFenceSnapshot(disabled = false, certain = true))
        } finally {
            first.clear()
        }
    }

    @Test
    fun realDisableFenceRejectsCorruptionAndInterruptedNewFile() = kotlinx.coroutines.runBlocking {
        val directory = java.io.File(context.filesDir, "consent-fence-corruption-test")
        directory.mkdirs()
        val marker = java.io.File(directory, "catalog_disable.fence")
        val fence = AtomicFileCatalogConsentFence(directory)
        try {
            marker.writeBytes(byteArrayOf(0x44, 0x00))
            assertThat(fence.read()).isEqualTo(CatalogConsentFenceSnapshot(disabled = true, certain = false))
            marker.delete()
            java.io.File(marker.path + ".new").writeBytes(byteArrayOf(0x44))
            assertThat(fence.read()).isEqualTo(CatalogConsentFenceSnapshot(disabled = true, certain = false))
            fence.clear()
            assertThat(fence.read()).isEqualTo(CatalogConsentFenceSnapshot(disabled = false, certain = true))
            fence.disable()
            marker.renameTo(java.io.File(marker.path + ".bak"))
            marker.writeBytes(byteArrayOf(0x00))
            assertThat(AtomicFileCatalogConsentFence(directory).read())
                .isEqualTo(CatalogConsentFenceSnapshot(disabled = true, certain = true))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun realDisableFenceTreatsUnreadableStorageAsUncertain() = kotlinx.coroutines.runBlocking {
        val blockedDirectory = java.io.File(context.filesDir, "consent-fence-blocked-test")
        blockedDirectory.writeText("synthetic blocking file")
        try {
            assertThat(AtomicFileCatalogConsentFence(blockedDirectory).read())
                .isEqualTo(CatalogConsentFenceSnapshot(disabled = true, certain = false))
        } finally {
            blockedDirectory.delete()
        }
    }

    private fun productionParser(): CatalogEnvelopeParser = CatalogEnvelopeParser(
        verifier = JcaCatalogSignatureVerifier(ProductionCatalogTrust.PUBLIC_KEY_SPKI_BASE64),
        clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-16T14:00:00Z")
        },
        // No guard override: the REAL android.util.JsonReader guard runs here.
    )

    @Test
    fun realGuardAcceptsTheProductionSignedFixtureWithDefaultDepthBudget() {
        val raw = javaClass.getResourceAsStream("/catalog/catalog-v1.json")!!.readBytes()

        val result = productionParser().parse(raw)

        val verified = result as CatalogVerification.Valid
        assertThat(verified.catalog.version).isEqualTo(1)
        assertThat(verified.catalog.entries).hasSize(58)
        assertThat(verified.catalog.payloadDigest)
            .isEqualTo("c8c837bafcb8915b8237ecc9ec54f41a255d20a3b6baa5fd0f431074454a7d19")
    }

    @Test
    fun realGuardRejectsWellFormedDuplicateJsonKeys() {
        // Insert a complete duplicate property immediately after the root brace.
        // This preserves the real payload/signature and is independent of fixture
        // whitespace, so the guard—not malformed JSON—proves duplicate rejection.
        val original = javaClass.getResourceAsStream("/catalog/catalog-v1.json")!!
            .readBytes()
            .toString(Charsets.UTF_8)
        val duplicate = original.replaceFirst(
            "{",
            "{\n  \"keyId\": \"${ProductionCatalogTrust.KEY_ID}\",")

        assertThat(duplicate).isNotEqualTo(original)
        assertThat(duplicate.split("\"keyId\"")).hasSize(3)
        assertThat(duplicate).contains("\"payload\"")
        assertThat(duplicate).contains("\"signature\"")

        val result = productionParser().parse(duplicate.toByteArray(Charsets.UTF_8))

        assertThat(result).isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_JSON))
    }

    @Test
    fun realGuardRejectsDeeplyNestedMaliciousEnvelope() {
        // The envelope guard (budget 1: one flat object of scalars) must reject a
        // payload field that is a nested OBJECT instead of a Base64 string — this
        // happens before any signature work, so no signing key is needed.
        val envelope = """{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":{"nested":["deep"]},"signature":"AAAA"}"""

        val result = productionParser().parse(envelope.toByteArray(Charsets.UTF_8))

        assertThat(result).isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_JSON))
    }

    @Test
    fun realAtomicFileRoundTripsAndSurvivesRecovery() {
        val store = AtomicFileCatalogCacheStore(context)
        val first = javaClass.getResourceAsStream("/catalog/catalog-v1.json")!!.readBytes()

        kotlinx.coroutines.runBlocking {
            store.clear()
            store.promoteAuthenticated(first, trustedPrevious = null)
            store.promoteAuthenticated(
                first.copyOf(first.size + 1).also { it[first.size] = 0x7D },
                trustedPrevious = first,
            )

            assertThat(store.readPrevious()).isEqualTo(first)
            assertThat(store.readCurrent()).isNotNull()

            store.clear()
            assertThat(store.readCurrent()).isNull()
            assertThat(store.readPrevious()).isNull()
        }
    }

    @Test
    fun realAtomicFileRetainsAuthenticatedPreviousWhenCurrentWasCorrupt() {
        val store = AtomicFileCatalogCacheStore(context)
        val verified = javaClass.getResourceAsStream("/catalog/catalog-v1.json")!!.readBytes()
        assertThat(productionParser().parse(verified)).isInstanceOf(CatalogVerification.Valid::class.java)
        val current = java.io.File(context.filesDir, "merchant_catalog/catalog_current.bin")

        kotlinx.coroutines.runBlocking {
            store.clear()
            try {
                store.promoteAuthenticated(verified, trustedPrevious = null)
                store.promoteAuthenticated(verified, trustedPrevious = verified)
                current.writeBytes("corrupt".toByteArray())

                store.promoteAuthenticated(verified, trustedPrevious = verified)

                assertThat(store.readPrevious()).isEqualTo(verified)
                assertThat(store.readCurrent()).isEqualTo(verified)
            } finally {
                store.clear()
            }
        }
    }

    @Test
    fun realAtomicFileRecoversBackupRejectsOversizeAndClearsNewRemnants() {
        val store = AtomicFileCatalogCacheStore(context)
        val first = javaClass.getResourceAsStream("/catalog/catalog-v1.json")!!.readBytes()
        val directory = java.io.File(context.filesDir, "merchant_catalog")
        val current = java.io.File(directory, "catalog_current.bin")
        val backup = java.io.File(directory, "catalog_current.bin.bak")
        val newFile = java.io.File(directory, "catalog_current.bin.new")

        kotlinx.coroutines.runBlocking {
            store.clear()
            current.writeBytes(ByteArray(com.athar.core.data.catalog.CatalogEnvelopeParser.MAX_ENVELOPE_BYTES + 1))
            assertThat(store.readCurrent()).isNull()
            store.clear()

            current.writeBytes("corrupt".toByteArray())
            backup.writeBytes(first)
            assertThat(store.readCurrent()).isEqualTo(first)

            newFile.writeBytes(byteArrayOf(1, 2, 3))
            store.clear()
            assertThat(current.exists()).isFalse()
            assertThat(backup.exists()).isFalse()
            assertThat(newFile.exists()).isFalse()
        }
    }
}
