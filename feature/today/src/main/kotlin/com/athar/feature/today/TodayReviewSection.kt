package com.athar.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.HairlineThickness
import com.athar.core.designsystem.theme.MinTouchTarget

/**
 * Quiet review entries that replace the old full-width attention banners. The
 * pending money itself stays visible in the tray above; these rows only route to
 * History and keep optional category cleanup visually separate from real
 * financial review — calm text on parchment with a hairline, never a demanding
 * color block, and no crimson anywhere.
 */
@Composable
internal fun ReviewHistoryEntryRow(
    pendingCount: Int,
    dismissedCount: Int,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    ReviewEntryRow(
        title = stringResource(
            if (pendingCount == 0 && dismissedCount == 0) R.string.history_overline
            else R.string.today_review_history_title,
        ),
        detail = reviewCountsText(pendingCount, dismissedCount),
        action = stringResource(R.string.today_review_open),
        actionColor = theme.colors.ember,
        onClick = onClick,
    )
}

@Composable
internal fun ReviewCleanupEntryRow(
    nudge: TodayRepeatedBacklogNudge,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    ReviewEntryRow(
        title = stringResource(R.string.today_review_cleanup_title),
        detail = stringResource(
            R.string.today_review_cleanup_detail,
            nudge.transactionCount,
            nudge.groupCount,
            nudge.largestGroupCount,
        ) + " · " + stringResource(R.string.today_w_cleanup_optional_badge),
        action = stringResource(R.string.today_review_cleanup_open),
        actionColor = theme.colors.muted,
        onClick = onClick,
    )
}

@Composable
private fun ReviewEntryRow(
    title: String,
    detail: String,
    action: String,
    actionColor: Color,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Column {
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MinTouchTarget)
                .clickable(onClick = onClick)
                .padding(horizontal = theme.spacing.s, vertical = theme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
            ) {
                AtharText(text = title, style = theme.typography.headline)
                if (detail.isNotEmpty()) {
                    AtharText(text = detail, style = theme.typography.caption, color = theme.colors.muted)
                }
            }
            AtharText(text = action, style = theme.typography.caption, color = actionColor)
        }
    }
}

@Composable
private fun reviewCountsText(pendingCount: Int, dismissedCount: Int): String =
    listOf(
        if (pendingCount > 0) stringResource(R.string.today_review_counts_pending, pendingCount) else null,
        if (dismissedCount > 0) stringResource(R.string.today_review_counts_dismissed, dismissedCount) else null,
    ).filterNotNull().joinToString(separator = " · ")

@Composable
private fun HairlineDivider(modifier: Modifier = Modifier) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HairlineThickness)
            .background(theme.colors.divider),
    )
}
