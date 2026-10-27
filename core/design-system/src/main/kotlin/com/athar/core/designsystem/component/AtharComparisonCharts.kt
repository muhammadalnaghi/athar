package com.athar.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.athar.core.common.money.Money
import com.athar.core.designsystem.R
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import java.math.BigDecimal
import java.util.Locale

/**
 * Comparison & proportion charts split out of [AtharMonthlyChartWithLines] (028-P-CHARTS,
 * ≤300-line file rule). Same package and identical public symbols, so every existing
 * Trends call site stays import-compatible.
 */

/**
 * Side-by-side paired bars for period-vs-prior comparison (TMOAP Historical Comparison).
 * Six bars total: 2 income / 2 expense / 2 savings, current bar tinted + prior in muted.
 * The scale is signed BigDecimal: a negative savings bar hangs below the zero baseline
 * and a zero value renders no bar at all — no clamping, no fake minimum.
 */
/** Which of the six comparison bars a tap landed on (metric + side). */
enum class AtharComparisonSlot {
    INCOME_CURRENT, INCOME_PREVIOUS,
    EXPENSE_CURRENT, EXPENSE_PREVIOUS,
    SAVINGS_CURRENT, SAVINGS_PREVIOUS,
}

@Composable
fun AtharComparisonBars(
    currentIncome: Money,
    previousIncome: Money,
    currentExpense: Money,
    previousExpense: Money,
    currentSavings: Money,
    previousSavings: Money,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    /** Optional tap per bar (zero bars included); null keeps the chart read-only. */
    onBarClick: ((AtharComparisonSlot) -> Unit)? = null,
) {
    val theme = AtharTheme
    val range = AtharMonthlyChartGeometry.rangeOf(
        listOf(
            currentIncome, previousIncome,
            currentExpense, previousExpense,
            currentSavings, previousSavings,
        ).map { it.amount },
    )
    val locale = currentUiLocale()
    val currentWord = stringResource(R.string.chart_pair_current)
    val previousWord = stringResource(R.string.chart_pair_previous)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Six independently tappable cells must not shrink below 48dp on narrow
        // screens. Scroll the chart when its real minimum width exceeds the viewport.
        val minimumWidth = (48.dp * 2 + theme.spacing.xs) * 3 + theme.spacing.m * 2
        val chartWidth = maxOf(maxWidth, minimumWidth)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .width(chartWidth)
                .height(height),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            PairBar(label = stringResource(R.string.chart_pair_income), current = currentIncome, previous = previousIncome,
                currentColor = theme.colors.olive, previousColor = theme.colors.muted, range = range,
                locale = locale, currentWord = currentWord, previousWord = previousWord,
                onCurrentClick = onBarClick?.let { cb -> { cb(AtharComparisonSlot.INCOME_CURRENT) } },
                onPreviousClick = onBarClick?.let { cb -> { cb(AtharComparisonSlot.INCOME_PREVIOUS) } },
                modifier = Modifier.weight(1f))
            PairBar(label = stringResource(R.string.chart_pair_expense), current = currentExpense, previous = previousExpense,
                currentColor = theme.colors.ember, previousColor = theme.colors.muted, range = range,
                locale = locale, currentWord = currentWord, previousWord = previousWord,
                onCurrentClick = onBarClick?.let { cb -> { cb(AtharComparisonSlot.EXPENSE_CURRENT) } },
                onPreviousClick = onBarClick?.let { cb -> { cb(AtharComparisonSlot.EXPENSE_PREVIOUS) } },
                modifier = Modifier.weight(1f))
            PairBar(label = stringResource(R.string.chart_pair_savings), current = currentSavings, previous = previousSavings,
                currentColor = theme.colors.ink, previousColor = theme.colors.muted, range = range,
                locale = locale, currentWord = currentWord, previousWord = previousWord,
                onCurrentClick = onBarClick?.let { cb -> { cb(AtharComparisonSlot.SAVINGS_CURRENT) } },
                onPreviousClick = onBarClick?.let { cb -> { cb(AtharComparisonSlot.SAVINGS_PREVIOUS) } },
                modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PairBar(
    label: String,
    current: Money,
    previous: Money,
    currentColor: Color,
    previousColor: Color,
    range: AtharMonthlyChartGeometry.SignedRange,
    locale: Locale,
    currentWord: String,
    previousWord: String,
    onCurrentClick: (() -> Unit)? = null,
    onPreviousClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.xs),
        ) {
            SignedBar(
                geometry = AtharMonthlyChartGeometry.barGeometry(current.amount, range),
                color = currentColor,
                contentDescription = "$label · $currentWord " +
                    AtharMonthlyChartGeometry.amountLabel(current.amount, current.currency, locale),
                onClick = onCurrentClick,
                modifier = Modifier.weight(1f),
            )
            SignedBar(
                geometry = AtharMonthlyChartGeometry.barGeometry(previous.amount, range),
                color = previousColor,
                contentDescription = "$label · $previousWord " +
                    AtharMonthlyChartGeometry.amountLabel(previous.amount, previous.currency, locale),
                onClick = onPreviousClick,
                modifier = Modifier.weight(1f),
            )
        }
        AtharText(
            text = label,
            style = theme.typography.caption,
            color = theme.colors.muted,
            modifier = Modifier.padding(top = theme.spacing.xs),
        )
    }
}

