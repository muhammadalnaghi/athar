package com.athar.core.data.catalog

import com.athar.core.domain.model.MerchantCatalogEntry
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.isSpecificMerchantKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

/** Outcome of processing a raw downloaded envelope. */
internal sealed interface CatalogVerification {
    data class Valid(val catalog: VerifiedCatalog) : CatalogVerification
    data class Invalid(val reason: CatalogRejection) : CatalogVerification
}

/** A fully verified catalog: signature, schema, bounds and identity checks all passed. */
internal class VerifiedCatalog(
    val version: Long,
    val issuedAt: Instant,
    val entries: List<MerchantCatalogEntry>,
    val payloadBytes: ByteArray,
    val payloadDigest: String,
)

/** Machine-readable rejection reasons; mapped to generic localized strings at the UI layer. */
internal enum class CatalogRejection {
    LIMIT_EXCEEDED,
    MALFORMED_BASE64,
    BAD_KEY_ID,
    BAD_SIGNATURE,
    BAD_JSON,
    SCHEMA_UNSUPPORTED,
    INVALID_FIELD,
    FUTURE_ISSUED_AT,
    DUPLICATE_IDENTITY,
    UNACCEPTABLE_VERSION,
}

/**
 * Parses and verifies one downloaded envelope against the frozen wire contract:
 * strict UTF-8, exact field sets, shallow JsonReader guard, strict Base64, JCA
 * DER signature, entry bounds/namespace/identity validation. The exact decoded
 * payload bytes are never canonicalized or re-serialized.
 */
