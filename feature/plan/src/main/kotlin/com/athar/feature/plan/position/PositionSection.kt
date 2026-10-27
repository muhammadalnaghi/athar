package com.athar.feature.plan.position

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.activity.compose.BackHandler
import com.athar.core.designsystem.component.AtharTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharSegment
import com.athar.core.designsystem.component.AtharSegmentedControl
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.InvestmentDeleteConfirm
import com.athar.feature.plan.R
import com.athar.feature.plan.portfolio.PortfolioTextAction
import kotlinx.collections.immutable.persistentListOf
import java.math.BigDecimal

/**
 * The quiet dated-position surface hosted inside InvestmentsContent: saved
 * USER-DECLARED dated balances — never verified bank balances. Everything is
 * state-driven from [PositionState]; no business rules live here.
 */
@Composable
internal fun PositionSection(onPositionChanged: () -> Unit = {}, viewModel: PositionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saveSerial, state.problem) {
        if (state.saveSerial > 0L || state.problem != null) onPositionChanged()
    }
    BackHandler(enabled = state.editor != null || state.open != null || state.confirmDelete != null) {
        if (!state.isBusy) viewModel.onEvent(when {
            state.confirmDelete != null -> PositionEvent.DismissDelete
            state.editor != null -> PositionEvent.DismissEditor
            else -> PositionEvent.CloseDoc
        })
    }
    PositionSectionContent(state = state, onEvent = viewModel::onEvent)
}

@Composable
internal fun PositionSectionContent(state: PositionState, onEvent: (PositionEvent) -> Unit) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(stringResource(R.string.plan_position_section_title), style = theme.typography.headline)
            AtharText(stringResource(R.string.plan_position_section_hint), style = theme.typography.caption, color = theme.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                PortfolioTextAction(stringResource(R.string.plan_position_add)) { onEvent(PositionEvent.OpenCreateEditor) }
            }
            state.problem?.let { PositionProblemMessage(it, onEvent) }
            if (state.rows.isEmpty()) {
                AtharText(stringResource(R.string.plan_position_empty), style = theme.typography.caption, color = theme.colors.muted)
            }
            state.rows.forEach { row -> PositionRowItem(row, onEvent) }
            state.open?.let { open -> PositionResult(open, onEvent) }
            state.editor?.let { editor -> PositionEditor(editor, state.isBusy, onEvent) }
        }
    }
    state.confirmDelete?.let { row ->
        InvestmentDeleteConfirm(
            title = stringResource(R.string.plan_position_delete_title),
            body = stringResource(R.string.plan_position_delete_body),
            onConfirm = { if (!state.isBusy) onEvent(PositionEvent.ConfirmDelete) },
            onDismiss = { onEvent(PositionEvent.DismissDelete) },
        )
    }
}

@Composable
private fun PositionProblemMessage(problem: PositionProblem, onEvent: (PositionEvent) -> Unit) {
    val theme = AtharTheme
    val text = stringResource(
        when (problem) {
            PositionProblem.INVALID_INPUT -> R.string.plan_position_problem_invalid
            PositionProblem.UNSUPPORTED_DOCUMENT -> R.string.plan_position_problem_unsupported
            PositionProblem.CONFLICT -> R.string.plan_position_problem_conflict
            PositionProblem.STORAGE -> R.string.plan_position_problem_storage
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(text, modifier = Modifier.weight(1f), style = theme.typography.caption, color = theme.colors.ember)
        PortfolioTextAction(stringResource(R.string.plan_position_cancel)) { onEvent(PositionEvent.DismissProblem) }
    }
}

@Composable
private fun PositionRowItem(row: PositionRow, onEvent: (PositionEvent) -> Unit) {
    val theme = AtharTheme
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            AtharText(row.title, style = theme.typography.body)
            if (row.unavailable) {
                AtharText(
                    stringResource(R.string.plan_position_state_unavailable),
                    style = theme.typography.caption,
                    color = theme.colors.dust,
                )
            } else {
                AtharText(
                    stringResource(R.string.plan_position_row_hint, row.entryCount, row.revision, row.currency),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
        if (!row.unavailable) {
            PortfolioTextAction(stringResource(R.string.plan_position_open)) { onEvent(PositionEvent.OpenDoc(row.planId)) }
            PortfolioTextAction(stringResource(R.string.plan_position_delete)) { onEvent(PositionEvent.RequestDelete(row.planId)) }
        }
    }
}

@Composable
private fun PositionResult(open: PositionOpen, onEvent: (PositionEvent) -> Unit) {
    val theme = AtharTheme
    val totals = open.totals
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AtharText(open.title, style = theme.typography.headline, modifier = Modifier.weight(1f))
            PortfolioTextAction(stringResource(R.string.plan_position_close)) { onEvent(PositionEvent.CloseDoc) }
        }
        AtharText(stringResource(R.string.plan_position_totals_title), style = theme.typography.body)
        totals.perCurrency.forEach { line ->
            PositionAmount(stringResource(R.string.plan_position_assets), line.assets, line.currency)
            PositionAmount(stringResource(R.string.plan_position_liabilities), line.liabilities, line.currency)
            PositionAmount(stringResource(R.string.plan_position_net), line.net, line.currency)
        }
        AtharText(
            if (totals.ageDays == 0L) {
                stringResource(R.string.plan_position_disclosure_today, totals.asOfDate)
            } else {
                stringResource(R.string.plan_position_disclosure_freshness, totals.asOfDate, totals.ageDays)
            },
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        AtharText(stringResource(R.string.plan_position_disclosure_declared), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.plan_position_disclosure_no_fx), style = theme.typography.caption, color = theme.colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            PortfolioTextAction(stringResource(R.string.plan_position_update)) { onEvent(PositionEvent.OpenUpdateEditor(open.planId)) }
            PortfolioTextAction(stringResource(R.string.plan_position_delete)) { onEvent(PositionEvent.RequestDelete(open.planId)) }
        }
    }
}

