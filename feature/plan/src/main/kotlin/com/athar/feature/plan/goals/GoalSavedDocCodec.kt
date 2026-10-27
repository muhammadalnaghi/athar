package com.athar.feature.plan.goals

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.athar.core.domain.model.wealth.ContributionTiming

/**
 * Feature-owned versioned codec for goal input documents stored through the frozen
 * `SavedFinancialPlanRepository` (kind GOAL). Version 1 stores the exact
 * [GoalScenarioDraft] input strings and enum names — the same literal decimal text the
 * user typed, re-parsed verbatim by [GoalScenarioDraft.solve] on load — and never any
 * computed solver outcome.
 *
 * The explicit [JsonObject] API is used on purpose: values are written and read as
 * primitives without number coercion, so `"12.300"` round-trips byte-exact (KWD's 3
 * decimals included) and no currency or amount is ever relabeled. Every decode failure
 * collapses into [GoalSavedDocResult.Unsupported]: malformed JSON, wrong field types,
 * missing fields, unknown enum names, and any `payloadVersion` other than
 * [PAYLOAD_VERSION] all describe a document this build must retain but cannot load.
 */
internal object GoalSavedDocCodec {

    const val PAYLOAD_VERSION = 1

    private val json = Json

    /** Version-1 document for [draft]; field names are the codec's stable contract. */
    fun encode(draft: GoalScenarioDraft): String = buildJsonObject {
        put(KEY_PAYLOAD_VERSION, PAYLOAD_VERSION)
        put(KEY_TARGET_AMOUNT, draft.targetAmountText)
        put(KEY_TARGET_BASIS, draft.targetBasis.name)
        put(KEY_INFLATION, draft.inflationPercentText)
        put(KEY_ALLOCATION, draft.currentAllocationText)
        put(KEY_CONTRIBUTION, draft.monthlyContributionText)
        put(KEY_HORIZON, draft.horizonMonthsText)
        put(KEY_ASSUMPTIONS, draft.assumptionsKind.name)
        put(KEY_APR, draft.aprPercentText)
        put(KEY_FEE, draft.feePercentText)
        put(KEY_TIMING, draft.timing.name)
        put(KEY_SOLVE_MODE, draft.solveMode.name)
    }.toString()

    /** Never throws: any structural or version problem is [GoalSavedDocResult.Unsupported]. */
    fun decode(payloadJson: String): GoalSavedDocResult = try {
        val document = json.parseToJsonElement(payloadJson).jsonObject
        if (document.intOf(KEY_PAYLOAD_VERSION) != PAYLOAD_VERSION) {
            GoalSavedDocResult.Unsupported
        } else {
            GoalSavedDocResult.V1(
                draft = GoalScenarioDraft(
                    targetAmountText = document.textOf(KEY_TARGET_AMOUNT),
                    targetBasis = enumOf<GoalTargetBasis>(document.textOf(KEY_TARGET_BASIS)),
                    inflationPercentText = document.textOf(KEY_INFLATION),
                    currentAllocationText = document.textOf(KEY_ALLOCATION),
                    monthlyContributionText = document.textOf(KEY_CONTRIBUTION),
                    horizonMonthsText = document.textOf(KEY_HORIZON),
                    assumptionsKind = enumOf<GoalAssumptionsKind>(document.textOf(KEY_ASSUMPTIONS)),
                    aprPercentText = document.textOf(KEY_APR),
                    feePercentText = document.textOf(KEY_FEE),
                    timing = enumOf<ContributionTiming>(document.textOf(KEY_TIMING)),
                    solveMode = enumOf<GoalSolveMode>(document.textOf(KEY_SOLVE_MODE)),
                ),
            )
        }
    } catch (expected: Exception) {
        // SerializationException/IllegalArgumentException/ClassCastException and any
        // future surprise are all the same honest answer: unsupported, never fabricated.
        GoalSavedDocResult.Unsupported
    }

    private const val KEY_PAYLOAD_VERSION = "payloadVersion"
    private const val KEY_TARGET_AMOUNT = "targetAmountText"
    private const val KEY_TARGET_BASIS = "targetBasis"
    private const val KEY_INFLATION = "inflationPercentText"
    private const val KEY_ALLOCATION = "currentAllocationText"
    private const val KEY_CONTRIBUTION = "monthlyContributionText"
    private const val KEY_HORIZON = "horizonMonthsText"
    private const val KEY_ASSUMPTIONS = "assumptionsKind"
    private const val KEY_APR = "aprPercentText"
    private const val KEY_FEE = "feePercentText"
    private const val KEY_TIMING = "timing"
    private const val KEY_SOLVE_MODE = "solveMode"

    private fun JsonObject.textOf(key: String): String {
        val value = getValue(key).jsonPrimitive
        require(value.isString) { "Unsupported goal input type" }
        return value.content
    }

    private fun JsonObject.intOf(key: String): Int? =
        (getValue(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()

    private inline fun <reified T : Enum<T>> enumOf(name: String): T =
        enumValueOf<T>(name)
}

/** A saved goal payload either loads as a version-1 input document or is unsupported. */
internal sealed interface GoalSavedDocResult {
    data class V1(val draft: GoalScenarioDraft) : GoalSavedDocResult
    data object Unsupported : GoalSavedDocResult
}
