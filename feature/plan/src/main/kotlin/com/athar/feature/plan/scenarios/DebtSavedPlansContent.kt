package com.athar.feature.plan.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.feature.plan.R
import com.athar.feature.plan.goals.ActionButton
import java.time.Instant

/**
 * Saved exact-terms documents for the debt scenario: save-as-new, update of the
 * loaded plan at its captured revision, load, rename, explicit-confirm delete
 * and cancel paths. Unavailable records stay listed and retained.
 */
@Composable
internal fun DebtSavedPlans(state: DebtScenarioState, onEvent: (DebtScenarioEvent) -> Unit) {
    val theme = AtharTheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<DebtSavedEntry?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.m)) {
        HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
        AtharText(stringResource(R.string.debt_saved_header), style = theme.typography.headline)
        ScenarioInput(state.saveTitle, stringResource(R.string.debt_saved_title_label), state.message == DebtSaveMessage.TITLE_INVALID) {
            onEvent(DebtScenarioEvent.EditSaveTitle(it))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            ActionButton(
                stringResource(if (state.saving) R.string.debt_saved_working else R.string.debt_saved_save_new),
                theme.colors.ember, theme.colors.parchment, Modifier.weight(1f), enabled = !state.saving,
            ) {
                focus.clearFocus()
                keyboard?.hide()
                onEvent(DebtScenarioEvent.SaveAsNew)
            }
            ActionButton(
                stringResource(R.string.debt_saved_update),
                theme.colors.surface, theme.colors.ink, Modifier.weight(1f), hairlineBorder = true,
                enabled = !state.saving && state.loaded != null,
            ) {
                focus.clearFocus()
                keyboard?.hide()
                onEvent(DebtScenarioEvent.UpdateSaved)
            }
        }
        state.loaded?.let { loaded ->
            AtharText(
                stringResource(R.string.debt_saved_loaded, loaded.title),
                style = theme.typography.caption, color = theme.colors.olive,
            )
        }
        state.message?.let { message ->
            AtharText(
                saveMessageText(message),
                style = theme.typography.caption,
                color = if (message == DebtSaveMessage.SAVED || message == DebtSaveMessage.UPDATED ||
                    message == DebtSaveMessage.RENAMED || message == DebtSaveMessage.DELETED
                ) theme.colors.olive else theme.colors.dust,
                modifier = Modifier.fillMaxWidth(),
            )
            ScenarioTextAction(stringResource(R.string.debt_saved_dismiss)) { onEvent(DebtScenarioEvent.DismissMessage) }
        }
        if (state.saved.isEmpty()) {
            AtharText(stringResource(R.string.debt_saved_empty), style = theme.typography.caption, color = theme.colors.muted)
        }
        state.saved.forEach { entry ->
            HorizontalDivider(thickness = HairlineThickness, color = theme.colors.divider)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AtharText(entry.plan.title, style = theme.typography.body)
                    AtharText(
                        if (entry.available) {
                            stringResource(
                                R.string.debt_saved_meta,
                                entry.plan.currency,
                                Instant.ofEpochMilli(entry.plan.updatedAtEpochMillis).toString().take(10),
                            )
                        } else {
                            stringResource(R.string.debt_saved_unavailable, entry.plan.currency)
                        },
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
                if (state.loaded?.id == entry.plan.id) {
                    AtharText(stringResource(R.string.debt_saved_loaded_badge), style = theme.typography.caption, color = theme.colors.olive)
                }
            }
            Row {
                ScenarioTextAction(stringResource(R.string.debt_saved_load), enabled = entry.available) {
                    onEvent(DebtScenarioEvent.LoadSaved(entry.plan.id))
                }
                ScenarioTextAction(stringResource(R.string.debt_saved_rename)) {
                    renameTarget = entry.plan.id
                    renameText = entry.plan.title
                }
                ScenarioTextAction(stringResource(R.string.debt_saved_delete)) { deleteTarget = entry }
            }
        }
    }
    renameTarget?.let { id ->
        val valid = renameText.trim().isNotEmpty() && renameText.trim().length <= 64
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { AtharText(stringResource(R.string.debt_saved_rename_title), style = AtharTheme.typography.headline) },
            text = {
                AtharTextField(renameText, { renameText = it }, stringResource(R.string.debt_saved_title_label), Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onEvent(DebtScenarioEvent.RenameSaved(id, renameText))
                    renameTarget = null
                }) { AtharText(stringResource(R.string.debt_saved_confirm), color = AtharTheme.colors.ember) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { AtharText(stringResource(R.string.debt_saved_cancel)) }
            },
            containerColor = AtharTheme.colors.parchment,
        )
    }
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { AtharText(stringResource(R.string.debt_saved_delete_title), style = AtharTheme.typography.headline) },
            text = {
                AtharText(
                    stringResource(R.string.debt_saved_delete_body, entry.plan.title),
                    style = AtharTheme.typography.body, color = AtharTheme.colors.muted,
                )
            },
            confirmButton = {
                TextButton(enabled = !state.saving, onClick = {
                    onEvent(DebtScenarioEvent.DeleteSaved(entry.plan.id))
                    deleteTarget = null
                }) { AtharText(stringResource(R.string.debt_saved_confirm), color = AtharTheme.colors.crimson) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { AtharText(stringResource(R.string.debt_saved_cancel)) }
            },
            containerColor = AtharTheme.colors.parchment,
        )
    }
}

@Composable
private fun saveMessageText(message: DebtSaveMessage): String = stringResource(when (message) {
    DebtSaveMessage.SAVED -> R.string.debt_saved_msg_saved
    DebtSaveMessage.UPDATED -> R.string.debt_saved_msg_updated
    DebtSaveMessage.RENAMED -> R.string.debt_saved_msg_renamed
    DebtSaveMessage.DELETED -> R.string.debt_saved_msg_deleted
    DebtSaveMessage.CONFLICT -> R.string.debt_saved_msg_conflict
    DebtSaveMessage.FAILED -> R.string.debt_saved_msg_failed
    DebtSaveMessage.TITLE_INVALID -> R.string.debt_saved_msg_title
    DebtSaveMessage.UNAVAILABLE -> R.string.debt_saved_msg_unavailable
})
