package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Test
import java.time.YearMonth

/**
 * Behavior tests for the Today single-LazyColumn hierarchy: stable ordered rows,
 * the capped pending tray so recent history stays reachable, understanding
 * sections before the optional cleanup chore, honest single-currency pending
 * totals, compact review/classification entries, and the empty rule.
 */
class TodaySectionsTest {

    private val month = YearMonth.of(2026, 6)

    private fun tx(id: String, status: com.athar.core.domain.model.TxStatus) =
        Fixtures.transaction(id = id, status = status)

    private fun state(
        today: List<com.athar.core.domain.model.Transaction> = emptyList(),
        recent: List<com.athar.core.domain.model.Transaction> = emptyList(),
        pending: List<com.athar.core.domain.model.Transaction> = emptyList(),
        dismissedToday: List<com.athar.core.domain.model.Transaction> = emptyList(),
        backlog: TodayRepeatedBacklogNudge? = null,
    ) = TodayState.empty(month).copy(
        today = today.toPersistentList(),
        recent = recent.toPersistentList(),
        pending = pending.toPersistentList(),
        dismissedToday = dismissedToday.toPersistentList(),
        repeatedBacklogNudge = backlog,
        isLoading = false,
    )

    @Test
    fun `rows follow financial-first order header pending review then history`() {
        val rows = buildTodayRows(
            state(
                today = listOf(tx("t1", com.athar.core.domain.model.TxStatus.CONFIRMED)),
                recent = listOf(tx("r1", com.athar.core.domain.model.TxStatus.CONFIRMED)),
                pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING)),
            ),
        )

        assertThat(rows.map { it.key }).containsExactly(
            "header",
            "pending-header",
            "pending-p1",
            "review-history",
            "today-header",
            "today-t1",
            "recent-header",
            "recent-r1",
        ).inOrder()
    }

    @Test
    fun `unresolved initial state never presents settled zero money or an empty month`() {
        assertThat(buildTodayRows(TodayState.empty(month))).isEmpty()
    }

    @Test
    fun `large backlog is one compact entry before understanding without a money headline`() {
        val rows = buildTodayRows(state(
            pending = (1..1000).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) },
            recent = listOf(tx("recent", com.athar.core.domain.model.TxStatus.CONFIRMED)),
        ))
        assertThat(rows[1]).isInstanceOf(TodayRow.ReviewHistory::class.java)
        assertThat(rows.filterIsInstance<TodayRow.ReviewHistory>().single().pendingCount).isEqualTo(1000)
        assertThat(rows.filterIsInstance<TodayRow.PendingHeader>()).isEmpty()
        assertThat(rows.filterIsInstance<TodayRow.PendingTransaction>()).isEmpty()
        assertThat(rows.map { it.key }).doesNotContain("pending-bulk-actions")
        assertThat(rows.map { it.key }).contains("recent-recent")
    }

    @Test
    fun `keys are unique and stable across rebuilds`() {
        val rich = state(
            today = (1..3).map { tx("t$it", com.athar.core.domain.model.TxStatus.CONFIRMED) },
            recent = (1..3).map { tx("r$it", com.athar.core.domain.model.TxStatus.CONFIRMED) },
            pending = (1..3).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) },
            dismissedToday = listOf(tx("d1", com.athar.core.domain.model.TxStatus.DISMISSED)),
            backlog = TodayRepeatedBacklogNudge(groupCount = 2, transactionCount = 4, largestGroupCount = 3),
        )
        val first = buildTodayRows(rich)
        val second = buildTodayRows(rich)

        assertThat(first).isEqualTo(second)
        assertThat(first.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `pending caps at five visible rows with overflow remainder`() {
        val pending = (1..14).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) }
        val rows = buildTodayRows(state(pending = pending))

        val pendingTxKeys = rows.map { it.key }.filter { it.startsWith("pending-p") }
        // WM030 revises the large-backlog oracle: zero inline previews is within the
        // five-row cap; the complete population is reachable through one compact entry.
        assertThat(pendingTxKeys.size).isAtMost(MAX_PENDING_VISIBLE)
        assertThat(pendingTxKeys).isEmpty()
        assertThat(rows.filterIsInstance<TodayRow.PendingOverflow>()).isEmpty()
        assertThat(rows.filterIsInstance<TodayRow.ReviewHistory>().single().pendingCount).isEqualTo(14)
    }

    @Test
    fun `substantial backlog keeps recent history reachable and cleanup last`() {
        val rows = buildTodayRows(
            state(
                pending = (1..20).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) },
                recent = (1..5).map { tx("r$it", com.athar.core.domain.model.TxStatus.CONFIRMED) },
                backlog = TodayRepeatedBacklogNudge(groupCount = 2, transactionCount = 6, largestGroupCount = 4),
            ),
        )
        val keys = rows.map { it.key }

        // Recent history must start within the first dozen rows, never buried
        // under a large pending tray; the optional cleanup chore renders last.
        assertThat(keys.indexOf("recent-header")).isAtMost(12)
        assertThat(keys.last()).isEqualTo("review-cleanup")
    }

    @Test
    fun `cleanup chore sorts after today and recent understanding sections`() {
        val rows = buildTodayRows(
            state(
                today = listOf(tx("t1", com.athar.core.domain.model.TxStatus.CONFIRMED)),
                recent = listOf(tx("r1", com.athar.core.domain.model.TxStatus.CONFIRMED)),
                pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING)),
                backlog = TodayRepeatedBacklogNudge(groupCount = 1, transactionCount = 2, largestGroupCount = 2),
            ),
        )

        assertThat(rows.map { it.key }).containsExactly(
            "header",
            "pending-header",
            "pending-p1",
            "review-history",
            "today-header",
            "today-t1",
            "recent-header",
            "recent-r1",
            "review-cleanup",
        ).inOrder()
    }

    @Test
    fun `pending header carries single currency confirmable total`() {
        val pending = listOf(
            tx("p1", com.athar.core.domain.model.TxStatus.PENDING).copy(amount = Money.of("10.50")),
            tx("p2", com.athar.core.domain.model.TxStatus.PENDING).copy(amount = Money.of("20.50")),
        )
        val rows = buildTodayRows(state(pending = pending))

        val total = rows.filterIsInstance<TodayRow.PendingHeader>().single().pendingTotal
        assertThat(total).isNotNull()
        assertThat(total!!.amount.compareTo(java.math.BigDecimal("31"))).isEqualTo(0)
        assertThat(total.currency).isEqualTo(pending.first().amount.currency)
    }

    @Test
    fun `pending total is unavailable for mixed currency or empty trays`() {
        val pending = listOf(
            tx("p1", com.athar.core.domain.model.TxStatus.PENDING).copy(amount = Money.of("10.50")),
            tx("p2", com.athar.core.domain.model.TxStatus.PENDING)
                .copy(amount = Money.of(java.math.BigDecimal("5"), "USD")),
        )
        val rows = buildTodayRows(state(pending = pending))

        assertThat(rows.filterIsInstance<TodayRow.PendingHeader>().single().pendingTotal).isNull()
        assertThat(pendingConfirmableTotal(emptyList())).isNull()
    }

    @Test
    fun `review history entry appears only when pending or dismissed exist`() {
        val none = buildTodayRows(state(today = listOf(tx("t1", com.athar.core.domain.model.TxStatus.CONFIRMED))))
        assertThat(none.map { it.key }).doesNotContain("review-history")

        val withPending = buildTodayRows(state(pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING))))
        assertThat(withPending.map { it.key }).contains("review-history")

        val withDismissed = buildTodayRows(
            state(dismissedToday = listOf(tx("d1", com.athar.core.domain.model.TxStatus.DISMISSED))),
        )
        assertThat(withDismissed.map { it.key }).contains("review-history")
    }

    @Test
    fun `review history entry carries pending and dismissed counts`() {
        val rows = buildTodayRows(
            state(
                pending = (1..2).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) },
                dismissedToday = listOf(tx("d1", com.athar.core.domain.model.TxStatus.DISMISSED)),
            ),
        )
        val entry = rows.filterIsInstance<TodayRow.ReviewHistory>().single()
        assertThat(entry.pendingCount).isEqualTo(2)
        assertThat(entry.dismissedCount).isEqualTo(1)
    }

    @Test
    fun `cleanup entry appears only with repeated backlog nudge`() {
        val without = buildTodayRows(state(pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING))))
        assertThat(without.map { it.key }).doesNotContain("review-cleanup")

        val with = buildTodayRows(
            state(
                backlog = TodayRepeatedBacklogNudge(groupCount = 1, transactionCount = 2, largestGroupCount = 2),
            ),
        )
        assertThat(with.map { it.key }).contains("review-cleanup")
    }

    @Test
    fun `empty month shows the empty state while pending stays visible`() {
        val rows = buildTodayRows(state(pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING))))

        assertThat(rows.map { it.key }).contains("empty-state")
        assertThat(rows.map { it.key }).contains("pending-p1")
        assertThat(rows.map { it.key }).doesNotContain("today-header")
    }

    @Test
    fun `empty month without pending shows only header and empty state`() {
        val rows = buildTodayRows(state())

        assertThat(rows.map { it.key }).containsExactly("header", "empty-state").inOrder()
    }

    @Test
    fun `today section reports empty note when no confirmed rows today`() {
        val rows = buildTodayRows(state(recent = listOf(tx("r1", com.athar.core.domain.model.TxStatus.CONFIRMED))))

        assertThat(rows.map { it.key }).containsExactly(
            "header",
            "today-header",
            "today-empty",
            "recent-header",
            "recent-r1",
        ).inOrder()
    }

    @Test
    fun `bulk actions bar appears only at the five pending threshold`() {
        val four = buildTodayRows(
            state(pending = (1..4).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) }),
        )
        assertThat(four.map { it.key }).doesNotContain("pending-bulk-actions")

        val five = buildTodayRows(
            state(pending = (1..5).map { tx("p$it", com.athar.core.domain.model.TxStatus.PENDING) }),
        )
        assertThat(five.map { it.key }).contains("pending-bulk-actions")
    }

    @Test
    fun `bulk suggestion row follows actually available suggestions never an invented count`() {
        val without = buildTodayRows(state(pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING))))
        assertThat(without.map { it.key }).doesNotContain("pending-suggestions-bulk")

        val with = TodayState.empty(month).copy(
            pending = listOf(tx("p1", com.athar.core.domain.model.TxStatus.PENDING)).toPersistentList(),
            pendingCategorySuggestions = persistentMapOf(
                "p1" to PendingCategorySuggestion(categoryId = "cat-1", useCount = 3),
            ),
            pendingCategorySuggestionSummary = persistentListOf(
                PendingCategorySuggestionSummary(categoryId = "cat-1", count = 1),
            ),
            isLoading = false,
        )
        val rows = buildTodayRows(with)
        assertThat(rows.filterIsInstance<TodayRow.PendingSuggestionsBulk>().single().suggestionCount)
            .isEqualTo(1)
    }
}
