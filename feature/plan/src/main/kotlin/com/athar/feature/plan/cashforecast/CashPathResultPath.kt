package com.athar.feature.plan.cashforecast

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.feature.plan.R
import java.util.Locale

@Composable
internal fun KnownPathPoints(partition: CurrencyPartitionForecast, locale: Locale) {
    val theme = AtharTheme
    val path = partition.path
    val shown = knownCashPathPrefix(partition)
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_path_points_label),
            style = theme.typography.overline,
            color = theme.colors.muted,
        )
        shown.forEach { day -> PathPointRow(day, locale) }
        AtharText(
            text = stringResource(
                R.string.plan_cash_path_result_path_points_count,
                shown.size,
                path.size,
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
internal fun PathPointRow(day: DatedCashDay, locale: Locale) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        FigureRow(dayLabel(day.date, locale), day.cash, locale)
        FigureRow(stringResource(R.string.plan_cash_path_result_figure_available), day.available, locale)
    }
}

@Composable
internal fun FullPathAction(currency: String, onOpenPath: (String) -> Unit) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.sm))
            .border(HairlineThickness, theme.colors.divider, RoundedCornerShape(theme.spacing.sm))
            .clickable { onOpenPath(currency) }
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_action_open_full_path),
            modifier = Modifier.weight(1f),
            style = theme.typography.headline,
            color = theme.colors.ember,
        )
        AtharText(text = currency, style = theme.typography.caption, color = theme.colors.muted)
    }
}
