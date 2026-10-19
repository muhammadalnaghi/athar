package com.athar.core.data.catalog

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject

/**
 * Signature seam for the shared catalog. Production binds
 * [JcaCatalogSignatureVerifier] over the pinned public SPKI; tests inject
 * ephemeral-key verifiers through this constructor seam only.
 */
internal fun interface CatalogSignatureVerifier {
    /** True iff [signature] is a valid DER ECDSA signature over prefix+payload. */
    fun verify(contextPrefix: ByteArray, payload: ByteArray, signature: ByteArray): Boolean
}

/**
 * Standard JCA `SHA256withECDSA` verification against an X.509 SPKI public key.
 * No custom signature math. IEEE-P1363 raw (r‖s) signatures are rejected
 * naturally — JCA consumes DER only. Any crypto failure degrades to `false`.
 */
internal class JcaCatalogSignatureVerifier @Inject constructor(
    private val publicKeySpkiBase64: String,
) : CatalogSignatureVerifier {

    private val publicKey by lazy {
        val spki = Base64.getDecoder().decode(publicKeySpkiBase64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
    }

    override fun verify(contextPrefix: ByteArray, payload: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(contextPrefix)
                update(payload)
            }.verify(signature)
        }.getOrDefault(false)
}
