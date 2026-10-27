package com.athar.feature.today

import androidx.compose.runtime.Immutable
import com.athar.core.common.money.Money
import com.athar.core.domain.model.Transaction
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.math.BigDecimal
import java.time.YearMonth

@Immutable
data class TodayState(
    val month: YearMonth,
    val netFlow: Money,
    val totalExpense: Money,
    val totalIncome: Money,
    val todayNet: Money,
    val netWorth: Money,
    val netWorthIsMixed: Boolean,
    val today: ImmutableList<Transaction>,
    val recent: ImmutableList<Transaction>,
    val pending: ImmutableList<Transaction>,
    val pendingCategorySuggestions: ImmutableMap<String, PendingCategorySuggestion>,
    val pendingCategorySuggestionSummary: ImmutableList<PendingCategorySuggestionSummary>,
    val dismissedToday: ImmutableList<Transaction>,
    val repeatedBacklogNudge: TodayRepeatedBacklogNudge?,
    val categoryLabels: ImmutableMap<String, CategoryLabel>,
    val goalNudge: TodayGoalNudge?,
    val isLoading: Boolean,
    val declaredPosition: TodayDeclaredPosition? = null,
) {
    /** Percentage of income that became savings this month, or null if no income yet. */
    val savingsRate: Double? =
        if (totalIncome.isZero()) null
        else netFlow.amount.toDouble() / totalIncome.amount.toDouble() * 100.0

    companion object {
        fun empty(month: YearMonth): TodayState = TodayState(
            month = month,
            netFlow = Money.zero(),
            totalExpense = Money.zero(),
            totalIncome = Money.zero(),
            todayNet = Money.zero(),
            netWorth = Money.zero(),
            netWorthIsMixed = false,
            today = persistentListOf(),
            recent = persistentListOf(),
            pending = persistentListOf(),
            pendingCategorySuggestions = persistentMapOf(),
            pendingCategorySuggestionSummary = persistentListOf(),
            dismissedToday = persistentListOf(),
            repeatedBacklogNudge = null,
            categoryLabels = persistentMapOf(),
            goalNudge = null,
            isLoading = true,
        )
    }
}

@Immutable
data class PendingCategorySuggestion(
    val categoryId: String,
    val useCount: Int,
)

@Immutable
data class PendingCategorySuggestionSummary(
    val categoryId: String,
    val count: Int,
)

@Immutable
data class TodayRepeatedBacklogNudge(
    val groupCount: Int,
    val transactionCount: Int,
    val largestGroupCount: Int,
)

@Immutable
data class TodayGoalNudge(
    val savingsRatePercent: BigDecimal?,
    val savingsRateTargetPercent: Int,
    val savingsRateProgress: Float,
    val emergencyMonthsCovered: BigDecimal?,
    val emergencyFundTargetMonths: Int,
    val emergencyFundProgress: Float,
)

sealed interface TodayEvent {
    data class ConfirmPending(val id: String) : TodayEvent
    data class DismissPending(val id: String) : TodayEvent
    data class ApplyPendingCategorySuggestion(val id: String) : TodayEvent
    data object BulkApplyPendingCategorySuggestions : TodayEvent
    data class OpenTransaction(val id: String) : TodayEvent
    data object AddManual : TodayEvent
    data object OpenHistory : TodayEvent
    data object OpenRepeatedBacklog : TodayEvent
    /** Bulk-confirm every PENDING transaction with confidence ≥ 0.85 (heavily auto-classified). */
    data object BulkConfirmConfident : TodayEvent
    /** Bulk-dismiss every PENDING transaction with confidence < 0.70. */
    data object BulkDismissLowConfidence : TodayEvent
    /** Wipe every PENDING entry — the user has decided to start clean. */
    data object BulkDismissAll : TodayEvent
}
