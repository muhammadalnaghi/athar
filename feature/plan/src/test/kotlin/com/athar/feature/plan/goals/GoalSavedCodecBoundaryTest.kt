package com.athar.feature.plan.goals

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** Payload shape is a trust boundary, not permission to coerce unsupported documents. */
class GoalSavedCodecBoundaryTest {
    @Test
    fun `quoted version never becomes a supported integer version`() {
        val valid = Json.parseToJsonElement(GoalSavedDocCodec.encode(GoalScenarioDraft.empty())) as JsonObject
        val quoted = JsonObject(valid.toMutableMap().apply {
            this["payloadVersion"] = JsonPrimitive("1")
        }).toString()

        assertThat(GoalSavedDocCodec.decode(quoted)).isEqualTo(GoalSavedDocResult.Unsupported)
    }

    @Test
    fun `non string primitives never become literal numeric input text`() {
        val valid = Json.parseToJsonElement(GoalSavedDocCodec.encode(GoalScenarioDraft.empty())) as JsonObject
        val fields = listOf(
            "targetAmountText", "inflationPercentText", "currentAllocationText",
            "monthlyContributionText", "horizonMonthsText", "aprPercentText", "feePercentText",
        )
        for (field in fields) {
            for (value in listOf(JsonPrimitive(1), JsonPrimitive(true), JsonNull)) {
                val changed = JsonObject(valid.toMutableMap().apply { this[field] = value }).toString()
                assertThat(GoalSavedDocCodec.decode(changed)).isEqualTo(GoalSavedDocResult.Unsupported)
            }
        }
    }
}
