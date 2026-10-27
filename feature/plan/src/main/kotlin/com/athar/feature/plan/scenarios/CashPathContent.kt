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
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.feature.plan.R
import com.athar.feature.plan.cashforecast.FigureRow
import com.athar.feature.plan.cashforecast.dayLabel
import com.athar.feature.plan.cashforecast.partitionStatusLabel

@Composable
internal fun CashPathContent(page: CashPathPage, onEvent: (CashScenarioEvent) -> Unit) {
    val theme = AtharTheme
    val locale = currentUiLocale()
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.m)) {
        AtharText(page.currency, style = theme.typography.headline)
        AtharText(partitionStatusLabel(page.status), style = theme.typography.body)
        AtharText(stringResource(R.string.cash_scenario_path_note), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.cash_scenario_path_page, page.page + 1, page.totalPoints),
            style = theme.typography.caption, color = theme.colors.muted)
        page.points.forEach { point ->
            HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
            AtharText(dayLabel(point.day.date, locale), style = theme.typography.headline)
            FigureRow(stringResource(R.string.plan_cash_path_result_figure_cash), point.day.cash, locale)
            FigureRow(stringResource(R.string.plan_cash_path_result_figure_reserved), point.day.reserved, locale)
            FigureRow(stringResource(R.string.cash_scenario_available), point.day.available, locale)
            if (point.flowNumbers.isEmpty()) {
                AtharText(stringResource(R.string.cash_scenario_opening_point), style = theme.typography.caption, color = theme.colors.muted)
            } else point.flowNumbers.forEach { number ->
                AtharText(stringResource(R.string.cash_scenario_expected_flow, number), style = theme.typography.caption, color = theme.colors.muted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ScenarioTextAction(stringResource(R.string.scenario_previous), page.hasPrevious) { onEvent(CashScenarioEvent.PathPage(page.page - 1)) }
            ScenarioTextAction(stringResource(R.string.scenario_next), page.hasNext) { onEvent(CashScenarioEvent.PathPage(page.page + 1)) }
        }
    }
}