internal class CatalogEnvelopeParser(
    private val verifier: CatalogSignatureVerifier,
    private val clock: Clock,
    private val guard: CatalogJsonGuard = JsonReaderCatalogJsonGuard,
) {

    @Serializable
    private data class EnvelopeDto(
        val keyId: String,
        val payload: String,
        val signature: String,
    )

    @Serializable
    private data class PayloadDto(
        val schemaVersion: Int,
        val catalogVersion: Long,
        val issuedAt: String,
        val entries: List<EntryDto>,
    )

    @Serializable
    private data class EntryDto(
        val pattern: String,
        val patternType: String,
        val categoryId: String,
    )

    private val json = Json { ignoreUnknownKeys = false }

    fun parse(raw: ByteArray): CatalogVerification = runCatching { parseOrThrow(raw) }
        .getOrElse { failure ->
            CatalogVerification.Invalid(
                when (failure) {
                    is CatalogGuardException, is SerializationException -> CatalogRejection.BAD_JSON
                    is CatalogParseException -> failure.rejection
                    else -> CatalogRejection.BAD_JSON
                },
            )
        }

    private fun parseOrThrow(raw: ByteArray): CatalogVerification {
        if (raw.size > MAX_ENVELOPE_BYTES) throw CatalogParseException(CatalogRejection.LIMIT_EXCEEDED)
        val envelopeText = strictUtf8(raw) ?: throw CatalogParseException(CatalogRejection.BAD_JSON)

        // Shallow lexical guard on the envelope: one root object of scalars only
        // (root depth 0, scalar properties at depth 1 — payload is a STRING here).
        guard.validate(envelopeText, maxDepth = 1)
        val envelope = json.decodeFromString<EnvelopeDto>(envelopeText)

        val payloadBytes = runCatching { Base64.getDecoder().decode(envelope.payload) }
            .getOrElse { throw CatalogParseException(CatalogRejection.MALFORMED_BASE64) }
        val signatureBytes = runCatching { Base64.getDecoder().decode(envelope.signature) }
            .getOrElse { throw CatalogParseException(CatalogRejection.MALFORMED_BASE64) }

        if (payloadBytes.size > MAX_PAYLOAD_BYTES) throw CatalogParseException(CatalogRejection.LIMIT_EXCEEDED)
        if (envelope.keyId != ProductionCatalogTrust.KEY_ID) throw CatalogParseException(CatalogRejection.BAD_KEY_ID)
        if (!verifier.verify(CONTEXT_PREFIX, payloadBytes, signatureBytes)) {
            throw CatalogParseException(CatalogRejection.BAD_SIGNATURE)
        }

        val payloadText = strictUtf8(payloadBytes) ?: throw CatalogParseException(CatalogRejection.BAD_JSON)
        // Payload shape: root object(0) → entries array(1) → entry object(2) →
        // scalar properties(3). Budget 3 accepts exactly this and rejects anything
        // nested deeper (malicious deep-nesting input).
        guard.validate(payloadText, maxDepth = 3)
        val payload = json.decodeFromString<PayloadDto>(payloadText)

        if (payload.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw CatalogParseException(CatalogRejection.SCHEMA_UNSUPPORTED)
        }
        if (payload.catalogVersion < 1) throw CatalogParseException(CatalogRejection.INVALID_FIELD)
        if (payload.entries.size > MAX_ENTRIES) throw CatalogParseException(CatalogRejection.LIMIT_EXCEEDED)

        val issuedAt = runCatching { Instant.parse(payload.issuedAt) }
            .getOrElse { throw CatalogParseException(CatalogRejection.INVALID_FIELD) }
        if (issuedAt > clock.now().plus(FUTURE_DRIFT_HOURS, kotlinx.datetime.DateTimeUnit.HOUR)) {
            throw CatalogParseException(CatalogRejection.FUTURE_ISSUED_AT)
        }

        val entries = validateEntries(payload.entries)
            ?: throw CatalogParseException(CatalogRejection.DUPLICATE_IDENTITY)

        return CatalogVerification.Valid(
            VerifiedCatalog(
                version = payload.catalogVersion,
                issuedAt = issuedAt,
                entries = entries,
                payloadBytes = payloadBytes,
                payloadDigest = CatalogDigest.sha256Hex(payloadBytes),
            ),
        )
    }

    /** Returns null when two normalized identities carry contradictory categories. */
    private fun validateEntries(entries: List<EntryDto>): List<MerchantCatalogEntry>? {
        val seen = HashMap<String, String>()
        val result = ArrayList<MerchantCatalogEntry>(entries.size)
        entries.forEach { entry ->
            if (!isValidCategoryNamespace(entry.categoryId)) return nullSafeInvalid()
            val patternType = when (entry.patternType) {
                PatternType.EXACT.name -> PatternType.EXACT
                PatternType.SUBSTRING.name -> PatternType.SUBSTRING
                else -> return nullSafeInvalid()
            }
            val normalized = normalizePattern(entry.pattern)
            if (!isSafeCatalogPattern(normalized)) return nullSafeInvalid()

            val identity = "${patternType.name}|$normalized"
            val previous = seen.put(identity, entry.categoryId)
            when {
                previous == null -> result += MerchantCatalogEntry(normalized, patternType, entry.categoryId)
                previous == entry.categoryId -> Unit // agreeing duplicate: keep the first
                else -> return null // contradictory identity rejects the whole catalog
            }
        }
        return result
    }

    private fun nullSafeInvalid(): Nothing = throw CatalogParseException(CatalogRejection.INVALID_FIELD)

    companion object {
        const val MAX_ENVELOPE_BYTES: Int = 2 * 1024 * 1024
        const val MAX_PAYLOAD_BYTES: Int = 1024 * 1024
        const val MAX_ENTRIES: Int = 10_000
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
        private const val FUTURE_DRIFT_HOURS: Long = 24

        internal fun normalizePattern(raw: String): String =
            raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        private val CATEGORY_NAMESPACE = Regex("""cat-[a-z][a-z0-9-]{0,63}""")

        private fun isValidCategoryNamespace(categoryId: String): Boolean =
            CATEGORY_NAMESPACE.matches(categoryId)

        /**
         * Tightened public-input version of the existing merchant specificity
         * guard: bounded tokens, letter requirement, control-character and
         * URL/email/phone/account-like sequence rejection, plus a processor/
         * transfer-only ban list on top of the shared generic-key list.
         */
        internal fun isSafeCatalogPattern(normalized: String): Boolean {
            if (!normalized.isSpecificMerchantKey()) return false
            val codePoints = normalized.codePointCount(0, normalized.length)
            if (codePoints < 3 || codePoints > 128) return false
            if (normalized.any { Character.isISOControl(it) }) return false
            if (normalized.none { it.isLetter() }) return false
            val tokens = normalized.split(' ')
            if (tokens.size > 12 || tokens.any { it.isEmpty() || it.length > 64 }) return false
            if (normalized.contains("://") || normalized.contains("http") || normalized.startsWith("www.")) return false
            if (tokens.any { it == "www" || it.startsWith("www.") }) return false
            if (normalized.contains('@')) return false
            if (normalized.startsWith("+")) return false
            if (Regex("""\d{4}""").containsMatchIn(normalized)) return false // phone/account/card-like runs
            if (normalized in PublicCatalogOnlyGenericKeys) return false
            if (normalized.split(' ').any { it in PublicCatalogOnlyGenericKeys }) return false
            return true
        }

        private val PublicCatalogOnlyGenericKeys = setOf(
            "atm", "pos", "mada", "sadad", "transfer", "payment", "refund", "cashback",
            "شبكة", "مدى", "سداد", "حوالة", "استرداد", "بطاقة",
        )
    }
}

internal class CatalogParseException(val rejection: CatalogRejection) : RuntimeException(rejection.name)

private fun strictUtf8(bytes: ByteArray): String? =
    runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
