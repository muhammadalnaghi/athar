package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Transaction

/**
 * The whole Today screen as one ordered list of stable-key rows for a single
 * LazyColumn. Understanding before chores: header money, then the real pending
 * financial work with its honest confirmable total, then the quiet review
 * entry, then today's confirmed rows and the month's recent history, and only
 * then the optional category-cleanup chore — history is never blocked behind a
 * non-scrolling column, nothing is nested-lazy, and a substantial pending
 * backlog cannot bury recent history because visible pending rows are capped.
 *
 * Pure and unit-tested in [TodaySectionsTest]; the LazyColumn only renders it.
 */
internal sealed interface TodayRow {
    val key: String

    data object Header : TodayRow {
        override val key: String = "header"
    }

    // Pending tray — genuine financial work awaiting the user's one-tap decision.
    data class PendingHeader(
        val pendingCount: Int,
        val pendingTotal: Money?,
    ) : TodayRow {
        override val key: String = "pending-header"
    }

    data class PendingSuggestionsBulk(val suggestionCount: Int) : TodayRow {
        override val key: String = "pending-suggestions-bulk"
    }

    data object PendingBulkActions : TodayRow {
        override val key: String = "pending-bulk-actions"
    }

    data class PendingTransaction(val tx: Transaction) : TodayRow {
        override val key: String = "pending-${tx.id}"
    }

    data class PendingOverflow(val hiddenCount: Int) : TodayRow {
        override val key: String = "pending-overflow"
    }

    // Compact secondary entries — replace the old ember/dust attention banners.
    data class ReviewHistory(val pendingCount: Int, val dismissedCount: Int) : TodayRow {
        override val key: String = "review-history"
    }

    /** Optional category cleanup — explicitly not framed as unfinished money work. */
    data class ReviewCleanup(val nudge: TodayRepeatedBacklogNudge) : TodayRow {
        override val key: String = "review-cleanup"
    }

    data object EmptyState : TodayRow {
        override val key: String = "empty-state"
    }

    data object TodayHeader : TodayRow {
        override val key: String = "today-header"
    }

    data object TodayEmpty : TodayRow {
        override val key: String = "today-empty"
    }

    data class TodayTransaction(val tx: Transaction) : TodayRow {
        override val key: String = "today-${tx.id}"
    }

    data object RecentHeader : TodayRow {
        override val key: String = "recent-header"
    }

    data class RecentTransaction(val tx: Transaction) : TodayRow {
        override val key: String = "recent-${tx.id}"
    }
}

internal const val MAX_PENDING_VISIBLE: Int = 5

internal const val PENDING_BULK_ACTIONS_THRESHOLD: Int = 5

internal fun buildTodayRows(state: TodayState): List<TodayRow> {
    if (state.isLoading) return emptyList()
    val rows = mutableListOf<TodayRow>(TodayRow.Header)

    if (state.pending.isNotEmpty() && state.pending.size <= MAX_PENDING_VISIBLE) {
        rows += TodayRow.PendingHeader(
            pendingCount = state.pending.size,
            pendingTotal = pendingConfirmableTotal(state.pending),
        )
        if (state.pendingCategorySuggestions.isNotEmpty()) {
            rows += TodayRow.PendingSuggestionsBulk(state.pendingCategorySuggestions.size)
        }
        if (state.pending.size >= PENDING_BULK_ACTIONS_THRESHOLD) {
            rows += TodayRow.PendingBulkActions
        }
        state.pending.take(MAX_PENDING_VISIBLE).forEach { tx ->
            rows += TodayRow.PendingTransaction(tx)
        }
        val hidden = state.pending.size - MAX_PENDING_VISIBLE
        if (hidden > 0) {
            rows += TodayRow.PendingOverflow(hidden)
        }
    }

    if (state.pending.isNotEmpty() || state.dismissedToday.isNotEmpty()) {
        rows += TodayRow.ReviewHistory(
            pendingCount = state.pending.size,
            dismissedCount = state.dismissedToday.size,
        )
    }
    if (state.today.isEmpty() && state.recent.isEmpty()) {
        rows += TodayRow.EmptyState
    } else {
        rows += TodayRow.TodayHeader
        if (state.today.isEmpty()) {
            rows += TodayRow.TodayEmpty
        } else {
            state.today.forEach { tx -> rows += TodayRow.TodayTransaction(tx) }
        }
        if (state.recent.isNotEmpty()) {
            rows += TodayRow.RecentHeader
            state.recent.forEach { tx -> rows += TodayRow.RecentTransaction(tx) }
        }
    }

    // Optional category cleanup is a chore, not money work: it renders after
    // the understanding sections (today + recent) so a substantial backlog can
    // never push the user's own recent history below it, and it never reads as
    // overdue financial work.
    state.repeatedBacklogNudge?.let { nudge ->
        rows += TodayRow.ReviewCleanup(nudge)
    }
    return rows
}

/**
 * Total money sitting in the pending tray, or null when the facts cannot
 * honestly produce it: an empty tray, or rows in more than one currency —
 * Athar never converts. Single-currency trays use the shared exact summation.
 */
internal fun pendingConfirmableTotal(pending: List<Transaction>): Money? {
    val currency = pending.map { it.amount.currency }.distinct().singleOrNull() ?: return null
    return Money.sumAmounts(pending.map { it.amount }, currency)
}
