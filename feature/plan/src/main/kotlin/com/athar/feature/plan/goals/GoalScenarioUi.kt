package com.athar.feature.plan.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.feature.plan.GoalsEvent
import com.athar.feature.plan.GoalsState
import com.athar.feature.plan.R
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Transient goal-scenario section of the Plan Goals tab. Every number comes from the
 * accepted `GoalPlanCalc` via [GoalScenarioDraft.solve]; unavailable facts render as
 * explicit states, never as fabricated dates, rates or zeros. The form is labeled as a
 * temporary what-if: nothing here writes to storage.
 *
 * Currency authority is [GoalScenarioUi.currency] — the literal code this scenario was
 * created under — never the ambient [GoalsState.currency]. While preferences are
 * loading (or the scenario is not yet bound to a currency) the scope line says so,
 * compute/reset are disabled, and no seed/compute assumption is offered; results are
 * always labeled with the currency they were solved in, so a display-currency change
 * can never silently relabel old figures, and no FX conversion is implied anywhere.
 */
@Composable
fun GoalScenarioCard(
    state: GoalsState,
    onEvent: (GoalsEvent) -> Unit,
) {
    val theme = AtharTheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val draft = state.scenario.draft
    val invalid = state.scenario.result as? GoalSolveUi.Invalid
    val currencyBound = state.scenario.currency.isNotBlank()
    val inputsLive = !state.isLoading && currencyBound

    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.plan_goals_scenario_title),
                style = theme.typography.headline,
                color = theme.colors.ink,
            )
            AtharText(
                text = stringResource(R.string.plan_goals_scenario_transient),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            ScenarioCurrencyScope(
                currency = state.scenario.currency,
                loading = state.isLoading,
            )

            ScenarioField(
                value = draft.targetAmountText,
                label = stringResource(R.string.plan_goals_scenario_target_amount),
                decimal = true,
                isError = invalid.hasError(GoalField.TARGET_AMOUNT),
                onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(targetAmountText = it))) },
            )
            SelectorRow(
                options = listOf(
                    SelectorOption(GoalTargetBasis.NOMINAL, stringResource(R.string.plan_goals_scenario_basis_nominal)),
                    SelectorOption(GoalTargetBasis.TODAY_PRICE, stringResource(R.string.plan_goals_scenario_basis_today)),
                ),
                selected = draft.targetBasis,
                onSelect = { onEvent(GoalsEvent.UpdateScenario(draft.copy(targetBasis = it))) },
            )
            if (draft.targetBasis == GoalTargetBasis.TODAY_PRICE) {
                ScenarioField(
                    value = draft.inflationPercentText,
                    label = stringResource(R.string.plan_goals_scenario_inflation),
                    decimal = true,
                    isError = invalid.hasError(GoalField.TARGET_INFLATION),
                    onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(inflationPercentText = it))) },
                )
                ConventionText(stringResource(R.string.plan_goals_scenario_inflation_convention))
            }

            ScenarioField(
                value = draft.currentAllocationText,
                label = stringResource(R.string.plan_goals_scenario_allocation),
                decimal = true,
                isError = invalid.hasError(GoalField.CURRENT_ALLOCATION),
                onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(currentAllocationText = it))) },
            )
            ConventionText(stringResource(R.string.plan_goals_scenario_allocation_source))
            ScenarioField(
                value = draft.monthlyContributionText,
                label = stringResource(R.string.plan_goals_scenario_contribution),
                decimal = true,
                isError = invalid.hasError(GoalField.MONTHLY_CONTRIBUTION),
                onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(monthlyContributionText = it))) },
            )
            ConventionText(stringResource(R.string.plan_goals_scenario_contribution_source))
            if (draft.solveMode != GoalSolveMode.REQUIRED_MONTHS) {
                ScenarioField(
                    value = draft.horizonMonthsText,
                    label = stringResource(R.string.plan_goals_scenario_horizon),
                    decimal = false,
                    isError = invalid.hasError(GoalField.HORIZON_MONTHS),
                    onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(horizonMonthsText = it))) },
                )
            }

            SelectorRow(
                options = listOf(
                    SelectorOption(GoalAssumptionsKind.CASH_ONLY, stringResource(R.string.plan_goals_scenario_assumptions_cash)),
                    SelectorOption(GoalAssumptionsKind.FIXED_APR, stringResource(R.string.plan_goals_scenario_assumptions_apr)),
                ),
                selected = draft.assumptionsKind,
                onSelect = { onEvent(GoalsEvent.UpdateScenario(draft.copy(assumptionsKind = it))) },
            )
            when (draft.assumptionsKind) {
                GoalAssumptionsKind.CASH_ONLY ->
                    ConventionText(stringResource(R.string.plan_goals_scenario_zero_return_convention))
                GoalAssumptionsKind.FIXED_APR -> {
                    ScenarioField(
                        value = draft.aprPercentText,
                        label = stringResource(R.string.plan_goals_scenario_apr),
                        decimal = true,
                        isError = invalid.hasError(GoalField.APR_PERCENT),
                        onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(aprPercentText = it))) },
                    )
                    ScenarioField(
                        value = draft.feePercentText,
                        label = stringResource(R.string.plan_goals_scenario_fee),
                        decimal = true,
                        isError = invalid.hasError(GoalField.FEE_PERCENT),
                        onValueChange = { onEvent(GoalsEvent.UpdateScenario(draft.copy(feePercentText = it))) },
                    )
                    ConventionText(stringResource(R.string.plan_goals_scenario_fee_convention))
                }
            }

            SelectorRow(
                label = stringResource(R.string.plan_goals_scenario_timing_label),
                options = listOf(
                    SelectorOption(ContributionTiming.BEGINNING_OF_MONTH, stringResource(R.string.plan_goals_scenario_timing_beginning)),
                    SelectorOption(ContributionTiming.END_OF_MONTH, stringResource(R.string.plan_goals_scenario_timing_end)),
                ),
                selected = draft.timing,
                onSelect = { onEvent(GoalsEvent.UpdateScenario(draft.copy(timing = it))) },
            )
            SelectorRow(
                label = stringResource(R.string.plan_goals_scenario_mode_label),
                options = listOf(
                    SelectorOption(GoalSolveMode.PROJECT_FUTURE_AMOUNT, stringResource(R.string.plan_goals_scenario_mode_project)),
                    SelectorOption(GoalSolveMode.REQUIRED_MONTHLY_CONTRIBUTION, stringResource(R.string.plan_goals_scenario_mode_contribution)),
                    SelectorOption(GoalSolveMode.REQUIRED_MONTHS, stringResource(R.string.plan_goals_scenario_mode_months)),
                    SelectorOption(GoalSolveMode.REQUIRED_NOMINAL_APR, stringResource(R.string.plan_goals_scenario_mode_rate)),
                ),
                selected = draft.solveMode,
                onSelect = { onEvent(GoalsEvent.UpdateScenario(draft.copy(solveMode = it))) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                ActionButton(
                    label = stringResource(R.string.plan_goals_scenario_compute),
                    container = theme.colors.ember,
                    content = theme.colors.parchment,
                    modifier = Modifier.weight(1f),
                    enabled = inputsLive,
                    onClick = {
                        focus.clearFocus()
                        keyboard?.hide()
                        onEvent(GoalsEvent.ComputeScenario)
                    },
                )
                ActionButton(
                    label = stringResource(R.string.plan_goals_scenario_reset),
                    container = Color.Transparent,
                    content = theme.colors.ink,
                    hairlineBorder = true,
                    modifier = Modifier.weight(1f),
                    enabled = inputsLive,
                    onClick = { onEvent(GoalsEvent.ResetScenario) },
                )
            }

            ScenarioResult(state.scenario.result, inputsLive = inputsLive)
        }
    }
}

