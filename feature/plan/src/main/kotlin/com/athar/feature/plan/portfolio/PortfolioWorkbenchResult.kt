package com.athar.feature.plan.portfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.feature.plan.cashforecast.FigureRow
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.calc.portfolio.AllocationReport
import com.athar.core.domain.calc.portfolio.GoalStatus
import com.athar.core.domain.calc.portfolio.MoneyGain
import com.athar.core.domain.calc.portfolio.PortfolioAccounting
import com.athar.core.domain.calc.portfolio.PortfolioValuation
import com.athar.core.domain.calc.portfolio.RangePosition
import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.core.domain.calc.portfolio.TwrResult
import com.athar.core.domain.calc.portfolio.XirrResult
import com.athar.core.domain.model.portfolio.PriceStatus
import com.athar.feature.plan.InvestmentDeleteConfirm
import com.athar.feature.plan.InvestmentWriteProblem
import com.athar.feature.plan.InvestmentsEvent
import com.athar.feature.plan.InvestmentsEvent.AddPortfolioEntry
import com.athar.feature.plan.InvestmentsEvent.AddPortfolioQuote
import com.athar.feature.plan.InvestmentsEvent.AddPortfolioSecurity
import com.athar.feature.plan.InvestmentsEvent.CancelPortfolioEntryPreview
import com.athar.feature.plan.InvestmentsEvent.ConfirmPortfolioEntryPreview
import com.athar.feature.plan.InvestmentsEvent.DeletePortfolioEvent
import com.athar.feature.plan.InvestmentsEvent.ImportPortfolioStatement
import com.athar.feature.plan.InvestmentsEvent.PreviewPortfolioEntry
import com.athar.feature.plan.PortfolioEntryPreviewUi
import com.athar.feature.plan.PortfolioOpen
import com.athar.feature.plan.PortfolioPreviewTotals
import com.athar.feature.plan.R
import com.athar.feature.plan.editDraftOf
import java.math.BigDecimal
import kotlinx.datetime.LocalDate

