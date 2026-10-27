package com.athar.feature.today

import com.athar.core.designsystem.display.currentUiLocale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharListRow
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

/**
 * Pending tray pieces: section header, safe-suggestion bulk action (optional
 * classification cleanup), bulk bars, and the per-row suggestion line. The tray
 * rows themselves are ordinary LazyColumn items — see TodayScreen.
 */
@Composable
internal fun PendingTrayHeader(
    pendingCount: Int,
) {
    val theme = AtharTheme
    Column(
        modifier = Modifier.padding(top = theme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(
            text = stringResource(R.string.today_pending_title, pendingCount),
            style = theme.typography.headline,
        )
        // Pending shapes are not verified obligations: never headline their raw amount sum.
        AtharText(
            text = stringResource(R.string.today_pending_subtle),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
internal fun PendingSuggestionApplyToast(
    count: Int,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier.clickable(onClick = onDismiss)) {
        AtharText(
            text = stringResource(R.string.today_pending_suggestions_applied, count),
            style = theme.typography.body,
            color = theme.colors.olive,
        )
    }
}

@Composable
internal fun PendingSuggestionsBulkAction(
    count: Int,
    summary: ImmutableList<PendingCategorySuggestionSummary>,
    categoryLabels: ImmutableMap<String, CategoryLabel>,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = theme.spacing.xs)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.olive)
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(
            text = stringResource(R.string.today_pending_apply_safe_suggestions, count),
            style = theme.typography.caption,
            color = theme.colors.parchment,
        )
        PendingSuggestionSummaryText(
            summary = summary,
            categoryLabels = categoryLabels,
        )
    }
}

@Composable
private fun PendingSuggestionSummaryText(
    summary: ImmutableList<PendingCategorySuggestionSummary>,
    categoryLabels: ImmutableMap<String, CategoryLabel>,
) {
    val theme = AtharTheme
    val visible = summary.take(MAX_PENDING_SUGGESTION_SUMMARY_ITEMS)
    val summaryItems = visible.map { item ->
        stringResource(
            R.string.today_pending_safe_suggestion_summary_item,
            categoryLabelText(item.categoryId, categoryLabels),
            item.count,
        )
    }.toMutableList()
    val hiddenCategoryCount = summary.size - visible.size
    if (hiddenCategoryCount > 0) {
        summaryItems += stringResource(
            R.string.today_pending_safe_suggestion_summary_more,
            hiddenCategoryCount,
        )
    }
    if (summaryItems.isEmpty()) return

    AtharText(
        text = stringResource(
            R.string.today_pending_safe_suggestions_summary,
            summaryItems.joinToString(separator = " · "),
        ),
        style = theme.typography.caption,
        color = theme.colors.parchment,
    )
}

@Composable
internal fun PendingTransactionRow(
    tx: com.athar.core.domain.model.Transaction,
    suggestion: PendingCategorySuggestion?,
    categoryLabels: ImmutableMap<String, CategoryLabel>,
    onOpen: () -> Unit,
    onApplySuggestion: () -> Unit,
) {
    val theme = AtharTheme
    Column {
        AtharListRow(
            title = tx.merchant,
            subtitle = transactionCategoryLabel(tx, categoryLabels),
            trailing = tx.amount,
            onClick = onOpen,
        )
        if (suggestion != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = theme.spacing.m,
                        end = theme.spacing.m,
                        bottom = theme.spacing.s,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                AtharText(
                    text = stringResource(
                        R.string.today_pending_category_suggestion,
                        categoryLabelText(suggestion.categoryId, categoryLabels),
                        suggestion.useCount,
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                    modifier = Modifier.weight(1f),
                )
                SuggestionButton(onClick = onApplySuggestion)
            }
        }
    }
}

@Composable
private fun SuggestionButton(onClick: () -> Unit) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.olive)
            .clickable(onClick = onClick)
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = stringResource(R.string.today_pending_apply_suggestion),
            style = theme.typography.caption,
            color = theme.colors.parchment,
        )
    }
}

@Composable
internal fun BulkActionsBar(onEvent: (TodayEvent) -> Unit) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = theme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        BulkButton(
            text = stringResource(R.string.today_bulk_confirm_confident),
            background = theme.colors.olive,
            onClick = { onEvent(TodayEvent.BulkConfirmConfident) },
            modifier = Modifier.weight(1f),
        )
        BulkButton(
            text = stringResource(R.string.today_bulk_dismiss_low),
            background = theme.colors.dust,
            onClick = { onEvent(TodayEvent.BulkDismissLowConfidence) },
            modifier = Modifier.weight(1f),
        )
        BulkButton(
            text = stringResource(R.string.today_bulk_dismiss_all),
            background = theme.colors.crimson,
            onClick = { onEvent(TodayEvent.BulkDismissAll) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BulkButton(
    text: String,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(background)
            .clickable(onClick = onClick)
            .padding(theme.spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = text, style = theme.typography.caption, color = theme.colors.parchment)
    }
}

private const val MAX_PENDING_SUGGESTION_SUMMARY_ITEMS = 3

@Composable
internal fun categoryLabelText(
    categoryId: String,
    labels: ImmutableMap<String, CategoryLabel>,
): String {
    val label = labels[categoryId] ?: return categoryId
    val isArabic = currentUiLocale().language == "ar"
    return if (isArabic) {
        label.nameAr.ifBlank { label.name }
    } else {
        label.name.ifBlank { label.nameAr }
    }
}