/**
 * Signed bar drawn with constraint fractions (top spacer → bar): the zero baseline is
 * implicit in [geometry], zero renders nothing, negatives start below the baseline.
 * Labeled for accessibility — child boxes are never unlabeled.
 */
@Composable
private fun SignedBar(
    geometry: AtharMonthlyChartGeometry.BarGeometry,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // The whole bar cell is the touch target (a full pair-row slot, ≥48dp wide and
    // 180dp tall), so zero-value bars stay tappable — no dead zones.
    val interactive = if (onClick != null) {
        modifier.clickable(role = Role.Button, onClickLabel = contentDescription) { onClick() }
    } else {
        modifier
    }
    BoxWithConstraints(
        modifier = interactive
            .fillMaxHeight()
            .semantics { this.contentDescription = contentDescription },
    ) {
        if (geometry.isVisible) {
            // Both fractions refer to the full plot, exactly as in the monthly
            // chart. A Column would shrink the second fraction to the remainder
            // after its spacer and halve a symmetric negative bar's height.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = maxHeight * geometry.topFraction)
                    .height(maxHeight * geometry.heightFraction)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * Horizontal stacked bar showing the percentage split of categories — the pie-chart
 * replacement (Master Brief §2.4 explicitly bans pies).
 *
 * Each entry contributes a colored segment proportional to its value. Click is only
 * wired when the caller provides onSegmentClick. Caller supplies the colors. Totals
 * and shares are BigDecimal: mixed currencies or a non-positive total render nothing
 * explicitly, and zero/negative segments never fake a positive share.
 */
data class AtharSplitSegment(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val value: Money,
    val color: Color,
)

@Composable
fun AtharProportionBar(
    segments: List<AtharSplitSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    onSegmentClick: ((String) -> Unit)? = null,
) {
    val theme = AtharTheme
    val locale = currentUiLocale()
    val currencies = segments.map { it.value.currency }.distinct()
    if (currencies.size != 1) return
    val total = segments.fold(BigDecimal.ZERO) { acc, seg -> acc.add(seg.value.amount) }
    if (total.signum() <= 0) return
    val positive = segments.filter { it.value.amount.signum() > 0 }
    if (positive.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2)),
        ) {
            positive.forEach { seg ->
                val frac = AtharMonthlyChartGeometry.shareFraction(seg.value.amount, total)
                Box(
                    modifier = Modifier
                        .weight(frac)
                        .fillMaxHeight()
                        .background(seg.color),
                )
            }
        }
        // Legend below: each segment with a colored swatch and its % share.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = theme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
        ) {
            positive.forEach { seg ->
                val pct = AtharMonthlyChartGeometry.sharePercent(seg.value.amount, total)
                val legendModifier = if (onSegmentClick != null) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onSegmentClick(seg.key) }
                } else {
                    Modifier.fillMaxWidth()
                }
                Row(
                    modifier = legendModifier,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = theme.spacing.xs)
                            .clip(RoundedCornerShape(2.dp))
                            .background(seg.color)
                            .height(10.dp),
                    ) {
                        Box(modifier = Modifier.height(10.dp).background(seg.color)) {
                            Box(modifier = Modifier.padding(horizontal = 6.dp)) {}
                        }
                    }
                    val isArabic = locale.language == "ar"
                    val segLabel = if (isArabic) seg.labelAr else seg.labelEn
                    val pctChar = if (isArabic) "٪" else "%"
                    AtharText(
                        text = "$segLabel · ${"%.1f".format(locale, pct)}$pctChar",
                        style = theme.typography.caption,
                        color = theme.colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    AtharNumber(money = seg.value, color = theme.colors.muted)
                }
            }
        }
    }
}
