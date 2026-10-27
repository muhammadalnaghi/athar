package com.athar.feature.trends

import com.athar.core.designsystem.display.currentUiLocale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.designsystem.component.AtharBarChart
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharComparisonBars
import com.athar.core.designsystem.component.AtharComparisonSlot
import com.athar.core.designsystem.component.AtharEmptyState
import com.athar.core.designsystem.component.AtharMonthlyChartWithLines
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharProportionBar
import com.athar.core.designsystem.component.AtharSegment
import com.athar.core.designsystem.component.AtharSegmentedControl
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TrendsScreen(
    modifier: Modifier = Modifier,
    viewModel: TrendsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrendsContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

@Composable
private fun TrendsContent(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    var showCustomRange by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier
        .fillMaxSize()
        .background(theme.colors.parchment)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.l),
        ) {
            AtharText(text = stringResource(R.string.trends_overline), style = theme.typography.overline, color = theme.colors.muted)

            AtharSegmentedControl(
                segments = listOf(
                    AtharSegment(PeriodKey.WEEK, stringResource(R.string.trends_period_week)),
                    AtharSegment(PeriodKey.MONTH, stringResource(R.string.trends_period_month)),
                    AtharSegment(PeriodKey.MONTHS_3, stringResource(R.string.trends_period_months_3)),
                    AtharSegment(PeriodKey.YEAR, stringResource(R.string.trends_period_year)),
                    AtharSegment(PeriodKey.MONTH_VS_PREVIOUS, stringResource(R.string.trends_period_vs_previous)),
                    AtharSegment(PeriodKey.CUSTOM, stringResource(R.string.trends_period_custom)),
                ),
                selected = state.periodKey,
                onSelect = {
                    if (it == PeriodKey.CUSTOM) showCustomRange = true
                    else onEvent(TrendsEvent.SelectPeriod(it))
                },
            )

            if (state.periodKey == PeriodKey.CUSTOM && state.customStart != null && state.customEnd != null) {
                CustomRangeBanner(
                    start = state.customStart,
                    end = state.customEnd,
                    onEdit = { showCustomRange = true },
                )
            }

            FilterBar(state = state, onOpenFilters = { showFilters = true }, onEvent = onEvent)

            HeadlineNumbersCard(state = state, onEvent = onEvent)
            IncomeExpenseSavingsCard(state = state, onEvent = onEvent)

            // Answer the current spending question before the deeper comparison/dashboard sections.
            // Category split (horizontal proportional bar — pie replacement).
            if (state.categorySplit.isNotEmpty()) {
                AtharCard {
                    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                        AtharText(text = stringResource(R.string.trends_section_category_split), style = theme.typography.headline)
                        AtharProportionBar(
                            segments = state.categorySplit.map { segment ->
                                val resolved = bucketLabel(segment.key, segment.labelAr, segment.labelEn)
                                segment.copy(labelAr = resolved, labelEn = resolved)
                            },
                            onSegmentClick = { key -> onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket(key))) },
                        )
                    }
                }
            }

            if (state.categories.isEmpty() && !state.isLoading) {
                AtharEmptyState(
                    text = stringResource(R.string.trends_empty_text),
                    subtle = stringResource(R.string.trends_empty_subtle),
                )
            } else if (state.categories.isNotEmpty()) {
                AtharCard {
                    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                        AtharText(text = stringResource(R.string.trends_section_top_categories), style = theme.typography.headline)
                        AtharText(
                            text = stringResource(R.string.trends_top_categories_hint),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                        AtharBarChart(
                            items = state.categories.map { item ->
                                val resolved = bucketLabel(item.key, item.labelAr, item.labelEn.orEmpty())
                                item.copy(labelAr = resolved, labelEn = resolved)
                            },
                            modifier = Modifier.padding(top = theme.spacing.s),
                            onItemClick = { key -> onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket(key))) },
                        )
                    }
                }
            }

            // Current vs comparison — like-for-like windows, explicitly labeled.
            PeriodComparisonCard(state = state, onEvent = onEvent)
            if (state.categoryDeltas.isNotEmpty()) {
                CategoryComparisonTable(state = state, onEvent = onEvent)
            }

            // TMOAP Dashboard parity — three monthly bar panels with target + average overlays.
            if (state.monthly.expense.any { it.amount.amount.signum() > 0 } ||
                state.monthly.income.any { it.amount.amount.signum() > 0 }
            ) {
                MonthlyDashboardCard(state = state, onEvent = onEvent)
            }
        }
    }

    if (showCustomRange) {
        CustomRangePickerSheet(
            initialStart = state.customStart,
            initialEnd = state.customEnd,
            today = state.today,
            onDismiss = { showCustomRange = false },
            onConfirm = { s, e ->
                onEvent(TrendsEvent.SelectCustomRange(s, e))
                showCustomRange = false
            },
        )
    }

    if (showFilters) {
        FiltersSheet(
            state = state,
            onEvent = onEvent,
            onDismiss = { showFilters = false },
        )
    }

    state.evidence?.let {
        EvidenceSheet(state = state, onEvent = onEvent)
    }

    state.detailTransaction?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            categoryLabels = state.evidence?.categoryLabels ?: emptyMap(),
            accountNames = state.accountNames,
            onDismiss = { onEvent(TrendsEvent.CloseTransactionDetail) },
        )
    }
}

