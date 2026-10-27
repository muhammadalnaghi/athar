package com.athar.feature.plan.debt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.StrategyRun
import com.athar.core.domain.model.debt.UnsupportedTermReason
import com.athar.feature.plan.R
import java.time.Month
import java.time.format.TextStyle
import kotlinx.datetime.LocalDate

@Composable
internal fun StrategySummary(
    run: StrategyRun,
    onOpenSchedule: (DebtStrategy) -> Unit,
    isCheaper: Boolean = false,
    partialWithinHorizon: Boolean = false,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtharText(text = strategyName(run.strategy), style = theme.typography.headline)
            if (isCheaper) {
                AtharText(
                    text = stringResource(R.string.debt_result_cheaper_total),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
            }
        }
        if (partialWithinHorizon) {
            AtharText(
                text = stringResource(R.string.debt_result_horizon_partial),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        MoneyRow(label = stringResource(R.string.debt_result_total_interest), money = run.totalInterest)
        MoneyRow(label = stringResource(R.string.debt_result_total_fees), money = run.totalFees)
        MoneyRow(label = stringResource(R.string.debt_result_total_paid), money = run.totalPaid)
        debtPayoffDate(run)?.let { payoffDate ->
            ValueRow(
                label = stringResource(R.string.debt_result_payoff_date),
                value = settledDateText(payoffDate),
            )
        }
        TextButton(
            onClick = { onOpenSchedule(run.strategy) },
            modifier = Modifier.heightIn(min = MinTouchTarget),
            colors = ButtonDefaults.textButtonColors(contentColor = theme.colors.ember),
        ) {
            AtharText(
                text = stringResource(R.string.debt_result_open_schedule),
                style = theme.typography.body,
                color = theme.colors.ember,
            )
        }
    }
}

@Composable
internal fun MoneyRow(label: String, money: Money) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
        AtharNumber(money = money)
    }
}

@Composable
internal fun ValueRow(label: String, value: String) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
        AtharText(text = value, style = theme.typography.headline)
    }
}

@Composable
internal fun strategyName(strategy: DebtStrategy): String = when (strategy) {
    DebtStrategy.AVALANCHE -> stringResource(R.string.debt_result_strategy_avalanche)
    DebtStrategy.SNOWBALL -> stringResource(R.string.debt_result_strategy_snowball)
}

@Composable
internal fun unsupportedReasonText(reason: UnsupportedTermReason): String = when (reason) {
    UnsupportedTermReason.MISSING_TERMS -> stringResource(R.string.debt_result_reason_missing_terms)
    UnsupportedTermReason.TIMING_NOT_COMPLETE_MONTHLY_CYCLE -> stringResource(R.string.debt_result_reason_timing)
    UnsupportedTermReason.NON_NOMINAL_OR_DAILY_ACCRUAL -> stringResource(R.string.debt_result_reason_accrual)
    UnsupportedTermReason.FLAT_PROFIT_TERMS -> stringResource(R.string.debt_result_reason_flat_profit)
    UnsupportedTermReason.UNKNOWN_OR_VARIABLE_FEES -> stringResource(R.string.debt_result_reason_variable_fees)
    UnsupportedTermReason.PREPAYMENT_RESTRICTED -> stringResource(R.string.debt_result_reason_prepayment)
}

/** Locale-reactive date text; only ever called for an actually settled run. */

@Composable
internal fun settledDateText(date: LocalDate): String {
    val locale = currentUiLocale()
    val monthName = Month.of(date.monthNumber).getDisplayName(TextStyle.FULL, locale)
    return stringResource(R.string.debt_result_payoff_date_value, date.dayOfMonth, monthName, date.year)
}

/** Strictly cheaper actual total; equal totals carry no tag. */
