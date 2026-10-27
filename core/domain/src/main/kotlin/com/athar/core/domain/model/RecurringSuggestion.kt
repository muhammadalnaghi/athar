package com.athar.core.domain.model

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate

/**
 * A detected pattern in the user's confirmed transaction history that looks like
 * a recurring expense or income (rent, salary, Netflix). Produced by
 * [com.athar.core.domain.repo.RecurringSuggestionRepository] and shown to the user
 * for one-tap conversion into a [RecurringRule] — never auto-created.
 */
data class RecurringSuggestion(
    val merchant: String,
    val merchantNormalized: String,
    val amount: Money,
    val type: TxType,
    /** Account used by every detected occurrence, when history is unambiguous. */
    val suggestedAccountId: String?,
    /** Category used by every detected occurrence, when history is unambiguous. */
    val suggestedCategoryId: String?,
    val occurrenceCount: Int,
    /** The day-of-month at which most occurrences happened (median-ish). */
    val typicalDayOfMonth: Int,
    /** Observed cadence of the pattern (weekly, monthly, quarterly, yearly). */
    val cadence: Cadence = Cadence.MONTHLY,
    /** Day-of-week anchor for [Cadence.WEEKLY] (1=Monday … 7=Sunday), when observed. */
    val dayOfWeek: Int? = null,
    /** Month anchor for [Cadence.YEARLY] (1..12), when observed. */
    val monthOfYear: Int? = null,
    /** The most recent occurrence — used to seed the new rule's nextRunDate. */
    val lastSeen: LocalDate,
    /** Estimated nextRunDate: same cadence and anchor after [lastSeen], clamped, always after today. */
    val suggestedNextRun: LocalDate,
)
