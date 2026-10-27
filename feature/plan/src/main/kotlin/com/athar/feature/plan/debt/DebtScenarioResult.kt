package com.athar.feature.plan.debt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.feature.plan.R

@Composable
fun DebtScenarioResult(
    outcome: DebtPayoffOutcome,
    onOpenSchedule: (DebtStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (outcome) {
        is DebtPayoffOutcome.Compared -> ComparedResult(outcome, onOpenSchedule, modifier)
        is DebtPayoffOutcome.HorizonExhausted -> HorizonExhaustedResult(outcome, onOpenSchedule, modifier)
        is DebtPayoffOutcome.BudgetBelowMinimums -> BudgetBelowMinimumsResult(outcome, modifier)
        is DebtPayoffOutcome.UnsupportedTerms -> UnsupportedTermsResult(outcome, modifier)
        is DebtPayoffOutcome.InvalidInput -> InvalidInputResult(modifier)
    }
}

@Composable
internal fun ComparedResult(
    outcome: DebtPayoffOutcome.Compared,
    onOpenSchedule: (DebtStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    val cheaper = debtCheaperStrategy(outcome.avalanche, outcome.snowball)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
    ) {
        AtharText(
            text = stringResource(R.string.debt_result_compared_title),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        StrategySummary(
            run = outcome.avalanche,
            onOpenSchedule = onOpenSchedule,
            isCheaper = cheaper == DebtStrategy.AVALANCHE,
        )
        HorizontalDivider(color = theme.colors.divider, thickness = HairlineThickness)
        StrategySummary(
            run = outcome.snowball,
            onOpenSchedule = onOpenSchedule,
            isCheaper = cheaper == DebtStrategy.SNOWBALL,
        )
    }
}

@Composable
internal fun HorizonExhaustedResult(
    outcome: DebtPayoffOutcome.HorizonExhausted,
    onOpenSchedule: (DebtStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
    ) {
        AtharText(
            text = stringResource(R.string.debt_result_horizon_title),
            style = theme.typography.headline,
            color = theme.colors.dust,
        )
        AtharText(
            text = stringResource(R.string.debt_result_horizon_body, outcome.horizonCycles),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        StrategySummary(run = outcome.avalanche, onOpenSchedule = onOpenSchedule, partialWithinHorizon = true)
        HorizontalDivider(color = theme.colors.divider, thickness = HairlineThickness)
        StrategySummary(run = outcome.snowball, onOpenSchedule = onOpenSchedule, partialWithinHorizon = true)
    }
}

@Composable
internal fun BudgetBelowMinimumsResult(
    outcome: DebtPayoffOutcome.BudgetBelowMinimums,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        AtharText(
            text = stringResource(R.string.debt_result_below_minimums_title),
            style = theme.typography.headline,
            color = theme.colors.dust,
        )
        AtharText(
            text = stringResource(R.string.debt_result_below_minimums_body),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        MoneyRow(label = stringResource(R.string.debt_result_budget), money = outcome.budget)
        MoneyRow(label = stringResource(R.string.debt_result_required_minimums), money = outcome.requiredMinimumsTotal)
        if (outcome.byDebt.isNotEmpty()) {
            AtharText(
                text = stringResource(R.string.debt_result_minimums_by_debt),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            outcome.byDebt.forEach { share ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
                ) {
                    AtharText(text = share.debtId.value, style = theme.typography.caption)
                    AtharNumber(money = share.cappedMinimum)
                }
            }
        }
    }
}

@Composable
internal fun UnsupportedTermsResult(
    outcome: DebtPayoffOutcome.UnsupportedTerms,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        AtharText(
            text = stringResource(R.string.debt_result_unsupported_title),
            style = theme.typography.headline,
            color = theme.colors.dust,
        )
        AtharText(
            text = stringResource(R.string.debt_result_unsupported_body),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        outcome.rejections.forEach { rejection ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
            ) {
                AtharText(
                    text = rejection.debtId.value,
                    style = theme.typography.caption,
                )
                AtharText(
                    text = unsupportedReasonText(rejection.reason),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
    }
}

@Composable
internal fun InvalidInputResult(modifier: Modifier = Modifier) {
    val theme = AtharTheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(
            text = stringResource(R.string.debt_result_invalid_title),
            style = theme.typography.headline,
        )
        AtharText(
            text = stringResource(R.string.debt_result_invalid_body),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}
