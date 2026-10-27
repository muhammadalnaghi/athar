package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.common.time.previous
import com.athar.core.domain.calc.OperatingTransactions
import com.athar.core.domain.calc.selectOperatingTransactions
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.Transaction
import com.athar.core.designsystem.component.AtharMonthlyBar
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/** Resolved comparison windows for one query evaluation. */
data class TrendsWindows(
    val current: Period,
    val previous: Period?,
    /** True when [previous] is the equal-elapsed slice of the previous window (in-progress current). */
    val previousMatchedElapsed: Boolean,
    /** Equal-elapsed slice of the CURRENT window the deltas use; null = the full current participates. */
    val comparisonCurrent: Period? = null,
    /** Current tail excluded from the delta (never silently folded into it). */
    val unmatchedTail: Period? = null,
    /** Exclusive as-of bound: actual postings on/after it are excluded from every population. */
    val asOfExclusive: LocalDate? = null,
    /** True when the selected period starts at/after the as-of bound: nothing has elapsed yet. */
    val futureRange: Boolean = false,
)

/** One expense bucket (a real category, uncategorized, or the Other roll-up). */
data class CategoryAggregate(
    val key: String,
    val labelAr: String,
    val labelEn: String,
    val total: Money,
    val transactionIds: List<String>,
)

/** Pure result of the Trends aggregation for one query evaluation. */
data class TrendsAggregates(
    val reportingCurrency: String,
    val windows: TrendsWindows,
    val totalExpense: Money,
    val totalIncome: Money,
    val previousExpense: Money,
    val previousIncome: Money,
    val savings: Money,
    val previousSavings: Money,
    /** Comparison-side totals: the equal-elapsed CURRENT population the deltas actually use. */
    val comparisonExpense: Money,
    val comparisonIncome: Money,
    val comparisonSavings: Money,
    /** Ranked buckets, explicit [UNCATEGORIZED_KEY] even below the cutoff, and [OTHER_KEY] last. */
    val expenseBreakdown: List<CategoryAggregate>,
    val deltaRows: List<CategoryDeltaRow>,
    val monthly: MonthlySeries,
    /** True when any current/prior/monthly window contained rows in a currency other than [reportingCurrency]; those rows are excluded, not converted. */
    val mixedCurrency: Boolean,
    val currentOperating: OperatingTransactions,
    val priorOperating: OperatingTransactions,
    val comparisonOperating: OperatingTransactions,
    val monthlyOperating: OperatingTransactions,
)

const val DEFAULT_TOP_N = 8

/** Resolves the current + comparison windows for a query as of [today]. */
fun computeTrendsWindows(query: TrendsQuery, today: LocalDate): TrendsWindows {
    val current = currentPeriod(query, today)
    // Half-open as-of bound: actual postings on/after it never enter any population.
    val asOf = minOf(current.endExclusive, today.plus(DatePeriod(days = 1)))
    val futureRange = current.start >= asOf
    val customPrior = query.comparison.customPrior
    val customComparison = query.comparison.mode == ComparisonMode.CUSTOM_PRIOR && customPrior != null
    val previous = if (customComparison) customPrior!! else current.previous()
    val inProgress = today < current.endExclusive && !futureRange
    val windows = TrendsWindows(
        current = current,
        previous = previous,
        previousMatchedElapsed = !customComparison && inProgress,
        asOfExclusive = asOf,
        futureRange = futureRange,
    )
    if (customComparison || !inProgress) return windows
    // In-progress period: BOTH sides of the delta use the common elapsed day count, so
    // "month to date vs month to date" is never a fake comparison — and when the prior
    // window is shorter (Feb vs Mar), the current side is truncated to match, with the
    // excluded tail exposed instead of silently folded into the delta.
    val elapsedDays = current.start.daysUntil(asOf).coerceAtLeast(0)
    val previousLength = previous.start.daysUntil(previous.endExclusive)
    val common = elapsedDays.coerceAtMost(previousLength)
    if (common <= 0) return windows
    val previousSlice = Period.Custom(previous.start, previous.start.plus(DatePeriod(days = common)))
    return if (common < elapsedDays) {
        val currentSlice = Period.Custom(current.start, current.start.plus(DatePeriod(days = common)))
        windows.copy(
            previous = previousSlice,
            comparisonCurrent = currentSlice,
            unmatchedTail = Period.Custom(currentSlice.endExclusive, asOf),
        )
    } else {
        windows.copy(previous = previousSlice)
    }
}