@Composable
private fun bucketLabel(key: String, fallbackAr: String, fallbackEn: String = ""): String = when (key) {
    UNCATEGORIZED_KEY -> stringResource(R.string.trends_uncategorized)
    OTHER_KEY -> stringResource(R.string.trends_other)
    // An orphaned (deleted) category id has no labels — show the calm localized
    // label, never the raw id.
    else -> fallbackAr.ifBlank { fallbackEn }.ifBlank { stringResource(R.string.trends_unknown_category) }
}

@Composable
private fun accountLabel(state: TrendsState, accountId: String): String =
    state.accountNames[accountId] ?: stringResource(R.string.trends_unknown_account)

@Composable
private fun metricTitle(metric: TrendsMetric): String = when (metric) {
    TrendsMetric.EXPENSE -> stringResource(R.string.trends_label_expense)
    TrendsMetric.INCOME -> stringResource(R.string.trends_label_income)
    TrendsMetric.SAVINGS -> stringResource(R.string.trends_label_savings)
}

@Composable
private fun sourceLabel(source: IngestSource): String = when (source) {
    IngestSource.MANUAL -> stringResource(R.string.trends_source_manual)
    IngestSource.SMS -> stringResource(R.string.trends_source_sms)
    IngestSource.NOTIFICATION -> stringResource(R.string.trends_source_notification)
    IngestSource.SHARE -> stringResource(R.string.trends_source_share)
    IngestSource.IMPORT -> stringResource(R.string.trends_source_import)
    IngestSource.RECURRING -> stringResource(R.string.trends_source_recurring)
}

@Composable
private fun typeLabel(type: TxType): String = when (type) {
    TxType.EXPENSE -> stringResource(R.string.trends_label_expense)
    TxType.INCOME -> stringResource(R.string.trends_label_income)
    TxType.TRANSFER -> stringResource(R.string.trends_type_transfer)
}

private fun inclusiveEnd(period: Period): String = when (period) {
    is Period.Custom -> period.endExclusive.minus(DatePeriod(days = 1)).toString()
    is Period.Last -> period.endExclusive.minus(DatePeriod(days = 1)).toString()
    is Period.Month -> period.month.atEndOfMonth().toString()
    is Period.Year -> LocalDate(period.year, 12, 31).toString()
}

@Composable
private fun monthName(ym: YearMonth): String =
    ym.month.getDisplayName(TextStyle.FULL, currentUiLocale())

@Composable
private fun periodLabel(period: Period?): String = when (period) {
    is Period.Month -> stringResource(R.string.trends_month_year, monthName(period.month), period.month.year)
    is Period.Year -> period.year.toString()
    is Period.Custom, is Period.Last -> stringResource(
        R.string.trends_period_range,
        period.start.toString(),
        inclusiveEnd(period),
    )
    null -> ""
}

@Composable
private fun comparisonLabel(state: TrendsState): String = when {
    state.customPrior != null -> stringResource(
        R.string.trends_comparison_custom,
        state.customPrior.start.toString(),
        state.customPrior.endExclusive.minus(DatePeriod(days = 1)).toString(),
    )
    state.previousMatchedElapsed -> stringResource(R.string.trends_comparison_matched_elapsed)
    else -> stringResource(R.string.trends_comparison_previous_period)
}

/** Discloses exactly which current days the delta excludes (null tail = nothing excluded). */
@Composable
private fun TailNote(state: TrendsState) {
    if (state.unmatchedTail == null) return
    val theme = AtharTheme
    val matchedEnd = state.comparisonWindow?.endExclusive?.minus(DatePeriod(days = 1))?.toString() ?: return
    AtharText(
        text = stringResource(R.string.trends_comparison_tail_excluded, matchedEnd),
        style = theme.typography.caption,
        color = theme.colors.dust,
    )
}

@Composable
private fun rangeErrorText(error: TrendsRangeError): String = when (error) {
    TrendsRangeError.END_BEFORE_START -> stringResource(R.string.trends_error_start_before_end)
    TrendsRangeError.START_IN_FUTURE -> stringResource(R.string.trends_error_future_start)
}

@Composable
private fun FilterBar(
    state: TrendsState,
    onOpenFilters: () -> Unit,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    val filters = state.filters
    val activeCount = filters.accountIds.size + filters.sources.size +
        (if (filters.type != null) 1 else 0) +
        (if (filters.categoryId != null) 1 else 0) +
        (if (filters.merchantQuery != null) 1 else 0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        val label = if (activeCount == 0) {
            stringResource(R.string.trends_filters)
        } else {
            stringResource(R.string.trends_filters_active, activeCount)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(theme.spacing.m))
                .background(if (activeCount == 0) theme.colors.surface else theme.colors.ember.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClickLabel = label) { onOpenFilters() }
                .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s)
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            AtharText(
                text = label,
                style = theme.typography.caption,
                color = if (activeCount == 0) theme.colors.ink else theme.colors.ember,
            )
        }
        if (activeCount > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(theme.spacing.m))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.trends_filters_clear)) {
                        onEvent(TrendsEvent.ClearFilters)
                    }
                    .padding(horizontal = theme.spacing.s, vertical = theme.spacing.s)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(
                    text = stringResource(R.string.trends_filters_clear),
                    style = theme.typography.caption,
                    color = theme.colors.ember,
                )
            }
        }
    }
}

