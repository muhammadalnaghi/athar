package com.athar.feature.plan.portfolio

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Dated securities portfolio — durable INPUT document (version 1) and its codec.
 *
 * This is the feature-owned codec over the frozen `SavedFinancialPlanRepository`
 * PORTFOLIO contract: exact decimal amounts stay strings, the currency is a literal
 * code, and nothing computed is ever persisted. Encoding/decoding uses the
 * kotlinx-serialization-json element API directly (strict, no schema plugin), so:
 * - the root object must carry exactly the known keys,
 * - every per-kind event object must carry exactly its kind's keys,
 * - every monetary/quantity value must be a JSON string holding a decimal literal,
 * - dates must parse as strict ISO `YYYY-MM-DD`.
 * A document that fails any of this decodes as [PortfolioDecodeResult.Malformed] or
 * [PortfolioDecodeResult.UnsupportedVersion] and stays RETAINED but unloadable —
 * never deleted, never silently migrated, and never described with raw payload text.
 */
enum class PortfolioEntryKind { BUY, SELL, CONTRIBUTION, WITHDRAWAL, FEE, CASH_DIVIDEND, REINVESTMENT }

data class SecurityInput(val securityId: String, val name: String, val assetClass: String?)

data class EventInput(
    val kind: PortfolioEntryKind,
    val eventId: String,
    val date: String,
    val securityId: String? = null,
    val quantity: String? = null,
    val unitPrice: String? = null,
    val fee: String? = null,
    val amount: String? = null,
    val destination: String? = null,
    val note: String? = null,
)

data class QuoteInput(val securityId: String, val date: String, val unitPrice: String)

/** `kind` is POINT (uses [fraction]) or RANGE (uses [minFraction]/[maxFraction]); fractions are exact decimal strings. */
data class TargetInput(
    val assetClass: String,
    val kind: String,
    val fraction: String? = null,
    val minFraction: String? = null,
    val maxFraction: String? = null,
)

data class PortfolioPlanDocument(
    val currency: String,
    val openingCashAmount: String,
    val securities: List<SecurityInput> = emptyList(),
    val events: List<EventInput> = emptyList(),
    val quotes: List<QuoteInput> = emptyList(),
    val allocationTargets: List<TargetInput> = emptyList(),
    /** Explicit user-supplied capital date; absent legacy evidence stays absent. */
    val openingCashDate: String? = null,
) {
    fun emptyLike(currency: String, openingCashAmount: String): PortfolioPlanDocument =
        PortfolioPlanDocument(currency = currency, openingCashAmount = openingCashAmount)
}

/** Safe, constant decode failures — never carry payload text. */
enum class PortfolioCodecProblem {
    NOT_JSON_OBJECT,
    MISSING_OR_EXTRA_ROOT_KEY,
    UNSUPPORTED_VERSION,
    BAD_FIELD_TYPE,
    BAD_LIST_SHAPE,
    EVENT_SHAPE,
    QUOTE_SHAPE,
    TARGET_SHAPE,
    BAD_DATE,
}

sealed interface PortfolioDecodeResult {
    data class Success(val document: PortfolioPlanDocument) : PortfolioDecodeResult
    data class UnsupportedVersion(val found: String) : PortfolioDecodeResult
    data class Malformed(val problem: PortfolioCodecProblem) : PortfolioDecodeResult
}

object PortfolioPlanCodec {

    const val VERSION = 1

    private const val KEY_VERSION = "portfolioPlanVersion"
    private const val KEY_CURRENCY = "currency"
    private const val KEY_OPENING_CASH = "openingCashAmount"
    private const val KEY_OPENING_DATE = "openingCashDate"
    private const val KEY_SECURITIES = "securities"
    private const val KEY_EVENTS = "events"
    private const val KEY_QUOTES = "quotes"
    private const val KEY_TARGETS = "allocationTargets"
    private const val KEY_KIND = "kind"

    private val rootRequired = setOf(KEY_VERSION, KEY_CURRENCY, KEY_OPENING_CASH, KEY_SECURITIES, KEY_EVENTS, KEY_QUOTES)
    private val rootOptional = setOf(KEY_TARGETS, KEY_OPENING_DATE)

