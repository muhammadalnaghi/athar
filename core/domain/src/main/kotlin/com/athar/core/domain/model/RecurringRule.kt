package com.athar.core.domain.model

import com.athar.core.common.money.Money
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * A user-defined rule that materializes into a [Transaction] on a schedule.
 *
 * Athar v1 supports monthly, weekly, and yearly cadences. The pipeline runs
 * idempotently — each "due" rule produces one PENDING transaction on the user's
 * Today tray; the user confirms it like an SMS-parsed entry. Never auto-confirm,
 * since the user might want to adjust the amount or skip the month.
 *
 * Cf. ROADMAP_GLOBAL G-3: rent, salary, subscriptions, utilities.
 */
data class RecurringRule(
    val id: String,
    val displayName: String,
    val merchant: String,
    val amount: Money,
    val type: TxType,
    val accountId: String,
    val categoryId: String?,
    val cadence: Cadence,
    /** Day-of-month for [Cadence.MONTHLY] (1..31, clamped to month length) and [Cadence.YEARLY]. */
    val dayOfMonth: Int?,
    /** Day-of-week for [Cadence.WEEKLY] (1=Monday … 7=Sunday, matches ISO). */
    val dayOfWeek: Int?,
    /** Month for [Cadence.YEARLY] (1..12). */
    val monthOfYear: Int?,
    val nextRunDate: LocalDate,
    val lastRunDate: LocalDate?,
    val isActive: Boolean,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class Cadence {
    MONTHLY,
    WEEKLY,
    QUARTERLY,
    YEARLY,
}