@Composable
private fun PositionAmount(label: String, amount: BigDecimal, currency: String) {
    val theme = AtharTheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(label, modifier = Modifier.weight(1f), style = theme.typography.caption, color = theme.colors.muted)
        val money = runCatching { Money.of(amount, currency) }.getOrNull()
        if (money != null) AtharNumber(money = money)
        else AtharText(amount.toPlainString() + " " + currency, style = theme.typography.body)
    }
}

@Composable
private fun PositionEditor(editor: PositionEditorUi, busy: Boolean, onEvent: (PositionEvent) -> Unit) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            stringResource(
                if (editor.planId == null) R.string.plan_position_edit_create_title else R.string.plan_position_edit_update_title,
            ),
            style = theme.typography.body,
        )
        PositionField(stringResource(R.string.plan_position_edit_doc_title), editor.title) {
            onEvent(PositionEvent.EditField(PositionTextField.TITLE, it))
        }
        PositionField(stringResource(R.string.plan_position_edit_asof), editor.asOfDate) {
            onEvent(PositionEvent.EditField(PositionTextField.AS_OF_DATE, it))
        }
        PositionField(stringResource(R.string.plan_position_edit_note), editor.note) {
            onEvent(PositionEvent.EditField(PositionTextField.NOTE, it))
        }
        editor.entries.forEachIndexed { index, draft ->
            PositionEntryEditor(index, draft, onEvent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            PortfolioTextAction(stringResource(R.string.plan_position_entry_add)) { onEvent(PositionEvent.AddEntry) }
            PortfolioTextAction(stringResource(if (busy) R.string.plan_position_saving else R.string.plan_position_save)) {
                if (!busy) onEvent(PositionEvent.SaveEditor)
            }
            PortfolioTextAction(stringResource(R.string.plan_position_cancel)) { onEvent(PositionEvent.DismissEditor) }
        }
    }
}

@Composable
private fun PositionEntryEditor(index: Int, draft: PositionEntryDraft, onEvent: (PositionEvent) -> Unit) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharSegmentedControl(
            segments = listOf(
                AtharSegment(PositionSide.ASSET, stringResource(R.string.plan_position_entry_side_asset)),
                AtharSegment(PositionSide.LIABILITY, stringResource(R.string.plan_position_entry_side_liability)),
            ),
            selected = draft.side,
            onSelect = { if (it != draft.side) onEvent(PositionEvent.EditEntry(index, PositionEntryField.SIDE, draft.side.name)) },
        )
        PositionField(stringResource(R.string.plan_position_entry_label), draft.label) {
            onEvent(PositionEvent.EditEntry(index, PositionEntryField.LABEL, it))
        }
        PositionField(stringResource(R.string.plan_position_entry_currency), draft.currency) {
            onEvent(PositionEvent.EditEntry(index, PositionEntryField.CURRENCY, it))
        }
        PositionField(stringResource(R.string.plan_position_entry_amount), draft.amount) {
            onEvent(PositionEvent.EditEntry(index, PositionEntryField.AMOUNT, it))
        }
        PositionField(stringResource(R.string.plan_position_entry_scope), draft.ownerScope) {
            onEvent(PositionEvent.EditEntry(index, PositionEntryField.OWNER_SCOPE, it))
        }
        PortfolioTextAction(stringResource(R.string.plan_position_entry_remove)) {
            onEvent(PositionEvent.RemoveEntry(index))
        }
    }
}

@Composable
private fun PositionField(label: String, value: String, onValue: (String) -> Unit) {
    AtharTextField(value = value, onValueChange = onValue, label = label, modifier = Modifier.fillMaxWidth())
}

@Preview(name = "Position - list and result", showBackground = true, locale = "ar")
@Composable
private fun PositionListPreview() {
    AtharTheme {
        PositionSectionContent(
            state = PositionState(
                rows = persistentListOf(
                    PositionRow("p1", "مركز أكتوبر", 3, "SAR", 2),
                ),
                open = PositionOpen(
                    planId = "p1",
                    title = "مركز أكتوبر",
                    revision = 3,
                    document = PositionDocument("2026-10-01"),
                    totals = PositionTotals(
                        asOfDate = "2026-10-01",
                        ageDays = 23,
                        perCurrency = listOf(
                            PositionCurrencyTotal("SAR", BigDecimal("15000.00"), BigDecimal("4200.50"), BigDecimal("10799.50")),
                            PositionCurrencyTotal("USD", BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal("1000.00")),
                        ),
                        entryCount = 3,
                    ),
                ),
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Position - editor", showBackground = true, locale = "ar")
@Composable
private fun PositionEditorPreview() {
    AtharTheme {
        PositionSectionContent(
            state = PositionState(
                editor = PositionEditorUi(
                    planId = null,
                    expectedRevision = null,
                    title = "مركز أكتوبر",
                    asOfDate = "2026-10-24",
                    entries = persistentListOf(PositionEntryDraft(label = "حساب جاري", currency = "SAR", amount = "15000.00")),
                ),
            ),
            onEvent = {},
        )
    }
}
