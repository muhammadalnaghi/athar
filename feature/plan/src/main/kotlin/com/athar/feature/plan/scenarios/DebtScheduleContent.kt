package com.athar.feature.plan.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.feature.plan.R
import com.athar.feature.plan.debt.MoneyRow
import com.athar.feature.plan.debt.strategyName

@Composable
internal fun DebtScheduleContent(page: DebtSchedulePage, onEvent: (DebtScenarioEvent) -> Unit) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.m)) {
        AtharText(strategyName(page.strategy), style = theme.typography.headline)
        AtharText(stringResource(R.string.debt_schedule_terms_note), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.debt_schedule_page, page.page + 1, page.totalCycles),
            style = theme.typography.caption, color = theme.colors.muted)
        page.cycles.forEach { cycle ->
            HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
            AtharText(stringResource(R.string.debt_schedule_cycle, cycle.cycle, cycle.dueDate.toString()), style = theme.typography.headline)
            cycle.entries.forEach { entry ->
                AtharText(entry.debtId.value, style = theme.typography.body)
                MoneyRow(stringResource(R.string.debt_schedule_opening), entry.openingBalance)
                MoneyRow(stringResource(R.string.debt_result_total_interest), entry.interest)
                MoneyRow(stringResource(R.string.debt_result_total_fees), entry.fee)
                MoneyRow(stringResource(R.string.debt_schedule_payment), entry.payment)
                MoneyRow(stringResource(R.string.debt_schedule_principal), entry.principalPaid)
                MoneyRow(stringResource(R.string.debt_schedule_closing), entry.closingBalance)
            }
            MoneyRow(stringResource(R.string.debt_schedule_unused), cycle.unusedBudget)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ScenarioTextAction(stringResource(R.string.scenario_previous), page.hasPrevious) {
                onEvent(DebtScenarioEvent.SchedulePage(page.page - 1))
            }
            ScenarioTextAction(stringResource(R.string.scenario_next), page.hasNext) {
                onEvent(DebtScenarioEvent.SchedulePage(page.page + 1))
            }
        }
    }
}
