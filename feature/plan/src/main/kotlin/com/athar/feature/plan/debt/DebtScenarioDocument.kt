package com.athar.feature.plan.debt

import com.athar.feature.plan.scenarios.DebtLoanDraft
import com.athar.feature.plan.scenarios.DebtScenarioDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The only payload version this feature writes and reads; later versions stay retained-unloadable. */
internal const val DEBT_DOCUMENT_VERSION = 1

private val DOCUMENT_KEYS = setOf(
    "version", "currency", "firstDueDate", "horizon", "budget", "supportedTermsAcknowledged", "loans",
)
private val LOAN_KEYS = setOf("name", "principal", "annualPercent", "minimum", "fee")

/**
 * Debt-owned storage document: the user's exact terms, exactly as typed.
 * Amounts persist as the original decimal strings — never converted numbers —
 * and the currency stays literal. No solver output is ever encoded here.
 */
internal fun encodeDebtScenarioDocument(draft: DebtScenarioDraft): String = buildJsonObject {
    put("version", DEBT_DOCUMENT_VERSION)
    put("currency", draft.currency)
    put("firstDueDate", draft.firstDueDate)
    put("horizon", draft.horizon)
    put("budget", draft.budget)
    put("supportedTermsAcknowledged", draft.supportedTermsAcknowledged)
    put("loans", buildJsonArray {
        draft.loans.forEach { loan ->
            add(buildJsonObject {
                put("name", loan.name)
                put("principal", loan.principal)
                put("annualPercent", loan.annualPercent)
                put("minimum", loan.minimum)
                put("fee", loan.fee)
            })
        }
    })
}.toString()

/**
 * Strict decode: rejects malformed JSON, wrong containers, missing or unknown
 * keys, non-string amount fields, quoted/fractional versions and future
 * versions by returning null. Callers keep such records retained, never delete.
 */
internal fun decodeDebtScenarioDocument(json: String): DebtScenarioDraft? {
    val root = try {
        Json.parseToJsonElement(json)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (root !is JsonObject || root.keys != DOCUMENT_KEYS) return null
    val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
    if (version != DEBT_DOCUMENT_VERSION) return null
    val currency = root.string("currency") ?: return null
    val firstDueDate = root.string("firstDueDate") ?: return null
    val horizon = root.string("horizon") ?: return null
    val budget = root.string("budget") ?: return null
    val acknowledged = (root["supportedTermsAcknowledged"] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return null
    val loansElement = root["loans"] as? JsonArray ?: return null
    if (loansElement.size !in 1..12) return null
    val loans = ArrayList<DebtLoanDraft>(loansElement.size)
    loansElement.forEachIndexed { index, element ->
        if (element !is JsonObject || element.keys != LOAN_KEYS) return null
        val loan = DebtLoanDraft(
            id = index + 1,
            name = element.string("name") ?: return null,
            principal = element.string("principal") ?: return null,
            annualPercent = element.string("annualPercent") ?: return null,
            minimum = element.string("minimum") ?: return null,
            fee = element.string("fee") ?: return null,
        )
        loans += loan
    }
    return DebtScenarioDraft(
        currency = currency,
        firstDueDate = firstDueDate,
        horizon = horizon,
        budget = budget,
        supportedTermsAcknowledged = acknowledged,
        loans = loans,
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