@Composable
private fun HeadlineNumbersCard(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.trends_open_evidence),
                    ) { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE))) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    AtharText(text = stringResource(R.string.trends_label_expense), style = theme.typography.caption, color = theme.colors.muted)
                    AtharNumber(money = state.totalExpense, landmark = true)
                    AtharText(
                        text = stringResource(R.string.trends_open_evidence),
                        style = theme.typography.caption,
                        color = theme.colors.ember,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.trends_open_evidence),
                    ) { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.EXPENSE))) },
            ) {
                val delta = state.expenseDelta
                val pct = state.expenseDeltaPercent
                if (delta == null) {
                    AtharText(
                        text = stringResource(R.string.trends_no_comparison),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                } else {
                    if (pct == null) {
                        // Zero baseline: absolute change only, never a fabricated percentage.
                        AtharText(
                            text = stringResource(R.string.trends_zero_baseline),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        val isOver = delta.isPositive()
                        val color = if (isOver) theme.colors.ember else theme.colors.olive
                        val arrow = if (isOver) "↑" else "↓"
                        // WM071: locale digits/grouping; non-finite is withheld like a zero baseline.
                        val absPct = TrendsPercentage071.magnitudeToken(kotlin.math.abs(pct), currentUiLocale())
                        if (absPct != null) {
                            AtharText(text = "$arrow $absPct", style = theme.typography.headline, color = color)
                        }
                    }
                    AtharText(
                        text = comparisonLabel(state),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
            }
            TailNote(state)
            state.customRangeError?.let {
                AtharText(text = rangeErrorText(it), style = theme.typography.caption, color = theme.colors.dust)
            }
            state.comparisonRangeError?.let {
                AtharText(text = rangeErrorText(it), style = theme.typography.caption, color = theme.colors.dust)
            }
            if (state.futureRange) {
                AtharText(
                    text = stringResource(R.string.trends_future_range_note),
                    style = theme.typography.caption,
                    color = theme.colors.dust,
                )
            }
            state.expensePercentOfIncome?.let { pct ->
                TrendsPercentage071.number(pct, currentUiLocale())?.let { text ->
                    AtharText(
                        text = stringResource(R.string.trends_expense_percent_of_income, text),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
            }
            if (state.mixedCurrency) {
                AtharText(
                    text = stringResource(R.string.trends_mixed_currency_note),
                    style = theme.typography.caption,
                    color = theme.colors.dust,
                )
            }
            if (state.currencyOverride != null) {
                AtharText(
                    text = stringResource(R.string.trends_currency_line, state.reportingCurrency),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
    }
}

@Composable
private fun IncomeExpenseSavingsCard(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.trends_income_expense_savings), style = theme.typography.headline)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricColumn(
                    label = stringResource(R.string.trends_label_income),
                    money = state.totalIncome,
                    color = theme.colors.olive,
                    onClickLabel = stringResource(R.string.trends_open_evidence),
                ) { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.INCOME))) }
                MetricColumn(
                    label = stringResource(R.string.trends_label_expense),
                    money = state.totalExpense,
                    color = theme.colors.ember,
                    onClickLabel = stringResource(R.string.trends_open_evidence),
                ) { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE))) }
                MetricColumn(
                    label = stringResource(R.string.trends_label_savings),
                    money = state.savings,
                    color = if (state.savings.isNegative()) theme.colors.ember else theme.colors.olive,
                    onClickLabel = stringResource(R.string.trends_open_evidence),
                ) { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.SAVINGS))) }
            }
            state.savingsRate?.let { rate ->
                TrendsPercentage071.number(rate, currentUiLocale())?.let { text ->
                    AtharText(
                        text = stringResource(R.string.trends_savings_rate, text),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    money: Money,
    color: androidx.compose.ui.graphics.Color,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = onClickLabel) { onClick() }
            .heightIn(min = 48.dp),
    ) {
        Column {
            AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted)
            AtharNumber(money = money, color = color)
        }
    }
}

