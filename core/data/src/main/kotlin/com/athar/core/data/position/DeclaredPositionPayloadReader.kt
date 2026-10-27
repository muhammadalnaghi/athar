package com.athar.core.data.position

import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import com.athar.core.domain.model.position.DeclaredPositionStatus
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WM033 strict read adapter for the FROZEN v1 saved declared-position payload
 * written by `feature/plan/.../position/PositionDocument.kt` (`documentType`
 * `balance_anchor`, `balanceAnchorVersion` 1, stored under
 * `SavedFinancialPlanKind.CASH`).
 *
 * The writer's codec is internal to `feature/plan` and core-data cannot depend
 * on a feature module, so the decode rules are restated here verbatim against
 * the frozen format: this is a bounded, read-only adapter — never a second
 * writer and never a financial reimplementation.
 *
 * Writer-format validation with bounded projection magnitude: JSON object root; exact root key
 * set (`documentType`, `balanceAnchorVersion`, `asOfDate`, `entries`, optional
 * `note`); `documentType` must be the string `balance_anchor` (anything else is
 * a foreign CASH-sibling document, e.g. a cash scenario); `balanceAnchorVersion`
 * must be the JSON NUMBER `1` — a quoted "1" is a field-type malformation and
 * is never coerced through `.content`; `asOfDate` must parse as an ISO
 * LocalDate; `note`, when present, must be a string primitive ≤ 300 chars;
 * entries ≤ 64 and each entry must be an object with exactly
 * `id,label,side,currency,amount,ownerScope`, all string primitives, with
 * `side` ∈ {ASSET, LIABILITY}, `amount` an exact decimal literal and fields
 * ≤ 80 chars. Anything else returns null: the row stays retained-but-unloadable
 * in storage and is never surfaced, normalized or repaired.
 *
 * Aggregation is per LITERAL currency only: sums are exact (`BigDecimal.add` /
 * `subtract`, rendered with `toPlainString`, no scale normalization). Net
 * exists only for currencies the document declares; an absent side inside a
 * declared currency is an empty sum, while a never-declared currency is an
 * absent key — never zero-filled. Cross-document summation, currency
 * conversion and ownership-label parsing do not exist on this path.
 */
internal object DeclaredPositionPayloadReader {

    /** Frozen discriminator and version of the writer format. */
    const val DOC_TYPE = "balance_anchor"
    const val PAYLOAD_VERSION = 1

    /** Frozen authoring bounds restated from the writer codec (no module dependency). */
    const val MAX_NOTE_CHARS = 300
    const val MAX_ENTRIES = 64
    const val MAX_FIELD_CHARS = 80

    private const val KEY_DOC_TYPE = "documentType"
    private const val KEY_VERSION = "balanceAnchorVersion"
    private const val KEY_AS_OF = "asOfDate"
    private const val KEY_NOTE = "note"
    private const val KEY_ENTRIES = "entries"

    private val entryKeys = setOf("id", "label", "side", "currency", "amount", "ownerScope")
    private val rootRequired = setOf(KEY_DOC_TYPE, KEY_VERSION, KEY_AS_OF, KEY_ENTRIES)
    private val rootOptional = setOf(KEY_NOTE)

    /**
     * Reads one saved row into a snapshot, or null when the row is not a
     * loadable v1 balance_anchor document. The envelope's `currency` column is
     * the writer's first-entry convenience and is deliberately ignored: the
     * per-entry currency literals are authoritative.
     */
    fun read(plan: SavedFinancialPlan): DeclaredPositionSnapshot? {
        // Outer validation: kind lane and envelope payload version.
        if (plan.kind != SavedFinancialPlanKind.CASH) return null
        if (plan.payloadVersion != PAYLOAD_VERSION) return null

        if (plan.payloadJson.length > 65_536) return null
        val root = parseObject(plan.payloadJson) ?: return null
        if (root.string(KEY_DOC_TYPE) != DOC_TYPE) return null
        if (root.keys.any { it !in rootRequired && it !in rootOptional } ||
            rootRequired.any { it !in root.keys }
        ) {
            return null
        }

        // Strict version read, mirroring the writer: JSON number 1 only.
        val version = (root[KEY_VERSION] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content
            ?: return null
        if (version != PAYLOAD_VERSION.toString()) return null

        val asOfDate = root.string(KEY_AS_OF) ?: return null
        if (dateOrNull(asOfDate) == null) return null

        when (val rawNote = root[KEY_NOTE]) {
            null -> Unit
            is JsonPrimitive -> {
                if (!rawNote.isString) return null
                if (rawNote.content.length > MAX_NOTE_CHARS) return null
            }
            else -> return null
        }

        val rawEntries = root.array(KEY_ENTRIES) ?: return null
        if (rawEntries.isEmpty() || rawEntries.size > MAX_ENTRIES) return null

        val assets = LinkedHashMap<String, BigDecimal>()
        val liabilities = LinkedHashMap<String, BigDecimal>()
        var hasOwnershipLabel = false
        for (element in rawEntries) {
            val entry = element as? JsonObject ?: return null
            if (entry.keys != entryKeys) return null
            if (entry.values.any { it !is JsonPrimitive || !it.isString }) return null
            val isAsset = when (entry.string("side")) {
                "ASSET" -> true
                "LIABILITY" -> false
                else -> return null
            }
            val id = entry.string("id") ?: return null
            val label = entry.string("label") ?: return null
            val currency = entry.string("currency") ?: return null
            val amountRaw = entry.string("amount") ?: return null
            val ownerScope = entry.string("ownerScope") ?: return null
            if (maxOf(id.length, label.length, currency.length, amountRaw.length, ownerScope.length) > MAX_FIELD_CHARS) {
                return null
            }
            val amount = decimalOrNull(amountRaw) ?: return null
            if (isAsset) merge(assets, currency, amount) else merge(liabilities, currency, amount)
            if (ownerScope.isNotBlank()) hasOwnershipLabel = true
        }

        val net = LinkedHashMap<String, String>()
        for (currency in assets.keys + liabilities.keys) {
            val assetSum = assets[currency]
            val liabilitySum = liabilities[currency]
            val exactNet = if (assetSum != null && liabilitySum != null) {
                assetSum.subtract(liabilitySum)
            } else {
                assetSum ?: liabilitySum?.negate()
            }
            if (exactNet != null) net[currency] = exactNet.toPlainString()
        }

        return DeclaredPositionSnapshot(
            id = plan.id,
            title = plan.title,
            revision = plan.revision,
            asOfDate = asOfDate,
            updatedAtEpochMillis = plan.updatedAtEpochMillis,
            status = DeclaredPositionStatus.USER_DECLARED,
            hasUnstructuredOwnershipLabels = hasOwnershipLabel,
            entryCount = rawEntries.size,
            assetsByCurrency = assets.mapValues { it.value.toPlainString() },
            liabilitiesByCurrency = liabilities.mapValues { it.value.toPlainString() },
            netByCurrency = net,
        )
    }

    private fun parseObject(json: String): JsonObject? = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** ISO date strictness kept identical to the writer codec. */
    private fun dateOrNull(raw: String): LocalDate? = try {
        LocalDate.parse(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Exact decimal literal; whitespace or garbage never parses as money. */
    private fun decimalOrNull(raw: String): BigDecimal? = try {
        BigDecimal(raw).takeIf {
            it.precision().toLong() + kotlin.math.abs(it.scale().toLong()) <= 256L
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun merge(target: MutableMap<String, BigDecimal>, currency: String, amount: BigDecimal) {
        val existing = target[currency]
        target[currency] = if (existing == null) amount else existing.add(amount)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
}
