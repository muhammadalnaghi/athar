package com.athar.core.data.planning

import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JVM stand-in for [JsonReaderSavedFinancialPlanPayloadGuard]: the identical
 * refusal contract (object root, depth/member/text budgets, strict syntax,
 * one complete document) exercised through kotlinx strict parsing, mirroring
 * the established catalog-guard JVM split. It is a test fixture only —
 * production always binds the raw token-stream guard.
 *
 * Known honest limitation, identical to the platform note on the production
 * guard: duplicate object keys are only detectable on the raw token stream, so
 * duplicate-key refusal is proven by the native instrumented suite, not here.
 */
internal object JvmSavedFinancialPlanPayloadGuard : SavedFinancialPlanPayloadGuard {

    private val json = Json

    override fun validate(payloadJson: String) {
        val root = runCatching { json.parseToJsonElement(payloadJson) }
            .getOrElse { throw SavedFinancialPlanValidationException() }
        if (root !is JsonObject) throw SavedFinancialPlanValidationException()
        walk(root, depth = 0)
    }

    private fun walk(element: JsonElement, depth: Int) {
        if (depth > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_DEPTH) {
            throw SavedFinancialPlanValidationException()
        }
        when (element) {
            is JsonObject -> {
                if (element.size > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS) {
                    throw SavedFinancialPlanValidationException()
                }
                element.values.forEach { walk(it, depth + 1) }
            }
            is JsonArray -> {
                if (element.size > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS) {
                    throw SavedFinancialPlanValidationException()
                }
                element.forEach { walk(it, depth + 1) }
            }
            is JsonPrimitive -> {
                if (element.isString &&
                    element.content.length > SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_STRING_CHARS
                ) {
                    throw SavedFinancialPlanValidationException()
                }
            }
        }
    }
}
