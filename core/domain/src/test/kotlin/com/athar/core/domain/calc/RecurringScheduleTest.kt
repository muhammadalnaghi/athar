package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class RecurringScheduleTest {

    @Test
    fun `monthly rules clamp to shorter months`() {
        val next = RecurringSchedule.nextRunAfter(
            fromDate = LocalDate(2026, 1, 31),
            cadence = Cadence.MONTHLY,
            dayOfMonth = 31,
            dayOfWeek = null,
            monthOfYear = null,
        )

        assertThat(next).isEqualTo(LocalDate(2026, 2, 28))
    }

    @Test
    fun `weekly rules land on configured day of week`() {
        val next = RecurringSchedule.nextRunAfter(
            fromDate = LocalDate(2026, 6, 13),
            cadence = Cadence.WEEKLY,
            dayOfMonth = null,
            dayOfWeek = 1,
            monthOfYear = null,
        )

        assertThat(next).isEqualTo(LocalDate(2026, 6, 15))
    }

    @Test
    fun `yearly rules use configured month and day`() {
        val next = RecurringSchedule.nextRunAfter(
            fromDate = LocalDate(2026, 6, 13),
            cadence = Cadence.YEARLY,
            dayOfMonth = 29,
            dayOfWeek = null,
            monthOfYear = 2,
        )

        assertThat(next).isEqualTo(LocalDate(2027, 2, 28))
    }

    @Test
    fun `project returns active occurrences through horizon`() {
        val occurrences = RecurringSchedule.project(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 5,
                nextRunDate = LocalDate(2026, 6, 5),
            ),
            today = LocalDate(2026, 6, 1),
            horizonDays = 65,
        )

        assertThat(occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 6, 5),
            LocalDate(2026, 7, 5),
            LocalDate(2026, 8, 5),
        ).inOrder()
        assertThat(occurrences.any { it.isOverdue }).isFalse()
    }

    @Test
    fun `project includes one overdue occurrence then upcoming occurrences`() {
        val occurrences = RecurringSchedule.project(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 10,
                nextRunDate = LocalDate(2026, 5, 10),
            ),
            today = LocalDate(2026, 6, 13),
            horizonDays = 45,
        )

        assertThat(occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 5, 10),
            LocalDate(2026, 6, 10),
            LocalDate(2026, 7, 10),
        ).inOrder()
        assertThat(occurrences.take(2).all { it.isOverdue }).isTrue()
        assertThat(occurrences.last().isOverdue).isFalse()
    }

    @Test
    fun `inactive rules project no occurrences`() {
        val occurrences = RecurringSchedule.project(
            rule = rule(isActive = false),
            today = LocalDate(2026, 6, 13),
        )

        assertThat(occurrences).isEmpty()
    }

    @Test
    fun `quarterly rules advance three months with month-end clamping`() {
        val next = RecurringSchedule.nextRunAfter(
            fromDate = LocalDate(2026, 1, 31),
            cadence = Cadence.QUARTERLY,
            dayOfMonth = 31,
            dayOfWeek = null,
            monthOfYear = null,
        )

        assertThat(next).isEqualTo(LocalDate(2026, 4, 30))
    }

    @Test
    fun `quarterly rules clamp across february`() {
        val next = RecurringSchedule.nextRunAfter(
            fromDate = LocalDate(2025, 11, 30),
            cadence = Cadence.QUARTERLY,
            dayOfMonth = 30,
            dayOfWeek = null,
            monthOfYear = null,
        )

        assertThat(next).isEqualTo(LocalDate(2026, 2, 28))
    }

    @Test
    fun `quarterly projection includes overdue occurrence then quarterly steps`() {
        val occurrences = RecurringSchedule.project(
            rule = rule(
                cadence = Cadence.QUARTERLY,
                dayOfMonth = 5,
                nextRunDate = LocalDate(2026, 4, 5),
            ),
            today = LocalDate(2026, 6, 15),
            horizonDays = 100,
        )

        assertThat(occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 4, 5),
            LocalDate(2026, 7, 5),
        ).inOrder()
        assertThat(occurrences.first().isOverdue).isTrue()
    }

    @Test
    fun `project also emits each intermediate missed occurrence`() {
        val occurrences = RecurringSchedule.project(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 1,
                nextRunDate = LocalDate(2026, 9, 1),
            ),
            today = LocalDate(2026, 10, 20),
            horizonDays = 60,
        )

        assertThat(occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 9, 1),
            LocalDate(2026, 10, 1),
            LocalDate(2026, 11, 1),
            LocalDate(2026, 12, 1),
        ).inOrder()
    }

    @Test
    fun `bounded projection exposes window and reports no truncation when complete`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 1,
                nextRunDate = LocalDate(2026, 9, 1),
            ),
            today = LocalDate(2026, 10, 20),
            horizonDays = 60,
        )

        assertThat(projection.occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 9, 1),
            LocalDate(2026, 10, 1),
            LocalDate(2026, 11, 1),
            LocalDate(2026, 12, 1),
        ).inOrder()
        assertThat(projection.occurrences.filter { it.isOverdue }.map { it.dueDate })
            .containsExactly(LocalDate(2026, 9, 1), LocalDate(2026, 10, 1))
            .inOrder()
        assertThat(projection.historyWindowStart).isEqualTo(LocalDate(2026, 7, 20))
        assertThat(projection.historyTruncated).isFalse()
        assertThat(projection.futureTruncated).isFalse()
    }

    @Test
    fun `weekly projection enumerates bounded history for a decade stale rule`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.WEEKLY,
                dayOfMonth = null,
                dayOfWeek = 1,
                nextRunDate = LocalDate(2016, 6, 6),
            ),
            today = LocalDate(2026, 6, 15),
            horizonDays = 60,
        )

        assertThat(projection.occurrences.first().dueDate).isEqualTo(LocalDate(2026, 3, 16))
        assertThat(projection.occurrences.count { it.isOverdue }).isEqualTo(13)
        assertThat(projection.occurrences.last().dueDate).isEqualTo(LocalDate(2026, 8, 10))
        // The rule has run weekly since 2016: occurrences before the window start are
        // omitted, and that omission is reported honestly instead of hidden.
        assertThat(projection.historyTruncated).isTrue()
        // 2026-06-15 is a scheduled Monday, so the due-today occurrence stays in.
        val dueToday = projection.occurrences.last { it.dueDate == LocalDate(2026, 6, 15) }
        assertThat(dueToday.isOverdue).isFalse()
    }

    @Test
    fun `hopelessly stale weekly rule reanchors without walking history and keeps due today`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.WEEKLY,
                dayOfMonth = null,
                dayOfWeek = 1,
                nextRunDate = LocalDate(1, 1, 1),
            ),
            today = LocalDate(2026, 6, 15),
            horizonDays = 60,
        )

        assertThat(projection.historyTruncated).isTrue()

        // Reanchored straight onto the 92-day window: first Monday on/after
        // 2026-03-15, computed by calendar arithmetic — no per-period walk from year 1.
        assertThat(projection.occurrences.first().dueDate).isEqualTo(LocalDate(2026, 3, 16))
        assertThat(projection.occurrences.count { it.isOverdue }).isEqualTo(13)
        // A reanchor/step guard must never silently drop an occurrence due today.
        val dueToday = projection.occurrences.last { it.dueDate == LocalDate(2026, 6, 15) }
        assertThat(dueToday.isOverdue).isFalse()
        assertThat(projection.occurrences.last().dueDate).isEqualTo(LocalDate(2026, 8, 10))
        assertThat(projection.futureTruncated).isFalse()
    }

    @Test
    fun `missed occurrence cap reports truncation and preserves future dues`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 1,
                nextRunDate = LocalDate(2026, 9, 1),
            ),
            today = LocalDate(2026, 10, 20),
            horizonDays = 60,
            maxMissedOccurrences = 1,
        )

        assertThat(projection.historyTruncated).isTrue()
        assertThat(projection.futureTruncated).isFalse()
        // The newest missed occurrence is kept (2026-09-01 is truncated honestly) and
        // the missed cap does not consume any future occurrence.
        assertThat(projection.occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 10, 1),
            LocalDate(2026, 11, 1),
            LocalDate(2026, 12, 1),
        ).inOrder()
    }

    @Test
    fun `future cap is reported instead of silently hiding dues`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 5,
                nextRunDate = LocalDate(2026, 6, 5),
            ),
            today = LocalDate(2026, 6, 1),
            horizonDays = 65,
            maxFutureOccurrences = 1,
        )

        assertThat(projection.occurrences.map { it.dueDate }).containsExactly(LocalDate(2026, 6, 5))
        assertThat(projection.futureTruncated).isTrue()
        assertThat(projection.historyTruncated).isFalse()
    }

    @Test
    fun `missed and future caps are independent`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 1,
                nextRunDate = LocalDate(2026, 8, 1),
            ),
            today = LocalDate(2026, 10, 20),
            horizonDays = 60,
            maxMissedOccurrences = 1,
            maxFutureOccurrences = 1,
        )

        // Missed cap leaves one future occurrence; future cap leaves one missed one.
        assertThat(projection.occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 10, 1),
            LocalDate(2026, 11, 1),
        ).inOrder()
        assertThat(projection.historyTruncated).isTrue()
        assertThat(projection.futureTruncated).isTrue()
    }

    @Test
    fun `quarterly bounded projection enumerates every missed quarter in a wide window`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.QUARTERLY,
                dayOfMonth = 15,
                nextRunDate = LocalDate(2025, 1, 15),
            ),
            today = LocalDate(2026, 6, 20),
            horizonDays = 60,
            historyLookbackDays = 400,
        )

        assertThat(projection.occurrences.filter { it.isOverdue }.map { it.dueDate }).containsExactly(
            LocalDate(2025, 7, 15),
            LocalDate(2025, 10, 15),
            LocalDate(2026, 1, 15),
            LocalDate(2026, 4, 15),
        ).inOrder()
        assertThat(projection.occurrences.last().dueDate).isEqualTo(LocalDate(2026, 7, 15))
        assertThat(projection.historyTruncated).isTrue()
        assertThat(projection.futureTruncated).isFalse()
    }

    @Test
    fun `bounded projection keeps an occurrence due today for monthly cadence`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 1,
                nextRunDate = LocalDate(2026, 9, 1),
            ),
            today = LocalDate(2026, 11, 1),
            horizonDays = 60,
        )

        assertThat(projection.occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 9, 1),
            LocalDate(2026, 10, 1),
            LocalDate(2026, 11, 1),
            LocalDate(2026, 12, 1),
        ).inOrder()
        assertThat(projection.occurrences.take(2).all { it.isOverdue }).isTrue()
        val dueToday = projection.occurrences.last { it.dueDate == LocalDate(2026, 11, 1) }
        assertThat(dueToday.isOverdue).isFalse()
        assertThat(projection.historyTruncated).isFalse()
        assertThat(projection.futureTruncated).isFalse()
    }

    @Test
    fun `missed month end occurrences clamp across february`() {
        val projection = RecurringSchedule.projectBounded(
            rule = rule(
                cadence = Cadence.MONTHLY,
                dayOfMonth = 31,
                nextRunDate = LocalDate(2026, 1, 31),
            ),
            today = LocalDate(2026, 3, 5),
            horizonDays = 26,
        )

        assertThat(projection.occurrences.map { it.dueDate }).containsExactly(
            LocalDate(2026, 1, 31),
            LocalDate(2026, 2, 28),
            LocalDate(2026, 3, 31),
        ).inOrder()
        assertThat(projection.occurrences.take(2).all { it.isOverdue }).isTrue()
    }

    private fun rule(
        cadence: Cadence = Cadence.MONTHLY,
        dayOfMonth: Int? = 15,
        dayOfWeek: Int? = null,
        monthOfYear: Int? = null,
        nextRunDate: LocalDate = LocalDate(2026, 6, 15),
        isActive: Boolean = true,
    ): RecurringRule {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        return RecurringRule(
            id = "rule-${cadence.name}",
            displayName = "Rent",
            merchant = "Rent",
            amount = Money.of("2500"),
            type = TxType.EXPENSE,
            accountId = MANUAL_ACCOUNT_ID,
            categoryId = "cat-rent",
            cadence = cadence,
            dayOfMonth = dayOfMonth,
            dayOfWeek = dayOfWeek,
            monthOfYear = monthOfYear,
            nextRunDate = nextRunDate,
            lastRunDate = null,
            isActive = isActive,
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
    }
}
