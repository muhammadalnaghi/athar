package com.athar.feature.plan.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.feature.plan.GoalsEvent
import com.athar.feature.plan.R
import com.athar.feature.plan.SavedGoalCompareUi

/**
 * Conditional side-by-side comparison of the loaded saved goal against the current
 * form: the literal stored inputs on one side, the live inputs on the other, visible
 * assumptions on both, and each side's outcome recomputed by the accepted `GoalPlanCalc`
 * at open time — no second calculator and no cached outcome. Every assumption that can
 * change the answer (target basis, inflation, growth model, fees, deposit timing) is
 * rendered as text, so the two columns are never compared silently. The allocation row
 * is explicitly an in-scenario assumption, and the card closes explicitly; any edit,
 * reset, load or currency change discards the whole snapshot in the ViewModel.
 */
@Composable
internal fun SavedGoalCompareCard(
    compare: SavedGoalCompareUi,
    onEvent: (GoalsEvent) -> Unit,
) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.plan_goals_compare_title),
                style = theme.typography.headline,
                color = theme.colors.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                CompareColumn(
                    modifier = Modifier.weight(1f),
                    header = stringResource(R.string.plan_goals_compare_saved_inputs, compare.savedCurrency),
                    subheader = compare.savedTitle,
                    draft = compare.savedDraft,
                    result = compare.savedResult,
                )
                CompareColumn(
                    modifier = Modifier.weight(1f),
                    header = stringResource(R.string.plan_goals_compare_live_inputs),
                    subheader = null,
                    draft = compare.liveDraft,
                    result = compare.liveResult,
                )
            }
            ConventionText(stringResource(R.string.plan_goals_compare_allocation_assumption))
            ActionButton(
                label = stringResource(R.string.plan_goals_compare_close),
                container = Color.Transparent,
                content = theme.colors.ink,
                hairlineBorder = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onEvent(GoalsEvent.DismissSavedGoalCompare) },
            )
        }
    }
}

@Composable
private fun CompareColumn(
    header: String,
    subheader: String?,
    draft: GoalScenarioDraft,
    result: GoalSolveUi?,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = header, style = theme.typography.caption, color = theme.colors.muted)
        subheader?.let {
            AtharText(text = it, style = theme.typography.caption, color = theme.colors.ink)
        }
        CompareCell(stringResource(R.string.plan_goals_scenario_target_amount), draft.targetAmountText)
        CompareCell(stringResource(R.string.plan_goals_compare_basis), basisLabel(draft.targetBasis))
        if (draft.targetBasis == GoalTargetBasis.TODAY_PRICE) {
            CompareCell(stringResource(R.string.plan_goals_scenario_inflation), draft.inflationPercentText)
        }
        CompareCell(stringResource(R.string.plan_goals_scenario_allocation), draft.currentAllocationText)
        CompareCell(stringResource(R.string.plan_goals_scenario_contribution), draft.monthlyContributionText)
        if (draft.solveMode != GoalSolveMode.REQUIRED_MONTHS) {
            CompareCell(stringResource(R.string.plan_goals_scenario_horizon), draft.horizonMonthsText)
        }
        CompareCell(stringResource(R.string.plan_goals_compare_assumptions), assumptionsLabel(draft.assumptionsKind))
        if (draft.assumptionsKind == GoalAssumptionsKind.FIXED_APR) {
            CompareCell(stringResource(R.string.plan_goals_scenario_apr), draft.aprPercentText)
            CompareCell(stringResource(R.string.plan_goals_scenario_fee), draft.feePercentText)
        }
        CompareCell(stringResource(R.string.plan_goals_scenario_timing_label), timingLabel(draft.timing))
        CompareCell(stringResource(R.string.plan_goals_scenario_mode_label), modeLabel(draft.solveMode))
        ScenarioResult(result = result, inputsLive = true)
    }
}

@Composable
private fun CompareCell(label: String, value: String) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
        AtharText(
            text = value.ifBlank { "—" },
            style = theme.typography.body,
            color = theme.colors.ink,
        )
    }
}

/** Visible assumption labels reuse the exact form wording; nothing is paraphrased. */
@Composable
private fun basisLabel(basis: GoalTargetBasis): String = stringResource(
    if (basis == GoalTargetBasis.NOMINAL) {
        R.string.plan_goals_scenario_basis_nominal
    } else {
        R.string.plan_goals_scenario_basis_today
    },
)

@Composable
private fun assumptionsLabel(kind: GoalAssumptionsKind): String = stringResource(
    if (kind == GoalAssumptionsKind.CASH_ONLY) {
        R.string.plan_goals_scenario_assumptions_cash
    } else {
        R.string.plan_goals_scenario_assumptions_apr
    },
)

@Composable
private fun timingLabel(timing: ContributionTiming): String = stringResource(
    if (timing == ContributionTiming.BEGINNING_OF_MONTH) {
        R.string.plan_goals_scenario_timing_beginning
    } else {
        R.string.plan_goals_scenario_timing_end
    },
)

@Composable
private fun modeLabel(mode: GoalSolveMode): String = stringResource(
    when (mode) {
        GoalSolveMode.PROJECT_FUTURE_AMOUNT -> R.string.plan_goals_scenario_mode_project
        GoalSolveMode.REQUIRED_MONTHLY_CONTRIBUTION -> R.string.plan_goals_scenario_mode_contribution
        GoalSolveMode.REQUIRED_MONTHS -> R.string.plan_goals_scenario_mode_months
        GoalSolveMode.REQUIRED_NOMINAL_APR -> R.string.plan_goals_scenario_mode_rate
    },
)