    fun encode(document: PortfolioPlanDocument): String = buildJsonObject {
        put(KEY_VERSION, VERSION)
        put(KEY_CURRENCY, document.currency)
        put(KEY_OPENING_CASH, document.openingCashAmount)
        document.openingCashDate?.let { put(KEY_OPENING_DATE, it) }
        putJsonArray(KEY_SECURITIES) {
            document.securities.forEach { security ->
                add(
                    buildJsonObject {
                        put("securityId", security.securityId)
                        put("name", security.name)
                        security.assetClass?.let { put("assetClass", it) }
                    },
                )
            }
        }
        putJsonArray(KEY_EVENTS) {
            document.events.forEach { event ->
                add(
                    buildJsonObject {
                        put(KEY_KIND, event.kind.name)
                        put("eventId", event.eventId)
                        put("date", event.date)
                        event.securityId?.let { put("securityId", it) }
                        event.quantity?.let { put("quantity", it) }
                        event.unitPrice?.let { put("unitPrice", it) }
                        event.fee?.let { put("fee", it) }
                        event.amount?.let { put("amount", it) }
                        event.destination?.let { put("destination", it) }
                        event.note?.let { put("note", it) }
                    },
                )
            }
        }
        putJsonArray(KEY_QUOTES) {
            document.quotes.forEach { quote ->
                add(
                    buildJsonObject {
                        put("securityId", quote.securityId)
                        put("date", quote.date)
                        put("unitPrice", quote.unitPrice)
                    },
                )
            }
        }
        if (document.allocationTargets.isNotEmpty()) {
            putJsonArray(KEY_TARGETS) {
                document.allocationTargets.forEach { target ->
                    add(
                        buildJsonObject {
                            put("assetClass", target.assetClass)
                            put(KEY_KIND, target.kind)
                            target.fraction?.let { put("fraction", it) }
                            target.minFraction?.let { put("minFraction", it) }
                            target.maxFraction?.let { put("maxFraction", it) }
                        },
                    )
                }
            }
        }
    }.toString()