fun currentPeriod(query: TrendsQuery, today: LocalDate): Period {
    val ym = YearMonth.of(today.year, today.monthNumber)
    return when (query.periodKey) {
        PeriodKey.WEEK -> Period.Custom(
            start = today.minus(DatePeriod(days = 6)),
            endExclusive = today.plus(DatePeriod(days = 1)),
        )
        PeriodKey.MONTH -> Period.Month(ym)
        PeriodKey.MONTHS_3 -> Period.Last(months = 3, endingAt = today)
        PeriodKey.YEAR -> Period.Year(today.year)
        PeriodKey.MONTH_VS_PREVIOUS -> Period.Month(ym)
        PeriodKey.CUSTOM -> query.customRange ?: Period.Month(ym)
    }
}

fun monthsWindow(today: LocalDate, count: Int = 12): List<YearMonth> {
    val nowYm = YearMonth.of(today.year, today.monthNumber)
    return ((count - 1) downTo 0).map { nowYm.minusMonths(it.toLong()) }
}

fun periodCovering(months: List<YearMonth>): Period {
    val first = months.first()
    val last = months.last()
    val lastEnd = last.plusMonths(1)
    return Period.Custom(
        LocalDate(first.year, first.monthValue, 1),
        LocalDate(lastEnd.year, lastEnd.monthValue, 1),
    )
}

/**
 * Pure aggregation: filters apply first (persisted fields only), then the shared
 * Plan001 [selectOperatingTransactions] parity selector — confirmed, non-reconciliation
 * rows only, transfers never counted as income or spend. Uncategorized expenses stay
 * in every total and breakdown under [UNCATEGORIZED_KEY].
 */
