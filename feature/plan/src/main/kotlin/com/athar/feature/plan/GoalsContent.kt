package com.athar.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.feature.plan.goals.GoalScenarioCard
import com.athar.feature.plan.goals.SavedGoalCompareCard
import com.athar.feature.plan.goals.SavedGoalsCard
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun GoalsContent(viewModel: GoalsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GoalTarget?>(null) }
    val theme = AtharTheme

    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
        SavingsRateCard(
            state = state,
            onClick = { editing = GoalTarget.SAVINGS_RATE },
        )
        EmergencyFundCard(
            state = state,
            onClick = { editing = GoalTarget.EMERGENCY_FUND },
        )
        // Scope disclosure: the card totals above are literal in the loaded display
        // currency only; foreign-currency amounts are excluded, never converted.
        if (!state.isLoading) {
            CurrencyScopeNote(currency = state.currency, excluded = state.excludedCurrencies)
        }
        GoalScenarioCard(state = state, onEvent = viewModel::onEvent)
        SavedGoalsCard(state = state, onEvent = viewModel::onEvent)
        // Conditional side-by-side: only while a saved-vs-current comparison snapshot
        // is open; any edit/reset/load/currency change discards it in the ViewModel.
        state.savedGoalCompare?.let { compare ->
            SavedGoalCompareCard(compare = compare, onEvent = viewModel::onEvent)
        }
    }

    editing?.let { target ->
        GoalTargetSheet(
            target = target,
            state = state,
            onDismiss = { editing = null },
            onSave = { value ->
                when (target) {
                    GoalTarget.SAVINGS_RATE -> viewModel.onEvent(GoalsEvent.SetSavingsRateTarget(value))
                    GoalTarget.EMERGENCY_FUND -> viewModel.onEvent(GoalsEvent.SetEmergencyFundTarget(value))
                }
                editing = null
            },
        )
    }
}

@Composable
private fun SavingsRateCard(
    state: GoalsState,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    val percent = state.savingsRatePercent
    GoalCard(
        title = stringResource(R.string.plan_goals_savings_title),
        headline = if (state.isLoading || percent == null) {
            stringResource(R.string.plan_goals_not_available)
        } else {
            stringResource(R.string.plan_goals_percent_value, percent.formatOne())
        },
        target = stringResource(R.string.plan_goals_savings_target, state.savingsRateTargetPercent),
        progress = state.savingsRateProgress,
        progressColor = if (state.savingsRateProgress >= 1f) theme.colors.olive else theme.colors.ember,
        showProgress = !state.isLoading,
        onClick = onClick,
    ) {
        if (state.isLoading) {
            // While loading, the zero-initialized amounts are not settled facts.
            LoadingFiguresNote()
        } else {
            MoneyMetricRow(label = stringResource(R.string.plan_goals_monthly_income), money = state.monthlyIncome)
            MoneyMetricRow(label = stringResource(R.string.plan_goals_monthly_expense), money = state.monthlyExpense)
            MoneyMetricRow(
                label = stringResource(R.string.plan_goals_monthly_savings),
                money = state.monthlySavings,
                color = if (state.monthlySavings.isNegative()) theme.colors.ember else theme.colors.olive,
            )
        }
    }
}