/**
 * Read-only result surface for one opened PORTFOLIO document: honest valuation,
 * holdings, performance and the stored event list. Every number is recomputed from
 * the stored dated inputs by the reviewed engines; nothing here is cached truth.
 * Unsupported/partial states are disclosed in place, never silently zeroed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PortfolioWorkbenchResult(
    open: PortfolioOpen,
    problem: String?,
    onEvent: (InvestmentsEvent) -> Unit,
    allocationWrite: PortfolioAllocationWrite? = null,
    writeProblem: InvestmentWriteProblem? = null,
) {
    val theme = AtharTheme
    val bench = open.workbench
    var addingSecurity by remember { mutableStateOf(false) }
    var addingEntry by remember { mutableStateOf(false) }
    var addingQuote by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deletingEventId by remember { mutableStateOf<String?>(null) }
    var editingEventId by remember { mutableStateOf<String?>(null) }
    var editingOpeningDate by remember(open.planId) { mutableStateOf(false) }
    var restoredOpeningDate by remember(open.planId) { mutableStateOf<String?>(null) }
    LaunchedEffect(open.openingDatePreview?.token) {
        if (open.openingDatePreview != null) editingOpeningDate = false
    }

    // Preview cancel hands the exact reviewed draft back: reopen the editor with it.
    val restoredDraft = open.restoredEntryDraft
    LaunchedEffect(restoredDraft?.eventId, restoredDraft?.draft) {
        if (restoredDraft != null) editingEventId = restoredDraft.eventId
    }

    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AtharText(open.title, style = theme.typography.headline)
                    val asOf = bench.asOf
                    AtharText(
                        text = if (asOf == null) {
                            stringResource(R.string.plan_portfolio_scope_empty, open.currency)
                        } else {
                            stringResource(R.string.plan_portfolio_scope, open.currency, asOf.toString())
                        },
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
                PortfolioTextAction(stringResource(R.string.plan_portfolio_close)) {
                    onEvent(InvestmentsEvent.ClosePortfolio)
                }
            }

            problem?.let { PortfolioRejectMessage(it) }
            bench.document.openingCashDate?.let { date ->
                AtharText(
                    stringResource(R.string.plan_portfolio_opening_date_saved, date),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            if (bench.document.openingCashDate == null) {
                AtharText(stringResource(R.string.plan_portfolio_opening_date_none), style = theme.typography.caption, color = theme.colors.muted)
            }
            PortfolioTextAction(stringResource(R.string.plan_portfolio_opening_date_edit)) {
                restoredOpeningDate = null
                onEvent(InvestmentsEvent.CancelPortfolioOpeningDatePreview)
                editingOpeningDate = true
            }
            bench.rejection?.let { token ->
                AtharText(
                    stringResource(R.string.plan_portfolio_state_rejected),
                    style = theme.typography.caption,
                    color = theme.colors.ember,
                )
                PortfolioRejectMessage(token)
            }

            open.entryPreview?.let { preview -> PortfolioEntryPreviewBlock(preview, onEvent) }
            open.openingDatePreview?.let { preview ->
                PortfolioOpeningDatePreviewBlock(preview) { event ->
                    if (event == InvestmentsEvent.CancelPortfolioOpeningDatePreview) {
                        restoredOpeningDate = preview.draftText
                        editingOpeningDate = true
                    }
                    onEvent(event)
                }
            }

            val accounting = bench.accounting
            if (bench.isEmpty) {
                AtharText(
                    stringResource(R.string.plan_portfolio_scope_empty, open.currency),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
            if (accounting != null) {
                PortfolioValuationBlock(accounting.valuation, accounting.cash)
                PortfolioPerformanceBlock(bench.xirr, bench.twr, accounting.moneyGain, accounting.asOf)
            }
            val evidence = remember(bench) {
                PerformanceEvidence040.of(
                    bench.document.events,
                    bench.document.quotes,
                    bench.asOf,
                    bench.xirr,
                    bench.twr,
                    accounting?.moneyGain,
                    bench.document.openingCashDate,
                )
            }
            PortfolioPerformanceEvidence040(evidence)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                PortfolioTextAction(stringResource(R.string.plan_portfolio_add_security)) { addingSecurity = true }
                PortfolioTextAction(stringResource(R.string.plan_portfolio_add_entry)) { addingEntry = true }
                PortfolioTextAction(stringResource(R.string.plan_portfolio_add_quote)) { addingQuote = true }
                PortfolioTextAction(stringResource(R.string.plan_portfolio_import)) { importing = true }
            }

            PortfolioAllocationControls(open, allocationWrite, onEvent)

            if (accounting != null) {
                PortfolioHoldingsBlock(accounting)
                PortfolioGainsBlock(accounting)
                bench.allocationProblem?.let { PortfolioRejectMessage(it) }
            }
            PortfolioEventsBlock(
                events = bench.document.events,
                onEdit = { editingEventId = it },
                onDelete = { deletingEventId = it },
            )
        }
    }

    val planId = open.planId
    if (editingOpeningDate) {
        PortfolioOpeningDateSheet(
            currentDate = bench.document.openingCashDate,
            initialText = restoredOpeningDate ?: bench.document.openingCashDate.orEmpty(),
            problem = problem,
            writeProblem = writeProblem,
            onDismiss = {
                editingOpeningDate = false
                restoredOpeningDate = null
                onEvent(InvestmentsEvent.CancelPortfolioOpeningDatePreview)
            },
            onReview = { onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, it)) },
        )
    }
    if (addingSecurity) {
        PortfolioSecuritySheet(
            onDismiss = { addingSecurity = false },
            onSave = { id, name, assetClass ->
                addingSecurity = false
                onEvent(AddPortfolioSecurity(planId, id, name, assetClass))
            },
        )
    }
    if (addingEntry) {
        PortfolioEntrySheet(
            securityIds = bench.document.securities.map { it.securityId },
            onDismiss = { addingEntry = false },
            onSave = { draft ->
                addingEntry = false
                onEvent(AddPortfolioEntry(planId, draft))
            },
        )
    }
    if (addingQuote) {
        PortfolioQuoteSheet(
            securityIds = bench.document.securities.map { it.securityId },
            onDismiss = { addingQuote = false },
            onSave = { securityId, date, price ->
                addingQuote = false
                onEvent(AddPortfolioQuote(planId, securityId, date, price))
            },
        )
    }
    if (importing) {
        PortfolioImportInputSheet(
            onDismiss = { importing = false },
            onPreview = { text ->
                importing = false
                onEvent(ImportPortfolioStatement(planId, text))
            },
        )
    }
    deletingEventId?.let { eventId ->
        InvestmentDeleteConfirm(
            title = stringResource(R.string.plan_portfolio_event_delete_title),
            body = stringResource(R.string.plan_portfolio_event_delete_body),
            onConfirm = {
                deletingEventId = null
                onEvent(DeletePortfolioEvent(planId, eventId))
            },
            onDismiss = { deletingEventId = null },
        )
    }
    editingEventId?.let { eventId ->
        bench.document.events.firstOrNull { it.eventId == eventId }?.let { stored ->
            // After a preview cancel the preserved draft pre-fills the editor again;
            // otherwise the editor starts from the exact stored literals.
            val initial = restoredDraft
                ?.takeIf { it.eventId == eventId }
                ?.draft
                ?: editDraftOf(stored)
            PortfolioEntrySheet(
                securityIds = bench.document.securities.map { it.securityId },
                initial = initial,
                onDismiss = {
                    editingEventId = null
                    // Giving up after a cancel also drops the preserved draft (no write).
                    if (restoredDraft != null) onEvent(CancelPortfolioEntryPreview)
                },
                onSave = { draft ->
                    // Review first: the engines recompute the before/after off Main and
                    // nothing is written until the preview's explicit confirm.
                    editingEventId = null
                    onEvent(PreviewPortfolioEntry(planId, eventId, draft))
                },
            )
        }
    }
}

/**
 * The review gate before any correction is written: exact before/after cash, priced
 * holdings and total, recomputed by the reviewed engines in the ViewModel (never in a
 * composable) with the literal currency kept visually separate. Saving is explicit —
 * Cancel writes nothing and returns to the editor with the input preserved.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PortfolioEntryPreviewBlock(preview: PortfolioEntryPreviewUi, onEvent: (InvestmentsEvent) -> Unit) {
    val theme = AtharTheme
    val requester = remember { BringIntoViewRequester() }
    var placed by remember(preview.token) { mutableStateOf(false) }
    LaunchedEffect(preview.token, placed) {
        if (placed) requester.bringIntoView()
    }
    Column(
        modifier = Modifier.bringIntoViewRequester(requester).onGloballyPositioned { placed = true },
        verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        AtharText(stringResource(R.string.plan_portfolio_preview_title), style = theme.typography.body)
        AtharText(
            stringResource(R.string.plan_portfolio_preview_hint),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                PortfolioPreviewTotalsColumn(
                    label = stringResource(R.string.plan_portfolio_preview_before),
                    totals = preview.before,
                    currency = preview.currency,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                PortfolioPreviewTotalsColumn(
                    label = stringResource(R.string.plan_portfolio_preview_after),
                    totals = preview.after,
                    currency = preview.currency,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            PortfolioTextAction(stringResource(R.string.plan_portfolio_preview_save)) {
                onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(preview.token))
            }
            PortfolioTextAction(stringResource(R.string.plan_portfolio_preview_cancel)) {
                onEvent(InvestmentsEvent.CancelPortfolioEntryPreview)
            }
        }
    }
}

@Composable
internal fun PortfolioPreviewTotalsColumn(label: String, totals: PortfolioPreviewTotals, currency: String) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(label, style = theme.typography.caption, color = theme.colors.muted)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_cash), totals.cashAmount, currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_priced_holdings), totals.pricedHoldingsAmount, currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_total), totals.totalAmount, currency)
    }
}

/** Exact plain-decimal money line: value and literal currency stay visually separate. */
@Composable
private fun PortfolioMoneyLine(label: String, amount: BigDecimal?, currency: String?) {
    val money = if (amount != null && currency != null) Money.of(amount, currency) else null
    FigureRow(label = label, money = money, locale = currentUiLocale())
}

