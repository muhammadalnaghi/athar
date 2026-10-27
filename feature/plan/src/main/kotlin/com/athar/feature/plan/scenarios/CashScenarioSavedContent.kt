package com.athar.feature.plan.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.feature.plan.R
import com.athar.feature.plan.cashforecast.FigureRow
import com.athar.feature.plan.cashforecast.dayLabel
import com.athar.feature.plan.cashforecast.partitionStatusLabel
import com.athar.feature.plan.goals.ActionButton
import java.util.Locale
import androidx.compose.material3.ButtonDefaults

/**
 * Saved cash-scenario panel: save the validated inputs, reopen them, update or delete
 * them through the frozen revision-fenced repository, and compare scenarios that share
 * the same opening source, currency, opening date and horizon.
 */
@Composable
internal fun CashSavedSection(state: CashScenarioState, onEvent: (CashScenarioEvent) -> Unit) {
    val theme = AtharTheme
    val locale = currentUiLocale()
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.m)) {
        HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
        AtharText(stringResource(R.string.cash_saved_section_title), style = theme.typography.headline)
        AtharText(stringResource(R.string.cash_saved_section_hint), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.cash_saved_opening_source_note), style = theme.typography.caption, color = theme.colors.muted)
        if (state.savedList.unavailable > 0) {
            AtharText(
                stringResource(R.string.cash_saved_unavailable_note, state.savedList.unavailable),
                style = theme.typography.caption, color = theme.colors.dust,
            )
        }
        SaveControls(state, onEvent)
        SaveMessageLine(state.saved.message)
        CompareControls(state, locale, onEvent)
        state.savedList.summaries.forEach { summary ->
            key(summary.id) { SavedRow(summary, state, locale, onEvent) }
        }
    }
}

@Composable
private fun SaveControls(state: CashScenarioState, onEvent: (CashScenarioEvent) -> Unit) {
    val theme = AtharTheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val loaded = state.saved.loaded
    if (loaded == null && state.outcome == null) return
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        loaded?.let {
            AtharText(
                stringResource(R.string.cash_saved_loaded_badge, it.title),
                style = theme.typography.caption, color = theme.colors.olive,
            )
        }
        ScenarioInput(state.saved.titleDraft, stringResource(R.string.cash_saved_title_label), false) {
            onEvent(CashScenarioEvent.SaveTitle(it))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            val saving = state.saved.saving
            ActionButton(
                label = stringResource(
                    when {
                        saving -> R.string.cash_saved_saving
                        loaded == null -> R.string.cash_saved_save
                        else -> R.string.cash_saved_update
                    },
                ),
                container = theme.colors.ember,
                content = theme.colors.parchment,
                modifier = Modifier.weight(1f),
                enabled = state.outcome != null && !saving,
                onClick = {
                    focus.clearFocus()
                    keyboard?.hide()
                    onEvent(CashScenarioEvent.SaveScenario)
                },
            )
        }
    }
}

@Composable
private fun SaveMessageLine(message: CashSaveMessage?) {
    val theme = AtharTheme
    if (message == null) return
    val (text, color) = when (message) {
        CashSaveMessage.SAVED -> R.string.cash_saved_msg_saved to theme.colors.muted
        CashSaveMessage.UPDATED -> R.string.cash_saved_msg_updated to theme.colors.muted
        CashSaveMessage.DELETED -> R.string.cash_saved_msg_deleted to theme.colors.muted
        CashSaveMessage.TITLE_REQUIRED -> R.string.cash_saved_msg_title to theme.colors.dust
        CashSaveMessage.CONFLICT -> R.string.cash_saved_msg_conflict to theme.colors.dust
        CashSaveMessage.STORAGE -> R.string.cash_saved_msg_storage to theme.colors.dust
        CashSaveMessage.MISSING -> R.string.cash_saved_msg_missing to theme.colors.dust
    }
    AtharText(stringResource(text), style = theme.typography.caption, color = color)
}

