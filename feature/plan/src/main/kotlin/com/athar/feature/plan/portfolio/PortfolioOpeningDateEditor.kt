package com.athar.feature.plan.portfolio

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.InvestmentEditorSheet
import com.athar.feature.plan.InvestmentPrimaryButton
import com.athar.feature.plan.InvestmentWriteMessage
import com.athar.feature.plan.InvestmentWriteProblem
import com.athar.feature.plan.InvestmentsEvent
import com.athar.feature.plan.PortfolioOpeningDatePreviewUi
import com.athar.feature.plan.R

/** Failed review keeps the exact draft here; only a successful preview closes this sheet. */
@Composable
internal fun PortfolioOpeningDateSheet(
    currentDate: String?,
    problem: String?,
    writeProblem: InvestmentWriteProblem?,
    onDismiss: () -> Unit,
    onReview: (String) -> Unit,
    initialText: String = currentDate.orEmpty(),
) {
    val theme = AtharTheme
    var date by remember(currentDate, initialText) { mutableStateOf(initialText) }
    var attempted by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val invalid = date.isNotEmpty() && PortfolioInputText.date(date)?.toString() != date
    val unchanged = date == currentDate.orEmpty()
    InvestmentEditorSheet(stringResource(R.string.plan_portfolio_opening_date_edit_title), onDismiss) {
        AtharTextField(
            value = date,
            onValueChange = { date = it; submitted = null },
            label = stringResource(R.string.plan_portfolio_opening_date_label),
            modifier = Modifier.fillMaxWidth(),
            isError = attempted && (invalid || unchanged),
            supportingText = stringResource(
                when {
                    attempted && invalid -> R.string.plan_portfolio_opening_date_invalid
                    attempted && unchanged -> R.string.plan_portfolio_opening_date_unchanged
                    else -> R.string.plan_portfolio_opening_date_hint
                },
            ),
        )
        AtharText(
            stringResource(R.string.plan_portfolio_opening_date_clear_note),
            style = theme.typography.caption, color = theme.colors.muted,
        )
        if (submitted == date) {
            problem?.let { PortfolioRejectMessage(it) }
            writeProblem?.let { InvestmentWriteMessage(it) }
        }
        InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_opening_date_review)) {
            attempted = true
            if (!invalid && !unchanged) {
                submitted = date
                onReview(date)
            }
        }
    }
}

/** Review has no side effects; the explicit save is bound to this immutable token. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PortfolioOpeningDatePreviewBlock(preview: PortfolioOpeningDatePreviewUi, onEvent: (InvestmentsEvent) -> Unit) {
    val theme = AtharTheme
    val requester = remember { BringIntoViewRequester() }
    var placed by remember(preview.token) { mutableStateOf(false) }
    LaunchedEffect(preview.token, placed) {
        if (placed) requester.bringIntoView()
    }
    Column(
        modifier = Modifier.bringIntoViewRequester(requester).onGloballyPositioned { placed = true },
        verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        AtharText(stringResource(R.string.plan_portfolio_opening_date_preview_title), style = theme.typography.body)
        AtharText(
            preview.currentText?.let { stringResource(R.string.plan_portfolio_opening_date_preview_current, it) }
                ?: stringResource(R.string.plan_portfolio_opening_date_none),
            style = theme.typography.caption, color = theme.colors.muted,
        )
        AtharText(
            if (preview.draftText.isEmpty()) stringResource(R.string.plan_portfolio_opening_date_none)
            else stringResource(R.string.plan_portfolio_opening_date_preview_new, preview.draftText),
            style = theme.typography.caption,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                PortfolioPreviewTotalsColumn(stringResource(R.string.plan_portfolio_preview_before), preview.before, preview.currency)
            }
            Column(modifier = Modifier.weight(1f)) {
                PortfolioPreviewTotalsColumn(stringResource(R.string.plan_portfolio_preview_after), preview.after, preview.currency)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            PortfolioTextAction(stringResource(R.string.plan_portfolio_preview_save)) {
                onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(preview.token))
            }
            PortfolioTextAction(stringResource(R.string.plan_portfolio_preview_cancel)) {
                onEvent(InvestmentsEvent.CancelPortfolioOpeningDatePreview)
            }
        }
    }
}
