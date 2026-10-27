package com.athar.feature.plan.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.wealth.GoalInputProblemCode
import com.athar.core.domain.model.wealth.GoalNotReachableReason
import com.athar.feature.plan.R
import java.math.RoundingMode
import java.time.format.TextStyle

/**
 * Solver-outcome rendering extracted from [GoalScenarioCard] (WM028-P-goals UI scope).
 * Every figure comes from the accepted `GoalPlanCalc` via [GoalScenarioDraft.solve];
 * unavailable facts render as explicit states, never as fabricated dates, rates or
 * zeros. Money rendering stays on the shared [AtharNumber] formatter.
 */

/** Draft field carries a local (pre-solver) input error, for flagging the exact input. */
internal fun GoalSolveUi.Invalid?.hasError(field: GoalField): Boolean =
    this?.fieldErrors?.contains(field) == true

/**
 * Latest solver outcome, or the explicit waiting/empty states. While [inputsLive] is
 * false (preferences or display currency still loading) the compute invitation is
 * withheld — an unbound scenario cannot be computed, so the card must not read as if
 * it could, and no initial zero is presented as a settled result.
 */
@Composable
internal fun ScenarioResult(result: GoalSolveUi?, inputsLive: Boolean) {
    val theme = AtharTheme
    when (result) {
        null -> AtharText(
            text = if (inputsLive) {
                stringResource(R.string.plan_goals_scenario_empty_hint)
            } else {
                stringResource(R.string.plan_goals_scenario_loading_currency)
            },
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        is GoalSolveUi.Invalid -> InvalidResult(result)
        is GoalSolveUi.Projected -> {
            ResultRow(stringResource(R.string.plan_goals_scenario_result_future), result.projection.futureAmount)
            ResultRow(stringResource(R.string.plan_goals_scenario_result_contributions), result.projection.totalContributions)
            ResultRow(
                label = stringResource(R.string.plan_goals_scenario_result_growth),
                money = result.projection.growthAmount,
                color = if (result.projection.growthAmount.isNegative()) theme.colors.ember else theme.colors.olive,
            )
            ResultRow(stringResource(R.string.plan_goals_scenario_result_nominal_target), result.projection.nominalTargetAtHorizon, muted = true)
            result.cashOnlyFutureAmount?.let { baseline ->
                ResultRow(stringResource(R.string.plan_goals_scenario_result_cash_baseline), baseline, muted = true)
            }
        }
        is GoalSolveUi.RequiredContributionUi -> {
            ResultRow(stringResource(R.string.plan_goals_scenario_result_required_contribution), result.value.monthlyContribution)
            ResultRow(stringResource(R.string.plan_goals_scenario_result_nominal_target), result.value.nominalTargetAtHorizon, muted = true)
        }
        is GoalSolveUi.RequiredMonthsUi -> {
            ValueRow(
                label = stringResource(R.string.plan_goals_scenario_mode_months),
                value = stringResource(R.string.plan_goals_scenario_result_reach_in, result.value.months),
            )
            ValueRow(
                label = stringResource(R.string.plan_goals_scenario_result_reach_month),
                value = stringResource(
                    R.string.plan_goals_scenario_month_year,
                    result.value.reachMonth.month.getDisplayName(TextStyle.FULL, currentUiLocale()),
                    result.value.reachMonth.year,
                ),
            )
            ResultRow(stringResource(R.string.plan_goals_scenario_result_nominal_target), result.value.nominalTargetAtReach, muted = true)
        }
        is GoalSolveUi.RequiredRateUi -> {
            ValueRow(
                label = stringResource(R.string.plan_goals_scenario_result_required_rate),
                value = stringResource(
                    R.string.plan_goals_scenario_result_rate_value,
                    result.value.nominalAprPercent.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString(),
                ),
            )
            ResultRow(stringResource(R.string.plan_goals_scenario_result_nominal_target), result.value.nominalTargetAtHorizon, muted = true)
        }
        GoalSolveUi.AlreadyFunded -> AtharText(
            text = stringResource(R.string.plan_goals_scenario_already_funded),
            style = theme.typography.caption,
            color = theme.colors.olive,
        )
        is GoalSolveUi.NotReachable -> AtharText(
            text = when (result.reason) {
                GoalNotReachableReason.BEYOND_TECHNICAL_HORIZON ->
                    stringResource(R.string.plan_goals_scenario_not_reachable_horizon)
                GoalNotReachableReason.NEVER_REACHES ->
                    stringResource(R.string.plan_goals_scenario_not_reachable_never)
                GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS ->
                    stringResource(R.string.plan_goals_scenario_not_reachable_rate_bounds)
            },
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
private fun InvalidResult(invalid: GoalSolveUi.Invalid) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        if (invalid.problems.isNotEmpty()) {
            AtharText(
                text = stringResource(R.string.plan_goals_scenario_invalid_summary),
                style = theme.typography.caption,
                color = theme.colors.ember,
            )
        }
        invalid.problems.forEach { code ->
            AtharText(text = problemText(code), style = theme.typography.caption, color = theme.colors.muted)
        }
        if (invalid.problems.isEmpty()) {
            AtharText(
                text = stringResource(R.string.plan_goals_scenario_invalid_summary),
                style = theme.typography.caption,
                color = theme.colors.ember,
            )
        }
    }
}

@Composable
private fun problemText(code: GoalInputProblemCode): String = when (code) {
    GoalInputProblemCode.BLANK_SCENARIO_NAME -> stringResource(R.string.plan_goals_problem_blank_name)
    GoalInputProblemCode.CURRENCY_MISMATCH -> stringResource(R.string.plan_goals_problem_currency_mismatch)
    GoalInputProblemCode.TARGET_NOT_POSITIVE -> stringResource(R.string.plan_goals_problem_target_not_positive)
    GoalInputProblemCode.NEGATIVE_CURRENT_ALLOCATION -> stringResource(R.string.plan_goals_problem_negative_allocation)
    GoalInputProblemCode.NEGATIVE_MONTHLY_CONTRIBUTION -> stringResource(R.string.plan_goals_problem_negative_contribution)
    GoalInputProblemCode.HORIZON_OUT_OF_TECHNICAL_BOUNDS -> stringResource(R.string.plan_goals_problem_horizon_bounds)
    GoalInputProblemCode.APR_MONTHLY_RATE_OUT_OF_RANGE -> stringResource(R.string.plan_goals_problem_apr_rate_range)
    GoalInputProblemCode.NEGATIVE_FEE -> stringResource(R.string.plan_goals_problem_negative_fee)
    GoalInputProblemCode.INFLATION_MONTHLY_RATE_OUT_OF_RANGE -> stringResource(R.string.plan_goals_problem_inflation_range)
}

@Composable
private fun ResultRow(label: String, money: Money, color: Color = AtharTheme.colors.ink, muted: Boolean = false) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
        AtharNumber(money = money, color = if (muted) theme.colors.muted else color)
    }
}

@Composable
private fun ValueRow(label: String, value: String, color: Color = AtharTheme.colors.ink) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
        AtharText(text = value, style = theme.typography.headline, color = color)
    }
}