fun aggregateTrends(
    currentTxs: List<Transaction>,
    priorTxs: List<Transaction>,
    monthlyTxs: List<Transaction>,
    months: List<YearMonth>,
    categories: List<Category>,
    filters: TrendsFilters,
    reportingCurrency: String,
    windows: TrendsWindows,
    topN: Int = DEFAULT_TOP_N,
): TrendsAggregates {
    val currentFiltered = currentTxs.filter(filters::matches)
    val priorFiltered = priorTxs.filter(filters::matches)
    val monthlyFiltered = monthlyTxs.filter(filters::matches)
    // Currency-safe selection: only rows already denominated in the reporting currency
    // participate in every aggregate. Other-currency rows are excluded outright — never
    // summed or relabelled, since no FX conversion happens in this app.
    val inReportingCurrency = { tx: Transaction -> tx.amount.currency == reportingCurrency }
    // As-of bound: future-dated actuals are excluded from every population, consistently.
    val inAsOf = { tx: Transaction -> windows.asOfExclusive == null || tx.date < windows.asOfExclusive }
    val currentOp = selectOperatingTransactions(currentFiltered.filter(inReportingCurrency).filter(inAsOf))
    val priorOp = selectOperatingTransactions(priorFiltered.filter(inReportingCurrency).filter(inAsOf))
    val monthlyOp = selectOperatingTransactions(monthlyFiltered.filter(inReportingCurrency).filter(inAsOf))
    // Equal-elapsed current side of the comparison; the headline above stays full.
    val comparisonPeriod = windows.comparisonCurrent
    val comparisonOp = if (comparisonPeriod == null) currentOp else
        selectOperatingTransactions(
            currentFiltered.filter(inReportingCurrency).filter(inAsOf).filter { comparisonPeriod.contains(it.date) },
        )

    val totalExpense = sumOf(currentOp.expenses, reportingCurrency)
    val totalIncome = sumOf(currentOp.income, reportingCurrency)
    val previousExpense = sumOf(priorOp.expenses, reportingCurrency)
    val previousIncome = sumOf(priorOp.income, reportingCurrency)
    val comparisonExpense = sumOf(comparisonOp.expenses, reportingCurrency)
    val comparisonIncome = sumOf(comparisonOp.income, reportingCurrency)

    val categoryById = categories.associateBy { it.id }
    val breakdown = breakdownWithOther(currentOp.expenses, categoryById, reportingCurrency, topN)

    // Delta rows compare the equal-elapsed populations, not the full headline.
    val currentByKey = expenseTotalsByKey(comparisonOp.expenses, reportingCurrency)
    val priorByKey = expenseTotalsByKey(priorOp.expenses, reportingCurrency)
    val deltaRows = (currentByKey.keys + priorByKey.keys).map { key ->
        val currentTotal = currentByKey[key] ?: Money.zero(reportingCurrency)
        val previousTotal = priorByKey[key] ?: Money.zero(reportingCurrency)
        CategoryDeltaRow(
            categoryId = key,
            labelAr = labelOf(key, categoryById, arabic = true),
            labelEn = labelOf(key, categoryById, arabic = false),
            currentTotal = currentTotal,
            previousTotal = previousTotal,
        )
    }.sortedByDescending { maxOf(it.currentTotal.amount, it.previousTotal.amount) }

    // Truthful flag: any window had rows in a currency other than the reporting one.
    // Those rows are excluded from every aggregate above, not converted.
    val mixedCurrency = (currentFiltered + priorFiltered + monthlyFiltered)
        .any { it.amount.currency != reportingCurrency }

    return TrendsAggregates(
        reportingCurrency = reportingCurrency,
        windows = windows,
        totalExpense = totalExpense,
        totalIncome = totalIncome,
        previousExpense = previousExpense,
        previousIncome = previousIncome,
        savings = totalIncome - totalExpense,
        previousSavings = previousIncome - previousExpense,
        comparisonExpense = comparisonExpense,
        comparisonIncome = comparisonIncome,
        comparisonSavings = comparisonIncome - comparisonExpense,
        expenseBreakdown = breakdown,
        deltaRows = deltaRows,
        monthly = buildMonthlySeries(monthlyOp, months, categories, reportingCurrency),
        mixedCurrency = mixedCurrency,
        currentOperating = currentOp,
        priorOperating = priorOp,
        comparisonOperating = comparisonOp,
        monthlyOperating = monthlyOp,
    )
}

private fun sumOf(txs: List<Transaction>, currency: String): Money =
    Money.sumAmounts(txs.map { it.amount }, currency)

private fun expenseTotalsByKey(txs: List<Transaction>, currency: String): Map<String, Money> =
    txs.groupBy { it.categoryId ?: UNCATEGORIZED_KEY }
        .mapValues { (_, list) -> sumOf(list, currency) }

private fun labelOf(key: String, categoryById: Map<String, Category>, arabic: Boolean): String {
    val cat = categoryById[key] ?: return ""
    return if (arabic) cat.nameAr else cat.name
}

/**
 * Builds the ranked top-N breakdown, retaining an explicit uncategorized bucket
 * even below the cutoff. Other contains only the remaining assigned categories.
 */
