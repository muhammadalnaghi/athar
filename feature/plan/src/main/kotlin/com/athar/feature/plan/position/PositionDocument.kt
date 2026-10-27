package com.athar.feature.plan.position

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal

/**
 * WM031 saved dated position — durable USER-DECLARED INPUT document (version 1).
 *
 * Stored under the shared `SavedFinancialPlanKind.CASH` kind with
 * [POSITION_DOC_TYPE] as the discriminator: the cash lane's documented contract
 * reserves `balance_anchor` documents for this position worker (see
 * `cashforecast/CashScenarioDocument.kt`), so no enum constant is added and the
 * cash lane keeps excluding them untouched.
 *
 * These are declared balances as of a date — never verified bank balances and
 * never transaction postings. Amounts are exact decimal literal strings
 * (BigDecimal); the currency and ownership/scope are stored verbatim as entered.
 * Nothing computed is ever persisted.
 */
internal const val POSITION_DOC_TYPE = "balance_anchor"

/** The only payload version this feature encodes, decodes or loads. */
internal const val POSITION_PAYLOAD_VERSION = 1

/** Authoring bounds enforced before persistence and on decode; oversized documents are refused, not truncated. */
internal const val POSITION_MAX_TITLE_CHARS = 80
internal const val POSITION_MAX_NOTE_CHARS = 300
internal const val POSITION_MAX_ENTRIES = 64
internal const val POSITION_MAX_FIELD_CHARS = 80

enum class PositionSide { ASSET, LIABILITY }

data class PositionEntryDocument(
    val id: String,
    val label: String,
    val side: PositionSide,
    val currency: String,
    val amount: String,
    val ownerScope: String,
)

data class PositionDocument(
    val asOfDate: String,
    val note: String? = null,
    val entries: List<PositionEntryDocument> = emptyList(),
)

/** Safe, constant decode failures — never carry payload text. */
internal enum class PositionCodecProblem {
    NOT_JSON_OBJECT,
    MISSING_OR_EXTRA_ROOT_KEY,
    UNSUPPORTED_VERSION,
    BAD_FIELD_TYPE,
    BAD_ENTRY_SHAPE,
    BAD_DATE,
    BAD_AMOUNT,
    OVER_BOUNDS,
}

internal sealed interface PositionDecodeResult {
    data class Success(val document: PositionDocument) : PositionDecodeResult
    data class UnsupportedVersion(val found: String) : PositionDecodeResult
    data class Malformed(val problem: PositionCodecProblem) : PositionDecodeResult

    /** A CASH-sibling document (e.g. a cash scenario): foreign, excluded, never surfaced. */
    data object Foreign : PositionDecodeResult
}

internal object PositionDocumentCodec {

    const val VERSION = POSITION_PAYLOAD_VERSION

    private const val KEY_DOC_TYPE = "documentType"
    private const val KEY_VERSION = "balanceAnchorVersion"
    private const val KEY_AS_OF = "asOfDate"
    private const val KEY_NOTE = "note"
    private const val KEY_ENTRIES = "entries"

    private val entryKeys = setOf("id", "label", "side", "currency", "amount", "ownerScope")
    private val rootRequired = setOf(KEY_DOC_TYPE, KEY_VERSION, KEY_AS_OF, KEY_ENTRIES)
    private val rootOptional = setOf(KEY_NOTE)

