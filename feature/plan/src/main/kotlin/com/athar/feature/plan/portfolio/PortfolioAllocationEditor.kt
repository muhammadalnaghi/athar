package com.athar.feature.plan.portfolio

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharAmountField
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.MoneyTextFormat
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.calc.portfolio.AllocationReport
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.feature.plan.InvestmentEditorSheet
import com.athar.feature.plan.InvestmentPrimaryButton
import com.athar.feature.plan.InvestmentsEvent
import com.athar.feature.plan.PortfolioOpen
import com.athar.feature.plan.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.UUID

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PortfolioAllocationControls(open: PortfolioOpen, write: PortfolioAllocationWrite?, onEvent: (InvestmentsEvent) -> Unit) {
    var editing by remember(open.planId) { mutableStateOf(false) }
    val requester = remember(open.planId) { BringIntoViewRequester() }
    var placed by remember(open.planId) { mutableStateOf(false) }
    LaunchedEffect(write?.token, write?.saved, placed) {
        if (placed && write?.saved == true && write.planId == open.planId) requester.bringIntoView()
    }
    if (open.entryPreview == null) PortfolioTextAction(stringResource(R.string.allocation037_edit)) { editing = true }
    PortfolioAllocationResults(open.workbench.allocation, open.workbench.document.allocationTargets,
        Modifier.bringIntoViewRequester(requester).onGloballyPositioned { placed = true })
    if (editing) {
        val captured = remember { open }
        AllocationSheet(captured, write, onEvent) { editing = false }
    }
}

@Composable
private fun AllocationSheet(open: PortfolioOpen, write: PortfolioAllocationWrite?, onEvent: (InvestmentsEvent) -> Unit, dismiss: () -> Unit) {
    val token = remember { UUID.randomUUID().toString() }
    var draft by remember { mutableStateOf(allocationDraftOf(open.workbench.document.allocationTargets)) }
    val ownWrite = write?.takeIf { it.token == token }
    val busy = ownWrite?.saving == true
    LaunchedEffect(ownWrite?.saved) { if (ownWrite?.saved == true) dismiss() }
    InvestmentEditorSheet(stringResource(R.string.allocation037_edit), onDismiss = { if (!busy) dismiss() }) {
        AtharText(stringResource(R.string.allocation037_hint), style = AtharTheme.typography.caption, color = AtharTheme.colors.muted)
        val retainedRanges = if (draft.clearAll) emptyList() else draft.original.filter { it.kind == "RANGE" }
        retainedRanges.forEach {
            AtharText(stringResource(R.string.allocation037_range_retained, assetLabel(it.assetClass)), style = AtharTheme.typography.caption)
        }
        AssetClass.entries.filter { asset -> retainedRanges.none { it.assetClass == asset.name } }.forEach { asset ->
            AtharAmountField(
                value = draft.percentages[asset.name].orEmpty(),
                label = stringResource(R.string.allocation037_percent_label, assetLabel(asset.name)),
                onValueChange = { text -> if (!busy) draft = draft.copy(percentages = draft.percentages + (asset.name to text), clearAll = false) },
            )
        }
        ownWrite?.problem?.let { problem ->
            val text = when (problem) {
                AllocationWriteProblem.TOTAL -> R.string.allocation037_total_error
                AllocationWriteProblem.EMPTY -> R.string.allocation037_empty_error
                AllocationWriteProblem.CONFLICT -> R.string.allocation037_conflict
                AllocationWriteProblem.STORAGE -> R.string.allocation037_storage
                AllocationWriteProblem.UNSUPPORTED -> R.string.allocation037_unsupported
                AllocationWriteProblem.INVALID -> R.string.allocation037_invalid
            }
            AtharText(stringResource(text), style = AtharTheme.typography.caption, color = AtharTheme.colors.muted)
        }
        if (busy) {
            AtharText(stringResource(R.string.allocation037_saving), style = AtharTheme.typography.body)
        } else {
            InvestmentPrimaryButton(stringResource(R.string.allocation037_save)) {
                onEvent(InvestmentsEvent.SavePortfolioAllocation(open.planId, open.revision, token, draft))
            }
            PortfolioTextAction(stringResource(R.string.allocation037_clear)) {
                draft = draft.copy(percentages = draft.percentages.mapValues { "" }, clearAll = true)
            }
            PortfolioTextAction(stringResource(R.string.allocation037_cancel), dismiss)
        }
    }
}

@Composable
private fun PortfolioAllocationResults(report: AllocationReport?, targets: List<TargetInput>, modifier: Modifier) {
    if (report == null) return
    val theme = AtharTheme
    val locale = currentUiLocale()
    fun percent(fraction: BigDecimal): String = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 4
    }.format(fraction.movePointRight(2))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.allocation037_result), style = theme.typography.body)
        if (report.isPartial) AtharText(stringResource(R.string.allocation037_partial), style = theme.typography.caption, color = theme.colors.muted)
        for ((asset, value) in report.classValues) {
            val target = targets.firstOrNull { it.assetClass == asset.name }
            if (value.amount.signum() == 0 && target == null) continue
            AtharText(assetLabel(asset.name) + ": " + MoneyTextFormat.format(value.amount, value.currency, locale), style = theme.typography.body)
            val fraction = report.fractions[asset] ?: continue
            val point = target?.takeIf { it.kind == "POINT" }?.fraction?.toBigDecimalOrNull()
            AtharText(
                if (point == null) stringResource(R.string.allocation037_actual, percent(fraction)) else
                    stringResource(R.string.allocation037_actual_target, percent(fraction), percent(point)),
                style = theme.typography.caption, color = theme.colors.muted,
            )
            if (point != null) AtharText(stringResource(R.string.allocation037_gap, percent(fraction - point)), style = theme.typography.caption)
        }
        report.unknownClassValue?.let { value ->
            AtharText(stringResource(R.string.allocation037_unknown, MoneyTextFormat.format(value.amount, value.currency, locale)), style = theme.typography.caption)
        }
    }
}

@Composable
private fun assetLabel(asset: String): String = stringResource(when (asset) {
    "EQUITY" -> R.string.allocation037_equity
    "SUKUK" -> R.string.allocation037_sukuk
    "CASH" -> R.string.allocation037_cash
    else -> R.string.allocation037_other
})
