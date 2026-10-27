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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.R
import com.athar.feature.plan.cashforecast.CashPathResult

@Composable
internal fun CashScenarioContent(onClose: () -> Unit, onPositionChanged: () -> Unit, viewModel: CashScenarioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = AtharTheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.path?.currency, state.path?.page) { onPositionChanged() }
    BackHandler(enabled = state.path != null) { viewModel.onEvent(CashScenarioEvent.BackToResult) }
    DisposableEffect(viewModel) { onDispose { viewModel.onEvent(CashScenarioEvent.BackToResult) } }
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AtharText(stringResource(R.string.cash_scenario_title), style = theme.typography.headline, modifier = Modifier.weight(1f))
            ScenarioTextAction(stringResource(R.string.scenario_back)) {
                if (state.path != null) viewModel.onEvent(CashScenarioEvent.BackToResult) else onClose()
            }
        }
        val path = state.path
        if (path != null) CashPathContent(path) { viewModel.onEvent(it) } else {
            CashScenarioForm(state, viewModel::onEvent) {
                focus.clearFocus()
                keyboard?.hide()
                viewModel.onEvent(CashScenarioEvent.Compute)
            }
            state.outcome?.let { CashPathResult(it, onOpenPath = { currency -> viewModel.onEvent(CashScenarioEvent.OpenPath(currency)) }) }
            CashSavedSection(state, viewModel::onEvent)
        }
    }
}
