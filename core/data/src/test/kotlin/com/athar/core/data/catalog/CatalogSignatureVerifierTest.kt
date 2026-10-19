package com.athar.core.data.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifier contract tests. All keys here are ephemeral test keys created inside
 * the test JVM through the constructor seam — the production trust anchor is
 * never a signature source for adversarial fixtures.
 */
class CatalogSignatureVerifierTest {

    @Test
    fun `verifies a correct DER signature over prefix plus payload`() {
        val keyPair = ephemeralKeyPair()
        val verifier = JcaCatalogSignatureVerifier(spkiBase64(keyPair))
        val payload = "payload-bytes".toByteArray(Charsets.UTF_8)

        val signature = sign(keyPair, CONTEXT_PREFIX, payload)

        assertThat(verifier.verify(CONTEXT_PREFIX, payload, signature)).isTrue()
    }

    @Test
    fun `rejects a tampered payload`() {
        val keyPair = ephemeralKeyPair()
        val verifier = JcaCatalogSignatureVerifier(spkiBase64(keyPair))
        val signature = sign(keyPair, CONTEXT_PREFIX, "original".toByteArray(Charsets.UTF_8))

        assertThat(
            verifier.verify(CONTEXT_PREFIX, "tampered".toByteArray(Charsets.UTF_8), signature),
        ).isFalse()
    }

    @Test
    fun `rejects a signature from a different key`() {
        val signer = ephemeralKeyPair()
        val otherKeyPair = ephemeralKeyPair()
        val verifier = JcaCatalogSignatureVerifier(spkiBase64(otherKeyPair))
        val payload = "payload-bytes".toByteArray(Charsets.UTF_8)
        val signature = sign(signer, CONTEXT_PREFIX, payload)

        assertThat(verifier.verify(CONTEXT_PREFIX, payload, signature)).isFalse()
    }

    @Test
    fun `rejects an IEEE-P1363 raw 64-byte signature instead of DER`() {
        val keyPair = ephemeralKeyPair()
        val verifier = JcaCatalogSignatureVerifier(spkiBase64(keyPair))
        val payload = "payload-bytes".toByteArray(Charsets.UTF_8)
        val der = sign(keyPair, CONTEXT_PREFIX, payload)

        val p1363 = derToP1363(der)

        // Standard JCA SHA256withECDSA consumes DER only; a raw r||s blob must not verify.
        assertThat(verifier.verify(CONTEXT_PREFIX, payload, p1363)).isFalse()
    }

    @Test
    fun `rejects malformed signature bytes without throwing`() {
        val keyPair = ephemeralKeyPair()
        val verifier = JcaCatalogSignatureVerifier(spkiBase64(keyPair))

        assertThat(
            verifier.verify(CONTEXT_PREFIX, "payload".toByteArray(), byteArrayOf(1, 2, 3)),
        ).isFalse()
    }

    @Test
    fun `production anchor accepts the parent's recorded public self-test vector`() {
        // Public, non-secret release data from plans/execution/002-catalog-public-key.json.
        // Proves the embedded production SPKI + JCA path work together; contains no private key.
        val verifier = JcaCatalogSignatureVerifier(ProductionCatalogTrust.PUBLIC_KEY_SPKI_BASE64)
        val message = Base64.getDecoder().decode(
            "QVRIQVItTUVSQ0hBTlQtQ0FUQUxPRy1LRVktU0VMRi1URVNUL3YxCg==",
        )
        val signature = Base64.getDecoder().decode(
            "MEQCIGCry4pVcR1BVBPthsOAXctskN1NpAg3vyJlLme9NCVVAiBEzEC//1hg54FGKROY4lEDdgkDz0QetEedN/XmgU3mxA==",
        )

        assertThat(verifier.verify(ByteArray(0), message, signature)).isTrue()
        assertThat(
            verifier.verify(ByteArray(0), "tampered".toByteArray(Charsets.UTF_8), signature),
        ).isFalse()
    }

    @Test
    fun `production anchor SPKI decodes as an EC P-256 public key`() {
        val spki = Base64.getDecoder().decode(ProductionCatalogTrust.PUBLIC_KEY_SPKI_BASE64)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

        assertThat(key.algorithm).isEqualTo("EC")
        val params = (key as java.security.interfaces.ECKey).params
        assertThat(params.curve.field.fieldSize).isEqualTo(256)
    }

    private fun ephemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun spkiBase64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun sign(keyPair: KeyPair, prefix: ByteArray, payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(prefix)
            update(payload)
        }.sign()

    /** Splits a DER ECDSA-Sig-Value into a raw 32+32 byte r||s blob (IEEE-P1363). */
    private fun derToP1363(der: ByteArray): ByteArray {
        // Minimal DER walk: 0x30 len 0x02 rLen r 0x02 sLen s
        var i = 2 // skip 0x30 + total length
        fun readInt(): ByteArray {
            // 0x02, length, bytes (strip leading zero pad byte)
            val len = der[i + 1].toInt()
            val value = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            return if (len == 33) value.copyOfRange(1, 33) else {
                val out = ByteArray(32)
                value.copyInto(out, 32 - value.size)
                out
            }
        }
        val r = readInt()
        val s = readInt()
        return r + s
    }
}
