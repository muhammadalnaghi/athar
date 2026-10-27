package com.athar.core.domain.calc

import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.RecurringRule
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class RecurringOccurrence(
    val rule: RecurringRule,
    val dueDate: LocalDate,
    val isOverdue: Boolean,
)

/**
 * Complete, size-bounded view of one rule's schedule around [historyWindowStart].
 *
 * [occurrences] lists every missed occurrence inside the history window (ascending,
 * [RecurringOccurrence.isOverdue] true), then any occurrence due today (never dropped,
 * `isOverdue` false), then upcoming occurrences through the future horizon. Missed and
 * future lists are capped independently, so stale backlog can never consume future
 * capacity. [historyTruncated] is true when older occurrences were honestly omitted
 * (they predate the window or overflow the missed cap); [futureTruncated] is true when
 * more occurrences exist inside the horizon than the future cap allowed.
 */
data class ScheduleProjection(
    val occurrences: List<RecurringOccurrence>,
    val historyWindowStart: LocalDate,
    val historyTruncated: Boolean,
    val futureTruncated: Boolean,
)

object RecurringSchedule {

    /**
     * Legacy single-cap projection, kept for [BillReminderPlanner].
     *
     * Emits every missed occurrence first (ascending, `isOverdue = true`), then
     * upcoming occurrences through [horizonDays] (`isOverdue = false`); ordering is
     * oldest missed, newest missed, nearest upcoming, furthest upcoming. The combined
     * list is capped at [maxOccurrences] total, so a hopelessly stale rule can spend
     * the whole cap on missed occurrences before any future one is reported — the
     * loop is always bounded by [maxOccurrences]. For independent past/future caps
     * and explicit truncation reporting use [projectBounded].
     */
    fun project(
        rule: RecurringRule,
        today: LocalDate,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        maxOccurrences: Int = DEFAULT_MAX_OCCURRENCES,
    ): List<RecurringOccurrence> {
        require(horizonDays in 1..366) { "horizonDays must be in 1..366, got $horizonDays" }
        require(maxOccurrences in 1..120) { "maxOccurrences must be in 1..120, got $maxOccurrences" }
        if (!rule.isActive) return emptyList()

        val endInclusive = today.plus(DatePeriod(days = horizonDays))
        val occurrences = mutableListOf<RecurringOccurrence>()
        var cursor = rule.nextRunDate

        while (cursor < today && occurrences.size < maxOccurrences) {
            occurrences += RecurringOccurrence(rule = rule, dueDate = cursor, isOverdue = true)
            cursor = nextRunAfter(cursor, rule)
        }

        while (cursor <= endInclusive && occurrences.size < maxOccurrences) {
            occurrences += RecurringOccurrence(rule = rule, dueDate = cursor, isOverdue = false)
            cursor = nextRunAfter(cursor, rule)
        }

        return occurrences
    }

    /**
     * Deterministic bounded projection: every relevant past occurrence inside the
     * history window (including one due today when the cadence warrants it) plus the
     * future horizon, with explicit truncation flags. Re-anchoring onto the window is
     * O(1) calendar arithmetic per cadence — never a period-by-period walk from a
     * decades-stale anchor. Pure data for the controller: no reminder scheduling or
     * ledger writes happen here.
     */
    fun projectBounded(
        rule: RecurringRule,
        today: LocalDate,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        historyLookbackDays: Int = DEFAULT_HISTORY_LOOKBACK_DAYS,
        maxFutureOccurrences: Int = DEFAULT_MAX_OCCURRENCES,
        maxMissedOccurrences: Int = DEFAULT_MAX_OCCURRENCES,
    ): ScheduleProjection {
        require(horizonDays in 1..366) { "horizonDays must be in 1..366, got $horizonDays" }
        require(historyLookbackDays in 0..3650) {
            "historyLookbackDays must be in 0..3650, got $historyLookbackDays"
        }
        require(maxFutureOccurrences in 1..120) {
            "maxFutureOccurrences must be in 1..120, got $maxFutureOccurrences"
        }
        require(maxMissedOccurrences in 1..120) {
            "maxMissedOccurrences must be in 1..120, got $maxMissedOccurrences"
        }

        val historyWindowStart = today.minus(DatePeriod(days = historyLookbackDays))
        if (!rule.isActive) {
            return ScheduleProjection(
                occurrences = emptyList(),
                historyWindowStart = historyWindowStart,
                historyTruncated = false,
                futureTruncated = false,
            )
        }

        val missed = collectMissed(rule, today, historyWindowStart, maxMissedOccurrences)
        val upcoming = collectUpcoming(rule, today, horizonDays, maxFutureOccurrences)
        return ScheduleProjection(
            occurrences = missed.occurrences + upcoming.occurrences,
            historyWindowStart = historyWindowStart,
            historyTruncated = missed.truncated,
            futureTruncated = upcoming.truncated,
        )
    }

