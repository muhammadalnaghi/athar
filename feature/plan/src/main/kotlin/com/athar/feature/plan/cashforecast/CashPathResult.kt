package com.athar.feature.plan.cashforecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastResult
import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.feature.plan.R
import java.util.Locale

@Composable
fun CashPathResult(
    outcome: CashForecastOutcome,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resource-reactive locale, identical to the design system's currentUiLocale();
    // never the process-default JVM locale.
    val locale = currentUiLocale()
    val theme = AtharTheme
    AtharCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.sm)) {
            AtharText(
                text = stringResource(R.string.plan_cash_path_result_title),
                style = theme.typography.headline,
                color = theme.colors.ink,
            )
            when (outcome) {
                is CashForecastOutcome.Rejected -> RejectedBody(outcome)
                is CashForecastOutcome.Forecast -> ForecastBody(outcome.result, locale, onOpenPath)
            }
        }
    }
}

/** Maximum known-path prefix points shown inline; the rest live in the full-path drilldown. */

@Composable
internal fun RejectedBody(outcome: CashForecastOutcome.Rejected) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_rejected_intro),
            style = theme.typography.body,
            color = theme.colors.ink,
        )
        outcome.reasons.forEach { reason ->
            AtharText(
                text = rejectionLine(reason),
                style = theme.typography.body,
                color = theme.colors.ink,
            )
        }
        if (outcome.offendingIds.isNotEmpty()) {
            AtharText(
                text = stringResource(
                    R.string.plan_cash_path_result_rejected_offending_ids,
                    outcome.offendingIds.joinToString(", "),
                ),
                style = theme.typography.caption,
                color = theme.colors.dust,
            )
        }
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_rejected_none_applied),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
internal fun ForecastBody(
    result: CashForecastResult,
    locale: Locale,
    onOpenPath: (String) -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.sm)) {
        result.partitions.forEachIndexed { index, partition ->
            if (index > 0) HairlineDivider()
            PartitionBlock(partition, locale, onOpenPath)
        }
        if (result.disclosures.isNotEmpty()) {
            DisclosuresBlock(result.disclosures)
        }
        AtharText(
            text = stringResource(
                if (result.decisionReady) {
                    R.string.plan_cash_path_result_decision_ready
                } else {
                    R.string.plan_cash_path_result_decision_not_ready
                },
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
internal fun PartitionBlock(
    partition: CurrencyPartitionForecast,
    locale: Locale,
    onOpenPath: (String) -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtharText(text = partition.currency, style = theme.typography.overline, color = theme.colors.ink)
            AtharText(
                text = partitionStatusLabel(partition.status),
                style = theme.typography.overline,
                color = partitionStatusColor(partition.status),
            )
        }
        partition.opening?.let { opening -> OpeningBlock(opening, locale) }
        val headline = cashDisplayHeadline(partition)
        when (partition.status) {
            PartitionStatus.CONFIDENT ->
                if (headline != null) HeadlineBlock(headline, locale) else KnownEndBlock(partition, locale)
            PartitionStatus.PARTIAL -> KnownEndBlock(partition, locale)
            PartitionStatus.WITHHELD -> WithheldBlock()
        }
        if (partition.path.isNotEmpty() && partition.status != PartitionStatus.WITHHELD) {
            KnownPathPoints(partition, locale)
            FullPathAction(partition.currency, onOpenPath)
        }
    }
}

@Composable
internal fun KnownEndBlock(partition: CurrencyPartitionForecast, locale: Locale) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        val last = partition.path.lastOrNull()
        if (last != null) {
            FigureRow(
                label = stringResource(
                    R.string.plan_cash_path_result_partial_known_end,
                    dayLabel(last.date, locale),
                ),
                money = last.cash,
                locale = locale,
            )
        }
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_partial_note),
            style = theme.typography.caption,
            color = theme.colors.dust,
        )
    }
}

@Composable
internal fun WithheldBlock() {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_withheld_body),
            style = theme.typography.body,
            color = theme.colors.ink,
        )
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_withheld_note),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}
