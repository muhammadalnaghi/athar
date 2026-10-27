package com.athar.feature.plan

import com.athar.core.common.money.Money
import com.athar.core.domain.model.GoalSettings
import com.athar.feature.plan.goals.GoalScenarioDraft
import com.athar.feature.plan.goals.GoalScenarioUi
import com.athar.feature.plan.goals.GoalSolveUi
import java.math.BigDecimal

data class GoalsState(
    val monthlyIncome: Money,
    val monthlyExpense: Money,
    val monthlySavings: Money,
    val savingsRatePercent: BigDecimal?,
    val savingsRateTargetPercent: Int,
    val savingsRateProgress: Float,
    val liquidBalance: Money,
    /**
     * False while the only balance source is ledger-derived (no dated balance anchor):
     * [liquidBalance] then remains available as a scenario-seeding input but is NEVER
     * presented as current liquidity or as funded emergency coverage.
     */
    val liquidityCertified: Boolean = false,
    val emergencyTargetAmount: Money,
    val emergencyMonthsCovered: BigDecimal?,
    val emergencyFundTargetMonths: Int,
    val emergencyFundProgress: Float,
    val isLoading: Boolean,

    /**
     * Currency codes present in confirmed-transaction amounts or liquid balances that
     * were EXCLUDED from the display-currency totals (no-FX policy: amounts are never
     * silently relabeled or converted). Empty when every input matches [currency].
     */
    val excludedCurrencies: Set<String> = emptySet(),
    val currency: String = Money.SAR,
    val scenario: GoalScenarioUi = GoalScenarioUi.initial(),

    /**
     * Saved-goal documents observed from the frozen `SavedFinancialPlanRepository`
     * (kind GOAL), each already classified against the CURRENT display currency.
     * Stored inputs only — never cached solver outcomes.
     */
    val savedGoals: List<SavedGoalSummary> = emptyList(),

    /** The document currently loaded into the transient scenario, if any. */
    val activeSavedGoalId: String? = null,
    val savedGoalTitleText: String = "",
    val savedGoalSaving: Boolean = false,
    val savedGoalNotice: SavedGoalNotice? = null,

    /**
     * Conditional side-by-side snapshot: the decoded saved document next to the live
     * form, both solved by the accepted calculator at open time. Any later edit,
     * reset, load, or currency rebind discards it instead of mislabeling the snapshot.
     */
    val savedGoalCompare: SavedGoalCompareUi? = null,
) {
    companion object {
        fun initial(): GoalsState = GoalsState(
            monthlyIncome = Money.zero(),
            monthlyExpense = Money.zero(),
            monthlySavings = Money.zero(),
            savingsRatePercent = null,
            savingsRateTargetPercent = GoalSettings.DEFAULT_SAVINGS_RATE_TARGET_PERCENT,
            savingsRateProgress = 0f,
            liquidBalance = Money.zero(),
            emergencyTargetAmount = Money.zero(),
            emergencyMonthsCovered = null,
            emergencyFundTargetMonths = GoalSettings.DEFAULT_EMERGENCY_FUND_TARGET_MONTHS,
            emergencyFundProgress = 0f,
            isLoading = true,
        )
    }
}

sealed interface GoalsEvent {
    data class SetSavingsRateTarget(val percent: Int) : GoalsEvent
    data class SetEmergencyFundTarget(val months: Int) : GoalsEvent
    data class UpdateScenario(val draft: GoalScenarioDraft) : GoalsEvent
    data object ComputeScenario : GoalsEvent
    data object ResetScenario : GoalsEvent
    data class SetSavedGoalTitle(val title: String) : GoalsEvent

    /**
     * Create-only save when no document is loaded; captured-revision update of the
     * loaded document when one is. Both persist the VISIBLE draft as inputs.
     */
    data object SaveCurrentGoal : GoalsEvent
    data class LoadSavedGoal(val id: String) : GoalsEvent
    data class DeleteSavedGoal(val id: String) : GoalsEvent
    data object DismissSavedGoalNotice : GoalsEvent

    /**
     * Opens the conditional saved-vs-current side-by-side comparison: both drafts are
     * solved by the accepted calculator at open time. Read-only — no document is
     * written and no outcome is persisted.
     */
    data class CompareSavedGoal(val id: String) : GoalsEvent
    data object DismissSavedGoalCompare : GoalsEvent
}

/**
 * One comparison snapshot. [savedDraft] is the decoded stored input document, verbatim;
 * [liveDraft] is the form as it stood when the comparison was opened. Results are
 * recomputed solver outcomes for exactly these drafts, never cached storage.
 */
data class SavedGoalCompareUi(
    val savedId: String,
    val savedTitle: String,
    val savedCurrency: String,
    val savedDraft: GoalScenarioDraft,
    val liveDraft: GoalScenarioDraft,
    val savedResult: GoalSolveUi?,
    val liveResult: GoalSolveUi?,
)

/** One row of the saved-goal list, classified for reachability under the live currency. */
data class SavedGoalSummary(
    val id: String,
    val title: String,
    val currency: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val state: SavedGoalDocState,
)

enum class SavedGoalDocState {
    /** Inputs decode as version 1 and the literal currency matches the live display currency. */
    LOADABLE,

    /** Valid inputs, saved under a different literal currency: retained, never relabeled. */
    OTHER_CURRENCY,

    /** Malformed, missing fields, or a payload version this build does not support. */
    UNSUPPORTED,
}

/** Fixed, constant notices only — no stored JSON or internal error text is ever shown. */
sealed interface SavedGoalNotice {
    data object Saved : SavedGoalNotice
    data object Updated : SavedGoalNotice
    data object Loaded : SavedGoalNotice
    data object Deleted : SavedGoalNotice
    data object NeedsTitle : SavedGoalNotice
    data object Conflict : SavedGoalNotice
    data object UnsupportedDocument : SavedGoalNotice
    data object CurrencyMismatch : SavedGoalNotice
    data object LoadFailed : SavedGoalNotice
    data object SaveFailed : SavedGoalNotice
    data object DeleteFailed : SavedGoalNotice
}
