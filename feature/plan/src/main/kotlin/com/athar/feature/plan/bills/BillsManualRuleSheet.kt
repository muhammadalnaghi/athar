package com.athar.feature.plan.bills

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.feature.plan.BillCadenceLabel
import com.athar.feature.plan.BillsManualRuleError
import com.athar.feature.plan.ManualCommitmentDraft
import com.athar.feature.plan.ManualRuleEditorState
import com.athar.feature.plan.ManualRuleStatus
import com.athar.feature.plan.R

/**
 * Explicit manual-commitment editor (WM033). Renders the live [ManualRuleEditorState]
 * draft: real input on every field, one primary save action, one cancel action. While a
 * save is in flight the save control is disabled — the ViewModel's synchronous guard
 * makes a duplicate submit a no-op regardless. A typed failure is shown on the exact
 * offending field (a storage failure as a general banner) with the draft kept verbatim,
 * so resubmitting retries the same commitment. Cancel/scrim discards the draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualRuleEditorSheet(
    editor: ManualRuleEditorState,
    onUpdateDraft: (ManualCommitmentDraft) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val failure = (editor.status as? ManualRuleStatus.Failed)?.error
    val saving = editor.status is ManualRuleStatus.Saving

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(
                text = stringResource(R.string.plan_bills033_editor_title),
                style = theme.typography.headline,
            )

            AtharTextField(
                value = editor.draft.name,
                onValueChange = { onUpdateDraft(editor.draft.copy(name = it)) },
                label = stringResource(R.string.plan_bills033_field_name),
                modifier = Modifier.fillMaxWidth(),
                isError = failure == BillsManualRuleError.NAME_REQUIRED,
                supportingText = failure
                    ?.takeIf { it == BillsManualRuleError.NAME_REQUIRED }
                    ?.let { stringResource(R.string.plan_bills033_error_name) },
            )

            AtharTextField(
                value = editor.draft.amountText,
                onValueChange = { onUpdateDraft(editor.draft.copy(amountText = it)) },
                label = stringResource(R.string.plan_bills033_field_amount),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Decimal,
                isError = failure in AMOUNT_FIELD_ERRORS,
                supportingText = when (failure) {
                    BillsManualRuleError.AMOUNT_NOT_POSITIVE ->
                        stringResource(R.string.plan_bills033_error_amount_positive)
                    BillsManualRuleError.AMOUNT_PRECISION_UNSUPPORTED ->
                        stringResource(R.string.plan_bills033_error_precision)
                    BillsManualRuleError.AMOUNT_INVALID ->
                        stringResource(R.string.plan_bills033_error_amount)
                    else -> null
                },
            )

            AtharTextField(
                value = editor.draft.currency,
                onValueChange = { onUpdateDraft(editor.draft.copy(currency = it.uppercase())) },
                label = stringResource(R.string.plan_bills033_field_currency),
                modifier = Modifier.fillMaxWidth(),
                isError = failure == BillsManualRuleError.CURRENCY_INVALID,
                supportingText = failure
                    ?.takeIf { it == BillsManualRuleError.CURRENCY_INVALID }
                    ?.let { stringResource(R.string.plan_bills033_error_currency) },
            )

            AtharText(
                text = stringResource(R.string.plan_bills033_cadence_title),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.xs),
            ) {
                BillCadenceLabel.entries.forEach { cadence ->
                    CadenceChip(
                        label = cadenceLabel(cadence),
                        selected = editor.draft.cadence == cadence,
                        onClick = { onUpdateDraft(editor.draft.copy(cadence = cadence)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            AtharTextField(
                value = editor.draft.nextDateText,
                onValueChange = { onUpdateDraft(editor.draft.copy(nextDateText = it)) },
                label = stringResource(R.string.plan_bills033_field_next_date),
                modifier = Modifier.fillMaxWidth(),
                placeholder = "2026-12-01",
                isError = failure == BillsManualRuleError.DATE_INVALID,
                supportingText = failure
                    ?.takeIf { it == BillsManualRuleError.DATE_INVALID }
                    ?.let { stringResource(R.string.plan_bills033_error_date) },
            )

            AtharTextField(
                value = editor.draft.notes,
                onValueChange = { onUpdateDraft(editor.draft.copy(notes = it)) },
                label = stringResource(R.string.plan_bills033_field_notes),
                modifier = Modifier.fillMaxWidth(),
            )

            failure?.let {
                if (it == BillsManualRuleError.SAVE_FAILED) {
                    AtharText(
                        text = stringResource(R.string.plan_bills033_error_save),
                        style = theme.typography.caption,
                        color = theme.colors.crimson,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(if (saving) theme.colors.divider else theme.colors.ember)
                    .then(if (saving) Modifier else Modifier.clickable(onClick = onSubmit))
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(
                    text = stringResource(R.string.plan_bills033_save),
                    style = theme.typography.headline,
                    color = if (saving) theme.colors.muted else theme.colors.parchment,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.divider)
                    .clickable(onClick = onDismiss)
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(
                    text = stringResource(R.string.plan_bills033_cancel),
                    style = theme.typography.body,
                    color = theme.colors.ink,
                )
            }
        }
    }
}

private val AMOUNT_FIELD_ERRORS = setOf(
    BillsManualRuleError.AMOUNT_INVALID,
    BillsManualRuleError.AMOUNT_NOT_POSITIVE,
    BillsManualRuleError.AMOUNT_PRECISION_UNSUPPORTED,
)

@Composable
private fun cadenceLabel(cadence: BillCadenceLabel): String = when (cadence) {
    BillCadenceLabel.MONTHLY -> stringResource(R.string.plan_bills_cadence_monthly)
    BillCadenceLabel.WEEKLY -> stringResource(R.string.plan_bills_cadence_weekly)
    BillCadenceLabel.QUARTERLY -> stringResource(R.string.plan_bills_cadence_quarterly)
    BillCadenceLabel.YEARLY -> stringResource(R.string.plan_bills_cadence_yearly)
}

@Composable
private fun CadenceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (selected) theme.colors.ember else theme.colors.divider)
            .clickable(onClick = onClick)
            .padding(horizontal = theme.spacing.xs, vertical = theme.spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = label,
            style = theme.typography.caption,
            color = if (selected) theme.colors.parchment else theme.colors.ink,
        )
    }
}
