package com.athar.feature.plan.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.feature.plan.R
import com.athar.feature.plan.goals.ActionButton
import com.athar.feature.plan.goals.SelectorOption
import com.athar.feature.plan.goals.SelectorRow

@Composable
internal fun CashScenarioForm(state: CashScenarioState, onEvent: (CashScenarioEvent) -> Unit, onCompute: () -> Unit) {
    val theme = AtharTheme
    val draft = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.m)) {
        AtharText(stringResource(R.string.scenario_temporary), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.cash_scenario_intro), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.debt_scenario_currency_note), style = theme.typography.caption, color = theme.colors.muted)
        ScenarioInput(draft.currency, stringResource(R.string.scenario_currency), "currency" in state.errors) {
            onEvent(CashScenarioEvent.Edit(CashField.CURRENCY, it))
        }
        ScenarioInput(draft.openingDate, stringResource(R.string.cash_scenario_opening_date), "openingDate" in state.errors) {
            onEvent(CashScenarioEvent.Edit(CashField.OPENING_DATE, it))
        }
        ScenarioInput(draft.openingCash, stringResource(R.string.cash_scenario_opening_cash), "openingCash" in state.errors, true) {
            onEvent(CashScenarioEvent.Edit(CashField.OPENING_CASH, it))
        }
        ScenarioInput(draft.reserve, stringResource(R.string.cash_scenario_reserve), "reserve" in state.errors, true) {
            onEvent(CashScenarioEvent.Edit(CashField.RESERVE, it))
        }
        AtharText(stringResource(R.string.cash_scenario_reserve_note), style = theme.typography.caption, color = theme.colors.muted)
        ScenarioInput(draft.horizonEnd, stringResource(R.string.cash_scenario_end), "horizonEnd" in state.errors) {
            onEvent(CashScenarioEvent.Edit(CashField.HORIZON_END, it))
        }
        draft.flows.forEachIndexed { index, flow ->
            key(flow.id) {
                HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
                AtharText(stringResource(R.string.cash_scenario_flow, index + 1), style = theme.typography.headline)
                FlowFields(flow, state.errors, onEvent)
                ScenarioTextAction(stringResource(R.string.scenario_remove)) { onEvent(CashScenarioEvent.RemoveFlow(flow.id)) }
            }
        }
        ScenarioTextAction(stringResource(R.string.cash_scenario_add), draft.flows.size < 24) { onEvent(CashScenarioEvent.AddFlow) }
        ScenarioCheck(stringResource(R.string.cash_scenario_complete), draft.scopeComplete) { onEvent(CashScenarioEvent.ScopeComplete(it)) }
        ScenarioCheck(stringResource(R.string.cash_scenario_fresh), draft.anchorFresh) { onEvent(CashScenarioEvent.AnchorFresh(it)) }
        if (state.errors.isNotEmpty()) AtharText(stringResource(
            if ("calculation" in state.errors) R.string.scenario_calculation_failed else R.string.scenario_review_inputs),
            style = theme.typography.body, color = theme.colors.dust)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            ActionButton(stringResource(if (state.computing) R.string.scenario_computing else R.string.cash_scenario_compute),
                theme.colors.ember, theme.colors.parchment, Modifier.weight(1f), enabled = !state.computing, onClick = onCompute)
            ActionButton(stringResource(R.string.scenario_reset), theme.colors.surface, theme.colors.ink,
                Modifier.weight(1f), hairlineBorder = true) { onEvent(CashScenarioEvent.Reset) }
        }
    }
}

@Composable
private fun FlowFields(flow: CashFlowDraft, errors: Set<String>, onEvent: (CashScenarioEvent) -> Unit) {
    SelectorRow(options = listOf(
        SelectorOption(CashFlowDirection.INFLOW, stringResource(R.string.cash_scenario_inflow)),
        SelectorOption(CashFlowDirection.OUTFLOW, stringResource(R.string.cash_scenario_outflow))),
        selected = flow.direction, onSelect = { onEvent(CashScenarioEvent.Direction(flow.id, it)) })
    ScenarioInput(flow.date, stringResource(R.string.cash_scenario_flow_date), "flow-${flow.id}-date" in errors) {
        onEvent(CashScenarioEvent.EditFlow(flow.id, CashFlowField.DATE, it))
    }
    ScenarioInput(flow.amount, stringResource(R.string.cash_scenario_flow_amount), "flow-${flow.id}-amount" in errors, true) {
        onEvent(CashScenarioEvent.EditFlow(flow.id, CashFlowField.AMOUNT, it))
    }
}

@Preview(name = "Temporary cash form", locale = "ar", widthDp = 360)
@Composable
private fun CashFormPreview() { AtharTheme { CashScenarioForm(CashScenarioState(), {}, {}) } }
