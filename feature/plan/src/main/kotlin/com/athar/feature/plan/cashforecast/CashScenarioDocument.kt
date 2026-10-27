package com.athar.feature.plan.cashforecast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * documentType discriminators inside [com.athar.core.domain.repo.SavedFinancialPlanKind.CASH].
 *
 * The CASH namespace is shared with the position worker, which owns
 * [BALANCE_ANCHOR] documents for user-declared dated balance anchors. This feature
 * only ever authors [CASH_SCENARIO]; foreign documents are retained untouched and
 * never surfaced as cash scenarios.
 */
internal object CashDocumentTypes {
    const val CASH_SCENARIO = "cash_scenario"
    const val BALANCE_ANCHOR = "balance_anchor"
}

/** The only payload version this feature encodes, decodes or loads. */
internal const val CASH_SCENARIO_PAYLOAD_VERSION = 1

/**
 * Opening-balance provenance stored verbatim. v1 documents are authored only from an
 * explicit dated user input; ledger-sourced openings would carry their own source key
 * in a future version and are never fabricated here.
 */
internal const val CASH_OPENING_SOURCE_EXPLICIT_INPUT = "explicit_input"

/** Upper bounds enforced before persistence; oversized documents are refused, not truncated. */
internal const val CASH_SCENARIO_MAX_TITLE_CHARS = 80
internal const val CASH_SCENARIO_MAX_FLOW_ROWS = 24

/** One dated planned movement; [amount] is the exact signed decimal string as entered. */
internal data class CashScenarioFlowDocument(
    val date: String,
    val amount: String,
)

/**
 * Version-1 saved-input document. Stores exact decimal STRINGS and the literal
 * currency only — never computed balances, paths or outcomes (those are recomputed
 * from these inputs on load).
 */
internal data class CashScenarioDocument(
    val documentType: String = CashDocumentTypes.CASH_SCENARIO,
    val payloadVersion: Int = CASH_SCENARIO_PAYLOAD_VERSION,
    val currency: String,
    val openingDate: String,
    val openingCash: String,
    val openingSource: String = CASH_OPENING_SOURCE_EXPLICIT_INPUT,
    val reserve: String? = null,
    val horizonEnd: String,
    val scopeComplete: Boolean,
    val anchorFresh: Boolean,
    val flows: List<CashScenarioFlowDocument> = emptyList(),
)

/**
 * Strict explicit-JsonObject codec (the module owns no serialization compiler
 * plugin): v1 must match v1 exactly. Every field is written/read as a primitive
 * WITHOUT coercion — quoted `"1"` versions, numeric amounts, boolean-as-string and
 * unknown keys all classify as [CashDocumentClassification.Malformed]; future
 * versions as [CashDocumentClassification.UnsupportedVersion]. Nothing is deleted
 * or repaired.
 */
private val v1RequiredKeys = setOf(
    "documentType", "payloadVersion", "currency", "openingDate", "openingCash",
    "openingSource", "horizonEnd", "scopeComplete", "anchorFresh", "flows",
)
private val v1OptionalKeys = setOf("reserve")
private val flowKeys = setOf("date", "amount")

/** Serializes the document with the discriminator fields always present. */
internal fun encodeCashScenarioDocument(document: CashScenarioDocument): String = buildJsonObject {
    put("documentType", document.documentType)
    put("payloadVersion", document.payloadVersion)
    put("currency", document.currency)
    put("openingDate", document.openingDate)
    put("openingCash", document.openingCash)
    put("openingSource", document.openingSource)
    document.reserve?.let { put("reserve", it) }
    put("horizonEnd", document.horizonEnd)
    put("scopeComplete", document.scopeComplete)
    put("anchorFresh", document.anchorFresh)
    putJsonArray("flows") {
        document.flows.forEach { flow ->
            add(buildJsonObject {
                put("date", flow.date)
                put("amount", flow.amount)
            })
        }
    }
}.toString()

/**
 * Reads the shared discriminator first, then decodes strictly. Non-object payloads,
 * unknown keys, wrong types and missing fields all land in [CashDocumentClassification.Malformed].
 */
