package com.athar.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.calc.GoalCalc
import com.athar.core.domain.calc.selectOperatingTransactions
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.specificMerchantKey
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val rules: CategoryRuleRepository,
    private val categories: CategoryRepository,
    private val prefs: UserPreferencesRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : ViewModel() {

    private val month = MutableStateFlow(currentMonth())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TodayState> =
        month
            .flatMapLatest { m ->
                val period = Period.Month(m)
                val goalsPeriod = last3MonthsPeriod()
                prefs.displayCurrency().flatMapLatest { currency ->
                    combine(
                        combine(
                            transactions.observeByPeriod(period, status = TxStatus.CONFIRMED),
                            transactions.observePending(),
                            transactions.observeByPeriod(period, status = TxStatus.DISMISSED),
                            transactions.observeAll(),
                            accounts.observeNetWorth(currency),
                        ) { confirmed, pending, dismissed, allTransactions, netWorth ->
                            TodayInputs(confirmed, pending, dismissed, allTransactions, netWorth)
                        },
                        categories.observeAll(kind = null, includeArchived = true),
                        transactions.observeByPeriod(goalsPeriod, status = TxStatus.CONFIRMED),
                        prefs.savingsRateTargetPercent(),
                        prefs.emergencyFundTargetMonths(),
                    ) { inputs, allCategories, goalTransactions, savingsTarget, emergencyMonths ->
                        deriveState(
                            month = m,
                            confirmed = inputs.confirmed,
                            pending = inputs.pending,
                            dismissed = inputs.dismissed,
                            allTransactions = inputs.allTransactions,
                            currency = currency,
                            netWorth = inputs.netWorth,
                            categories = allCategories,
                            categoryLabels = allCategories.toCategoryLabels(),
                            goalNudge = deriveGoalNudge(
                                transactions = goalTransactions,
                                netWorth = inputs.netWorth,
                                currency = currency,
                                savingsTarget = savingsTarget,
                                emergencyMonths = emergencyMonths,
                            ),
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState.empty(currentMonth()))

    fun onEvent(event: TodayEvent) {
        when (event) {
            is TodayEvent.ConfirmPending -> viewModelScope.launch {
                transactions.setStatus(event.id, TxStatus.CONFIRMED)
            }
            is TodayEvent.DismissPending -> viewModelScope.launch {
                transactions.setStatus(event.id, TxStatus.DISMISSED)
            }
            is TodayEvent.ApplyPendingCategorySuggestion -> viewModelScope.launch {
                applyPendingCategorySuggestionInternal(event.id)
            }
            TodayEvent.BulkApplyPendingCategorySuggestions -> viewModelScope.launch {
                applyPendingCategorySuggestions()
            }
            is TodayEvent.OpenTransaction,
            TodayEvent.AddManual,
            TodayEvent.OpenHistory,
            TodayEvent.OpenRepeatedBacklog -> Unit // UI-owned
            TodayEvent.BulkConfirmConfident -> viewModelScope.launch {
                transactions.confirmAllConfident(minConfidence = 0.85f)
            }
            TodayEvent.BulkDismissLowConfidence -> viewModelScope.launch {
                transactions.dismissAllLowConfidence(maxConfidence = 0.70f)
            }
            TodayEvent.BulkDismissAll -> viewModelScope.launch {
                transactions.dismissAllPending()
            }
        }
    }

    fun updateTransaction(tx: Transaction, learnRule: Boolean) {
        viewModelScope.launch {
            val now = clock.now()
            val existing = transactions.get(tx.id)
            val provenance = when {
                existing == null -> tx.categoryId?.let { CategorySource.USER }
                existing.categoryId != tx.categoryId -> tx.categoryId?.let { CategorySource.USER }
                else -> existing.categorySource
            }
            val confirmed = tx.copy(status = TxStatus.CONFIRMED, updatedAt = now, categorySource = provenance)
            transactions.upsert(confirmed)
            val categoryId = confirmed.categoryId
            val merchantKey = specificMerchantKey(
                merchantNormalized = confirmed.merchantNormalized,
                merchant = confirmed.merchant,
            )
            if (learnRule && categoryId != null && merchantKey != null) {
                rules.learnFromCorrection(
                    merchantNormalized = merchantKey,
                    categoryId = categoryId,
                    patternType = PatternType.SUBSTRING,
                )
                // Backfill: apply the same category to every other PENDING/DISMISSED
                // row whose merchant matches. Otherwise picking "Always categorize Hemmah
                // as Home maintenance" would only fix the one row the user just edited,
                // leaving every other Hemmah charge stranded in the dismissed tray.
                val backfilled = transactions.applyCategoryToMatching(
                    pattern = merchantKey,
                    categoryId = categoryId,
                )
                _lastBackfill.value = BackfillEvent(
                    pattern = confirmed.merchant.ifBlank { confirmed.merchantNormalized },
                    count = backfilled,
                )
            }
        }
    }

    private suspend fun applyPendingCategorySuggestions() {
        val ids = state.value.pendingCategorySuggestions.keys.toList()
        var applied = 0
        ids.forEach { id ->
            if (applyPendingCategorySuggestionInternal(id)) applied += 1
        }
        if (applied > 0) {
            _lastPendingSuggestionApply.value = PendingSuggestionApplyEvent(count = applied)
        }
    }

    private suspend fun applyPendingCategorySuggestionInternal(id: String): Boolean {
        val suggestion = state.value.pendingCategorySuggestions[id] ?: return false
        val tx = transactions.get(id) ?: return false
        if (tx.status != TxStatus.PENDING || !tx.categoryId.isNullOrBlank()) return false
        val category = categories.get(suggestion.categoryId)?.takeUnless { it.archived } ?: return false
        if (tx.categoryKind() != category.kind) return false
        transactions.upsert(
            tx.copy(
                categoryId = category.id,
                categorySource = CategorySource.USER,
                status = TxStatus.CONFIRMED,
                updatedAt = clock.now(),
            ),
        )
        return true
    }

    private val _lastBackfill = MutableStateFlow<BackfillEvent?>(null)
    /**
     * Emits the last "Always categorize X as Y" backfill result so the UI can show
     * a transient toast ("Applied to N other Hemmah charges"). Cleared by [clearBackfill].
     */
    val lastBackfill: StateFlow<BackfillEvent?> = _lastBackfill

    fun clearBackfill() { _lastBackfill.value = null }

    data class BackfillEvent(val pattern: String, val count: Int)

    private val _lastPendingSuggestionApply = MutableStateFlow<PendingSuggestionApplyEvent?>(null)
    val lastPendingSuggestionApply: StateFlow<PendingSuggestionApplyEvent?> = _lastPendingSuggestionApply

    fun clearPendingSuggestionApply() { _lastPendingSuggestionApply.value = null }

    data class PendingSuggestionApplyEvent(val count: Int)

    fun deleteTransaction(id: String) {
        viewModelScope.launch { transactions.delete(id) }
    }

    private fun deriveState(
        month: YearMonth,
        confirmed: List<Transaction>,
        pending: List<Transaction>,
        dismissed: List<Transaction>,
        allTransactions: List<Transaction>,
        currency: String,
        netWorth: NetWorth,
        categories: List<Category>,
        categoryLabels: ImmutableMap<String, CategoryLabel>,
        goalNudge: TodayGoalNudge?,
    ): TodayState {
        // Operating = confirmed, non-reconciliation rows via the shared domain
        // selector; transfers are money movement, never operating expense.
        val operating = selectOperatingTransactions(confirmed)
        val incomeSum = Money.sumAmounts(operating.income.map { it.amount }, currency)
        val expenseSum = Money.sumAmounts(operating.expenses.map { it.amount }, currency)
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val (todayTxns, monthTxns) = confirmed.partition { it.date == today }
        val todayOperating = selectOperatingTransactions(todayTxns)
        val todayNet = Money.sumAmounts(todayOperating.income.map { it.amount }, currency) -
            Money.sumAmounts(todayOperating.expenses.map { it.amount }, currency)
        val dismissedToday = dismissed.filter { it.date == today }
        val pendingCategorySuggestions = buildPendingCategorySuggestions(
            pending = pending,
            allTransactions = allTransactions,
            activeCategories = categories.filterNot { it.archived },
        )
        val repeatedBacklogNudge = buildTodayRepeatedBacklogNudge(allTransactions)
        return TodayState(
            month = month,
            netFlow = incomeSum - expenseSum,
            totalExpense = expenseSum,
            totalIncome = incomeSum,
            todayNet = todayNet,
            netWorth = netWorth.total,
            netWorthIsMixed = netWorth.isMixedCurrency,
            today = todayTxns.toImmutableList(),
            recent = monthTxns.take(10).toImmutableList(),
            pending = pending.toImmutableList(),
            pendingCategorySuggestions = pendingCategorySuggestions,
            pendingCategorySuggestionSummary = buildPendingCategorySuggestionSummary(pendingCategorySuggestions),
            dismissedToday = dismissedToday.toImmutableList(),
            repeatedBacklogNudge = repeatedBacklogNudge,
            categoryLabels = categoryLabels,
            goalNudge = goalNudge,
            isLoading = false,
        )
    }

    private fun deriveGoalNudge(
        transactions: List<Transaction>,
        netWorth: NetWorth,
        currency: String,
        savingsTarget: Int,
        emergencyMonths: Int,
    ): TodayGoalNudge {
        val operating = selectOperatingTransactions(transactions)
        val totalIncome = Money.sumAmounts(operating.income.map { it.amount }, currency)
        val totalExpense = Money.sumAmounts(operating.expenses.map { it.amount }, currency)
        val monthlyIncome = GoalCalc.averageMonthly(totalIncome, LOOKBACK_MONTHS)
        val monthlyExpense = GoalCalc.averageMonthly(totalExpense, LOOKBACK_MONTHS)
        val savingsRate = GoalCalc.savingsRatePercent(monthlyIncome, monthlyExpense)
        val emergencyCovered = GoalCalc.emergencyMonthsCovered(
            liquidBalance(netWorth.accounts, currency),
            monthlyExpense,
        )

        return TodayGoalNudge(
            savingsRatePercent = savingsRate,
            savingsRateTargetPercent = savingsTarget,
            savingsRateProgress = GoalCalc.savingsRateProgress(savingsRate, savingsTarget).toProgressFloat(),
            emergencyMonthsCovered = emergencyCovered,
            emergencyFundTargetMonths = emergencyMonths,
            emergencyFundProgress = GoalCalc.emergencyProgress(emergencyCovered, emergencyMonths).toProgressFloat(),
        )
    }

    private fun liquidBalance(balances: List<AccountBalance>, currency: String): Money =
        Money.sumAmounts(
            balances
                .filter { it.account.type in LIQUID_ACCOUNT_TYPES }
                .map { it.current },
            currency,
        ).let { if (it.amount.signum() < 0) Money.zero(currency) else it }

    private fun last3MonthsPeriod(): Period {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return Period.Last(months = LOOKBACK_MONTHS, endingAt = today)
    }

    private fun currentMonth(): YearMonth {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return YearMonth.of(now.year, now.monthNumber)
    }

    private fun BigDecimal.toProgressFloat(): Float =
        toFloat().coerceIn(0f, 1f)

    private data class TodayInputs(
        val confirmed: List<Transaction>,
        val pending: List<Transaction>,
        val dismissed: List<Transaction>,
        val allTransactions: List<Transaction>,
        val netWorth: NetWorth,
    )

    companion object {
        private const val LOOKBACK_MONTHS = 3
        private val LIQUID_ACCOUNT_TYPES = setOf(
            AccountType.CHECKING,
            AccountType.SAVINGS,
            AccountType.CASH,
        )
    }
}

internal fun buildPendingCategorySuggestionSummary(
    suggestions: ImmutableMap<String, PendingCategorySuggestion>,
): ImmutableList<PendingCategorySuggestionSummary> =
    suggestions.values
        .groupingBy { it.categoryId }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { (categoryId, count) ->
            PendingCategorySuggestionSummary(
                categoryId = categoryId,
                count = count,
            )
        }
        .toPersistentList()

internal fun buildTodayRepeatedBacklogNudge(
    allTransactions: List<Transaction>,
): TodayRepeatedBacklogNudge? {
    val repeatedRows = filterHistoryTransactions(
        all = allTransactions,
        query = "",
        status = HistoryStatusFilter.ALL,
        type = HistoryTypeFilter.ALL,
        source = HistorySourceFilter.ALL,
        category = HistoryCategoryFilter.REPEATED_UNCATEGORIZED,
    )
    val summary = buildRepeatedBacklogSummary(
        visibleRows = repeatedRows,
        category = HistoryCategoryFilter.REPEATED_UNCATEGORIZED,
    )
    return summary.takeIf { it.transactionCount > 0 }?.let {
        TodayRepeatedBacklogNudge(
            groupCount = it.groupCount,
            transactionCount = it.transactionCount,
            largestGroupCount = it.largestGroupCount,
        )
    }
}

internal fun buildPendingCategorySuggestions(
    pending: List<Transaction>,
    allTransactions: List<Transaction>,
    activeCategories: List<Category>,
): ImmutableMap<String, PendingCategorySuggestion> {
    val activeCategoryIdsByKind = activeCategories
        .filterNot { it.archived }
        .groupBy { it.kind }
        .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.id } }
    val confirmedCategoryCounts = allTransactions.asSequence()
        .filter { it.status == TxStatus.CONFIRMED }
        .mapNotNull { tx ->
            val kind = tx.categoryKind() ?: return@mapNotNull null
            val categoryId = tx.categoryId?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            if (categoryId !in activeCategoryIdsByKind[kind].orEmpty()) return@mapNotNull null
            val merchantKey = tx.specificMerchantSuggestionKey() ?: return@mapNotNull null
            PendingSuggestionCategoryHit(
                key = PendingSuggestionMerchantKey(merchantKey = merchantKey, categoryKind = kind),
                categoryId = categoryId,
            )
        }
        .groupingBy { it }
        .eachCount()
        .entries
        .groupBy(keySelector = { it.key.key }, valueTransform = { it.key.categoryId to it.value })
        .mapValues { (_, rows) -> rows.toMap() }

    return pending.mapNotNull { tx ->
        if (tx.status != TxStatus.PENDING || !tx.categoryId.isNullOrBlank()) return@mapNotNull null
        val kind = tx.categoryKind() ?: return@mapNotNull null
        val merchantKey = tx.specificMerchantSuggestionKey() ?: return@mapNotNull null
        if (activeCategoryIdsByKind[kind].isNullOrEmpty()) return@mapNotNull null

        val categoryCounts = confirmedCategoryCounts[
            PendingSuggestionMerchantKey(merchantKey = merchantKey, categoryKind = kind),
        ].orEmpty()
        val category = categoryCounts.entries.singleOrNull() ?: return@mapNotNull null
        tx.id to PendingCategorySuggestion(
            categoryId = category.key,
            useCount = category.value,
        )
    }.toMap().toPersistentMap()
}

private data class PendingSuggestionMerchantKey(
    val merchantKey: String,
    val categoryKind: CategoryKind,
)

private data class PendingSuggestionCategoryHit(
    val key: PendingSuggestionMerchantKey,
    val categoryId: String,
)

private fun Transaction.categoryKind(): CategoryKind? = when (type) {
    TxType.EXPENSE -> CategoryKind.EXPENSE
    TxType.INCOME -> CategoryKind.INCOME
    TxType.TRANSFER -> null
}

private fun Transaction.specificMerchantSuggestionKey(): String? =
    specificMerchantKey(merchantNormalized = merchantNormalized, merchant = merchant)