@Composable
private fun PortfolioValuationBlock(valuation: com.athar.core.domain.calc.portfolio.PortfolioValuation, cash: com.athar.core.common.money.Money) {
    val theme = AtharTheme
    val currency = valuation.cash.currency
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.plan_portfolio_valuation_title), style = theme.typography.body)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_cash), cash.amount, currency)
        PortfolioMoneyLine(
            stringResource(R.string.plan_portfolio_priced_holdings),
            valuation.pricedHoldingsValue.amount,
            currency,
        )
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_total), valuation.total?.amount, currency)
        if (valuation.isPartial) {
            AtharText(
                stringResource(R.string.plan_portfolio_partial, valuation.unpricedSecurityIds.joinToString("، ")),
                style = theme.typography.caption,
                color = theme.colors.dust,
            )
        }
    }
}

@Composable
private fun PortfolioHoldingsBlock(accounting: com.athar.core.domain.calc.portfolio.PortfolioAccounting) {
    val theme = AtharTheme
    if (accounting.holdings.isEmpty() && accounting.valuation.holdings.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.plan_portfolio_holdings_title), style = theme.typography.body)
        val valuations = accounting.valuation.holdings.associateBy { it.securityId }
        accounting.holdings.forEach { holding ->
            val valuation = valuations[holding.securityId]
            Column(modifier = Modifier.fillMaxWidth()) {
                AtharText(holding.securityId, style = theme.typography.caption)
                AtharText(
                    stringResource(R.string.plan_portfolio_holding_qty, holding.quantity.toPlainString()),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                PortfolioMoneyLine(
                    stringResource(R.string.plan_portfolio_holding_avg),
                    holding.averageCostPerUnit.amount,
                    holding.averageCostPerUnit.currency,
                )
                PortfolioMoneyLine(
                    stringResource(R.string.plan_portfolio_holding_basis),
                    holding.costBasis.amount,
                    holding.costBasis.currency,
                )
                when (val status = valuation?.status) {
                    is com.athar.core.domain.model.portfolio.PriceStatus.Priced -> {
                        val suffix = if (status.stale) stringResource(R.string.plan_portfolio_holding_stale) else ""
                        AtharText(
                            stringResource(R.string.plan_portfolio_holding_priced, status.unitPrice.amount.toPlainString(), status.quoteDate.toString()) + suffix,
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                    }
                    else -> AtharText(
                        stringResource(R.string.plan_portfolio_holding_unpriced),
                        style = theme.typography.caption,
                        color = theme.colors.dust,
                    )
                }
            }
        }
    }
}

