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

@Composable
internal fun DebtScenarioForm(state: DebtScenarioState, onEvent: (DebtScenarioEvent) -> Unit, onCompute: () -> Unit) {
    val theme = AtharTheme
    val draft = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.m)) {
        AtharText(stringResource(R.string.debt_saved_terms_note), style = theme.typography.caption, color = theme.colors.muted)
        ScenarioInput(draft.currency, stringResource(R.string.scenario_currency), "currency" in state.errors) {
            onEvent(DebtScenarioEvent.Edit(DebtField.CURRENCY, it))
        }
        ScenarioInput(draft.firstDueDate, stringResource(R.string.debt_scenario_first_due), "firstDueDate" in state.errors) {
            onEvent(DebtScenarioEvent.Edit(DebtField.FIRST_DUE, it))
        }
        ScenarioInput(draft.budget, stringResource(R.string.debt_scenario_budget), "budget" in state.errors, true) {
            onEvent(DebtScenarioEvent.Edit(DebtField.BUDGET, it))
        }
        draft.loans.forEachIndexed { index, loan ->
            key(loan.id) {
                HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
                AtharText(stringResource(R.string.debt_scenario_loan, index + 1), style = theme.typography.headline)
                LoanFields(loan, state.errors, onEvent)
                if (draft.loans.size > 1) ScenarioTextAction(stringResource(R.string.scenario_remove)) {
                    onEvent(DebtScenarioEvent.RemoveLoan(loan.id))
                }
            }
        }
        ScenarioTextAction(stringResource(R.string.debt_scenario_add), draft.loans.size < 12) { onEvent(DebtScenarioEvent.AddLoan) }
        AtharText(stringResource(R.string.debt_scenario_terms), style = theme.typography.caption, color = theme.colors.muted)
        ScenarioCheck(stringResource(R.string.debt_scenario_acknowledge), draft.supportedTermsAcknowledged) {
            onEvent(DebtScenarioEvent.AcknowledgeTerms(it))
        }
        DebtForm041Advanced(state, onEvent)
        if (state.errors.isNotEmpty()) AtharText(stringResource(
            if ("calculation" in state.errors) R.string.scenario_calculation_failed else R.string.scenario_review_inputs),
            style = theme.typography.body, color = theme.colors.dust)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            ActionButton(stringResource(if (state.computing) R.string.scenario_computing else R.string.debt_scenario_compute),
                theme.colors.ember, theme.colors.parchment, Modifier.weight(1f), enabled = !state.computing, onClick = onCompute)
            ActionButton(stringResource(R.string.scenario_reset), theme.colors.surface, theme.colors.ink,
                Modifier.weight(1f), hairlineBorder = true) { onEvent(DebtScenarioEvent.Reset) }
        }
    }
}

@Composable
private fun LoanFields(loan: DebtLoanDraft, errors: Set<String>, onEvent: (DebtScenarioEvent) -> Unit) {
    val prefix = "loan-${loan.id}-"
    ScenarioInput(loan.name, stringResource(R.string.debt_scenario_name), prefix + "name" in errors) {
        onEvent(DebtScenarioEvent.EditLoan(loan.id, DebtLoanField.NAME, it))
    }
    ScenarioInput(loan.principal, stringResource(R.string.debt_scenario_principal), prefix + "principal" in errors, true) {
        onEvent(DebtScenarioEvent.EditLoan(loan.id, DebtLoanField.PRINCIPAL, it))
    }
    ScenarioInput(loan.annualPercent, stringResource(R.string.debt_scenario_annual_percent), prefix + "annualPercent" in errors, true) {
        onEvent(DebtScenarioEvent.EditLoan(loan.id, DebtLoanField.ANNUAL_PERCENT, it))
    }
    ScenarioInput(loan.minimum, stringResource(R.string.debt_scenario_minimum), prefix + "minimum" in errors, true) {
        onEvent(DebtScenarioEvent.EditLoan(loan.id, DebtLoanField.MINIMUM, it))
    }
    ScenarioInput(loan.fee, stringResource(R.string.debt_scenario_fee), prefix + "fee" in errors, true) {
        onEvent(DebtScenarioEvent.EditLoan(loan.id, DebtLoanField.FEE, it))
    }
}

@Preview(name = "Temporary debt form", locale = "ar", widthDp = 360)
@Composable
private fun DebtFormPreview() { AtharTheme { DebtScenarioForm(DebtScenarioState(), {}, {}) } }