    fun decode(json: String): PortfolioDecodeResult {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (_: Exception) {
            return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.NOT_JSON_OBJECT)
        }
        if (root !is JsonObject) return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.NOT_JSON_OBJECT)
        if (!root.keys.containsAll(rootRequired) || root.keys.any { it !in rootRequired && it !in rootOptional }) {
            return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.MISSING_OR_EXTRA_ROOT_KEY)
        }

        // Strict version read: the stored value must be the JSON number `1`. A quoted
        // "1" (or any non-number primitive) is a field-type malformation — retained but
        // unloadable — never coerced through `.content`.
        val version = (root[KEY_VERSION] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content
            ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE)
        if (version != VERSION.toString()) {
            return PortfolioDecodeResult.UnsupportedVersion(version)
        }

        val currency = root.string(KEY_CURRENCY)
            ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE)
        val openingCash = root.string(KEY_OPENING_CASH)
            ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE)
        val openingDate = if (KEY_OPENING_DATE in root) {
            root.string(KEY_OPENING_DATE)
                ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_FIELD_TYPE)
        } else null
        if (openingDate != null && dateOrNull(openingDate) == null) {
            return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_DATE)
        }

        val securities = root.array(KEY_SECURITIES)?.map { element ->
            val obj = element as? JsonObject ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)
            val id = obj.string("securityId")
            val name = obj.string("name")
            if (id == null || name == null || obj.keys.any { it !in setOf("securityId", "name", "assetClass") } ||
                obj.values.any { it !is JsonPrimitive || !it.isString }
            ) {
                return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)
            }
            SecurityInput(id, name, obj.string("assetClass"))
        } ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)

        val events = root.array(KEY_EVENTS)?.map { element ->
            val obj = element as? JsonObject ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)
            val kindName = obj.string(KEY_KIND) ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.EVENT_SHAPE)
            val kind = PortfolioEntryKind.entries.firstOrNull { it.name == kindName }
                ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.EVENT_SHAPE)
            val eventId = obj.string("eventId") ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.EVENT_SHAPE)
            val date = obj.string("date") ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.EVENT_SHAPE)
            if (dateOrNull(date) == null) return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_DATE)
            val allowed = allowedEventKeys(kind)
            if (obj.keys.any { it !in allowed } || obj.values.any { it !is JsonPrimitive || !it.isString }) {
                return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.EVENT_SHAPE)
            }
            EventInput(
                kind = kind,
                eventId = eventId,
                date = date,
                securityId = obj.string("securityId"),
                quantity = obj.string("quantity"),
                unitPrice = obj.string("unitPrice"),
                fee = obj.string("fee"),
                amount = obj.string("amount"),
                destination = obj.string("destination"),
                note = obj.string("note"),
            )
        } ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)

        val quotes = root.array(KEY_QUOTES)?.map { element ->
            val obj = element as? JsonObject ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)
            val allowed = setOf("securityId", "date", "unitPrice")
            val securityId = obj.string("securityId")
            val date = obj.string("date")
            val unitPrice = obj.string("unitPrice")
            if (securityId == null || date == null || unitPrice == null || obj.keys.any { it !in allowed }) {
                return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.QUOTE_SHAPE)
            }
            if (dateOrNull(date) == null) return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_DATE)
            QuoteInput(securityId, date, unitPrice)
        } ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.BAD_LIST_SHAPE)

        val targets = root[KEY_TARGETS]?.let { element ->
            val array = element as? JsonArray ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.TARGET_SHAPE)
            array.map { item ->
                val obj = item as? JsonObject ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.TARGET_SHAPE)
                val assetClass = obj.string("assetClass") ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.TARGET_SHAPE)
                val kind = obj.string(KEY_KIND) ?: return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.TARGET_SHAPE)
                val fraction = obj.string("fraction")
                val minFraction = obj.string("minFraction")
                val maxFraction = obj.string("maxFraction")
                val shapeOk = when (kind) {
                    "POINT" -> fraction != null && minFraction == null && maxFraction == null &&
                        obj.keys.all { it in setOf("assetClass", KEY_KIND, "fraction") }
                    "RANGE" -> minFraction != null && maxFraction != null && fraction == null &&
                        obj.keys.all { it in setOf("assetClass", KEY_KIND, "minFraction", "maxFraction") }
                    else -> false
                }
                if (!shapeOk) return PortfolioDecodeResult.Malformed(PortfolioCodecProblem.TARGET_SHAPE)
                TargetInput(assetClass, kind, fraction, minFraction, maxFraction)
            }
        } ?: emptyList()

        return PortfolioDecodeResult.Success(
            PortfolioPlanDocument(
                currency = currency,
                openingCashAmount = openingCash,
                securities = securities,
                events = events,
                quotes = quotes,
                allocationTargets = targets,
                openingCashDate = openingDate,
            ),
        )
    }

    /** ISO date for the UI from validated parts; keeps strictness in one place. */
    fun isoDate(year: Int, month: Int, day: Int): String? = try {
        LocalDate(year, month, day).toString()
    } catch (_: IllegalArgumentException) {
        null
    }

    // Notes are optional annotations for every event, never a monetary requirement.
    private fun allowedEventKeys(kind: PortfolioEntryKind): Set<String> =
        setOf(KEY_KIND, "eventId", "date", "note") + when (kind) {
            PortfolioEntryKind.BUY, PortfolioEntryKind.SELL ->
                setOf("securityId", "quantity", "unitPrice", "fee")
            PortfolioEntryKind.CONTRIBUTION, PortfolioEntryKind.WITHDRAWAL, PortfolioEntryKind.FEE ->
                setOf("amount")
            PortfolioEntryKind.CASH_DIVIDEND ->
                setOf("securityId", "amount", "destination")
            PortfolioEntryKind.REINVESTMENT ->
                setOf("securityId", "amount", "unitPrice", "quantity")
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun dateOrNull(raw: String): LocalDate? = try {
        LocalDate.parse(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}