/** Per-event engine outputs exactly as replayed — the UI aggregates nothing on its own. */
@Composable
private fun PortfolioGainsBlock(accounting: PortfolioAccounting) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        if (accounting.realizedGains.isNotEmpty()) {
            AtharText(stringResource(R.string.plan_portfolio_realized), style = theme.typography.body)
            accounting.realizedGains.forEach { gain ->
                AtharText(
                    "${gain.securityId} · ${gain.date} · ${gain.gain.amount.toPlainString()} ${gain.gain.currency}",
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
        if (accounting.unrealizedGains.isNotEmpty()) {
            AtharText(stringResource(R.string.plan_portfolio_unrealized), style = theme.typography.body)
            accounting.unrealizedGains.forEach { gain ->
                AtharText(
                    "${gain.securityId} · ${gain.gain.amount.toPlainString()} ${gain.gain.currency}",
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_div_cash), accounting.dividendsToCash.amount, accounting.dividendsToCash.currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_div_external), accounting.dividendsToExternal.amount, accounting.dividendsToExternal.currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_div_reinvested), accounting.dividendsReinvested.amount, accounting.dividendsReinvested.currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_fees_trade), accounting.tradeFees.amount, accounting.tradeFees.currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_fees_portfolio), accounting.portfolioFees.amount, accounting.portfolioFees.currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_contributions), accounting.contributions.amount, accounting.contributions.currency)
        PortfolioMoneyLine(stringResource(R.string.plan_portfolio_withdrawals), accounting.withdrawals.amount, accounting.withdrawals.currency)
    }
}