    fun nextRunAfter(fromDate: LocalDate, rule: RecurringRule): LocalDate =
        nextRunAfter(
            fromDate = fromDate,
            cadence = rule.cadence,
            dayOfMonth = rule.dayOfMonth,
            dayOfWeek = rule.dayOfWeek,
            monthOfYear = rule.monthOfYear,
        )

    fun nextRunAfter(
        fromDate: LocalDate,
        cadence: Cadence,
        dayOfMonth: Int?,
        dayOfWeek: Int?,
        monthOfYear: Int?,
    ): LocalDate {
        val date = java.time.LocalDate.of(fromDate.year, fromDate.monthNumber, fromDate.dayOfMonth)
        val next = when (cadence) {
            Cadence.MONTHLY -> {
                val targetDay = (dayOfMonth ?: date.dayOfMonth).coerceIn(1, 31)
                val base = date.plusMonths(1)
                base.withDayOfMonth(targetDay.coerceAtMost(base.lengthOfMonth()))
            }
            Cadence.WEEKLY -> {
                val targetDow = (dayOfWeek ?: date.dayOfWeek.value).coerceIn(1, 7)
                var nextDate = date.plusDays(1)
                while (nextDate.dayOfWeek.value != targetDow) {
                    nextDate = nextDate.plusDays(1)
                }
                nextDate
            }
            Cadence.QUARTERLY -> {
                val targetDay = (dayOfMonth ?: date.dayOfMonth).coerceIn(1, 31)
                val base = date.plusMonths(3)
                base.withDayOfMonth(targetDay.coerceAtMost(base.lengthOfMonth()))
            }
            Cadence.YEARLY -> {
                val targetMonth = (monthOfYear ?: date.monthValue).coerceIn(1, 12)
                val targetDay = (dayOfMonth ?: date.dayOfMonth).coerceIn(1, 31)
                val base = java.time.LocalDate.of(date.year + 1, targetMonth, 1)
                base.withDayOfMonth(targetDay.coerceAtMost(base.lengthOfMonth()))
            }
        }
        return LocalDate(next.year, next.monthValue, next.dayOfMonth)
    }

    private const val DEFAULT_HORIZON_DAYS = 60
    private const val DEFAULT_MAX_OCCURRENCES = 24

    private data class BoundedSegment(
        val occurrences: List<RecurringOccurrence>,
        val truncated: Boolean,
    )

    private fun collectMissed(
        rule: RecurringRule,
        today: LocalDate,
        historyWindowStart: LocalDate,
        cap: Int,
    ): BoundedSegment {
        val anchor = rule.nextRunDate
        val first = if (anchor >= historyWindowStart) anchor else firstOccurrenceOnOrAfter(rule, historyWindowStart)
        val found = mutableListOf<LocalDate>()
        var cursor = first
        // Weekly is the shortest cadence, so this bound always covers the window.
        val maxSteps = (today.toEpochDays() - first.toEpochDays()).coerceAtLeast(0) / 7 + 2
        var steps = 0
        while (cursor <= today && steps <= maxSteps) {
            found += cursor
            cursor = nextRunAfter(cursor, rule)
            steps++
        }
        // Keep the most recent missed runs so a cap can never drop a due-today
        // occurrence; older history (before the window or overflowing the cap) is
        // reported via historyTruncated instead of vanishing silently.
        val truncated = found.size > cap || anchor < historyWindowStart
        return BoundedSegment(
            occurrences = found.takeLast(cap).map { RecurringOccurrence(rule, it, isOverdue = it < today) },
            truncated = truncated,
        )
    }

