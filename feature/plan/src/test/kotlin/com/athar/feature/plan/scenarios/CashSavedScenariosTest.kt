package com.athar.feature.plan.scenarios

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.feature.plan.cashforecast.CashDocumentTypes
import com.athar.feature.plan.cashforecast.CashScenarioDocument
import com.athar.feature.plan.cashforecast.CashScenarioFlowDocument
import com.athar.feature.plan.cashforecast.encodeCashScenarioDocument
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CashSavedScenariosTest {

    @Test fun `listing keeps only cash scenarios and counts retained unreadable documents`() {
        val plans = listOf(
            plan("cash-1", title = "رحلة", payloadJson = encoded(),
                createdAt = 100, updatedAt = 300),
            plan("anchor-1", title = "رصيد", payloadJson =
                """{"documentType":"balance_anchor","payloadVersion":3,"asOf":"2026-11-01"}""",
                createdAt = 100, updatedAt = 400),
            plan("future-1", title = "مستقبلي", payloadJson =
                """{"documentType":"cash_scenario","payloadVersion":2,"currency":"SAR"}""",
                createdAt = 100, updatedAt = 200),
            plan("broken-1", title = "تالف", payloadJson = "{not json", createdAt = 100, updatedAt = 150),
            plan("foreign-1", title = "غريب", payloadJson =
                """{"documentType":"car_log_v2","payloadVersion":1}""", createdAt = 100, updatedAt = 120),
        )
        val listing = classifySavedPlans(plans)
        assertThat(listing.summaries.map { it.id }).containsExactly("cash-1")
        assertThat(listing.unavailable).isEqualTo(3) // future + broken + foreign, never the anchor
        assertThat(plans).hasSize(5) // nothing deleted or repaired
    }

    @Test fun `summaries sort newest update first and carry the revision fence`() {
        val plans = listOf(
            plan("old", title = "قديم", payloadJson = encoded(), createdAt = 10, updatedAt = 100, revision = 3),
            plan("new", title = "جديد", payloadJson = encoded(), createdAt = 20, updatedAt = 200, revision = 7),
        )
        val listing = classifySavedPlans(plans)
        assertThat(listing.summaries.map { it.id }).containsExactly("new", "old").inOrder()
        assertThat(listing.summaries.first().revision).isEqualTo(7)
        assertThat(listing.summaries.first().currency).isEqualTo("KWD")
        assertThat(listing.summaries.first().openingDate).isEqualTo("2026-11-21")
        assertThat(listing.summaries.first().horizonEnd).isEqualTo("2026-11-23")
    }

    @Test fun `draft rebuild keeps exact strings and splits signs into directions`() {
        val document = CashScenarioDocument(
            currency = "KWD",
            openingDate = "2026-11-21",
            openingCash = "-10.123",
            reserve = null,
            horizonEnd = "2026-11-23",
            scopeComplete = false,
            anchorFresh = true,
            flows = listOf(
                CashScenarioFlowDocument("2026-11-22", "0.500"),
                CashScenarioFlowDocument("2026-11-23", "-1.250"),
            ),
        )
        val draft = draftOf(document)
        assertThat(draft.currency).isEqualTo("KWD")
        assertThat(draft.openingCash).isEqualTo("-10.123")
        assertThat(draft.reserve).isEmpty()
        assertThat(draft.scopeComplete).isFalse()
        assertThat(draft.anchorFresh).isTrue()
        assertThat(draft.flows).containsExactly(
            CashFlowDraft(1, "2026-11-22", "0.500", CashFlowDirection.INFLOW),
            CashFlowDraft(2, "2026-11-23", "1.250", CashFlowDirection.OUTFLOW),
        ).inOrder()
    }

    @Test fun `document encodes signed exact strings and unknown reserve`() {
        val draft = CashScenarioDraft(
            currency = "KWD", openingDate = "2026-11-21 ", openingCash = " 10.123",
            reserve = "", horizonEnd = "2026-11-23", scopeComplete = true, anchorFresh = false,
            flows = listOf(
                CashFlowDraft(1, " 2026-11-22", "+0.500", CashFlowDirection.INFLOW),
                CashFlowDraft(2, "2026-11-23", "1.250", CashFlowDirection.OUTFLOW),
            ),
        )
        val document = documentOf(draft)
        assertThat(document.openingDate).isEqualTo("2026-11-21")
        assertThat(document.openingCash).isEqualTo("10.123")
        assertThat(document.reserve).isNull()
        assertThat(document.openingSource).isEqualTo("explicit_input")
        assertThat(document.documentType).isEqualTo(CashDocumentTypes.CASH_SCENARIO)
        assertThat(document.flows).containsExactly(
            CashScenarioFlowDocument("2026-11-22", "0.500"),
            CashScenarioFlowDocument("2026-11-23", "-1.250"),
        ).inOrder()
    }

    @Test fun `document to draft to document round trips losslessly`() {
        val draft = CashScenarioDraft(
            currency = "KWD", openingDate = "2026-11-21", openingCash = "10.123",
            reserve = "0", horizonEnd = "2026-11-23", scopeComplete = true, anchorFresh = true,
            flows = listOf(CashFlowDraft(1, "2026-11-22", "0.500", CashFlowDirection.INFLOW)),
        )
        assertThat(draftOf(documentOf(draft))).isEqualTo(draft)
    }

    @Test fun `compare identity covers currency dates source and horizon`() {
        val base = classifySavedPlans(listOf(plan("a", "أ", payloadJson = encoded()))).summaries.single()
        assertThat(base.compareKey()).isEqualTo(base.compareKey())
        val otherHorizon = classifySavedPlans(listOf(
            plan("b", "ب", payloadJson = encoded(horizonEnd = "2026-11-24")),
        )).summaries.single()
        assertThat(base.compareKey()).isNotEqualTo(otherHorizon.compareKey())
        val otherCurrency = classifySavedPlans(listOf(
            plan("c", "ج", payloadJson = encoded(currency = "SAR")),
        )).summaries.single()
        assertThat(base.compareKey()).isNotEqualTo(otherCurrency.compareKey())
    }

    @Test fun `titles beyond the bound are refused not truncated`() {
        assertThat(validSavedTitle("رحلة نوفمبر")).isTrue()
        assertThat(validSavedTitle("   ")).isFalse()
        assertThat(validSavedTitle("x".repeat(81))).isFalse()
        assertThat(validSavedTitle("x".repeat(80))).isTrue()
    }
}

private fun plan(
    id: String,
    title: String,
    payloadJson: String,
    createdAt: Long = 0,
    updatedAt: Long = 0,
    revision: Long = 1,
) = SavedFinancialPlan(
    id = id,
    kind = SavedFinancialPlanKind.CASH,
    title = title,
    currency = "KWD",
    payloadVersion = 1,
    payloadJson = payloadJson,
    revision = revision,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = updatedAt,
)

private fun encoded(
    currency: String = "KWD",
    horizonEnd: String = "2026-11-23",
): String {
    val document = CashScenarioDocument(
        currency = currency,
        openingDate = "2026-11-21",
        openingCash = "10.123",
        reserve = "0",
        horizonEnd = horizonEnd,
        scopeComplete = true,
        anchorFresh = true,
        flows = listOf(CashScenarioFlowDocument("2026-11-22", "0.500")),
    )
    return com.athar.feature.plan.cashforecast.encodeCashScenarioDocument(document)
}