@Composable
private fun PeriodComparisonCard(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.trends_period_comparison), style = theme.typography.headline)
            AtharText(
                text = if (state.previousMatchedElapsed) {
                    stringResource(R.string.trends_period_comparison_legend_matched)
                } else {
                    stringResource(R.string.trends_period_comparison_legend)
                },
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            // Like-for-like: the bars compare the same equal-elapsed populations the
            // deltas use — the full headline is never pitted against a matched window.
            AtharComparisonBars(
                currentIncome = state.comparisonIncome,
                previousIncome = state.previousIncome,
                currentExpense = state.comparisonExpense,
                previousExpense = state.previousExpense,
                currentSavings = state.comparisonSavings,
                previousSavings = state.previousSavings,
                onBarClick = { slot ->
                    val metric = when (slot) {
                        AtharComparisonSlot.INCOME_CURRENT, AtharComparisonSlot.INCOME_PREVIOUS -> TrendsMetric.INCOME
                        AtharComparisonSlot.EXPENSE_CURRENT, AtharComparisonSlot.EXPENSE_PREVIOUS -> TrendsMetric.EXPENSE
                        AtharComparisonSlot.SAVINGS_CURRENT, AtharComparisonSlot.SAVINGS_PREVIOUS -> TrendsMetric.SAVINGS
                    }
                    onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.HeadlineComparison(metric)))
                },
            )
            TailNote(state)
            DeltaRow(
                label = stringResource(R.string.trends_delta_income),
                value = state.incomeDelta,
                positiveIsGood = true,
                onClick = { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.INCOME))) },
            )
            DeltaRow(
                label = stringResource(R.string.trends_delta_expense),
                value = state.expenseDelta,
                positiveIsGood = false,
                onClick = { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.EXPENSE))) },
            )
            DeltaRow(
                label = stringResource(R.string.trends_delta_savings),
                value = state.savingsDelta,
                positiveIsGood = true,
                onClick = { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.SAVINGS))) },
            )
            val curRate = state.savingsRate
            val prevRate = state.previousSavingsRate
            if (curRate != null && prevRate != null) {
                val curText = TrendsPercentage071.number(curRate, currentUiLocale())
                val prevText = TrendsPercentage071.number(prevRate, currentUiLocale())
                if (curText != null && prevText != null) {
                    AtharText(
                        text = stringResource(R.string.trends_savings_rate_comparison, curText, prevText),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeltaRow(
    label: String,
    value: Money?,
    positiveIsGood: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val theme = AtharTheme
    if (value == null) return
    val isPositive = value.isPositive()
    val isGood = (isPositive && positiveIsGood) || (!isPositive && !positiveIsGood)
    val color = when {
        value.isZero() -> theme.colors.muted
        isGood -> theme.colors.olive
        else -> theme.colors.ember
    }
    val sign = when {
        value.isZero() -> "·"
        value.isPositive() -> "+"
        else -> "−"
    }
    val absValue = if (value.isNegative()) -value else value
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.xs),
        modifier = if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.trends_open_evidence)) { onClick() }
                .heightIn(min = 48.dp)
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted, modifier = Modifier.weight(1f))
        AtharText(text = sign, style = theme.typography.caption, color = color)
        AtharNumber(money = absValue, color = color)
    }
}

@Composable
private fun MonthlyDashboardCard(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    val m = state.monthly
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
            AtharText(text = stringResource(R.string.trends_last_12_months), style = theme.typography.headline)
            MonthlyPanel(
                title = stringResource(R.string.trends_monthly_income),
                bars = m.income,
                color = theme.colors.olive,
                target = m.targetIncome,
                targetVariance = m.incomeTargetVariance,
                average = m.averageIncome,
                metric = TrendsMetric.INCOME,
                onMonthClick = { month -> onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthBar(month, TrendsMetric.INCOME))) },
            )
            MonthlyPanel(
                title = stringResource(R.string.trends_monthly_expense),
                bars = m.expense,
                color = theme.colors.ember,
                target = m.targetExpense,
                targetVariance = m.expenseTargetVariance,
                average = m.averageExpense,
                metric = TrendsMetric.EXPENSE,
                onMonthClick = { month -> onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthBar(month, TrendsMetric.EXPENSE))) },
            )
            MonthlyPanel(
                title = stringResource(R.string.trends_monthly_savings),
                bars = m.savings,
                color = theme.colors.ink,
                target = m.targetSavings,
                targetVariance = m.savingsTargetVariance,
                average = m.averageSavings,
                metric = TrendsMetric.SAVINGS,
                onMonthClick = { month -> onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthBar(month, TrendsMetric.SAVINGS))) },
            )
        }
    }
}

