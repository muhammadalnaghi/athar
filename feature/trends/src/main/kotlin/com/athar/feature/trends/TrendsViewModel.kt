package com.athar.feature.trends

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.designsystem.component.AtharBarItem
import com.athar.core.designsystem.component.AtharSplitSegment
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

/**
 * One query state in, one [TrendsState] out. Every figure on screen — headlines,
 * bars, splits, delta rows, evidence — comes from the same aggregation pass, so the
 * drilldown can never disagree with the summary it was opened from.
 */
@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    private val query = MutableStateFlow(TrendsQuery())

    /** Evidence drill history; the sheet shows the LAST request and can back out of it. */
    private val evidenceStack = MutableStateFlow<List<TrendsEvidenceRequest>>(emptyList())
    private val detailId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TrendsState> =
        combine(query, evidenceStack, detailId) { q, stack, detail -> Triple(q, stack, detail) }
            .flatMapLatest { (q, stack, detail) ->
                val today: LocalDate = today()
                val windows = computeTrendsWindows(q, today)
                val months = monthsWindow(today)
                val monthlyPeriod = periodCovering(months)
                // Account names ride the SAME single pipeline: renames re-derive the
                // whole immutable state while keys and totals stay untouched.
                val reference = combine(categories.observeAll(), accounts.observeAll()) { cats, accts -> cats to accts }
                combine(
                    reference,
                    transactions.observeByPeriod(windows.current, status = TxStatus.CONFIRMED),
                    transactions.observeByPeriod(windows.previous ?: windows.current, status = TxStatus.CONFIRMED),
                    transactions.observeByPeriod(monthlyPeriod, status = TxStatus.CONFIRMED),
                    prefs.displayCurrency(),
                ) { (cats, accts), currentTxs, priorTxs, monthlyTxs, prefCurrency ->
                    val currency = q.currencyOverride ?: prefCurrency
                    val aggregates = aggregateTrends(
                        currentTxs = currentTxs,
                        priorTxs = priorTxs,
                        monthlyTxs = monthlyTxs,
                        months = months,
                        categories = cats,
                        filters = q.filters,
                        reportingCurrency = currency,
                        windows = windows,
                    )
                    val request = stack.lastOrNull()
                    val evidence = request?.let { buildEvidence(it, aggregates, cats, currency) }
                    val knownRows = aggregates.currentOperating.expenses +
                        aggregates.currentOperating.income +
                        aggregates.priorOperating.expenses +
                        aggregates.priorOperating.income +
                        aggregates.monthlyOperating.expenses +
                        aggregates.monthlyOperating.income
                    val detailTx = detail?.let { id -> knownRows.firstOrNull { it.id == id } }
                    deriveState(q, aggregates, evidence, stack.size > 1, detailTx, cats, accts, currentTxs, priorTxs, monthlyTxs, prefCurrency, today)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState.initial())

    fun onEvent(event: TrendsEvent) {
        when (event) {
            is TrendsEvent.SelectPeriod -> query.update { it.copy(periodKey = event.key) }
            is TrendsEvent.SelectCustomRange -> query.update { q ->
                // Same guard as the prior range: end<=start and future starts are rejected
                // with a disclosed error instead of crashing on a Period.Custom require.
                val error = rangeError(event.start, event.endExclusive)
                if (error != null) {
                    q.copy(customRangeError = error)
                } else {
                    q.copy(
                        customRange = Period.Custom(event.start, event.endExclusive),
                        periodKey = PeriodKey.CUSTOM,
                        customRangeError = null,
                    )
                }
            }
            is TrendsEvent.SetComparisonCustomRange -> query.update { q ->
                val error = rangeError(event.start, event.endExclusive)
                if (error != null) {
                    q.copy(comparisonRangeError = error)
                } else {
                    q.copy(
                        comparison = ComparisonSelection(
                            mode = ComparisonMode.CUSTOM_PRIOR,
                            customPrior = Period.Custom(event.start, event.endExclusive),
                        ),
                        comparisonRangeError = null,
                    )
                }
            }
            TrendsEvent.ClearComparisonCustom -> query.update {
                it.copy(comparison = ComparisonSelection(), comparisonRangeError = null)
            }
            is TrendsEvent.SetCurrencyOverride -> query.update {
                val code = event.currency?.trim()?.uppercase()
                it.copy(currencyOverride = code?.takeIf { c -> c.length == 3 })
            }
            is TrendsEvent.ToggleAccountFilter -> query.update {
                it.copy(filters = it.filters.withToggledAccount(event.accountId))
            }
            is TrendsEvent.ToggleSourceFilter -> query.update {
                it.copy(filters = it.filters.withToggledSource(event.source))
            }
            is TrendsEvent.SetTypeFilter -> query.update {
                it.copy(filters = it.filters.copy(type = event.type))
            }
            is TrendsEvent.SetCategoryFilter -> query.update {
                it.copy(filters = it.filters.copy(categoryId = event.categoryId))
            }
            is TrendsEvent.SetMerchantQuery -> query.update {
                it.copy(filters = it.filters.copy(merchantQuery = event.query.trim().takeIf { s -> s.isNotEmpty() }))
            }
            TrendsEvent.ClearFilters -> query.update { it.copy(filters = TrendsFilters()) }
            is TrendsEvent.OpenEvidence -> {
                detailId.value = null
                evidenceStack.update { stack ->
                    // Tapping the surface already on top must not grow the history.
                    if (stack.lastOrNull() == event.request) stack else stack + event.request
                }
            }
            TrendsEvent.CloseEvidence -> {
                evidenceStack.value = emptyList()
                detailId.value = null
            }
            TrendsEvent.BackEvidence -> evidenceStack.update { stack ->
                if (stack.size > 1) stack.dropLast(1) else emptyList()
            }
            is TrendsEvent.OpenTransactionDetail -> detailId.value = event.transactionId
            TrendsEvent.CloseTransactionDetail -> detailId.value = null
        }
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    /** end<=start or a start in the future is invalid; both custom paths share this guard. */
    private fun rangeError(start: LocalDate, endExclusive: LocalDate): TrendsRangeError? = when {
        !(start < endExclusive) -> TrendsRangeError.END_BEFORE_START
        start > today() -> TrendsRangeError.START_IN_FUTURE
        else -> null
    }

    private fun deriveState(
        q: TrendsQuery,
        aggregates: TrendsAggregates,
        evidence: TrendsEvidenceState?,
        evidenceCanGoBack: Boolean,
        detailTx: Transaction?,
        cats: List<Category>,
        accounts: List<Account>,
        currentTxs: List<Transaction>,
        priorTxs: List<Transaction>,
        monthlyTxs: List<Transaction>,
        prefCurrency: String,
        today: LocalDate,
    ): TrendsState {
        val bars = aggregates.expenseBreakdown.map { bucket ->
            AtharBarItem(
                key = bucket.key,
                labelAr = bucket.labelAr,
                labelEn = bucket.labelEn,
                value = bucket.total,
            )
        }.toImmutableList()

        val split = aggregates.expenseBreakdown
            .take(SPLIT_MAX_SEGMENTS)
            .map { bucket ->
                AtharSplitSegment(
                    key = bucket.key,
                    labelAr = bucket.labelAr,
                    labelEn = bucket.labelEn,
                    value = bucket.total,
                    color = colorForBucket(bucket.key),
                )
            }
            .toImmutableList()

        return TrendsState(
            periodKey = q.periodKey,
            period = aggregates.windows.current,
            totalExpense = aggregates.totalExpense,
            totalIncome = aggregates.totalIncome,
            previousExpense = aggregates.previousExpense,
            previousIncome = aggregates.previousIncome,
            savings = aggregates.savings,
            previousSavings = aggregates.previousSavings,
            categories = bars,
            categoryDeltas = aggregates.deltaRows.toImmutableList(),
            categorySplit = split,
            monthly = aggregates.monthly,
            customStart = q.customRange?.start,
            customEnd = q.customRange?.endExclusive,
            isLoading = false,
            reportingCurrency = aggregates.reportingCurrency,
            currencyOverride = q.currencyOverride,
            filters = q.filters,
            filterAccountIds = (currentTxs + priorTxs + monthlyTxs)
                .map { it.accountId }
                .distinct()
                .sorted()
                .toImmutableList(),
            accountNames = accounts.associate { it.id to it.name },
            filterCategoryOptions = (
                cats.map { FilterOption(it.id, it.nameAr, it.name) } +
                    // Orphaned (deleted) category ids seen in the observed windows keep
                    // their own filter option; blank labels render the localized
                    // unknown-category fallback, never the raw id.
                    orphanedCategoryIds(cats, currentTxs + priorTxs + monthlyTxs)
                        .map { FilterOption(it, "", "") } +
                    FilterOption(UNCATEGORIZED_KEY, "", "")
                ).toImmutableList(),
            previousWindow = aggregates.windows.previous,
            previousMatchedElapsed = aggregates.windows.previousMatchedElapsed,
            customPrior = q.comparison.customPrior,
            mixedCurrency = aggregates.mixedCurrency,
            evidence = evidence,
            evidenceCanGoBack = evidenceCanGoBack,
            detailTransaction = detailTx,
            comparisonExpense = aggregates.comparisonExpense,
            comparisonIncome = aggregates.comparisonIncome,
            comparisonSavings = aggregates.comparisonSavings,
            comparisonWindow = aggregates.windows.comparisonCurrent,
            unmatchedTail = aggregates.windows.unmatchedTail,
            futureRange = aggregates.windows.futureRange,
            today = today,
            customRangeError = q.customRangeError,
            comparisonRangeError = q.comparisonRangeError,
        )
    }

    /** Non-null category ids with no matching [Category] among the observed rows. */
    private fun orphanedCategoryIds(cats: List<Category>, txs: List<Transaction>): List<String> {
        val known = cats.map { it.id }.toHashSet()
        return txs.mapNotNull { it.categoryId }.filter { it !in known }.distinct().sorted()
    }

    private fun colorForBucket(key: String): Color {
        // Deterministic palette mapped from the bucket key hash. Saudi-style muted tones.
        val palette = listOf(
            Color(0xFFC2541C), Color(0xFF5C6B3A), Color(0xFFB58A2C), Color(0xFF8E1F1F),
            Color(0xFF6B6B68), Color(0xFF8C4A26), Color(0xFF40583E), Color(0xFF7A5E3E),
            Color(0xFF933E33), Color(0xFF566373), Color(0xFF704A28), Color(0xFF3E5B3A),
        )
        val idx = (key.hashCode() and 0x7FFF_FFFF) % palette.size
        return palette[idx]
    }

    companion object {
        private const val SPLIT_MAX_SEGMENTS = 12
    }
}