private fun breakdownWithOther(
    expenses: List<Transaction>,
    categoryById: Map<String, Category>,
    currency: String,
    topN: Int,
): List<CategoryAggregate> {
    val byKey = expenses.groupBy { it.categoryId ?: UNCATEGORIZED_KEY }
    val all = byKey.map { (key, list) ->
        val cat = categoryById[key]
        CategoryAggregate(
            key = key,
            labelAr = cat?.nameAr.orEmpty(),
            labelEn = cat?.name.orEmpty(),
            total = sumOf(list, currency),
            transactionIds = list.map { it.id },
        )
    }.sortedByDescending { it.total.amount }
    if (all.size <= topN) return all
    val top = all.take(topN)
    val rest = all.drop(topN)
    val visible = top + listOfNotNull(rest.firstOrNull { it.key == UNCATEGORIZED_KEY })
    val assignedRest = rest.filterNot { it.key == UNCATEGORIZED_KEY }
    if (assignedRest.isEmpty()) return visible
    val other = CategoryAggregate(
        key = OTHER_KEY,
        labelAr = "",
        labelEn = "",
        total = Money.sumAmounts(assignedRest.map { it.total }, currency),
        transactionIds = assignedRest.flatMap { it.transactionIds },
    )
    return visible + other
}

private fun buildMonthlySeries(
    operating: OperatingTransactions,
    months: List<YearMonth>,
    categories: List<Category>,
    currency: String,
): MonthlySeries {
    val incomeByMonth = zeroedMonths(months)
    val expenseByMonth = zeroedMonths(months)
    operating.income.forEach { tx -> addToMonth(incomeByMonth, tx, months) }
    operating.expenses.forEach { tx -> addToMonth(expenseByMonth, tx, months) }
    val incomeBars = months.map { ym -> AtharMonthlyBar(ym, Money.of(incomeByMonth.getValue(ym), currency)) }
    val expenseBars = months.map { ym -> AtharMonthlyBar(ym, Money.of(expenseByMonth.getValue(ym), currency)) }
    val savingsBars = months.map { ym ->
        AtharMonthlyBar(ym, Money.of(incomeByMonth.getValue(ym) - expenseByMonth.getValue(ym), currency))
    }
    val n = months.size
    // Targets participate only when their actual currency matches the reporting currency.
    val targetExpense = Money.sumAmounts(
        categories.filter { it.kind == CategoryKind.EXPENSE }
            .mapNotNull { it.monthlyTarget?.takeIf { t -> t.currency == currency } },
        currency,
    )
    val targetIncome = Money.sumAmounts(
        categories.filter { it.kind == CategoryKind.INCOME }
            .mapNotNull { it.monthlyTarget?.takeIf { t -> t.currency == currency } },
        currency,
    )
    val targetSavings = if (targetIncome.isPositive() && targetExpense.isPositive())
        targetIncome - targetExpense else null
    return MonthlySeries(
        income = incomeBars.toImmutableList(),
        expense = expenseBars.toImmutableList(),
        savings = savingsBars.toImmutableList(),
        averageIncome = average(incomeBars.map { it.amount }, n, currency),
        averageExpense = average(expenseBars.map { it.amount }, n, currency),
        averageSavings = average(savingsBars.map { it.amount }, n, currency),
        targetIncome = targetIncome.takeIf { it.isPositive() },
        targetExpense = targetExpense.takeIf { it.isPositive() },
        targetSavings = targetSavings,
    )
}

private fun zeroedMonths(months: List<YearMonth>) =
    months.associateWith { BigDecimal.ZERO }.toMutableMap()

private fun addToMonth(byMonth: MutableMap<YearMonth, BigDecimal>, tx: Transaction, months: List<YearMonth>) {
    val ym = YearMonth.of(tx.date.year, tx.date.monthNumber)
    if (ym in byMonth) byMonth[ym] = byMonth.getValue(ym) + tx.amount.amount
}

private fun average(values: List<Money>, n: Int, currency: String): Money {
    if (n == 0) return Money.zero(currency)
    val total = Money.sumAmounts(values, currency)
    return Money.of(total.amount.divide(BigDecimal(n), 2, RoundingMode.HALF_EVEN), currency)
}