    fun encode(document: PositionDocument): String = buildJsonObject {
        put(KEY_DOC_TYPE, POSITION_DOC_TYPE)
        put(KEY_VERSION, VERSION)
        put(KEY_AS_OF, document.asOfDate)
        document.note?.let { put(KEY_NOTE, it) }
        putJsonArray(KEY_ENTRIES) {
            document.entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("id", entry.id)
                        put("label", entry.label)
                        put("side", entry.side.name)
                        put("currency", entry.currency)
                        put("amount", entry.amount)
                        put("ownerScope", entry.ownerScope)
                    },
                )
            }
        }
    }.toString()

    fun decode(json: String): PositionDecodeResult {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (_: Exception) {
            return PositionDecodeResult.Malformed(PositionCodecProblem.NOT_JSON_OBJECT)
        }
        if (root !is JsonObject) {
            return PositionDecodeResult.Malformed(PositionCodecProblem.NOT_JSON_OBJECT)
        }
        val docType = root.string(KEY_DOC_TYPE) ?: return PositionDecodeResult.Malformed(
            PositionCodecProblem.BAD_FIELD_TYPE,
        )
        if (docType != POSITION_DOC_TYPE) return PositionDecodeResult.Foreign
        if (root.keys.any { it !in rootRequired && it !in rootOptional } ||
            rootRequired.any { it !in root.keys }
        ) {
            return PositionDecodeResult.Malformed(PositionCodecProblem.MISSING_OR_EXTRA_ROOT_KEY)
        }

        // Strict version read: the stored value must be the JSON number `1`. A quoted
        // "1" (or any non-number primitive) is a field-type malformation — retained
        // but unloadable — never coerced through `.content`.
        val version = (root[KEY_VERSION] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content
            ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_FIELD_TYPE)
        if (version != VERSION.toString()) {
            return PositionDecodeResult.UnsupportedVersion(version)
        }

        val asOf = root.string(KEY_AS_OF)
            ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_FIELD_TYPE)
        if (dateOrNull(asOf) == null) {
            return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_DATE)
        }

        val rawEntries = root.array(KEY_ENTRIES)
            ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
        if (rawEntries.size > POSITION_MAX_ENTRIES) {
            return PositionDecodeResult.Malformed(PositionCodecProblem.OVER_BOUNDS)
        }
        val entries = rawEntries.map { element ->
            val obj = element as? JsonObject
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            if (obj.keys != entryKeys || obj.values.any { it !is JsonPrimitive || !it.isString }) {
                return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            }
            val id = obj.string("id") ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            val label = obj.string("label")
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            val sideName = obj.string("side")
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            val side = PositionSide.entries.firstOrNull { it.name == sideName }
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            val currency = obj.string("currency")
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            val amount = obj.string("amount")
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            if (decimalOrNull(amount) == null) {
                return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_AMOUNT)
            }
            val scope = obj.string("ownerScope")
                ?: return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_ENTRY_SHAPE)
            if (maxOf(id.length, label.length, currency.length, amount.length, scope.length) > POSITION_MAX_FIELD_CHARS) {
                return PositionDecodeResult.Malformed(PositionCodecProblem.OVER_BOUNDS)
            }
            PositionEntryDocument(id, label, side, currency, amount, scope)
        }

        // Optional note: a present note must be a JSON string primitive. A numeric,
        // boolean, null literal or structural note is a field-type malformation —
        // the row stays retained-but-unloadable, the note is never silently dropped.
        val note = when (val raw = root[KEY_NOTE]) {
            null -> null
            is JsonPrimitive -> {
                if (!raw.isString) return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_FIELD_TYPE)
                if (raw.content.length > POSITION_MAX_NOTE_CHARS) {
                    return PositionDecodeResult.Malformed(PositionCodecProblem.OVER_BOUNDS)
                }
                raw.content
            }
            else -> return PositionDecodeResult.Malformed(PositionCodecProblem.BAD_FIELD_TYPE)
        }

        return PositionDecodeResult.Success(
            PositionDocument(asOfDate = asOf, note = note, entries = entries),
        )
    }

    /** ISO date from validated parts for the UI; keeps strictness in one place. */
    fun isoDate(year: Int, month: Int, day: Int): String? = try {
        LocalDate(year, month, day).toString()
    } catch (_: IllegalArgumentException) {
        null
    }

    fun dateOrNull(raw: String): LocalDate? = try {
        LocalDate.parse(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Exact decimal literal; whitespace or garbage never parses as money. */
    fun decimalOrNull(raw: String): BigDecimal? = try {
        BigDecimal(raw)
    } catch (_: NumberFormatException) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.array(key: String) = this[key] as? kotlinx.serialization.json.JsonArray
}

/** The stored input must reopen identically, not merely decode successfully in memory. */
internal fun encodeLoadablePosition(document: PositionDocument): String? {
    val encoded = PositionDocumentCodec.encode(document)
    val decoded = PositionDocumentCodec.decode(encoded)
    return if (decoded is PositionDecodeResult.Success && decoded.document == document) {
        encoded
    } else {
        null
    }
}