@Composable
private fun MonthlyPanel(
    title: String,
    bars: List<com.athar.core.designsystem.component.AtharMonthlyBar>,
    color: androidx.compose.ui.graphics.Color,
    target: Money?,
    /** Exact 12-month-average vs target gap, computed purely on the state. */
    targetVariance: Money?,
    average: Money,
    metric: TrendsMetric,
    onMonthClick: (YearMonth) -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(text = title, style = theme.typography.body)
        AtharMonthlyChartWithLines(
            bars = bars,
            barColor = color,
            targetLine = target,
            averageLine = average,
            onBarClick = onMonthClick,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                AtharText(text = stringResource(R.string.trends_monthly_average), style = theme.typography.caption, color = theme.colors.muted)
                AtharNumber(money = average)
            }
            target?.let {
                Column(horizontalAlignment = Alignment.End) {
                    AtharText(text = stringResource(R.string.trends_target), style = theme.typography.caption, color = theme.colors.muted)
                    AtharNumber(money = it, color = theme.colors.muted)
                    // The variance is explicitly the 12-month average against the target —
                    // never presented as a single-month actual.
                    targetVariance?.let { variance ->
                        val sign = if (variance.isPositive()) "+" else if (variance.isNegative()) "−" else "·"
                        val abs = if (variance.isNegative()) -variance else variance
                        val varColor = when {
                            variance.isZero() -> theme.colors.muted
                            metric == TrendsMetric.EXPENSE -> if (variance.isPositive()) theme.colors.ember else theme.colors.olive
                            else -> if (variance.isPositive()) theme.colors.olive else theme.colors.ember
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AtharText(
                                text = stringResource(R.string.trends_variance_average_vs_target, sign),
                                style = theme.typography.caption,
                                color = varColor,
                            )
                            AtharNumber(money = abs, color = varColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryComparisonTable(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.trends_category_comparison), style = theme.typography.headline)
            AtharText(
                text = comparisonLabel(state),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            state.categoryDeltas.take(20).forEach { row ->
                val rowLabel = bucketLabel(row.categoryId, row.labelAr, row.labelEn)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.trends_open_evidence),
                        ) { onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.CategoryComparison(row.categoryId))) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AtharText(text = rowLabel, style = theme.typography.body)
                        if (row.labelEn.isNotBlank() && rowLabel != row.labelEn) {
                            AtharText(text = row.labelEn, style = theme.typography.caption, color = theme.colors.muted)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        AtharNumber(money = row.currentTotal)
                        val delta = row.deltaAmount
                        val deltaColor = when {
                            delta.isPositive() -> theme.colors.ember
                            delta.isNegative() -> theme.colors.olive
                            else -> theme.colors.muted
                        }
                        val sign = if (delta.isPositive()) "+" else if (delta.isNegative()) "−" else "·"
                        val absDelta = if (delta.isNegative()) -delta else delta
                        // WM071: locale digits/grouping for the category delta percentage.
                        val pctText = row.deltaPercent
                            ?.let { TrendsPercentage071.token(it, currentUiLocale(), alwaysSigned = true) }
                            ?.let { " ($it)" }
                            .orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AtharText(text = sign, style = theme.typography.caption, color = deltaColor)
                            AtharNumber(money = absDelta, color = deltaColor)
                            if (pctText.isNotEmpty()) {
                                AtharText(text = pctText, style = theme.typography.caption, color = deltaColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomRangeBanner(start: LocalDate, end: LocalDate, onEdit: () -> Unit) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.surface)
            .clickable(onClick = onEdit, role = Role.Button)
            .padding(theme.spacing.s)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtharText(
            text = stringResource(R.string.trends_custom_range_label, start.toString(), end.minus(DatePeriod(days = 1)).toString()),
            style = theme.typography.caption,
            color = theme.colors.muted,
            modifier = Modifier.weight(1f),
        )
        AtharText(text = stringResource(R.string.trends_custom_range_edit), style = theme.typography.caption, color = theme.colors.ember)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangePickerSheet(
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    today: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var startText by remember { mutableStateOf(initialStart?.toString().orEmpty()) }
    var endText by remember { mutableStateOf(initialEnd?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    val invalidDate = stringResource(R.string.trends_error_invalid_date)
    val startBeforeEnd = stringResource(R.string.trends_error_start_before_end)
    val futureStart = stringResource(R.string.trends_error_future_start)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = stringResource(R.string.trends_custom_range_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.trends_custom_range_hint),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharTextField(value = startText, onValueChange = { startText = it; error = null },
                label = stringResource(R.string.trends_custom_range_from), modifier = Modifier.fillMaxWidth())
            AtharTextField(value = endText, onValueChange = { endText = it; error = null },
                label = stringResource(R.string.trends_custom_range_to), modifier = Modifier.fillMaxWidth())
            error?.let {
                AtharText(text = it, style = theme.typography.caption, color = theme.colors.ember)
            }
            SheetActionButton(
                label = stringResource(R.string.trends_action_apply),
                containerColor = theme.colors.ember,
                contentColor = theme.colors.parchment,
            ) {
                val s = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
                val e = runCatching { LocalDate.parse(endText.trim()) }.getOrNull()
                when {
                    s == null || e == null -> error = invalidDate
                    !(s < e) -> error = startBeforeEnd
                    today != null && s > today -> error = futureStart
                    else -> onConfirm(s, e)
                }
            }
        }
    }
}

@Composable
private fun SheetActionButton(
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(containerColor)
            .clickable(role = Role.Button, onClickLabel = label) { onClick() }
            .padding(theme.spacing.m)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = label, style = theme.typography.headline, color = contentColor)
    }
}

@Composable
private fun EvidenceContext(state: TrendsState, evidence: TrendsEvidenceState) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        // WM072: name the drilled bucket first — the immutable request key,
        // never the mutable filter selection. Unbucketed requests add no line.
        evidenceScopeKey(evidence.request)?.let { key ->
            val labels = evidence.categoryLabels[key]
            ContextLine(
                stringResource(
                    R.string.trends_evidence_context_scope,
                    bucketLabel(key, labels?.first.orEmpty(), labels?.second.orEmpty()),
                ),
            )
        }
        when (val request = evidence.request) {
            // The drilled month itself — never the unrelated top-level period.
            is TrendsEvidenceRequest.MonthBar -> ContextLine(
                stringResource(R.string.trends_evidence_context_period, periodLabel(Period.Month(request.month))),
            )
            is TrendsEvidenceRequest.MonthInBucket -> ContextLine(
                stringResource(R.string.trends_evidence_context_period, periodLabel(Period.Month(request.month))),
            )
            // Comparison drilldowns name BOTH exact windows instead of the top-level period.
            is TrendsEvidenceRequest.HeadlineComparison, is TrendsEvidenceRequest.CategoryComparison -> {
                ContextLine(stringResource(R.string.trends_evidence_context_period, periodLabel(evidence.comparison?.currentWindow)))
                ContextLine(stringResource(R.string.trends_evidence_context_period, periodLabel(evidence.comparison?.priorWindow)))
            }
            else -> {
                ContextLine(stringResource(R.string.trends_evidence_context_period, periodLabel(state.period)))
                ContextLine(stringResource(R.string.trends_evidence_context_comparison, comparisonLabel(state)))
            }
        }
        // The aggregates' currency is part of the drilldown context — always visible,
        // not only when an override is active.
        ContextLine(stringResource(R.string.trends_currency_line, state.reportingCurrency))
        ContextLine(stringResource(R.string.trends_transfers_excluded))
        if (state.mixedCurrency) {
            ContextLine(stringResource(R.string.trends_mixed_currency_note), emphasized = true)
        }
    }
}

@Composable
private fun ContextLine(text: String, emphasized: Boolean = false) {
    val theme = AtharTheme
    AtharText(
        text = text,
        style = theme.typography.caption,
        color = if (emphasized) theme.colors.dust else theme.colors.muted,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceSheet(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    val evidence = state.evidence ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onEvent(TrendsEvent.CloseEvidence) },
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                if (state.evidenceCanGoBack) {
                    // One intra-sheet drill back: returns to the previous evidence view
                    // (e.g. bucket under month∩bucket) without touching the query.
                    Box(
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(theme.spacing.s))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.trends_evidence_back),
                            ) { onEvent(TrendsEvent.BackEvidence) }
                            .padding(horizontal = theme.spacing.s, vertical = theme.spacing.s),
                        contentAlignment = Alignment.Center,
                    ) {
                        AtharText(
                            text = stringResource(R.string.trends_evidence_back),
                            style = theme.typography.caption,
                            color = theme.colors.ember,
                        )
                    }
                }
                AtharText(
                    text = metricTitle(evidence.metric),
                    style = theme.typography.title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(theme.spacing.s))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.trends_action_close),
                        ) { onEvent(TrendsEvent.CloseEvidence) }
                        .padding(horizontal = theme.spacing.s, vertical = theme.spacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    AtharText(
                        text = stringResource(R.string.trends_action_close),
                        style = theme.typography.caption,
                        color = theme.colors.ember,
                    )
                }
            }
            AtharNumber(money = evidence.total, landmark = true)
            EvidenceContext(state, evidence)

            evidence.monthlyBars?.let { bars ->
                AtharMonthlyChartWithLines(
                    bars = bars,
                    barColor = theme.colors.ember,
                    targetLine = evidence.monthlyTarget,
                    averageLine = null,
                    // A month tap drills into exactly that bucket ∩ month — Other and
                    // Uncategorized included, via the same frozen membership rule.
                    onBarClick = when (val request = evidence.request) {
                        is TrendsEvidenceRequest.Bucket -> { ym: YearMonth ->
                            onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthInBucket(ym, request.key)))
                        }
                        else -> null
                    },
                )
            }

            if (!state.filters.isDefault) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AtharText(
                        text = filtersSummary(state),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clickable(role = Role.Button) { onEvent(TrendsEvent.ClearFilters) }
                            .padding(theme.spacing.s)
                            .heightIn(min = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AtharText(
                            text = stringResource(R.string.trends_filters_clear),
                            style = theme.typography.caption,
                            color = theme.colors.ember,
                        )
                    }
                }
            }

            val comparison = evidence.comparison
            if (comparison == null && evidence.transactions.isEmpty()) {
                AtharEmptyState(
                    text = stringResource(R.string.trends_empty_evidence),
                    subtle = stringResource(R.string.trends_empty_evidence_subtle),
                )
            } else if (comparison == null) {
                AtharText(
                    text = stringResource(R.string.trends_evidence_count, evidence.transactions.size),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
                ) {
                    items(
                        items = evidence.transactions,
                        key = { it.id },
                    ) { tx ->
                        EvidenceRow(
                            transaction = tx,
                            evidence = evidence,
                            onEvent = onEvent,
                        )
                    }
                }
            } else {
                // Both exact delta populations, each under its own window label.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
                ) {
                    if (comparison.currentRows.isEmpty() && comparison.priorRows.isEmpty()) {
                        item {
                            AtharEmptyState(
                                text = stringResource(R.string.trends_empty_evidence),
                                subtle = stringResource(R.string.trends_empty_evidence_subtle),
                            )
                        }
                    } else {
                        item(key = "hdr-current") {
                            ComparisonSectionHeader(
                                title = stringResource(R.string.trends_evidence_comparison_current, periodLabel(comparison.currentWindow)),
                                total = comparison.currentTotal,
                                count = comparison.currentRows.size,
                            )
                        }
                        items(
                            items = comparison.currentRows,
                            key = { "cur-${it.id}" },
                        ) { tx ->
                            EvidenceRow(transaction = tx, evidence = evidence, onEvent = onEvent)
                        }
                        item(key = "hdr-prior") {
                            ComparisonSectionHeader(
                                title = stringResource(R.string.trends_evidence_comparison_prior, periodLabel(comparison.priorWindow)),
                                total = comparison.priorTotal,
                                count = comparison.priorRows.size,
                            )
                        }
                        items(
                            items = comparison.priorRows,
                            key = { "pri-${it.id}" },
                        ) { tx ->
                            EvidenceRow(transaction = tx, evidence = evidence, onEvent = onEvent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun filtersSummary(state: TrendsState): String {
    val filters = state.filters
    val parts = buildList {
        // Names, never raw ids; a deleted account id renders the calm unknown label.
        if (filters.accountIds.isNotEmpty()) {
            val unknownAccount = stringResource(R.string.trends_unknown_account)
            add(filters.accountIds.sorted().joinToString("، ") { id -> state.accountNames[id] ?: unknownAccount })
        }
        if (filters.sources.isNotEmpty()) add(filters.sources.map { sourceLabel(it) }.joinToString("، "))
        filters.type?.let { add(typeLabel(it)) }
        if (filters.categoryId != null) {
            val resolved = when (val id = filters.categoryId) {
                UNCATEGORIZED_KEY -> stringResource(R.string.trends_uncategorized)
                else -> state.filterCategoryOptions.firstOrNull { it.id == id }
                    ?.let { it.labelAr.ifBlank { it.labelEn } }?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.trends_unknown_category)
            }
            add(resolved)
        }
        filters.merchantQuery?.let { add("«${it}»") }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun EvidenceRow(
    transaction: Transaction,
    evidence: TrendsEvidenceState,
    onEvent: (TrendsEvent) -> Unit,
) {
    val theme = AtharTheme
    val categoryLabel = when (val id = transaction.categoryId) {
        null -> stringResource(R.string.trends_uncategorized)
        else -> evidence.categoryLabels[id]?.let { (ar, en) -> ar.ifBlank { en } }?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.trends_unknown_category)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.trends_open_details),
            ) { onEvent(TrendsEvent.OpenTransactionDetail(transaction.id)) },
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtharText(text = transaction.merchant, style = theme.typography.body, modifier = Modifier.weight(1f))
            // Original currency is preserved — AtharNumber renders each row's own code.
            AtharNumber(money = transaction.amount)
        }
        AtharText(
            text = stringResource(
                R.string.trends_evidence_row_meta,
                transaction.date.toString(),
                categoryLabel,
                sourceLabel(transaction.source),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
private fun ComparisonSectionHeader(
    title: String,
    total: Money,
    count: Int,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(text = title, style = theme.typography.caption, color = theme.colors.muted)
        AtharNumber(money = total)
        AtharText(
            text = stringResource(R.string.trends_evidence_count, count),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: Transaction,
    categoryLabels: Map<String, Pair<String, String>>,
    accountNames: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val categoryLabel = when (val id = transaction.categoryId) {
        null -> stringResource(R.string.trends_uncategorized)
        else -> categoryLabels[id]?.let { (ar, en) -> ar.ifBlank { en } }?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.trends_unknown_category)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = stringResource(R.string.trends_detail_title), style = theme.typography.title)
            DetailRow(stringResource(R.string.trends_detail_merchant), transaction.merchant)
            DetailRow(stringResource(R.string.trends_detail_date), transaction.date.toString())
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AtharText(
                        text = stringResource(R.string.trends_label_amount),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    AtharNumber(money = transaction.amount)
                }
            }
            DetailRow(stringResource(R.string.trends_filter_type), typeLabel(transaction.type))
            DetailRow(
                stringResource(R.string.trends_detail_account),
                accountNames[transaction.accountId] ?: stringResource(R.string.trends_unknown_account),
            )
            DetailRow(stringResource(R.string.trends_detail_source), sourceLabel(transaction.source))
            DetailRow(stringResource(R.string.trends_detail_category), categoryLabel)
            transaction.notes?.let { DetailRow(stringResource(R.string.trends_detail_notes), it) }
            SheetActionButton(
                label = stringResource(R.string.trends_action_close),
                containerColor = theme.colors.surface,
                contentColor = theme.colors.ink,
            ) { onDismiss() }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val theme = AtharTheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        AtharText(text = label, style = theme.typography.caption, color = theme.colors.muted, modifier = Modifier.weight(1f))
        AtharText(text = value, style = theme.typography.body)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(
    state: TrendsState,
    onEvent: (TrendsEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var merchantText by remember { mutableStateOf(state.filters.merchantQuery.orEmpty()) }
    var priorStartText by remember { mutableStateOf(state.customPrior?.start?.toString().orEmpty()) }
    var priorEndText by remember { mutableStateOf(state.customPrior?.endExclusive?.toString().orEmpty()) }
    var priorError by remember { mutableStateOf<String?>(null) }
    val invalidDate = stringResource(R.string.trends_error_invalid_date)
    val startBeforeEnd = stringResource(R.string.trends_error_start_before_end)
    val futureStart = stringResource(R.string.trends_error_future_start)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = stringResource(R.string.trends_filters_title), style = theme.typography.title)

            // Reporting currency
            FilterSection(title = stringResource(R.string.trends_filter_currency)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    val options = listOf(null, "SAR", "USD", "EUR")
                    options.forEach { code ->
                        val label = code ?: stringResource(R.string.trends_filter_currency_default)
                        FilterChip48(
                            label = label,
                            selected = state.currencyOverride == code,
                            multiSelect = false,
                        ) { onEvent(TrendsEvent.SetCurrencyOverride(code)) }
                    }
                }
            }

            // Accounts
            if (state.filterAccountIds.isNotEmpty()) {
                FilterSection(title = stringResource(R.string.trends_filter_accounts)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                        state.filterAccountIds.forEach { accountId ->
                            FilterChip48(
                                label = accountLabel(state, accountId),
                                selected = accountId in state.filters.accountIds,
                                multiSelect = true,
                            ) { onEvent(TrendsEvent.ToggleAccountFilter(accountId)) }
                        }
                    }
                }
            }

            // Sources
            FilterSection(title = stringResource(R.string.trends_filter_sources)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    IngestSource.entries.forEach { source ->
                        FilterChip48(
                            label = sourceLabel(source),
                            selected = source in state.filters.sources,
                            multiSelect = true,
                        ) { onEvent(TrendsEvent.ToggleSourceFilter(source)) }
                    }
                }
            }

            // Type
            FilterSection(title = stringResource(R.string.trends_filter_type)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    FilterChip48(
                        label = stringResource(R.string.trends_filter_all),
                        selected = state.filters.type == null,
                        multiSelect = false,
                    ) { onEvent(TrendsEvent.SetTypeFilter(null)) }
                    TxType.entries.forEach { type ->
                        FilterChip48(
                            label = typeLabel(type),
                            selected = state.filters.type == type,
                            multiSelect = false,
                        ) { onEvent(TrendsEvent.SetTypeFilter(type)) }
                    }
                }
            }

            // Category
            FilterSection(title = stringResource(R.string.trends_filter_category)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    FilterChip48(
                        label = stringResource(R.string.trends_filter_all),
                        selected = state.filters.categoryId == null,
                        multiSelect = false,
                    ) { onEvent(TrendsEvent.SetCategoryFilter(null)) }
                    state.filterCategoryOptions.forEach { option ->
                        val label = when (option.id) {
                            UNCATEGORIZED_KEY -> stringResource(R.string.trends_uncategorized)
                            else -> option.labelAr.ifBlank { option.labelEn }
                                .ifBlank { stringResource(R.string.trends_unknown_category) }
                        }
                        FilterChip48(
                            label = label,
                            selected = state.filters.categoryId == option.id,
                            multiSelect = false,
                        ) { onEvent(TrendsEvent.SetCategoryFilter(option.id)) }
                    }
                }
            }

            // Merchant
            FilterSection(title = stringResource(R.string.trends_filter_merchant)) {
                AtharTextField(
                    value = merchantText,
                    onValueChange = { merchantText = it },
                    label = stringResource(R.string.trends_filter_merchant_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                SheetActionButton(
                    label = stringResource(R.string.trends_filter_merchant_apply),
                    containerColor = theme.colors.surface,
                    contentColor = theme.colors.ink,
                ) { onEvent(TrendsEvent.SetMerchantQuery(merchantText)) }
            }

            // Comparison window (independent of the period selection)
            FilterSection(title = stringResource(R.string.trends_comparison_section)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    FilterChip48(
                        label = stringResource(R.string.trends_comparison_auto),
                        selected = state.customPrior == null,
                        multiSelect = false,
                    ) { onEvent(TrendsEvent.ClearComparisonCustom) }
                }
                AtharTextField(
                    value = priorStartText,
                    onValueChange = { priorStartText = it; priorError = null },
                    label = stringResource(R.string.trends_custom_range_from),
                    modifier = Modifier.fillMaxWidth(),
                )
                AtharTextField(
                    value = priorEndText,
                    onValueChange = { priorEndText = it; priorError = null },
                    label = stringResource(R.string.trends_custom_range_to),
                    modifier = Modifier.fillMaxWidth(),
                )
                priorError?.let {
                    AtharText(text = it, style = theme.typography.caption, color = theme.colors.ember)
                }
                SheetActionButton(
                    label = stringResource(R.string.trends_comparison_apply),
                    containerColor = theme.colors.surface,
                    contentColor = theme.colors.ink,
                ) {
                    val s = runCatching { LocalDate.parse(priorStartText.trim()) }.getOrNull()
                    val e = runCatching { LocalDate.parse(priorEndText.trim()) }.getOrNull()
                    when {
                        s == null || e == null -> priorError = invalidDate
                        !(s < e) -> priorError = startBeforeEnd
                        state.today != null && s > state.today -> priorError = futureStart
                        else -> onEvent(TrendsEvent.SetComparisonCustomRange(s, e))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                Box(modifier = Modifier.weight(1f)) {
                    SheetActionButton(
                        label = stringResource(R.string.trends_filters_clear),
                        containerColor = theme.colors.surface,
                        contentColor = theme.colors.crimson,
                    ) { onEvent(TrendsEvent.ClearFilters) }
                }
                Box(modifier = Modifier.weight(1f)) {
                    SheetActionButton(
                        label = stringResource(R.string.trends_action_apply),
                        containerColor = theme.colors.ember,
                        contentColor = theme.colors.parchment,
                    ) { onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(text = title, style = theme.typography.caption, color = theme.colors.muted)
        content()
    }
}

@Composable
private fun FilterChip48(
    label: String,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(theme.spacing.m))
            .background(if (selected) theme.colors.ember.copy(alpha = 0.12f) else theme.colors.surface)
            .clickable(role = if (multiSelect) Role.Checkbox else Role.RadioButton, onClickLabel = label) { onClick() }
            .semantics { this.selected = selected }
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = label,
            style = theme.typography.caption,
            color = if (selected) theme.colors.ember else theme.colors.ink,
        )
    }
}
