package com.athar.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.AtharNumerals
import com.athar.core.designsystem.display.LocalHijriEnabled
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.common.time.HijriDate
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Today's money header, preserved from the incumbent screen: landmark is today's
 * net, the month net sits beneath it, then the income/expense/net-worth pills.
 * The pills are the one adaptive surface — three-up when the width × font scale
 * allows, stacked full-width rows otherwise — so a large amount always stays on
 * one unbroken line instead of wrapping into unreadable fragments.
 */
@Composable
internal fun Header(state: TodayState, onOpenHistory: () -> Unit = {}) {
    val theme = AtharTheme
    Column(
        verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        horizontalAlignment = Alignment.Start,
    ) {
        AtharText(
            text = stringResource(R.string.today_header_title),
            style = theme.typography.overline,
            color = theme.colors.muted,
        )
        AtharNumber(money = todayLandmarkMoney(state), landmark = true)
        val landmarkCaption = landmarkCaption(state)
        AtharText(
            text = landmarkCaption,
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        if (state.today.isNotEmpty()) MonthNetRow(state)
        SummaryPills(
            totalIncome = state.totalIncome,
            totalExpense = state.totalExpense,
            netWorth = state.netWorth,
            netWorthIsMixed = state.netWorthIsMixed,
            internalTransfers = state.internalTransfers,
        )
        SavingsRateCaption(state)
        if (state.netWorthIsMixed) {
            AtharText(
                text = stringResource(R.string.today_caption_net_worth_mixed),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        if (!state.netWorthIsMixed) {
            AtharText(
                text = stringResource(R.string.today_caption_recorded_balance),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        state.declaredPosition?.let { DeclaredPositionSummary(it) }
        state.goalNudge?.let { GoalsNudge(it) }
        if (shouldShowHeaderHistory(state)) {
            ReviewHistoryEntryRow(pendingCount = 0, dismissedCount = 0, onClick = onOpenHistory)
        }
    }
}

@Composable
private fun landmarkCaption(state: TodayState): String {
    val hijriOn = LocalHijriEnabled.current
    val locale = currentUiLocale()
    if (state.today.isEmpty()) {
        val gregorian = AtharNumerals.localize("${state.month.year}/${state.month.monthValue}", locale)
        return if (hijriOn) stringResource(
            R.string.today_caption_net_flow_month_hijri, gregorian,
            AtharNumerals.localize(HijriDate.formatYearMonth(state.month.year, state.month.monthValue), locale),
        ) else stringResource(R.string.today_caption_net_flow_month, gregorian)
    }
    return if (hijriOn) {
        stringResource(
            R.string.today_caption_landmark_today_hijri,
            AtharNumerals.localize(HijriDate.formatYearMonth(state.month.year, state.month.monthValue), currentUiLocale()),
        )
    } else {
        stringResource(R.string.today_caption_landmark_today)
    }
}

@Composable
private fun MonthNetRow(state: TodayState) {
    val theme = AtharTheme
    val gregorian = AtharNumerals.localize("${state.month.year}/${state.month.monthValue}", currentUiLocale())
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        AtharText(
            text = stringResource(R.string.today_caption_net_flow_month, gregorian),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        AtharNumber(money = state.netFlow, color = theme.colors.muted)
    }
}

@Composable
internal fun SummaryPills(
    totalIncome: Money,
    totalExpense: Money,
    netWorth: Money,
    netWorthIsMixed: Boolean = false,
    internalTransfers: Money = Money.zero(),
) {
    val theme = AtharTheme
    val incomeLabel = stringResource(R.string.today_pill_income)
    val expenseLabel = stringResource(R.string.today_pill_expense)
    val balanceLabel = stringResource(R.string.today_pill_net_worth)
    val transferLabel = stringResource(R.string.today_pill_internal_transfers)
    val pills = buildList {
        add(PillSpec(incomeLabel, totalIncome, theme.colors.olive))
        add(PillSpec(expenseLabel, totalExpense, theme.colors.ember))
        if (!internalTransfers.isZero()) {
            add(PillSpec(transferLabel, internalTransfers, theme.colors.muted))
        }
        if (!netWorthIsMixed) add(PillSpec(balanceLabel, netWorth, theme.colors.ink))
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        if (shouldStackSummaryPills(availableWidth = maxWidth, fontScale = fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                pills.forEach { pill -> SummaryPill(pill = pill, stacked = true) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                pills.forEach { pill ->
                    SummaryPill(pill = pill, stacked = false, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private data class PillSpec(val label: String, val money: Money, val accent: Color)

@Composable
private fun SummaryPill(
    pill: PillSpec,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    val surface: Modifier = modifier
        .clip(RoundedCornerShape(theme.spacing.s))
        .background(theme.colors.surface)
        .padding(theme.spacing.s)
    if (stacked) {
        // Full-width row: label and amount share one line — the amount can never wrap.
        Row(
            modifier = surface.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(
                text = pill.label,
                style = theme.typography.caption,
                color = theme.colors.muted,
                modifier = Modifier.weight(1f),
            )
            TodayMoneyText(money = pill.money, color = pill.accent)
        }
    } else {
        Column(
            modifier = surface,
            verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
        ) {
            AtharText(text = pill.label, style = theme.typography.caption, color = theme.colors.muted)
            TodayMoneyText(money = pill.money, color = pill.accent)
        }
    }
}

@Composable
private fun SavingsRateCaption(state: TodayState) {
    val theme = AtharTheme
    val savingsRate = state.savingsRate
    if (savingsRate != null) {
        AtharText(
            text = stringResource(
                R.string.today_caption_savings_rate,
                AtharNumerals.localize("%.0f".format(savingsRate), currentUiLocale()),
            ),
            style = theme.typography.caption,
            color = if (savingsRate >= 0) theme.colors.olive else theme.colors.ember,
        )
    }
}

@Composable
internal fun TodaySectionHeader(net: Money, showNet: Boolean) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.today_section_today),
            style = theme.typography.overline,
            color = theme.colors.muted,
        )
        if (showNet) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.xs),
            ) {
                AtharText(
                    text = stringResource(R.string.today_section_today_net_prefix),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                AtharNumber(
                    money = net,
                    color = if (net.amount.signum() >= 0) theme.colors.olive else theme.colors.ember,
                )
            }
        }
    }
}

@Composable
internal fun GoalsNudge(nudge: TodayGoalNudge) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
            AtharText(
                text = stringResource(R.string.today_goals_nudge_title),
                style = theme.typography.overline,
                color = theme.colors.muted,
            )
            AtharText(
                text = savingsNudgeText(nudge),
                style = theme.typography.caption,
                color = goalLineColor(
                    value = nudge.savingsRatePercent,
                    progress = nudge.savingsRateProgress,
                ),
            )
            AtharText(
                text = stringResource(R.string.today_goals_emergency_needs_balance),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun goalLineColor(value: BigDecimal?, progress: Float): Color {
    val theme = AtharTheme
    return when {
        value == null -> theme.colors.muted
        progress >= 1f -> theme.colors.olive
        else -> theme.colors.ember
    }
}

@Composable
private fun savingsNudgeText(nudge: TodayGoalNudge): String {
    val actual = nudge.savingsRatePercent ?: return stringResource(R.string.today_goals_savings_waiting)
    val actualLabel = actual.format(scale = 0)
    return if (nudge.savingsRateProgress >= 1f) {
        stringResource(R.string.today_goals_savings_on_track, actualLabel, nudge.savingsRateTargetPercent)
    } else {
        stringResource(R.string.today_goals_savings_below, actualLabel, nudge.savingsRateTargetPercent)
    }
}

private fun BigDecimal.format(scale: Int): String =
    setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
