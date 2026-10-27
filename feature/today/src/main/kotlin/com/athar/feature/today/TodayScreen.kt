package com.athar.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharEmptyState
import com.athar.core.designsystem.component.AtharFab
import com.athar.core.designsystem.component.AtharListRow
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharSwipeRow
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.Transaction

@Composable
fun TodayScreen(
    onOpenHistory: () -> Unit = {},
    onOpenRepeatedBacklog: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backfill by viewModel.lastBackfill.collectAsStateWithLifecycle()
    val pendingSuggestionApply by viewModel.lastPendingSuggestionApply.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }

    LaunchedEffect(backfill) {
        if (backfill != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.clearBackfill()
        }
    }

    LaunchedEffect(pendingSuggestionApply) {
        if (pendingSuggestionApply != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.clearPendingSuggestionApply()
        }
    }

    TodayContent(
        state = state,
        backfill = backfill,
        pendingSuggestionApply = pendingSuggestionApply,
        onClearBackfill = viewModel::clearBackfill,
        onClearPendingSuggestionApply = viewModel::clearPendingSuggestionApply,
        onEvent = { event ->
            when (event) {
                is TodayEvent.AddManual -> showAddSheet = true
                is TodayEvent.OpenHistory -> onOpenHistory()
                is TodayEvent.OpenRepeatedBacklog -> onOpenRepeatedBacklog()
                is TodayEvent.OpenTransaction -> {
                    editing = state.today.firstOrNull { it.id == event.id }
                        ?: state.recent.firstOrNull { it.id == event.id }
                        ?: state.pending.firstOrNull { it.id == event.id }
                }
                else -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
    )

    if (showAddSheet) {
        AddTransactionSheet(
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false },
        )
    }

    editing?.let { tx ->
        key(tx.id) {
            EditTransactionSheet(
                transaction = tx,
                onDismiss = { editing = null },
                onSave = { updated, learnRule ->
                    viewModel.updateTransaction(updated, learnRule)
                    editing = null
                },
                onDelete = { id ->
                    viewModel.deleteTransaction(id)
                    editing = null
                },
            )
        }
    }
}

/**
 * One coherent LazyColumn: the header money, the pending tray (real financial
 * work), the quiet review entries, then today's confirmed rows and the month's
 * recent history — all stable-keyed, nothing nested-lazy, nothing blocked behind
 * a non-scrolling column. See [buildTodayRows] for the tested ordering rules.
 */
@Composable
internal fun TodayContent(
    state: TodayState,
    onEvent: (TodayEvent) -> Unit,
    modifier: Modifier = Modifier,
    backfill: TodayViewModel.BackfillEvent? = null,
    pendingSuggestionApply: TodayViewModel.PendingSuggestionApplyEvent? = null,
    onClearBackfill: () -> Unit = {},
    onClearPendingSuggestionApply: () -> Unit = {},
) {
    val theme = AtharTheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.colors.parchment),
    ) {
        val rows = remember(state) { buildTodayRows(state) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
        ) {
            if (state.isLoading) item(key = "today-loading") {
                AtharText(
                    text = stringResource(R.string.today_loading),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
            backfill?.let {
                item(key = "toast-backfill") {
                    CategoryBackfillToast(
                        pattern = it.pattern,
                        count = it.count,
                        onDismiss = onClearBackfill,
                    )
                }
            }
            pendingSuggestionApply?.let {
                item(key = "toast-suggestion-apply") {
                    PendingSuggestionApplyToast(
                        count = it.count,
                        onDismiss = onClearPendingSuggestionApply,
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                TodayRowItem(
                    row = row,
                    state = state,
                    onEvent = onEvent,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (!state.isLoading) {
            // A real footer, not an overlay: no History action or monetary row can sit behind Add.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = theme.spacing.l, vertical = theme.spacing.s),
                contentAlignment = Alignment.CenterEnd,
            ) {
                AtharFab(onClick = { onEvent(TodayEvent.AddManual) })
            }
        }
    }
}

@Composable
private fun TodayRowItem(
    row: TodayRow,
    state: TodayState,
    onEvent: (TodayEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    when (row) {
        TodayRow.Header -> Header(state, onOpenHistory = { onEvent(TodayEvent.OpenHistory) })

        is TodayRow.PendingHeader -> PendingTrayHeader(
            pendingCount = row.pendingCount,
        )
        is TodayRow.PendingSuggestionsBulk -> PendingSuggestionsBulkAction(
            count = row.suggestionCount,
            summary = state.pendingCategorySuggestionSummary,
            categoryLabels = state.categoryLabels,
            onClick = { onEvent(TodayEvent.BulkApplyPendingCategorySuggestions) },
        )
        TodayRow.PendingBulkActions -> BulkActionsBar(onEvent = onEvent)
        is TodayRow.PendingTransaction -> AtharSwipeRow(
            onConfirm = { onEvent(TodayEvent.ConfirmPending(row.tx.id)) },
            onDismiss = { onEvent(TodayEvent.DismissPending(row.tx.id)) },
        ) {
            PendingTransactionRow(
                tx = row.tx,
                suggestion = state.pendingCategorySuggestions[row.tx.id],
                categoryLabels = state.categoryLabels,
                onOpen = { onEvent(TodayEvent.OpenTransaction(row.tx.id)) },
                onApplySuggestion = { onEvent(TodayEvent.ApplyPendingCategorySuggestion(row.tx.id)) },
            )
        }
        is TodayRow.PendingOverflow -> AtharText(
            text = stringResource(R.string.today_pending_more, row.hiddenCount),
            style = theme.typography.caption,
            color = theme.colors.muted,
            modifier = Modifier.padding(vertical = theme.spacing.s),
        )

        is TodayRow.ReviewHistory -> ReviewHistoryEntryRow(
            pendingCount = row.pendingCount,
            dismissedCount = row.dismissedCount,
            onClick = { onEvent(TodayEvent.OpenHistory) },
        )
        is TodayRow.ReviewCleanup -> ReviewCleanupEntryRow(
            nudge = row.nudge,
            onClick = { onEvent(TodayEvent.OpenRepeatedBacklog) },
        )

        TodayRow.EmptyState -> AtharEmptyState(
            text = stringResource(R.string.today_empty_text),
            subtle = stringResource(R.string.today_empty_subtle),
        )

        TodayRow.TodayHeader -> TodaySectionHeader(
            net = state.todayNet,
            showNet = state.today.isNotEmpty(),
        )
        TodayRow.TodayEmpty -> AtharText(
            text = stringResource(R.string.today_no_today_transactions),
            style = theme.typography.body,
            color = theme.colors.muted,
            modifier = Modifier.padding(vertical = theme.spacing.s),
        )
        is TodayRow.TodayTransaction -> AtharListRow(
            modifier = modifier,
            title = row.tx.merchant,
            subtitle = transactionCategoryLabel(row.tx, state.categoryLabels),
            trailing = row.tx.amount,
            onClick = { onEvent(TodayEvent.OpenTransaction(row.tx.id)) },
        )

        TodayRow.RecentHeader -> AtharText(
            text = stringResource(R.string.today_section_recent),
            style = theme.typography.overline,
            color = theme.colors.muted,
            modifier = Modifier.padding(top = theme.spacing.m),
        )
        is TodayRow.RecentTransaction -> AtharListRow(
            modifier = modifier,
            title = row.tx.merchant,
            subtitle = transactionCategoryLabel(row.tx, state.categoryLabels),
            trailing = row.tx.amount,
            onClick = { onEvent(TodayEvent.OpenTransaction(row.tx.id)) },
        )
    }
}
