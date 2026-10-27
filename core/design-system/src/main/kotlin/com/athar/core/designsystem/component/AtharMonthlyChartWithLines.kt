package com.athar.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.athar.core.designsystem.R
import com.athar.core.designsystem.display.currentUiLocale
import java.math.BigDecimal
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.athar.core.common.money.Money
import com.athar.core.designsystem.theme.AtharTheme
import java.time.YearMonth

/**
 * Monthly bars with optional horizontal overlays: a budget-target line (solid) and a
 * computed average line (dashed). Mirrors TMOAP's "Income / Expenses / Savings by Month"
 * dashboard charts where each panel shows monthly bars + budget target + average.
 *
 * Bar color is configurable so the same component renders the olive income chart, the
 * ember expense chart, and the ink savings chart with a single API.
 */
@Composable
fun AtharMonthlyChartWithLines(
    bars: List<AtharMonthlyBar>,
    modifier: Modifier = Modifier,
    barColor: Color,
    targetLine: Money? = null,
    averageLine: Money? = null,
    maxHeight: Dp = 160.dp,
    /** Optional month tap — the whole bar cell is the (≥48dp) touch target. */
    onBarClick: ((YearMonth) -> Unit)? = null,
    /** Optional externally-owned selection; without it selection is internal + saveable. */
    selectedMonth: YearMonth? = null,
) {
    val theme = AtharTheme
    if (bars.isEmpty()) return
    // Money stays BigDecimal until the final 0f..1f projection inside the geometry helper.
    val values = bars.map { it.amount.amount } +
        listOfNotNull(targetLine, averageLine).map { it.amount }
    val range = AtharMonthlyChartGeometry.rangeOf(values)
    val locale = currentUiLocale()
    var internalSelected by rememberSaveable { mutableStateOf<Long?>(null) }
    val effectiveSelected: YearMonth? = selectedMonth
        ?: internalSelected?.let(AtharMonthlyChartGeometry::decodeEpochMonth)

    val mutedColor = theme.colors.muted
    val inkColor = theme.colors.ink
    val dividerColor = theme.colors.divider
    // Series-level description for TalkBack: span, count, optional target/average
    // lines, and the selected point's real recorded identity (exact month + exact
    // amount — never an interpolation or an invented value).
    val join = stringResource(R.string.chart_series_join)
    val selectedBar = effectiveSelected?.let { selected ->
        bars.firstOrNull { it.month == selected }
    }
    val seriesDescription = buildString {
        append(
            stringResource(
                R.string.chart_series_a11y,
                AtharMonthlyChartGeometry.monthLabel(bars.first().month, locale),
                AtharMonthlyChartGeometry.monthLabel(bars.last().month, locale),
                bars.size,
            ),
        )
        targetLine?.let {
            append(join)
            append(
                stringResource(
                    R.string.chart_series_target_a11y,
                    AtharMonthlyChartGeometry.amountLabel(it.amount, it.currency, locale),
                ),
            )
        }
        averageLine?.let {
            append(join)
            append(
                stringResource(
                    R.string.chart_series_average_a11y,
                    AtharMonthlyChartGeometry.amountLabel(it.amount, it.currency, locale),
                ),
            )
        }
        selectedBar?.let {
            append(join)
            append(
                stringResource(
                    R.string.chart_series_selected_a11y,
                    AtharMonthlyChartGeometry.monthLabel(it.month, locale),
                    AtharMonthlyChartGeometry.amountLabel(
                        it.amount.amount,
                        it.amount.currency,
                        locale,
                    ),
                ),
            )
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = seriesDescription },
    ) {
        // Plot and month labels share one scroll container so they can never drift
        // apart; RTL keeps the same cell order and the same reading chronology.
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                Box(modifier = Modifier.height(maxHeight)) {
                    Row(modifier = Modifier.height(maxHeight)) {
                        bars.forEach { bar ->
                            val geometry = AtharMonthlyChartGeometry.barGeometry(
                                value = bar.amount.amount,
                                range = range,
                            )
                            val monthText = AtharMonthlyChartGeometry.monthLabel(bar.month, locale)
                            val amountText = AtharMonthlyChartGeometry.amountLabel(
                                bar.amount.amount,
                                bar.amount.currency,
                                locale,
                            )
                            val pointDescription = stringResource(
                                R.string.chart_point_a11y,
                                monthText,
                                amountText,
                            )
                            val cellModifier = Modifier
                                .width(48.dp)
                                .fillMaxHeight()
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(
                                        R.string.chart_bar_a11y,
                                        monthText,
                                        amountText,
                                    ),
                                ) {
                                    internalSelected = AtharMonthlyChartGeometry
                                        .encodeEpochMonth(bar.month)
                                    onBarClick?.invoke(bar.month)
                                }
                                .semantics {
                                    // Accessible name: localized month/date + exact money,
                                    // plus the platform-localized selected state below.
                                    contentDescription = pointDescription
                                    this.selected = effectiveSelected == bar.month
                                }
                            Box(modifier = cellModifier) {
                                if (geometry.isVisible) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .offset(y = maxHeight * geometry.topFraction)
                                            .height(maxHeight * geometry.heightFraction)
                                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                            .background(barColor),
                                    )
                                }
                            }
                        }
                    }
                    // Zero baseline + target/average lines span the whole scrolled content
                    // and move with it; the Canvas takes no pointer input, so taps reach
                    // the bars. Hairlines stay 1px — never 2px heavy rules.
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val hairline = 1.dp.toPx()
                        // Explicit zero baseline for the signed chart: zero renders as a
                        // hairline axis, never as a fake bar.
                        val zeroY = size.height * range.fractionOf(BigDecimal.ZERO)
                        drawLine(
                            color = dividerColor,
                            start = Offset(0f, zeroY),
                            end = Offset(size.width, zeroY),
                            strokeWidth = hairline,
                        )
                        targetLine?.let {
                            val y = size.height * range.fractionOf(it.amount)
                            drawLine(
                                color = mutedColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = hairline,
                            )
                        }
                        averageLine?.let {
                            val y = size.height * range.fractionOf(it.amount)
                            drawLine(
                                color = inkColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = hairline,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            )
                        }
                    }
                }
                Row {
                    bars.forEach { bar ->
                        AtharText(
                            text = bar.month.monthValue.toString().padStart(2, '0'),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                            modifier = Modifier.width(48.dp),
                        )
                    }
                }
            }
        }
        // Legend: dashed line = average, solid hairline = budget target.
        if (averageLine != null || targetLine != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = theme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                if (averageLine != null) {
                    AtharText(
                        text = stringResource(R.string.chart_legend_average),
                        style = theme.typography.caption,
                        color = theme.colors.ink,
                    )
                }
                if (targetLine != null) {
                    AtharText(
                        text = stringResource(R.string.chart_legend_target),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
            }
        }
    }
}


