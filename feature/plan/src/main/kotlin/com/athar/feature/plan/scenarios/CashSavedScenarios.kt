package com.athar.feature.plan.scenarios

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.feature.plan.cashforecast.CASH_OPENING_SOURCE_EXPLICIT_INPUT
import com.athar.feature.plan.cashforecast.CASH_SCENARIO_MAX_FLOW_ROWS
import com.athar.feature.plan.cashforecast.CASH_SCENARIO_MAX_TITLE_CHARS
import com.athar.feature.plan.cashforecast.CashDocumentClassification
import com.athar.feature.plan.cashforecast.CashDocumentTypes
import com.athar.feature.plan.cashforecast.CashScenarioDocument
import com.athar.feature.plan.cashforecast.CashScenarioFlowDocument
import com.athar.feature.plan.cashforecast.classifyCashDocument

/** A loadable saved cash scenario, kept alongside its optimistic-revision fence. */
internal data class CashSavedSummary(
    val id: String,
    val title: String,
    val currency: String,
    val revision: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val openingDate: String,
    val openingSource: String,
    val horizonEnd: String,
)

/** Snapshot view of one [SavedFinancialPlanKind.CASH] listing. */
internal data class CashSavedListing(
    val summaries: List<CashSavedSummary> = emptyList(),
    /** Retained but unloadable documents (unsupported version, malformed v1, unknown types). */
    val unavailable: Int = 0,
)

/** Identity that must match before two saved scenarios are compared side by side. */
internal data class CashCompareKey(
    val currency: String,
    val openingDate: String,
    val openingSource: String,
    val horizonEnd: String,
)

/**
 * Classifies a CASH-kind listing. Documents authored by this feature become summaries;
 * the position worker's balance anchors are excluded entirely; unknown future types and
 * unreadable documents are counted as retained-unavailable, never deleted or repaired.
 */
internal fun classifySavedPlans(plans: List<SavedFinancialPlan>): CashSavedListing {
    val summaries = ArrayList<CashSavedSummary>(plans.size)
    var unavailable = 0
    for (plan in plans) {
        when (val classified = classifyCashDocument(plan.payloadJson)) {
            is CashDocumentClassification.CashScenario -> summaries += summaryOf(plan, classified.document)
            CashDocumentClassification.BalanceAnchor -> Unit
            CashDocumentClassification.ForeignType,
            CashDocumentClassification.UnsupportedVersion,
            CashDocumentClassification.Malformed,
            -> unavailable++
        }
    }
    return CashSavedListing(summaries.sortedWith(compareByDescending { it.updatedAtEpochMillis }), unavailable)
}

internal fun summaryOf(plan: SavedFinancialPlan, document: CashScenarioDocument) = CashSavedSummary(
    id = plan.id,
    title = plan.title,
    currency = document.currency,
    revision = plan.revision,
    createdAtEpochMillis = plan.createdAtEpochMillis,
    updatedAtEpochMillis = plan.updatedAtEpochMillis,
    openingDate = document.openingDate,
    openingSource = document.openingSource,
    horizonEnd = document.horizonEnd,
)

internal fun CashSavedSummary.compareKey() =
    CashCompareKey(currency, openingDate, openingSource, horizonEnd)

/**
 * Rebuilds the editable draft from the saved inputs verbatim: exact decimal strings and
 * literal currency re-enter the form unchanged, flow identities are regenerated, and the
 * outcome is recomputed by the caller — never reloaded from storage.
 */
internal fun draftOf(document: CashScenarioDocument): CashScenarioDraft {
    val flows = document.flows.take(CASH_SCENARIO_MAX_FLOW_ROWS).mapIndexed { index, flow ->
        val signed = flow.amount.trim()
        val magnitude = signed.removePrefix("-").removePrefix("+")
        val direction = if (signed.startsWith("-")) {
            CashFlowDirection.OUTFLOW
        } else {
            CashFlowDirection.INFLOW
        }
        CashFlowDraft(id = index + 1, date = flow.date, amount = magnitude, direction = direction)
    }
    return CashScenarioDraft(
        currency = document.currency,
        openingDate = document.openingDate,
        openingCash = document.openingCash,
        reserve = document.reserve ?: "",
        horizonEnd = document.horizonEnd,
        scopeComplete = document.scopeComplete,
        anchorFresh = document.anchorFresh,
        flows = flows,
    )
}

/**
 * Builds the version-1 document from a draft whose inputs the caller has validated
 * (a genuine calculator outcome exists). Decimal strings are preserved exactly; only
 * the flow sign is applied, and a leading "+" is dropped so inflows stay bare.
 */
internal fun documentOf(draft: CashScenarioDraft): CashScenarioDocument = CashScenarioDocument(
    currency = draft.currency,
    openingDate = draft.openingDate.trim(),
    openingCash = draft.openingCash.trim().removePrefix("+"),
    openingSource = CASH_OPENING_SOURCE_EXPLICIT_INPUT,
    reserve = draft.reserve.takeIf { it.isNotBlank() }?.trim()?.removePrefix("+"),
    horizonEnd = draft.horizonEnd.trim(),
    scopeComplete = draft.scopeComplete,
    anchorFresh = draft.anchorFresh,
    flows = draft.flows.map { flow ->
        val magnitude = flow.amount.trim().removePrefix("+")
        val signed = if (flow.direction == CashFlowDirection.OUTFLOW) "-$magnitude" else magnitude
        CashScenarioFlowDocument(date = flow.date.trim(), amount = signed)
    },
)

/** Guards a stored title before persistence; blank or oversized titles are refused, not truncated. */
internal fun validSavedTitle(raw: String): Boolean =
    raw.isNotBlank() && raw.length <= CASH_SCENARIO_MAX_TITLE_CHARS
