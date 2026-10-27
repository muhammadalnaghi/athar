package com.athar.feature.plan.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.R

/** Presentation only: neither disclosure nor result navigation can write financial inputs. */
internal fun debtForm041ShowAdvanced(requested: Boolean, errors: Set<String>): Boolean =
    requested || "horizon" in errors

internal fun debtForm041ShowInputs(hasOutcome: Boolean, requested: Boolean): Boolean =
    !hasOutcome || requested

@Composable
internal fun DebtForm041Advanced(state: DebtScenarioState, onEvent: (DebtScenarioEvent) -> Unit) {
    val theme = AtharTheme
    var requested by rememberSaveable(state.loaded?.id, state.draft.currency) { mutableStateOf(false) }
    val hasError = "horizon" in state.errors
    LaunchedEffect(hasError) { if (hasError) requested = true }
    val expanded = debtForm041ShowAdvanced(requested, state.errors)
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        ScenarioTextAction(stringResource(if (expanded) R.string.debt041_less else R.string.debt041_more),
            enabled = !hasError) { requested = !expanded }
        AtharText(stringResource(R.string.debt041_horizon_summary, state.draft.horizon),
            style = theme.typography.caption, color = theme.colors.muted)
        if (expanded) {
            ScenarioInput(state.draft.horizon, stringResource(R.string.debt_scenario_horizon), hasError, true) {
                onEvent(DebtScenarioEvent.Edit(DebtField.HORIZON, it))
            }
            AtharText(stringResource(R.string.debt_scenario_rounding),
                style = theme.typography.caption, color = theme.colors.muted)
        }
    }
}
