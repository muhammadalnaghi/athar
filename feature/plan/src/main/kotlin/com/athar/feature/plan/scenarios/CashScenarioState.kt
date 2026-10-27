package com.athar.feature.plan.scenarios

import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.feature.plan.cashforecast.CashComparisonRow

internal data class CashPathPoint(val day: DatedCashDay, val flowNumbers: List<Int>)
internal data class CashPathPage(
    val currency: String,
    val status: PartitionStatus,
    val points: List<CashPathPoint>,
    val totalPoints: Int,
    val page: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)
/** Safe constant save/delete outcomes; never exposes stored JSON or storage internals. */
internal enum class CashSaveMessage { SAVED, UPDATED, DELETED, TITLE_REQUIRED, CONFLICT, STORAGE, MISSING }

/** Save/load/update/delete panel state; [loaded] fences updates and deletions by exact revision. */
internal data class CashSavedUiState(
    val loaded: CashSavedSummary? = null,
    val titleDraft: String = "",
    val saving: Boolean = false,
    val message: CashSaveMessage? = null,
)

/** Saved-scenario comparison state; rows are recomputed outcomes, never stored results. */
internal data class CashCompareUiState(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
    val running: Boolean = false,
    val rows: List<CashComparisonRow> = emptyList(),
    val empty: Boolean = false,
)

internal data class CashScenarioState(
    val draft: CashScenarioDraft = CashScenarioDraft(),
    val errors: Set<String> = emptySet(),
    val outcome: CashForecastOutcome? = null,
    val computing: Boolean = false,
    val path: CashPathPage? = null,
    val savedList: CashSavedListing = CashSavedListing(),
    val saved: CashSavedUiState = CashSavedUiState(),
    val compare: CashCompareUiState = CashCompareUiState(),
    /** Armed explicit-deletion confirmation; deletion happens only after a second user action. */
    val confirmDeleteId: String? = null,
)
internal enum class CashField { CURRENCY, OPENING_DATE, OPENING_CASH, RESERVE, HORIZON_END }
internal enum class CashFlowField { DATE, AMOUNT }
internal sealed interface CashScenarioEvent {
    data class Edit(val field: CashField, val text: String) : CashScenarioEvent
    data class EditFlow(val id: Int, val field: CashFlowField, val text: String) : CashScenarioEvent
    data class Direction(val id: Int, val direction: CashFlowDirection) : CashScenarioEvent
    data class ScopeComplete(val checked: Boolean) : CashScenarioEvent
    data class AnchorFresh(val checked: Boolean) : CashScenarioEvent
    data object AddFlow : CashScenarioEvent
    data class RemoveFlow(val id: Int) : CashScenarioEvent
    data object Compute : CashScenarioEvent
    data object Reset : CashScenarioEvent
    data class OpenPath(val currency: String) : CashScenarioEvent
    data class PathPage(val page: Int) : CashScenarioEvent
    data object BackToResult : CashScenarioEvent
    data class SaveTitle(val text: String) : CashScenarioEvent
    data object SaveScenario : CashScenarioEvent
    data class LoadSaved(val id: String) : CashScenarioEvent
    data class DeleteSaved(val id: String) : CashScenarioEvent
    data object ConfirmDeleteSaved : CashScenarioEvent
    data object CancelDeleteSaved : CashScenarioEvent
    data object CompareOpen : CashScenarioEvent
    data object CompareClose : CashScenarioEvent
    data class CompareToggle(val id: String) : CashScenarioEvent
    data object CompareRun : CashScenarioEvent
}
