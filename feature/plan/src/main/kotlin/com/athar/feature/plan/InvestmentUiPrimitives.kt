package com.athar.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme

@Composable
internal fun InvestmentPrimaryButton(text: String, onClick: () -> Unit) {
    val theme = AtharTheme
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.ember).clickable(role = Role.Button, onClick = onClick).padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) { AtharText(text, style = theme.typography.headline, color = theme.colors.parchment) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InvestmentEditorSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = AtharTheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(title, style = theme.typography.headline)
            content()
        }
    }
}

@Composable
internal fun InvestmentDeleteConfirm(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val theme = AtharTheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { AtharText(title, style = theme.typography.headline) },
        text = { AtharText(body, style = theme.typography.body, color = theme.colors.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                AtharText(stringResource(R.string.plan_investments_delete_confirm), color = theme.colors.crimson)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { AtharText(stringResource(R.string.plan_investments_delete_cancel)) }
        },
        containerColor = theme.colors.parchment,
    )
}

@Composable
internal fun InvestmentWriteMessage(problem: InvestmentWriteProblem) {
    val text = when (problem) {
        InvestmentWriteProblem.INVALID_INPUT -> R.string.plan_investments_invalid_input
        InvestmentWriteProblem.UNSUPPORTED_AMOUNT -> R.string.plan_investments_unsupported_amount
        InvestmentWriteProblem.UNSUPPORTED_ALLOCATION -> R.string.plan_investments_allocation_unavailable
        InvestmentWriteProblem.STORAGE -> R.string.plan_investments_storage_error
        InvestmentWriteProblem.CONFLICT -> R.string.plan_portfolio_problem_conflict
        InvestmentWriteProblem.UNSUPPORTED_DOCUMENT -> R.string.plan_portfolio_problem_unsupported_document
    }
    AtharText(stringResource(text), style = AtharTheme.typography.caption, color = AtharTheme.colors.muted)
}
