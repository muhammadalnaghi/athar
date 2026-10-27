package com.athar.feature.plan.scenarios

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.R
import com.athar.feature.plan.debt.DebtScenarioResult

@Composable
internal fun DebtScenarioContent(onClose: () -> Unit, onPositionChanged: () -> Unit, viewModel: DebtScenarioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = AtharTheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var inputsRequested by rememberSaveable(state.loaded?.id) { mutableStateOf(false) }
    val showInputs = debtForm041ShowInputs(state.outcome != null, inputsRequested)
    LaunchedEffect(state.outcome, showInputs) {
        if (state.outcome != null && !showInputs) onPositionChanged()
    }
    LaunchedEffect(state.schedule?.strategy, state.schedule?.page) { onPositionChanged() }
    BackHandler(enabled = state.schedule != null) { viewModel.onEvent(DebtScenarioEvent.BackToResult) }
    DisposableEffect(viewModel) { onDispose { viewModel.onEvent(DebtScenarioEvent.BackToResult) } }
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AtharText(stringResource(R.string.debt_scenario_title), style = theme.typography.headline, modifier = Modifier.weight(1f))
            ScenarioTextAction(stringResource(R.string.scenario_back)) {
                if (state.schedule != null) viewModel.onEvent(DebtScenarioEvent.BackToResult) else onClose()
            }
        }
        val schedule = state.schedule
        if (schedule != null) {
            DebtScheduleContent(schedule, viewModel::onEvent)
        } else {
            if (showInputs) {
                DebtScenarioForm(state, viewModel::onEvent) {
                    inputsRequested = false
                    focus.clearFocus()
                    keyboard?.hide()
                    viewModel.onEvent(DebtScenarioEvent.Compute)
                }
            } else {
                AtharText(stringResource(R.string.debt041_result_scope),
                    style = theme.typography.caption, color = theme.colors.muted)
                ScenarioTextAction(stringResource(R.string.debt041_edit_inputs)) {
                    inputsRequested = true
                    onPositionChanged()
                }
                state.outcome?.let { outcome ->
                    DebtScenarioResult(outcome, onOpenSchedule = { viewModel.onEvent(DebtScenarioEvent.OpenSchedule(it)) })
                }
            }
            DebtSavedPlans(state, viewModel::onEvent)
        }
    }
}