@Composable
private fun PortfolioPerformanceBlock(xirr: XirrResult, twr: TwrResult, moneyGain: MoneyGain, asOf: LocalDate?) {
    val theme = AtharTheme
    val locale = currentUiLocale()
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.plan_portfolio_perf_title), style = theme.typography.body)
        if (asOf != null) {
            AtharText(
                stringResource(R.string.plan_portfolio_perf_scope, asOf.toString()),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        when (xirr) {
            is XirrResult.Available -> AtharText(
                stringResource(R.string.plan_portfolio_xirr) + ": " +
                    PortfolioReturnText045.formatPercent(xirr.annualRatePercent, locale),
                style = theme.typography.caption,
            )
            is XirrResult.Undefined -> AtharText(
                stringResource(R.string.plan_portfolio_xirr) + ": " +
                    undefinedText(xirr.reason, xirr.unpricedSecurityIds),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        when (twr) {
            is TwrResult.Available -> AtharText(
                stringResource(R.string.plan_portfolio_twr) + ": " +
                    PortfolioReturnText045.formatPercent(twr.totalPercent, locale) + " (" +
                    stringResource(R.string.plan_portfolio_twr_periods, twr.periodCount) + ")",
                style = theme.typography.caption,
            )
            is TwrResult.Undefined -> AtharText(
                stringResource(R.string.plan_portfolio_twr) + ": " +
                    undefinedText(twr.reason, twr.unpricedSecurityIds),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        val gain = moneyGain.amount
        if (gain != null) {
            PortfolioMoneyLine(
                stringResource(R.string.plan_portfolio_money_gain),
                gain.amount,
                gain.currency,
            )
        } else {
            AtharText(
                stringResource(R.string.plan_portfolio_money_gain_unavailable, moneyGain.unavailableBecause.joinToString("، ")),
                style = theme.typography.caption,
                color = theme.colors.dust,
            )
        }
    }
}

@Composable
private fun undefinedText(reason: ReturnUndefinedReason, unpricedSecurityIds: List<String>): String =
    when {
        reason == ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION && unpricedSecurityIds.isNotEmpty() ->
            // Named securities: keep the existing per-security disclosure with the real ids.
            stringResource(R.string.plan_portfolio_return_undefined_missing, unpricedSecurityIds.joinToString("، "))
        // Empty structural case and every other reason resolve through the WM045 helper:
        // fixed no-evidence sentence or honest generic — never a zero claim.
        else -> stringResource(PortfolioReturnText045.undefinedTextRes(reason, unpricedSecurityIds))
    }

@Composable
private fun PortfolioEventsBlock(
    events: List<EventInput>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val theme = AtharTheme
    if (events.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.plan_portfolio_events_title), style = theme.typography.body)
        events.forEach { event ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AtharText(
                        "${kindLabel(event.kind)} · ${event.date}",
                        style = theme.typography.caption,
                    )
                    AtharText(
                        eventSummary(event),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    PortfolioTextAction(stringResource(R.string.plan_portfolio_event_edit)) { onEdit(event.eventId) }
                    PortfolioTextAction(stringResource(R.string.plan_portfolio_event_delete)) { onDelete(event.eventId) }
                }
            }
        }
    }
}

private fun eventSummary(event: EventInput): String = when (event.kind) {
    PortfolioEntryKind.BUY, PortfolioEntryKind.SELL, PortfolioEntryKind.REINVESTMENT ->
        listOfNotNull(
            event.securityId,
            event.quantity?.let { "×$it" },
            event.unitPrice?.let { "@$it" },
            event.amount?.let { "=$it" },
            event.fee?.takeIf { it != "0" }?.let { "+$it" },
        ).joinToString(" ")
    PortfolioEntryKind.CONTRIBUTION, PortfolioEntryKind.WITHDRAWAL, PortfolioEntryKind.FEE ->
        listOfNotNull(event.amount?.let { "=$it" }, event.note).joinToString(" ")
    PortfolioEntryKind.CASH_DIVIDEND ->
        listOfNotNull(event.securityId, event.amount?.let { "=$it" }, event.destination).joinToString(" ")
}

/** Secondary action: bordered ink chip at the 48dp touch floor — never a second accent fill. */
@Composable
internal fun PortfolioTextAction(label: String, onClick: () -> Unit) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .border(BorderStroke(0.5.dp, theme.colors.divider), RoundedCornerShape(theme.spacing.s))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
    ) {
        AtharText(label, style = theme.typography.caption, color = theme.colors.ink)
    }
}

