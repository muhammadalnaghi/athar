package com.athar.feature.plan.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.GoalsEvent
import com.athar.feature.plan.GoalsState
import com.athar.feature.plan.InvestmentDeleteConfirm
import com.athar.feature.plan.R
import com.athar.feature.plan.SavedGoalDocState
import com.athar.feature.plan.SavedGoalNotice
import com.athar.feature.plan.SavedGoalSummary

/**
 * Reachable saved-goal section under the transient scenario card: an explicit title,
 * save/update of the VISIBLE draft's inputs, the observed document list, and fixed
 * notices for refusals. Nothing here stores or shows a computed outcome — loading
 * restores inputs and clears the result, so every figure is recomputed by the accepted
 * solver. Refused loads (unsupported document, other literal currency) never mutate the
 * visible draft, matching the scenario card's no-relabel policy.
 */
@Composable
internal fun SavedGoalsCard(
    state: GoalsState,
    onEvent: (GoalsEvent) -> Unit,
) {
    val theme = AtharTheme
    val keyboard = LocalSoftwareKeyboardController.current
    val inputsLive = !state.isLoading && state.scenario.currency.isNotBlank()
    val active = state.savedGoals.firstOrNull { it.id == state.activeSavedGoalId }
    var deleting by remember { mutableStateOf<SavedGoalSummary?>(null) }

    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.plan_goals_saved_title),
                style = theme.typography.headline,
                color = theme.colors.ink,
            )
            AtharText(
                text = stringResource(R.string.plan_goals_saved_inputs_only),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharTextField(
                value = state.savedGoalTitleText,
                onValueChange = { onEvent(GoalsEvent.SetSavedGoalTitle(it)) },
                label = stringResource(R.string.plan_goals_saved_title_label),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Text,
                isError = state.savedGoalNotice is SavedGoalNotice.NeedsTitle,
                supportingText = if (state.savedGoalNotice is SavedGoalNotice.NeedsTitle) {
                    stringResource(R.string.plan_goals_saved_notice_needs_title)
                } else {
                    null
                },
            )
            if (active != null) {
                AtharText(
                    text = stringResource(R.string.plan_goals_saved_editing, active.title),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            ActionButton(
                label = when {
                    state.savedGoalSaving -> stringResource(R.string.plan_goals_saved_working)
                    active == null -> stringResource(R.string.plan_goals_saved_save)
                    else -> stringResource(R.string.plan_goals_saved_update, active.title)
                },
                container = theme.colors.ember,
                content = theme.colors.parchment,
                enabled = inputsLive && !state.savedGoalSaving && state.savedGoalTitleText.isNotBlank(),
                onClick = {
                    keyboard?.hide()
                    onEvent(GoalsEvent.SaveCurrentGoal)
                },
            )

            state.savedGoals.forEach { doc ->
                SavedGoalRow(doc = doc, onEvent = onEvent, onDelete = { deleting = doc })
            }
            if (state.savedGoals.isNotEmpty()) {
                // Honest allocation semantics: saved scenarios are independent
                // assumptions; the same real balance may appear in several of them and
                // nothing is deducted or reserved by saving.
                ConventionText(stringResource(R.string.plan_goals_saved_allocations_note))
            }

            state.savedGoalNotice?.let { notice ->
                NoticeRow(notice = notice, onDismiss = { onEvent(GoalsEvent.DismissSavedGoalNotice) })
            }
        }
    }
    deleting?.let { doc ->
        InvestmentDeleteConfirm(
            title = stringResource(R.string.plan_goals_saved_delete_title),
            body = stringResource(R.string.plan_goals_saved_delete_body, doc.title),
            onConfirm = {
                deleting = null
                onEvent(GoalsEvent.DeleteSavedGoal(doc.id))
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SavedGoalRow(doc: SavedGoalSummary, onEvent: (GoalsEvent) -> Unit, onDelete: () -> Unit) {
    val theme = AtharTheme
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(text = doc.title, style = theme.typography.body, color = theme.colors.ink)
        AtharText(
            text = when (doc.state) {
                SavedGoalDocState.LOADABLE ->
                    stringResource(R.string.plan_goals_saved_row_loadable, doc.currency)
                SavedGoalDocState.OTHER_CURRENCY ->
                    stringResource(R.string.plan_goals_saved_row_other_currency, doc.currency)
                SavedGoalDocState.UNSUPPORTED ->
                    stringResource(R.string.plan_goals_saved_row_unsupported)
            },
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            ActionButton(
                label = stringResource(R.string.plan_goals_saved_load),
                container = theme.colors.ember,
                content = theme.colors.parchment,
                modifier = Modifier.weight(1f),
                // Only a version-1 document in the CURRENT literal currency may load.
                enabled = doc.state == SavedGoalDocState.LOADABLE,
                onClick = {
                    keyboard?.hide()
                    onEvent(GoalsEvent.LoadSavedGoal(doc.id))
                },
            )
            ActionButton(
                label = stringResource(R.string.plan_goals_compare_open_short),
                container = Color.Transparent,
                content = theme.colors.ink,
                hairlineBorder = true,
                modifier = Modifier.weight(1f),
                // A comparison must not mix literal currencies either.
                enabled = doc.state == SavedGoalDocState.LOADABLE,
                onClick = { onEvent(GoalsEvent.CompareSavedGoal(doc.id)) },
            )
            ActionButton(
                label = stringResource(R.string.plan_goals_saved_delete),
                container = androidx.compose.ui.graphics.Color.Transparent,
                content = theme.colors.ink,
                hairlineBorder = true,
                modifier = Modifier.weight(1f),
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun NoticeRow(notice: SavedGoalNotice, onDismiss: () -> Unit) {
    val theme = AtharTheme
    val text = when (notice) {
        SavedGoalNotice.Saved -> stringResource(R.string.plan_goals_saved_notice_saved)
        SavedGoalNotice.Updated -> stringResource(R.string.plan_goals_saved_notice_updated)
        SavedGoalNotice.Loaded -> stringResource(R.string.plan_goals_saved_notice_loaded)
        SavedGoalNotice.Deleted -> stringResource(R.string.plan_goals_saved_notice_deleted)
        SavedGoalNotice.NeedsTitle -> stringResource(R.string.plan_goals_saved_notice_needs_title)
        SavedGoalNotice.Conflict -> stringResource(R.string.plan_goals_saved_notice_conflict)
        SavedGoalNotice.UnsupportedDocument -> stringResource(R.string.plan_goals_saved_notice_unsupported)
        SavedGoalNotice.CurrencyMismatch -> stringResource(R.string.plan_goals_saved_notice_currency_mismatch)
        SavedGoalNotice.LoadFailed -> stringResource(R.string.plan_goals_saved_notice_load_failed)
        SavedGoalNotice.SaveFailed -> stringResource(R.string.plan_goals_saved_notice_save_failed)
        SavedGoalNotice.DeleteFailed -> stringResource(R.string.plan_goals_saved_notice_delete_failed)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = text,
            style = theme.typography.caption,
            color = theme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            label = stringResource(R.string.plan_goals_saved_notice_dismiss),
            container = androidx.compose.ui.graphics.Color.Transparent,
            content = theme.colors.muted,
            hairlineBorder = true,
            onClick = onDismiss,
        )
    }
}
