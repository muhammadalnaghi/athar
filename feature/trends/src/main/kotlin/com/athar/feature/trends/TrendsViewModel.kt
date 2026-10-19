package com.athar.feature.trends

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.common.time.previous
import com.athar.core.designsystem.component.AtharBarItem
import com.athar.core.designsystem.component.AtharMonthlyBar
import com.athar.core.designsystem.component.AtharSplitSegment
import com.athar.core.domain.calc.selectOperatingTransactions
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import javax.inject.Inject

data class DrilldownState(
    val categoryId: String,
    val categoryLabelAr: String,
    val bars: ImmutableList<AtharMonthlyBar>,
    val total: Money,
    val monthlyTarget: Money?,
)

@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedKey = MutableStateFlow(PeriodKey.MONTH)
    private val customRange = MutableStateFlow<Pair<LocalDate, LocalDate>?>(null)
    private val selectedCategoryId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TrendsState> =
        selectedKey
            .flatMapLatest { key ->
                customRange.flatMapLatest { range ->
                    val period = period(key, range)
                    val previous = period.previous()
                    val last12 = last12Months()
                    combine(
                        categories.observeAll(),
                        transactions.observeByPeriod(period, status = TxStatus.CONFIRMED),
                        transactions.observeByPeriod(previous, status = TxStatus.CONFIRMED),
                        transactions.observeByPeriod(last12, status = TxStatus.CONFIRMED),
                        prefs.displayCurrency(),
                    ) { cats, current, prior, twelveMonths, currency ->
                        derive(key, period, range, cats, current, prior, twelveMonths, currency)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState.initial())

    @OptIn(ExperimentalCoroutinesApi::class)
    val drilldown: StateFlow<DrilldownState?> =
        selectedCategoryId
            .flatMapLatest { catId ->
                if (catId == null) flowOf(null)
                else combine(
                    categories.observeAll(includeArchived = true),
                    transactions.observeByPeriod(last12Months(), status = TxStatus.CONFIRMED),
                    prefs.displayCurrency(),
                ) { cats, txs, currency -> buildDrilldown(catId, cats, txs, currency) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onEvent(event: TrendsEvent) {
        when (event) {
            is TrendsEvent.SelectPeriod -> selectedKey.value = event.key
            is TrendsEvent.SelectCustomRange -> {
                customRange.value = event.start to event.end
                selectedKey.value = PeriodKey.CUSTOM
            }
        }
    }

    fun openDrilldown(categoryId: String) {
        selectedCategoryId.value = categoryId
    }

    fun closeDrilldown() {
        selectedCategoryId.value = null
    }

    private fun period(key: PeriodKey, custom: Pair<LocalDate, LocalDate>?): Period {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val ym = YearMonth.of(now.year, now.monthNumber)
        return when (key) {
            PeriodKey.WEEK -> Period.Custom(
                start = now.date.minus(DatePeriod(days = 6)),
                endExclusive = now.date.plus(DatePeriod(days = 1)),
            )
            PeriodKey.MONTH -> Period.Month(ym)
            PeriodKey.MONTHS_3 -> Period.Last(months = 3, endingAt = now.date)
            PeriodKey.YEAR -> Period.Year(now.year)
            PeriodKey.MONTH_VS_PREVIOUS -> Period.Month(ym)
            PeriodKey.CUSTOM -> custom?.let { (s, e) -> Period.Custom(s, e) }
                ?: Period.Month(ym)
        }
    }

    private fun last12Months(): Period {
        val today: LocalDate = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return Period.Last(months = 12, endingAt = today)
    }

    private fun derive(
        key: PeriodKey,
        period: Period,
        custom: Pair<LocalDate, LocalDate>?,
        cats: List<Category>,
        current: List<Transaction>,
        prior: List<Transaction>,
        twelveMonths: List<Transaction>,
        currency: String,
    ): TrendsState {
        // Shared operating selection: reconciliations are balance fixes, not spend;
        // transfers are money movement, not consumption or income.
        val currentOp = selectOperatingTransactions(current)
        val priorOp = selectOperatingTransactions(prior)
        val expense = currentOp.expenses
        val income = currentOp.income
        val expenseSum = Money.sumAmounts(expense.map { it.amount }, currency)
        val incomeSum = Money.sumAmounts(income.map { it.amount }, currency)
        val priorExpense = priorOp.expenses
        val priorIncome = priorOp.income
        val priorExpenseSum = Money.sumAmounts(priorExpense.map { it.amount }, currency)
        val priorIncomeSum = Money.sumAmounts(priorIncome.map { it.amount }, currency)

        val byCategoryId = expense.groupBy { it.categoryId }
            .mapValues { (_, list) -> Money.sumAmounts(list.map { it.amount }, currency) }
        val priorByCategoryId = priorExpense.groupBy { it.categoryId }
            .mapValues { (_, list) -> Money.sumAmounts(list.map { it.amount }, currency) }

        val categoryById = cats.associateBy { it.id }
        val bars = byCategoryId
            .mapNotNull { (catId, total) ->
                val cat = catId?.let { categoryById[it] } ?: return@mapNotNull null
                AtharBarItem(
                    key = cat.id,
                    labelAr = cat.nameAr,
                    labelEn = cat.name,
                    value = total,
                )
            }
            .sortedByDescending { it.value.amount }
            .take(TOP_N)
            .toImmutableList()

        val allCatIds = (byCategoryId.keys + priorByCategoryId.keys).filterNotNull().toSet()
        val deltas = allCatIds.mapNotNull { catId ->
            val cat = categoryById[catId] ?: return@mapNotNull null
            CategoryDeltaRow(
                categoryId = catId,
                labelAr = cat.nameAr,
                labelEn = cat.name,
                currentTotal = byCategoryId[catId] ?: Money.zero(currency),
                previousTotal = priorByCategoryId[catId] ?: Money.zero(currency),
            )
        }.sortedByDescending { it.currentTotal.amount.max(it.previousTotal.amount) }
            .toImmutableList()

        // Proportional category split for the current period (TMOAP donut → horizontal stacked bar).
        val split = byCategoryId
            .mapNotNull { (catId, total) ->
                val cat = catId?.let { categoryById[it] } ?: return@mapNotNull null
                AtharSplitSegment(
                    key = cat.id,
                    labelAr = cat.nameAr,
                    labelEn = cat.name,
                    value = total,
                    color = colorForCategory(cat),
                )
            }
            .sortedByDescending { it.value.amount }
            .take(12)
            .toImmutableList()

        val monthly = buildMonthlySeries(cats, twelveMonths, currency)

        return TrendsState(
            periodKey = key,
            period = period,
            totalExpense = expenseSum,
            totalIncome = incomeSum,
            previousExpense = priorExpenseSum,
            previousIncome = priorIncomeSum,
            savings = incomeSum - expenseSum,
            previousSavings = priorIncomeSum - priorExpenseSum,
            categories = bars,
            categoryDeltas = deltas,
            categorySplit = split,
            monthly = monthly,
            customStart = custom?.first,
            customEnd = custom?.second,
            isLoading = false,
        )
    }

    private fun buildMonthlySeries(cats: List<Category>, txs: List<Transaction>, currency: String): MonthlySeries {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val nowYm = YearMonth.of(now.year, now.monthNumber)
        val months: List<YearMonth> = (11 downTo 0).map { o -> nowYm.minusMonths(o.toLong()) }
        val incomeByMonthAmt = mutableMapOf<YearMonth, BigDecimal>().apply { months.forEach { put(it, BigDecimal.ZERO) } }
        val expenseByMonthAmt = mutableMapOf<YearMonth, BigDecimal>().apply { months.forEach { put(it, BigDecimal.ZERO) } }
        // Selector supplies only operating rows; per-month bucketing and every
        // target/comparison formula below stay unchanged.
        val operating = selectOperatingTransactions(txs)
        operating.income.forEach { tx ->
            val ym = YearMonth.of(tx.date.year, tx.date.monthNumber)
            if (ym !in incomeByMonthAmt) return@forEach
            incomeByMonthAmt[ym] = incomeByMonthAmt.getValue(ym) + tx.amount.amount
        }
        operating.expenses.forEach { tx ->
            val ym = YearMonth.of(tx.date.year, tx.date.monthNumber)
            if (ym !in expenseByMonthAmt) return@forEach
            expenseByMonthAmt[ym] = expenseByMonthAmt.getValue(ym) + tx.amount.amount
        }
        val incomeBars = months.map { ym -> AtharMonthlyBar(month = ym, amount = Money.of(incomeByMonthAmt.getValue(ym), currency)) }
        val expenseBars = months.map { ym -> AtharMonthlyBar(month = ym, amount = Money.of(expenseByMonthAmt.getValue(ym), currency)) }
        val savingsBars = months.map { ym ->
            AtharMonthlyBar(month = ym, amount = Money.of(incomeByMonthAmt.getValue(ym) - expenseByMonthAmt.getValue(ym), currency))
        }
        val n = months.size
        val avgIncome = average(incomeBars.map { it.amount }, n, currency)
        val avgExpense = average(expenseBars.map { it.amount }, n, currency)
        val avgSavings = average(savingsBars.map { it.amount }, n, currency)
        val targetExpense = Money.sumAmounts(
            cats.filter { it.kind == CategoryKind.EXPENSE }.mapNotNull { it.monthlyTarget },
            currency,
        )
        val targetIncome = Money.sumAmounts(
            cats.filter { it.kind == CategoryKind.INCOME }.mapNotNull { it.monthlyTarget },
            currency,
        )
        val targetSavings = if (targetIncome.isPositive() && targetExpense.isPositive())
            targetIncome - targetExpense else null

        return MonthlySeries(
            income = incomeBars.toImmutableList(),
            expense = expenseBars.toImmutableList(),
            savings = savingsBars.toImmutableList(),
            averageIncome = avgIncome,
            averageExpense = avgExpense,
            averageSavings = avgSavings,
            targetIncome = targetIncome.takeIf { it.isPositive() },
            targetExpense = targetExpense.takeIf { it.isPositive() },
            targetSavings = targetSavings,
        )
    }

    private fun average(values: List<Money>, n: Int, currency: String): Money {
        if (n == 0) return Money.zero(currency)
        val total = Money.sumAmounts(values, currency)
        val avg = total.amount.divide(BigDecimal(n), 2, RoundingMode.HALF_EVEN)
        return Money.of(avg, currency)
    }

    private fun colorForCategory(cat: Category): Color {
        // Deterministic palette mapped from the category id hash. Saudi-style muted tones.
        val palette = listOf(
            Color(0xFFC2541C), Color(0xFF5C6B3A), Color(0xFFB58A2C), Color(0xFF8E1F1F),
            Color(0xFF6B6B68), Color(0xFF8C4A26), Color(0xFF40583E), Color(0xFF7A5E3E),
            Color(0xFF933E33), Color(0xFF566373), Color(0xFF704A28), Color(0xFF3E5B3A),
        )
        val idx = (cat.id.hashCode() and 0x7FFF_FFFF) % palette.size
        return palette[idx]
    }

    private fun buildDrilldown(
        categoryId: String,
        cats: List<Category>,
        txs: List<Transaction>,
        currency: String,
    ): DrilldownState {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val nowYm = YearMonth.of(now.year, now.monthNumber)
        val months: List<YearMonth> = (11 downTo 0).map { o -> nowYm.minusMonths(o.toLong()) }
        val bucketed = mutableMapOf<YearMonth, BigDecimal>().apply { months.forEach { put(it, BigDecimal.ZERO) } }
        txs.filter { it.categoryId == categoryId }.forEach { tx ->
            val ym = YearMonth.of(tx.date.year, tx.date.monthNumber)
            if (ym in bucketed) bucketed[ym] = bucketed.getValue(ym) + tx.amount.amount
        }
        val bars = months.map { ym -> AtharMonthlyBar(month = ym, amount = Money.of(bucketed.getValue(ym), currency)) }
            .toImmutableList()
        val total = Money.sumAmounts(bars.map { it.amount }, currency)
        val cat = cats.firstOrNull { it.id == categoryId }
        return DrilldownState(
            categoryId = categoryId,
            categoryLabelAr = cat?.nameAr.orEmpty(),
            bars = bars,
            total = total,
            monthlyTarget = cat?.monthlyTarget,
        )
    }

    companion object {
        private const val TOP_N = 8
    }
}