internal fun classifyCashDocument(payloadJson: String): CashDocumentClassification {
    val root = try {
        Json.parseToJsonElement(payloadJson)
    } catch (_: IllegalArgumentException) {
        return CashDocumentClassification.Malformed
    }
    if (root !is JsonObject) return CashDocumentClassification.Malformed
    val documentType = root.stringOf("documentType") ?: return CashDocumentClassification.Malformed
    return when (documentType) {
        CashDocumentTypes.BALANCE_ANCHOR -> CashDocumentClassification.BalanceAnchor
        CashDocumentTypes.CASH_SCENARIO -> classifyCashScenario(root)
        else -> CashDocumentClassification.ForeignType
    }
}

private fun classifyCashScenario(root: JsonObject): CashDocumentClassification {
    // Strict version typing: the stored value must be the JSON number 1; a quoted "1"
    // is a malformation, never coerced through content.
    val version = (root["payloadVersion"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { !it.isString }?.content?.toIntOrNull()
        ?: return CashDocumentClassification.Malformed
    if (version != CASH_SCENARIO_PAYLOAD_VERSION) return CashDocumentClassification.UnsupportedVersion
    if (root.keys.any { it !in v1RequiredKeys && it !in v1OptionalKeys }) return CashDocumentClassification.Malformed
    if (v1RequiredKeys.any { root[it] == null }) return CashDocumentClassification.Malformed

    val currency = root.stringOf("currency") ?: return CashDocumentClassification.Malformed
    val openingDate = root.stringOf("openingDate") ?: return CashDocumentClassification.Malformed
    val openingCash = root.stringOf("openingCash") ?: return CashDocumentClassification.Malformed
    val openingSource = root.stringOf("openingSource") ?: return CashDocumentClassification.Malformed
    val horizonEnd = root.stringOf("horizonEnd") ?: return CashDocumentClassification.Malformed
    val reserve = root["reserve"]?.let { element ->
        (element as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return CashDocumentClassification.Malformed
    }
    val scopeComplete = root.booleanOf("scopeComplete") ?: return CashDocumentClassification.Malformed
    val anchorFresh = root.booleanOf("anchorFresh") ?: return CashDocumentClassification.Malformed

    val flowsElement = root["flows"] as? kotlinx.serialization.json.JsonArray
        ?: return CashDocumentClassification.Malformed
    if (flowsElement.size > CASH_SCENARIO_MAX_FLOW_ROWS) return CashDocumentClassification.Malformed
    val flows = ArrayList<CashScenarioFlowDocument>(flowsElement.size)
    for (element in flowsElement) {
        val flow = element as? JsonObject ?: return CashDocumentClassification.Malformed
        if (flow.keys != flowKeys) return CashDocumentClassification.Malformed
        val date = flow.stringOf("date") ?: return CashDocumentClassification.Malformed
        val amount = flow.stringOf("amount") ?: return CashDocumentClassification.Malformed
        flows += CashScenarioFlowDocument(date = date, amount = amount)
    }

    return CashDocumentClassification.CashScenario(
        CashScenarioDocument(
            documentType = CashDocumentTypes.CASH_SCENARIO,
            payloadVersion = version,
            currency = currency,
            openingDate = openingDate,
            openingCash = openingCash,
            openingSource = openingSource,
            reserve = reserve,
            horizonEnd = horizonEnd,
            scopeComplete = scopeComplete,
            anchorFresh = anchorFresh,
            flows = flows,
        ),
    )
}

private fun JsonObject.stringOf(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.booleanOf(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

/**
 * Classification of one stored payload against this feature's supported shape.
 * Every non-[CashScenario] outcome is retained in storage; none is ever deleted,
 * repaired or guessed at.
 */
internal sealed interface CashDocumentClassification {
    /** A loadable version-1 cash scenario authored by this feature. */
    data class CashScenario(val document: CashScenarioDocument) : CashDocumentClassification

    /** Known foreign sibling (position worker's balance anchors): excluded, not reported as ours. */
    data object BalanceAnchor : CashDocumentClassification

    /** Unknown future documentType: retained, never listed as a cash scenario. */
    data object ForeignType : CashDocumentClassification

    /** Our documentType but a newer payload version: retained, explicitly unloadable. */
    data object UnsupportedVersion : CashDocumentClassification

    /** Our documentType and version, but the payload does not strictly decode: retained. */
    data object Malformed : CashDocumentClassification
}
