package com.athar.feature.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.MoneyTextFormat
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme

import java.util.Locale

// Three compact money values (17sp tabular) plus gaps share one line from about
// 360dp of content width; below that — or once font scaling widens the glyphs —
// the summary pills stack into full-width rows so amounts never wrap or clip.
private val SummaryPillsRowMinWidth: Dp = 360.dp

/** Pure layout decision, unit-tested: stack the summary pills below this width × font scale. */
internal fun shouldStackSummaryPills(availableWidth: Dp, fontScale: Float): Boolean =
    availableWidth < SummaryPillsRowMinWidth * fontScale

/**
 * Money formatted so the amount can never fragment: the sign+digits run is wrapped
 * in an LTR isolate (LRI…PDI) and joined to the currency symbol with a no-break
 * space. At narrow widths or in RTL paragraphs only the symbol may ever wrap to
 * its own line — the number itself can neither split mid-digit nor reorder its
 * minus sign. Visible glyphs match AtharNumber exactly (same grouping and observed
 * significant fraction digits, symbol from the Money's own currency code — never a
 * conversion, never a fabricated figure).
 */
internal fun formatStableMoney(money: Money, locale: Locale): String =
    MoneyTextFormat.format(money.amount, money.currency, locale)

/**
 * Money text for constrained containers (summary pills). Full-width rows keep
 * using [AtharNumber] so the incumbent rendering stays untouched there.
 */
@Composable
internal fun TodayMoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = AtharTheme.typography.money,
    color: Color = AtharTheme.colors.ink,
) {
    AtharText(
        text = formatStableMoney(money, currentUiLocale()),
        modifier = modifier,
        style = style,
        color = color,
    )
}
