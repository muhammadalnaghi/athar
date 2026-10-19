package com.athar.core.data.catalog

import com.athar.core.domain.model.MerchantCatalogEntry
import com.athar.core.domain.model.PatternType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CatalogEnvelopeParserTest {

    private val now: Instant = Instant.parse("2026-10-16T12:00:00Z")
    private val keyPair = ephemeralKeyPair()

    /**
     * JVM tests bypass only the android.util.JsonReader token walk (an Android
     * platform class with no JVM implementation and no Robolectric in this
     * repo); kotlinx strict mode still enforces exact field sets. The
     * production default guard is [JsonReaderCatalogJsonGuard].
     */
    private val parser = CatalogEnvelopeParser(
        verifier = JcaCatalogSignatureVerifier(spkiBase64(keyPair)),
        clock = object : Clock {
            override fun now(): Instant = this@CatalogEnvelopeParserTest.now
        },
        guard = { _, _ -> },
    )

    @Test
    fun `accepts a well-formed signed envelope and exposes version entries and digest`() {
        val raw = signedEnvelope(
            payloadJson(
                version = 1,
                issuedAt = "2026-10-16T12:00:00Z",
                entries = entryJson(pattern = "starbucks", patternType = "SUBSTRING", categoryId = "cat-coffee"),
            ),
        )

        val result = parser.parse(raw)

        val verified = result as CatalogVerification.Valid
        assertThat(verified.catalog.version).isEqualTo(1)
        assertThat(verified.catalog.entries).containsExactly(
            MerchantCatalogEntry("starbucks", PatternType.SUBSTRING, "cat-coffee"),
        )
        assertThat(verified.catalog.payloadDigest)
            .isEqualTo(CatalogDigest.sha256Hex(verified.catalog.payloadBytes))
    }

    @Test
    fun `normalizes pattern whitespace and case`() {
        val raw = signedEnvelope(
            payloadJson(
                version = 1,
                issuedAt = "2026-10-16T12:00:00Z",
                entries = entryJson(pattern = "  Starbucks   Coffee ", patternType = "EXACT", categoryId = "cat-coffee"),
            ),
        )

        val verified = parser.parse(raw) as CatalogVerification.Valid

        assertThat(verified.catalog.entries.single().pattern).isEqualTo("starbucks coffee")
    }

    @Test
    fun `rejects a foreign keyId`() {
        val raw = signedEnvelope(
            payloadJson(1, "2026-10-16T12:00:00Z", entryJson("starbucks", "EXACT", "cat-coffee")),
            keyId = "p256-someoneelse",
        )

        assertThat(parser.parse(raw)).isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_KEY_ID))
    }

    @Test
    fun `rejects a tampered payload`() {
        // Re-sign the ORIGINAL payload, then swap in a tampered payload with the
        // original signature — exactly what an attacker would ship.
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("starbucks", "EXACT", "cat-coffee"))
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val signature = base64(sign(keyPair, payloadBytes))
        val tamperedBytes = payload.replace("starbucks", "starbuckz").toByteArray(Charsets.UTF_8)
        val envelope = """{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":"${base64(tamperedBytes)}","signature":"$signature"}"""

        assertThat(parser.parse(envelope.toByteArray(Charsets.UTF_8)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_SIGNATURE))
    }

    @Test
    fun `rejects malformed base64 in payload field`() {
        val envelope = """{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":"!!!not-base64!!!","signature":"AAAA"}"""
        assertThat(parser.parse(envelope.toByteArray(Charsets.UTF_8)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.MALFORMED_BASE64))
    }

    @Test
    fun `rejects an unknown envelope field`() {
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("starbucks", "EXACT", "cat-coffee"))
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val signature = base64(sign(keyPair, payloadBytes))
        val envelope = """{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":"${base64(payloadBytes)}","signature":"$signature","priority":"high"}"""

        assertThat(parser.parse(envelope.toByteArray(Charsets.UTF_8)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_JSON))
    }

    @Test
    fun `rejects an unknown payload field`() {
        val payload = """{"schemaVersion":1,"catalogVersion":1,"issuedAt":"2026-10-16T12:00:00Z","entries":[],"priority":"high"}"""
        val raw = signedEnvelope(payload)

        assertThat(parser.parse(raw)).isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_JSON))
    }

    @Test
    fun `rejects an unknown entry field`() {
        val payload = payloadJson(
            1,
            "2026-10-16T12:00:00Z",
            """{"pattern":"starbucks","patternType":"EXACT","categoryId":"cat-coffee","confidence":0.9}""",
        )

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_JSON))
    }

    @Test
    fun `rejects unsupported schema version`() {
        val payload = """{"schemaVersion":2,"catalogVersion":1,"issuedAt":"2026-10-16T12:00:00Z","entries":[]}"""

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.SCHEMA_UNSUPPORTED))
    }

    @Test
    fun `rejects non-positive or non-integral catalog version`() {
        val zero = """{"schemaVersion":1,"catalogVersion":0,"issuedAt":"2026-10-16T12:00:00Z","entries":[]}"""
        val fractional = """{"schemaVersion":1,"catalogVersion":1.5,"issuedAt":"2026-10-16T12:00:00Z","entries":[]}"""

        assertThat(parser.parse(signedEnvelope(zero)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
        assertThat(parser.parse(signedEnvelope(fractional)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_JSON))
    }

    @Test
    fun `rejects an issuedAt more than 24h in the future`() {
        val payload = payloadJson(1, "2026-10-18T12:00:01Z", "")

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.FUTURE_ISSUED_AT))
    }

    @Test
    fun `rejects an unparseable issuedAt`() {
        val payload = payloadJson(1, "not-a-timestamp", "")

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
    }

    @Test
    fun `rejects more than 10_000 entries`() {
        val entries = (0 until 10_001).joinToString(",") {
            entryJson("brand-$it", "EXACT", "cat-generic")
        }
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entries)

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.LIMIT_EXCEEDED))
    }

    @Test
    fun `rejects an envelope larger than 2 MiB`() {
        val filler = "x".repeat(2 * 1024 * 1024 + 1)
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson(filler.take(120), "EXACT", "cat-generic"))
        val raw = signedEnvelope(payload, payloadOverride = filler.toByteArray())

        assertThat(parser.parse(raw)).isEqualTo(CatalogVerification.Invalid(CatalogRejection.LIMIT_EXCEEDED))
    }

    @Test
    fun `rejects a decoded payload larger than 1 MiB`() {
        val filler = "x".repeat(1024 * 1024 + 1)
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson(filler.take(120), "EXACT", "cat-generic"))
        val raw = signedEnvelope(payload, payloadOverride = payload.toByteArray(Charsets.UTF_8) + filler.toByteArray())

        assertThat(parser.parse(raw)).isEqualTo(CatalogVerification.Invalid(CatalogRejection.LIMIT_EXCEEDED))
    }

    @Test
    fun `rejects out-of-bounds pattern lengths`() {
        val short = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("ab", "EXACT", "cat-coffee"))
        val long = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("a".repeat(129), "EXACT", "cat-coffee"))

        assertThat(parser.parse(signedEnvelope(short)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
        assertThat(parser.parse(signedEnvelope(long)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
    }

    @Test
    fun `rejects a pattern without any letter`() {
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("1234", "EXACT", "cat-coffee"))

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
    }

    @Test
    fun `rejects control characters in a pattern`() {
        val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("star\u0007bucks", "EXACT", "cat-coffee"))

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
    }

    @Test
    fun `rejects url email and phone-like patterns`() {
        val cases = listOf(
            "http://starbucks",
            "www starbucks com",
            "starbucks@corporate",
            "starbucks 9665",
            "+966 starbucks",
        )
        cases.forEach { pattern ->
            val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson(pattern, "SUBSTRING", "cat-coffee"))
            assertThat(parser.parse(signedEnvelope(payload)))
                .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
        }
    }

    @Test
    fun `rejects generic bank processor and transfer-only keys`() {
        listOf("mada", "sadad", "atm withdrawal", "bank transfer", "شبكة", "مدى").forEach { pattern ->
            val payload = payloadJson(1, "2026-10-16T12:00:00Z", entryJson(pattern, "EXACT", "cat-coffee"))
            assertThat(parser.parse(signedEnvelope(payload)))
                .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
        }
    }

    @Test
    fun `rejects remote regex pattern types`() {
        val payload = payloadJson(
            1,
            "2026-10-16T12:00:00Z",
            entryJson("starbucks.*", "REGEX", "cat-coffee"),
        )

        assertThat(parser.parse(signedEnvelope(payload)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
    }

    @Test
    fun `rejects a category outside the cat- namespace`() {
        val upper = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("starbucks", "EXACT", "cat-Coffee"))
        val missingPrefix = payloadJson(1, "2026-10-16T12:00:00Z", entryJson("starbucks", "EXACT", "coffee"))

        assertThat(parser.parse(signedEnvelope(upper)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
        assertThat(parser.parse(signedEnvelope(missingPrefix)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.INVALID_FIELD))
    }

    @Test
    fun `rejects contradictory duplicate identities and dedupes agreeing ones`() {
        val contradictory = payloadJson(
            1,
            "2026-10-16T12:00:00Z",
            listOf(
                entryJson("Starbucks", "EXACT", "cat-coffee"),
                entryJson("starbucks", "EXACT", "cat-restaurant"),
            ).joinToString(","),
        )
        assertThat(parser.parse(signedEnvelope(contradictory)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.DUPLICATE_IDENTITY))

        val agreeing = payloadJson(
            1,
            "2026-10-16T12:00:00Z",
            listOf(
                entryJson("Starbucks", "EXACT", "cat-coffee"),
                entryJson("  starbucks ", "EXACT", "cat-coffee"),
            ).joinToString(","),
        )
        val verified = parser.parse(signedEnvelope(agreeing)) as CatalogVerification.Valid
        assertThat(verified.catalog.entries).hasSize(1)
    }

    @Test
    fun `rejects an IEEE-P1363 raw signature at the processor level`() {
        val payloadBytes = payloadJson(
            1,
            "2026-10-16T12:00:00Z",
            entryJson("starbucks", "EXACT", "cat-coffee"),
        ).toByteArray(Charsets.UTF_8)
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(CONTEXT_PREFIX)
            update(payloadBytes)
        }.sign()
        val raw64 = p1363Of(der) // 64-byte r||s, standard JCA must reject it
        val envelope = """{"keyId":"${ProductionCatalogTrust.KEY_ID}","payload":"${base64(payloadBytes)}","signature":"${base64(raw64)}"}"""

        assertThat(parser.parse(envelope.toByteArray(Charsets.UTF_8)))
            .isEqualTo(CatalogVerification.Invalid(CatalogRejection.BAD_SIGNATURE))
    }

    @Test
    fun `verifies the real production-signed public release fixture end to end`() {
        val raw = javaClass.getResourceAsStream("/catalog/catalog-v1.json")?.readBytes()

        assertThat(raw).isNotNull()
        // The release fixture is signed by the PRODUCTION CNG key, so this test
        // verifies against the pinned production anchor (public data only).
        val productionParser = CatalogEnvelopeParser(
            verifier = JcaCatalogSignatureVerifier(ProductionCatalogTrust.PUBLIC_KEY_SPKI_BASE64),
            clock = object : Clock {
                override fun now(): Instant = this@CatalogEnvelopeParserTest.now
            },
            guard = { _, _ -> },
        )
        val result = productionParser.parse(raw!!)

        val verified = result as CatalogVerification.Valid
        // Parent-recorded digest of the exact release payload bytes.
        assertThat(verified.catalog.payloadDigest)
            .isEqualTo("c8c837bafcb8915b8237ecc9ec54f41a255d20a3b6baa5fd0f431074454a7d19")
        assertThat(verified.catalog.version).isEqualTo(1)
        assertThat(verified.catalog.entries).hasSize(58)
        // issuedAt is real publication time; must be within the future-drift bound at the fixed clock.
        assertThat(verified.catalog.issuedAt).isEqualTo(Instant.parse("2026-10-16T13:39:48Z"))
    }

    /** Splits DER ECDSA-Sig-Value into a raw 32+32 byte r||s blob (IEEE-P1363). */
    private fun p1363Of(der: ByteArray): ByteArray {
        var i = 2 // skip 0x30 + total length
        fun readInt(): ByteArray {
            val len = der[i + 1].toInt()
            val value = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            return if (len == 33) value.copyOfRange(1, 33) else {
                val out = ByteArray(32)
                value.copyInto(out, 32 - value.size)
                out
            }
        }
        return readInt() + readInt()
    }

    // ---- helpers ----

    private fun payloadJson(version: Long, issuedAt: String, entries: String): String =
        """{"schemaVersion":1,"catalogVersion":$version,"issuedAt":"$issuedAt","entries":[$entries]}"""

    private fun entryJson(pattern: String, patternType: String, categoryId: String): String =
        """{"pattern":"$pattern","patternType":"$patternType","categoryId":"$categoryId"}"""

    private fun signedEnvelope(
        payloadJson: String,
        keyId: String = ProductionCatalogTrust.KEY_ID,
        keyPair: KeyPair = this.keyPair,
        payloadOverride: ByteArray? = null,
    ): ByteArray {
        val payloadBytes = payloadOverride ?: payloadJson.toByteArray(Charsets.UTF_8)
        val signature = Base64.getEncoder().encodeToString(sign(keyPair, payloadBytes))
        return """{"keyId":"$keyId","payload":"${base64(payloadBytes)}","signature":"$signature"}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun sign(keyPair: KeyPair, payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(CONTEXT_PREFIX)
            update(payload)
        }.sign()

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun spkiBase64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun ephemeralKeyPair(): KeyPair =
        java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
}
