package com.athar.feature.plan.cashforecast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.core.domain.model.cashforecast.PartitionHeadline
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.feature.plan.R
import java.time.LocalDate as JavaLocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.datetime.LocalDate

@Composable
internal fun OpeningBlock(opening: DatedCashDay, locale: Locale) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_opening_prefix, dayLabel(opening.date, locale)),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        FigureRow(stringResource(R.string.plan_cash_path_result_figure_cash), opening.cash, locale)
        FigureRow(stringResource(R.string.plan_cash_path_result_figure_reserved), opening.reserved, locale)
    }
}

@Composable
internal fun HeadlineBlock(headline: PartitionHeadline, locale: Locale) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        FigureRow(
            label = stringResource(
                R.string.plan_cash_path_result_headline_minimum_on,
                dayLabel(headline.minimumAvailableDate, locale),
            ),
            money = headline.minimumAvailable,
            locale = locale,
        )
        val shortfall = headline.shortfall
        if (shortfall != null) {
            val firstNegative = headline.firstNegativeAvailableDate
            FigureRow(
                label = if (firstNegative != null) {
                    stringResource(
                        R.string.plan_cash_path_result_headline_shortfall_on,
                        dayLabel(firstNegative, locale),
                    )
                } else {
                    stringResource(R.string.plan_cash_path_result_headline_shortfall_plain)
                },
                money = shortfall,
                locale = locale,
                valueColor = theme.colors.dust,
            )
        }
        FigureRow(
            label = stringResource(R.string.plan_cash_path_result_headline_final),
            money = headline.finalBalance,
            locale = locale,
        )
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_confident_note),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
internal fun FigureRow(
    label: String,
    money: Money?,
    locale: Locale,
    valueColor: Color? = null,
) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(text = label, style = theme.typography.body, color = theme.colors.ink)
        if (money != null) {
            AtharNumber(money = money, locale = locale, color = valueColor)
        } else {
            AtharText(
                text = stringResource(R.string.plan_cash_path_result_value_unknown),
                style = theme.typography.body,
                color = theme.colors.dust,
            )
        }
    }
}

@Composable
internal fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HairlineThickness)
            .background(AtharTheme.colors.divider),
    )
}

@Composable
internal fun partitionStatusLabel(status: PartitionStatus): String = stringResource(
    when (status) {
        PartitionStatus.CONFIDENT -> R.string.plan_cash_path_result_status_confident
        PartitionStatus.PARTIAL -> R.string.plan_cash_path_result_status_partial
        PartitionStatus.WITHHELD -> R.string.plan_cash_path_result_status_withheld
    },
)

@Composable
internal fun partitionStatusColor(status: PartitionStatus) = when (status) {
    PartitionStatus.CONFIDENT -> AtharTheme.colors.olive
    PartitionStatus.PARTIAL, PartitionStatus.WITHHELD -> AtharTheme.colors.dust
}

internal fun dayLabel(date: LocalDate, locale: Locale): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(locale)
    .format(JavaLocalDate.of(date.year, date.monthNumber, date.dayOfMonth))