@Composable
private fun CompareControls(state: CashScenarioState, locale: Locale, onEvent: (CashScenarioEvent) -> Unit) {
    val theme = AtharTheme
    if (!state.compare.active) {
        ScenarioTextAction(stringResource(R.string.cash_saved_compare_open)) { onEvent(CashScenarioEvent.CompareOpen) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.cash_saved_compare_hint), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(
            stringResource(R.string.cash_saved_compare_selected, state.compare.selected.size),
            style = theme.typography.caption, color = theme.colors.muted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            ActionButton(
                label = stringResource(if (state.compare.running) R.string.scenario_computing else R.string.cash_saved_compare_run),
                container = theme.colors.ember,
                content = theme.colors.parchment,
                modifier = Modifier.weight(1f),
                enabled = !state.compare.running && state.compare.selected.size >= 2,
                onClick = { onEvent(CashScenarioEvent.CompareRun) },
            )
        }
        ScenarioTextAction(stringResource(R.string.cash_saved_compare_close)) { onEvent(CashScenarioEvent.CompareClose) }
        if (state.compare.empty && state.compare.rows.isEmpty()) {
            AtharText(stringResource(R.string.cash_saved_compare_none), style = theme.typography.caption, color = theme.colors.dust)
        }
        state.compare.rows.forEach { row ->
            HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
            AtharText(row.title, style = theme.typography.body)
            row.status?.let { status ->
                AtharText(partitionStatusLabel(status), style = theme.typography.caption, color = theme.colors.muted)
            }
            if (row.rejected) {
                AtharText(stringResource(R.string.cash_saved_compare_rejected), style = theme.typography.caption, color = theme.colors.dust)
            }
            if (row.minimum != null && row.minimumDate != null) {
                FigureRow(stringResource(R.string.cash_saved_compare_minimum_on, dayLabel(row.minimumDate, locale)), row.minimum, locale)
            }
            if (row.shortfall != null) {
                val label = if (row.shortfallDate != null) {
                    stringResource(R.string.cash_saved_compare_shortfall_on, dayLabel(row.shortfallDate, locale))
                } else {
                    stringResource(R.string.cash_saved_compare_shortfall_plain)
                }
                FigureRow(label, row.shortfall, locale, valueColor = theme.colors.dust)
            }
            if (row.knownEnd != null && row.knownEndDate != null) {
                FigureRow(stringResource(R.string.cash_saved_compare_known_end_on, dayLabel(row.knownEndDate, locale)), row.knownEnd, locale)
            }
            AtharText(
                stringResource(R.string.cash_saved_compare_flows, row.flowCount),
                style = theme.typography.caption, color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun SavedRow(
    summary: CashSavedSummary,
    state: CashScenarioState,
    locale: Locale,
    onEvent: (CashScenarioEvent) -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
        AtharText(summary.title, style = theme.typography.body)
        AtharText(
            "${summary.currency} · " + stringResource(R.string.cash_saved_range, summary.openingDate, summary.horizonEnd),
            style = theme.typography.caption, color = theme.colors.muted,
        )
        if (state.confirmDeleteId == summary.id) {
            AtharText(stringResource(R.string.cash_saved_delete_note), style = theme.typography.caption, color = theme.colors.dust)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                TextButton(
                    onClick = { onEvent(CashScenarioEvent.ConfirmDeleteSaved) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = theme.colors.crimson),
                ) { AtharText(stringResource(R.string.cash_saved_delete_confirm), style = theme.typography.body, color = theme.colors.crimson) }
                TextButton(
                    onClick = { onEvent(CashScenarioEvent.CancelDeleteSaved) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = theme.colors.muted),
                ) { AtharText(stringResource(R.string.cash_saved_cancel), style = theme.typography.body, color = theme.colors.muted) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                if (state.compare.active) {
                    val selected = summary.id in state.compare.selected
                    ScenarioTextAction(
                        stringResource(if (selected) R.string.cash_saved_selected_badge else R.string.cash_saved_compare_toggle),
                    ) { onEvent(CashScenarioEvent.CompareToggle(summary.id)) }
                } else {
                    ScenarioTextAction(stringResource(R.string.cash_saved_load)) { onEvent(CashScenarioEvent.LoadSaved(summary.id)) }
                    ScenarioTextAction(stringResource(R.string.cash_saved_delete)) { onEvent(CashScenarioEvent.DeleteSaved(summary.id)) }
                }
            }
        }
    }
}
