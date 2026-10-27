package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

data class BillReminder(
    val key: String,
    val kind: BillReminderKind,
    val label: String,
    val amount: Money,
    val dueDate: LocalDate,
)

enum class BillReminderKind {
    UPCOMING,
    DUE_TODAY,
    MISSED_REVIEW,
}

object BillReminderPlanner {

    fun plan(
        rules: List<RecurringRule>,
        pending: List<Transaction>,
        today: LocalDate,
    ): List<BillReminder> {
        val upcoming = rules
            .asSequence()
            .filter { it.isActive && it.type == TxType.EXPENSE }
            .flatMap { rule ->
                RecurringSchedule
                    .projectBounded(
                        rule,
                        today = today,
                        horizonDays = UPCOMING_DAYS,
                        historyLookbackDays = 0,
                        maxFutureOccurrences = MAX_FUTURE_OCCURRENCES,
                        maxMissedOccurrences = 1,
                    )
                    .occurrences
                    .asSequence()
                    .filterNot { it.isOverdue }
                    .mapNotNull { occurrence ->
                        val kind = when (occurrence.dueDate) {
                            today -> BillReminderKind.DUE_TODAY
                            today.plus(DatePeriod(days = UPCOMING_DAYS)) -> BillReminderKind.UPCOMING
                            else -> null
                        } ?: return@mapNotNull null

                        BillReminder(
                            key = "recurring:${kind.name.lowercase()}:${rule.id}:${occurrence.dueDate}",
                            kind = kind,
                            label = rule.displayName.ifBlank { rule.merchant },
                            amount = rule.amount,
                            dueDate = occurrence.dueDate,
                        )
                    }
            }

        val missed = pending
            .asSequence()
            .filter { it.source == IngestSource.RECURRING }
            .filter { it.type == TxType.EXPENSE }
            .filter { it.date < today }
            .map { tx ->
                val stableRef = tx.sourceRefId?.takeIf { it.isNotBlank() } ?: tx.id
                BillReminder(
                    key = "pending:missed:$stableRef:${tx.date}",
                    kind = BillReminderKind.MISSED_REVIEW,
                    label = tx.merchant,
                    amount = tx.amount,
                    dueDate = tx.date,
                )
            }

        return (upcoming + missed)
            .sortedWith(compareBy<BillReminder> { it.dueDate }.thenBy { it.kind.ordinal }.thenBy { it.label.lowercase() })
            .toList()
    }

    private const val UPCOMING_DAYS = 2

    /**
     * History is deliberately not projected here (lookback 0): missed obligations are
     * disclosed from real pending RECURRING transactions only, never invented from
     * schedule arithmetic, and a decades-stale anchor can no longer spend the whole
     * occurrence budget on the past and suppress today/upcoming reminders.
     */
    private const val MAX_FUTURE_OCCURRENCES = 4
}