    private fun collectUpcoming(
        rule: RecurringRule,
        today: LocalDate,
        horizonDays: Int,
        cap: Int,
    ): BoundedSegment {
        val endInclusive = today.plus(DatePeriod(days = horizonDays))
        val found = mutableListOf<LocalDate>()
        var cursor = firstOccurrenceOnOrAfter(rule, today.plus(DatePeriod(days = 1)))
        while (cursor <= endInclusive && found.size <= cap) {
            found += cursor
            cursor = nextRunAfter(cursor, rule)
        }
        return BoundedSegment(
            occurrences = found.take(cap).map { RecurringOccurrence(rule, it, isOverdue = false) },
            truncated = found.size > cap,
        )
    }

    /** Smallest schedule occurrence on or after [target], via O(1) calendar jumps. */
    private fun firstOccurrenceOnOrAfter(rule: RecurringRule, target: LocalDate): LocalDate {
        val anchor = rule.nextRunDate
        if (anchor >= target) return anchor
        val anchorDate = java.time.LocalDate.of(anchor.year, anchor.monthNumber, anchor.dayOfMonth)
        val targetDate = java.time.LocalDate.of(target.year, target.monthNumber, target.dayOfMonth)
        return when (rule.cadence) {
            Cadence.WEEKLY -> {
                val targetDow = (rule.dayOfWeek ?: anchor.dayOfWeek.value).coerceIn(1, 7)
                val delta = Math.floorMod(targetDow - targetDate.dayOfWeek.value, 7)
                val candidate = targetDate.plusDays(delta.toLong())
                LocalDate(candidate.year, candidate.monthValue, candidate.dayOfMonth)
            }
            Cadence.MONTHLY -> {
                val targetDay = (rule.dayOfMonth ?: anchor.dayOfMonth).coerceIn(1, 31)
                var months = monthsBetween(anchorDate, targetDate)
                var candidate = clampedDay(anchorDate.plusMonths(months.toLong()), targetDay)
                if (candidate < targetDate) {
                    months += 1
                    candidate = clampedDay(anchorDate.plusMonths(months.toLong()), targetDay)
                }
                LocalDate(candidate.year, candidate.monthValue, candidate.dayOfMonth)
            }
            Cadence.QUARTERLY -> {
                val targetDay = (rule.dayOfMonth ?: anchor.dayOfMonth).coerceIn(1, 31)
                var months = monthsBetween(anchorDate, targetDate)
                months -= Math.floorMod(months, 3)
                var candidate = clampedDay(anchorDate.plusMonths(months.toLong()), targetDay)
                if (candidate < targetDate) {
                    months += 3
                    candidate = clampedDay(anchorDate.plusMonths(months.toLong()), targetDay)
                }
                LocalDate(candidate.year, candidate.monthValue, candidate.dayOfMonth)
            }
            Cadence.YEARLY -> {
                val targetMonth = (rule.monthOfYear ?: anchor.monthNumber).coerceIn(1, 12)
                val targetDay = (rule.dayOfMonth ?: anchor.dayOfMonth).coerceIn(1, 31)
                var year = targetDate.year
                var candidate = clampedDay(java.time.LocalDate.of(year, targetMonth, 1), targetDay)
                if (candidate < targetDate) {
                    year += 1
                    candidate = clampedDay(java.time.LocalDate.of(year, targetMonth, 1), targetDay)
                }
                LocalDate(candidate.year, candidate.monthValue, candidate.dayOfMonth)
            }
        }
    }

    private fun monthsBetween(anchor: java.time.LocalDate, target: java.time.LocalDate): Int =
        (target.year - anchor.year) * 12 + (target.monthValue - anchor.monthValue)

    private fun clampedDay(base: java.time.LocalDate, targetDay: Int): java.time.LocalDate =
        base.withDayOfMonth(targetDay.coerceAtMost(base.lengthOfMonth()))

    private const val DEFAULT_HISTORY_LOOKBACK_DAYS = 92
}