@Composable
private fun EmergencyFundCard(
    state: GoalsState,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    GoalCard(
        title = stringResource(R.string.plan_goals_emergency_title),
        headline = when {
            state.isLoading -> stringResource(R.string.plan_goals_not_available)
            // No dated certified balance: coverage is withheld, never a "0 months" claim.
            !state.liquidityCertified -> stringResource(R.string.plan_goals_emergency_covered_withheld)
            state.emergencyMonthsCovered != null ->
                stringResource(R.string.plan_goals_months_value, state.emergencyMonthsCovered.formatOne())
            else -> stringResource(R.string.plan_goals_no_monthly_burn)
        },
        target = stringResource(R.string.plan_goals_emergency_target, state.emergencyFundTargetMonths),
        progress = state.emergencyFundProgress,
        progressColor = if (state.emergencyFundProgress >= 1f) theme.colors.olive else theme.colors.ember,
        showProgress = !state.isLoading && state.liquidityCertified,
        onClick = onClick,
    ) {
        if (state.isLoading) {
            LoadingFiguresNote()
        } else if (!state.liquidityCertified) {
            AtharText(
                text = stringResource(R.string.plan_goals_liquidity_withheld),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            MoneyMetricRow(label = stringResource(R.string.plan_goals_monthly_expense), money = state.monthlyExpense)
            MoneyMetricRow(label = stringResource(R.string.plan_goals_emergency_target_amount), money = state.emergencyTargetAmount)
        } else {
            MoneyMetricRow(label = stringResource(R.string.plan_goals_liquid_balance), money = state.liquidBalance)
            MoneyMetricRow(label = stringResource(R.string.plan_goals_monthly_expense), money = state.monthlyExpense)
            MoneyMetricRow(label = stringResource(R.string.plan_goals_emergency_target_amount), money = state.emergencyTargetAmount)
        }
    }
}

@Composable
private fun GoalCard(
    title: String,
    headline: String,
    target: String,
    progress: Float,
    progressColor: androidx.compose.ui.graphics.Color,
    showProgress: Boolean = true,
    onClick: () -> Unit,
    metrics: @Composable ColumnScope.() -> Unit,
) {
    val theme = AtharTheme
    AtharCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = title, style = theme.typography.caption, color = theme.colors.muted)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                AtharText(text = headline, style = theme.typography.headline, color = theme.colors.ink)
                AtharText(text = target, style = theme.typography.caption, color = theme.colors.muted)
            }
            if (showProgress) {
                ProgressRail(progress = progress, color = progressColor)
            }
            metrics()
        }
    }
}

@Composable
private fun ProgressRail(progress: Float, color: androidx.compose.ui.graphics.Color) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.colors.divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color),
        )
    }
}

@Composable
private fun MoneyMetricRow(
    label: String,
    money: Money,
    color: androidx.compose.ui.graphics.Color = AtharTheme.colors.ink,
) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
        AtharNumber(money = money, color = color)
    }
}

private enum class GoalTarget {
    SAVINGS_RATE,
    EMERGENCY_FUND,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalTargetSheet(
    target: GoalTarget,
    state: GoalsState,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val config = when (target) {
        GoalTarget.SAVINGS_RATE -> TargetConfig(
            title = stringResource(R.string.plan_goals_edit_savings_title),
            label = stringResource(R.string.plan_goals_edit_savings_label),
            initialValue = state.savingsRateTargetPercent,
            range = 0..100,
            error = stringResource(R.string.plan_goals_edit_savings_error),
        )
        GoalTarget.EMERGENCY_FUND -> TargetConfig(
            title = stringResource(R.string.plan_goals_edit_emergency_title),
            label = stringResource(R.string.plan_goals_edit_emergency_label),
            initialValue = state.emergencyFundTargetMonths,
            range = 1..120,
            error = stringResource(R.string.plan_goals_edit_emergency_error),
        )
    }
    var value by remember(target) { mutableStateOf(config.initialValue.toString()) }
    var error by remember(target) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = config.title, style = theme.typography.headline)
            AtharTextField(
                value = value,
                onValueChange = { value = it; error = false },
                label = config.label,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number,
                isError = error,
                supportingText = if (error) config.error else null,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.ember)
                    .clickable {
                        val parsed = value.trim().toIntOrNull()
                        if (parsed == null || parsed !in config.range) {
                            error = true
                        } else {
                            onSave(parsed)
                        }
                    }
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(text = stringResource(R.string.plan_action_save), style = theme.typography.headline, color = theme.colors.parchment)
            }
        }
    }
}

private data class TargetConfig(
    val title: String,
    val label: String,
    val initialValue: Int,
    val range: IntRange,
    val error: String,
)

private fun BigDecimal.formatOne(): String =
    setScale(1, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
