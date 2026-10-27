package com.athar.feature.plan

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme

/**
 * While [GoalsState.isLoading], the zero-initialized amounts must not read as settled
 * truth; the cards hide their figures and this caption explains the wait.
 */
@Composable
internal fun LoadingFiguresNote() {
    val theme = AtharTheme
    AtharText(
        text = stringResource(R.string.plan_goals_totals_loading),
        style = theme.typography.caption,
        color = theme.colors.muted,
    )
}

/**
 * No-FX scope disclosure for the goal cards above: totals are literal in the display
 * currency only, and any foreign-currency transactions or balances are named as
 * excluded as-is. It deliberately speaks about "the amounts above" — never about the
 * user's whole financial position or whole cash flow, which a single-currency subset
 * does not represent.
 */
@Composable
internal fun CurrencyScopeNote(currency: String, excluded: Set<String>) {
    val theme = AtharTheme
    AtharText(
        text = if (excluded.isEmpty()) {
            stringResource(R.string.plan_goals_totals_currency_scope, currency)
        } else {
            stringResource(R.string.plan_goals_totals_excluded, currency, excluded.sorted().joinToString(", "))
        },
        style = theme.typography.caption,
        color = theme.colors.muted,
    )
}

