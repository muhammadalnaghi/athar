package com.athar.feature.plan.scenarios

import com.athar.core.domain.model.debt.CycleLedger
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.repo.SavedFinancialPlan

internal data class DebtSchedulePage(
    val strategy: DebtStrategy,
    val cycles: List<CycleLedger>,
    val totalCycles: Int,
    val page: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/** One saved document as shown in the list; unavailable records stay retained, never hidden or deleted. */
internal data class DebtSavedEntry(
    val plan: SavedFinancialPlan,
    val available: Boolean,
)

/** The loaded document binding; severed by currency change, reset, delete and conflicts. */
internal data class DebtLoadedPlan(
    val id: String,
    val title: String,
    val revision: Long,
    val currency: String,
)

internal enum class DebtSaveMessage {
    SAVED, UPDATED, RENAMED, DELETED, CONFLICT, FAILED, TITLE_INVALID, UNAVAILABLE,
}

internal data class DebtScenarioState(
    val draft: DebtScenarioDraft = DebtScenarioDraft(),
    val errors: Set<String> = emptySet(),
    val outcome: DebtPayoffOutcome? = null,
    val computing: Boolean = false,
    val schedule: DebtSchedulePage? = null,
    val saved: List<DebtSavedEntry> = emptyList(),
    val loaded: DebtLoadedPlan? = null,
    val saving: Boolean = false,
    val saveTitle: String = "",
    val message: DebtSaveMessage? = null,
)

internal enum class DebtField { CURRENCY, FIRST_DUE, HORIZON, BUDGET }
internal enum class DebtLoanField { NAME, PRINCIPAL, ANNUAL_PERCENT, MINIMUM, FEE }

internal sealed interface DebtScenarioEvent {
    data class Edit(val field: DebtField, val text: String) : DebtScenarioEvent
    data class EditLoan(val id: Int, val field: DebtLoanField, val text: String) : DebtScenarioEvent
    data class AcknowledgeTerms(val checked: Boolean) : DebtScenarioEvent
    data object AddLoan : DebtScenarioEvent
    data class RemoveLoan(val id: Int) : DebtScenarioEvent
    data object Compute : DebtScenarioEvent
    data object Reset : DebtScenarioEvent
    data class OpenSchedule(val strategy: DebtStrategy) : DebtScenarioEvent
    data class SchedulePage(val page: Int) : DebtScenarioEvent
    data object BackToResult : DebtScenarioEvent
    data class EditSaveTitle(val text: String) : DebtScenarioEvent
    data object SaveAsNew : DebtScenarioEvent
    data object UpdateSaved : DebtScenarioEvent
    data class LoadSaved(val id: String) : DebtScenarioEvent
    data class RenameSaved(val id: String, val title: String) : DebtScenarioEvent
    data class DeleteSaved(val id: String) : DebtScenarioEvent
    data object DismissMessage : DebtScenarioEvent
}