/**
 * Scope disclosure for the scenario: names the literal bound currency, states that no
 * FX conversion is applied, and warns that changing the display currency clears the
 * scenario. While loading it withholds the compute invitation instead of implying a
 * currency that has not arrived yet.
 */
@Composable
private fun ScenarioCurrencyScope(currency: String, loading: Boolean) {
    val theme = AtharTheme
    AtharText(
        text = when {
            loading || currency.isBlank() ->
                stringResource(R.string.plan_goals_scenario_loading_currency)
            else -> stringResource(R.string.plan_goals_scenario_currency_scope, currency)
        },
        style = theme.typography.caption,
        color = theme.colors.muted,
    )
}

@Preview(name = "Goal scenario form", showBackground = true, locale = "ar")
@Composable
private fun GoalScenarioFormPreview() {
    AtharTheme {
        GoalScenarioCard(state = previewState(result = null), onEvent = {})
    }
}

@Preview(name = "Goal scenario computed", showBackground = true, locale = "ar")
@Composable
private fun GoalScenarioComputedPreview() {
    AtharTheme {
        val state = previewState(result = null)
        GoalScenarioCard(
            state = state.copy(scenario = state.scenario.copy(
                result = state.scenario.draft.solve(state.currency, YearMonth.of(2026, 10)),
            )),
            onEvent = {},
        )
    }
}

@Preview(name = "Goal scenario loading", showBackground = true, locale = "ar")
@Composable
private fun GoalScenarioLoadingPreview() {
    AtharTheme {
        GoalScenarioCard(
            state = previewState(result = null, isLoading = true, currency = ""),
            onEvent = {},
        )
    }
}

/** Preview-only sample state; production UI renders only real user figures. */
private fun previewState(
    result: GoalSolveUi?,
    isLoading: Boolean = false,
    currency: String = "SAR",
): GoalsState = GoalsState(
    monthlyIncome = Money.of("30000.00", "SAR"),
    monthlyExpense = Money.of("27000.00", "SAR"),
    monthlySavings = Money.of("3000.00", "SAR"),
    savingsRatePercent = BigDecimal("10.0"),
    savingsRateTargetPercent = 20,
    savingsRateProgress = 0.5f,
    liquidBalance = Money.of("7000.00", "SAR"),
    emergencyTargetAmount = Money.of("162000.00", "SAR"),
    emergencyMonthsCovered = null,
    emergencyFundTargetMonths = 6,
    emergencyFundProgress = 0f,
    isLoading = isLoading,
    currency = currency,
    scenario = GoalScenarioUi(
        draft = GoalScenarioDraft(
            targetAmountText = "25000",
            targetBasis = GoalTargetBasis.TODAY_PRICE,
            inflationPercentText = "3",
            currentAllocationText = "7000.00",
            monthlyContributionText = "3000.00",
            horizonMonthsText = "24",
            assumptionsKind = GoalAssumptionsKind.FIXED_APR,
            aprPercentText = "6",
            feePercentText = "1",
            timing = ContributionTiming.END_OF_MONTH,
            solveMode = GoalSolveMode.PROJECT_FUTURE_AMOUNT,
        ),
        result = result,
        currency = currency,
    ),
)
