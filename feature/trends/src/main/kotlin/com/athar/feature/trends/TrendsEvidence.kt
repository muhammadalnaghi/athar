package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.calc.OperatingTransactions
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.Transaction
import com.athar.core.designsystem.component.AtharMonthlyBar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.math.BigDecimal
import java.time.YearMonth

/** What the user drilled into — every aggregate surface has a matching request. */
sealed interface TrendsEvidenceRequest {
    data class Headline(val metric: TrendsMetric) : TrendsEvidenceRequest
    /** Typed selection behind a headline delta click: BOTH exact delta populations. */
    data class HeadlineComparison(val metric: TrendsMetric) : TrendsEvidenceRequest
    /** A real category id, [UNCATEGORIZED_KEY], or [OTHER_KEY]. */
    data class Bucket(val key: String) : TrendsEvidenceRequest
    data class MonthBar(val month: YearMonth, val metric: TrendsMetric) : TrendsEvidenceRequest
    /** BOTH exact compared populations behind one category delta row. */
    data class CategoryComparison(val key: String) : TrendsEvidenceRequest
    /** One bucket restricted to exactly one month of its own frozen-membership trend. */
    data class MonthInBucket(val month: YearMonth, val key: String) : TrendsEvidenceRequest
}

/**
 * Evidence for one drilldown: the FULL filtered transaction list behind the figure
 * (never page-limited), the matching total, and — for bucket drilldowns — the
 * twelve-month trend of exactly that membership plus its monthly target.
 */
data class TrendsEvidenceState(
    val request: TrendsEvidenceRequest,
    val metric: TrendsMetric,
    val transactions: ImmutableList<Transaction>,
    val total: Money,
    /** id → (arabic, english) label for the category picker/detail rows. */
    val categoryLabels: Map<String, Pair<String, String>>,
    val monthlyBars: ImmutableList<AtharMonthlyBar>?,
    val monthlyTarget: Money?,
    /** Present only for [TrendsEvidenceRequest.HeadlineComparison]: both exact delta populations. */
    val comparison: ComparisonEvidence? = null,
)

/**
 * The two exact populations a headline delta compares, with their window labels. The
 * current side is the equal-elapsed matched slice (never the full headline total
 * presented under a matched-window label).
 */
data class ComparisonEvidence(
    val currentRows: ImmutableList<Transaction>,
    val currentTotal: Money,
    val currentWindow: Period,
    val priorRows: ImmutableList<Transaction>,
    val priorTotal: Money,
    val priorWindow: Period,
)

/**
 * Pure evidence builder. Rows keep their original [Money] currency (rendered by
 * [com.athar.core.designsystem.component.AtharNumber]); ordering is date-desc, stable
 * on input order for equal dates.
 */
fun buildEvidence(
    request: TrendsEvidenceRequest,
    aggregates: TrendsAggregates,
    categories: List<Category>,
    reportingCurrency: String,
): TrendsEvidenceState? {
    val currency = reportingCurrency
    val categoryLabels = categories.associate { it.id to (it.nameAr to it.name) }

    fun rowsOf(op: OperatingTransactions, metric: TrendsMetric): List<Transaction> = when (metric) {
        TrendsMetric.EXPENSE -> op.expenses
        TrendsMetric.INCOME -> op.income
        TrendsMetric.SAVINGS -> op.expenses + op.income
    }.sortedByDescending { it.date }

    fun totalOf(txs: List<Transaction>, metric: TrendsMetric): Money = when (metric) {
        TrendsMetric.EXPENSE -> Money.sumAmounts(txs.filter { it.type == com.athar.core.domain.model.TxType.EXPENSE }.map { it.amount }, currency)
        TrendsMetric.INCOME -> Money.sumAmounts(txs.filter { it.type == com.athar.core.domain.model.TxType.INCOME }.map { it.amount }, currency)
        TrendsMetric.SAVINGS -> {
            val income = Money.sumAmounts(txs.filter { it.type == com.athar.core.domain.model.TxType.INCOME }.map { it.amount }, currency)
            val expense = Money.sumAmounts(txs.filter { it.type == com.athar.core.domain.model.TxType.EXPENSE }.map { it.amount }, currency)
            income - expense
        }
    }

    return when (request) {
        is TrendsEvidenceRequest.Headline -> {
            val rows = rowsOf(aggregates.currentOperating, request.metric)
            TrendsEvidenceState(
                request = request,
                metric = request.metric,
                transactions = rows.toImmutableList(),
                total = totalOf(rows, request.metric),
                categoryLabels = categoryLabels,
                monthlyBars = null,
                monthlyTarget = null,
            )
        }

        is TrendsEvidenceRequest.HeadlineComparison -> {
            val currentRows = rowsOf(aggregates.comparisonOperating, request.metric)
            val priorRows = rowsOf(aggregates.priorOperating, request.metric)
            val currentTotal = totalOf(currentRows, request.metric)
            TrendsEvidenceState(
                request = request,
                metric = request.metric,
                transactions = currentRows.toImmutableList(),
                total = currentTotal,
                categoryLabels = categoryLabels,
                monthlyBars = null,
                monthlyTarget = null,
                comparison = ComparisonEvidence(
                    currentRows = currentRows.toImmutableList(),
                    currentTotal = currentTotal,
                    currentWindow = aggregates.windows.comparisonCurrent ?: aggregates.windows.current,
                    priorRows = priorRows.toImmutableList(),
                    priorTotal = totalOf(priorRows, request.metric),
                    priorWindow = aggregates.windows.previous ?: aggregates.windows.current,
                ),
            )
        }

        is TrendsEvidenceRequest.MonthBar -> {
            val inMonth = { tx: Transaction ->
                YearMonth.of(tx.date.year, tx.date.monthNumber) == request.month
            }
            val op = OperatingTransactions(
                income = aggregates.monthlyOperating.income.filter(inMonth),
                expenses = aggregates.monthlyOperating.expenses.filter(inMonth),
            )
            val rows = rowsOf(op, request.metric)
            TrendsEvidenceState(
                request = request,
                metric = request.metric,
                transactions = rows.toImmutableList(),
                total = totalOf(rows, request.metric),
                categoryLabels = categoryLabels,
                monthlyBars = null,
                monthlyTarget = null,
            )
        }

        is TrendsEvidenceRequest.CategoryComparison -> {
            val membership = bucketMembership(request.key, aggregates)
            val currentRows = aggregates.comparisonOperating.expenses.filter(membership).sortedByDescending { it.date }
            val priorRows = aggregates.priorOperating.expenses.filter(membership).sortedByDescending { it.date }
            val currentTotal = Money.sumAmounts(currentRows.map { it.amount }, currency)
            TrendsEvidenceState(
                request = request,
                metric = TrendsMetric.EXPENSE,
                transactions = currentRows.toImmutableList(),
                total = currentTotal,
                categoryLabels = categoryLabels,
                monthlyBars = null,
                monthlyTarget = null,
                comparison = ComparisonEvidence(
                    currentRows = currentRows.toImmutableList(),
                    currentTotal = currentTotal,
                    currentWindow = aggregates.windows.comparisonCurrent ?: aggregates.windows.current,
                    priorRows = priorRows.toImmutableList(),
                    priorTotal = Money.sumAmounts(priorRows.map { it.amount }, currency),
                    priorWindow = aggregates.windows.previous ?: aggregates.windows.current,
                ),
            )
        }

        is TrendsEvidenceRequest.MonthInBucket -> {
            val membership = bucketMembership(request.key, aggregates)
            val rows = aggregates.monthlyOperating.expenses
                .filter { membership(it) && YearMonth.of(it.date.year, it.date.monthNumber) == request.month }
                .sortedByDescending { it.date }
            TrendsEvidenceState(
                request = request,
                metric = TrendsMetric.EXPENSE,
                transactions = rows.toImmutableList(),
                total = Money.sumAmounts(rows.map { it.amount }, currency),
                categoryLabels = categoryLabels,
                monthlyBars = null,
                monthlyTarget = null,
            )
        }

        is TrendsEvidenceRequest.Bucket -> {
            val aggregate = aggregates.expenseBreakdown.firstOrNull { it.key == request.key }
            val rows = when {
                request.key == UNCATEGORIZED_KEY ->
                    aggregates.currentOperating.expenses.filter { it.categoryId == null }
                request.key == OTHER_KEY -> {
                    val membership = aggregate?.transactionIds?.toSet() ?: emptySet()
                    aggregates.currentOperating.expenses.filter { it.id in membership }
                }
                aggregate != null -> {
                    val membership = aggregate.transactionIds.toSet()
                    aggregates.currentOperating.expenses.filter { it.id in membership }
                }
                // A real category outside the visible top N (reached from a delta row)
                // still drills into exactly its own transactions.
                else ->
                    aggregates.currentOperating.expenses.filter { it.categoryId == request.key }
            }.sortedByDescending { it.date }
            // Twelve-month trend of exactly this bucket's membership rule.
            val trend = when {
                request.key == UNCATEGORIZED_KEY -> monthlyTrend(aggregates) { it.categoryId == null }
                request.key == OTHER_KEY -> monthlyTrend(aggregates, bucketMembership(OTHER_KEY, aggregates))
                else -> monthlyTrend(aggregates) { it.categoryId == request.key }
            }
            TrendsEvidenceState(
                request = request,
                metric = TrendsMetric.EXPENSE,
                transactions = rows.toImmutableList(),
                total = Money.sumAmounts(rows.map { it.amount }, currency),
                categoryLabels = categoryLabels,
                monthlyBars = trend.toImmutableList(),
                // Target participates only when its actual currency matches the reporting currency.
                monthlyTarget = categories.firstOrNull { it.id == request.key }
                    ?.monthlyTarget?.takeIf { it.currency == currency },
            )
        }
    }
}

private fun monthlyTrend(
    aggregates: TrendsAggregates,
    predicate: (Transaction) -> Boolean,
): List<AtharMonthlyBar> {
    val months = aggregates.monthly.expense.map { it.month }
    val byMonth = months.associateWith { BigDecimal.ZERO }.toMutableMap()
    aggregates.monthlyOperating.expenses.filter(predicate).forEach { tx ->
        val ym = YearMonth.of(tx.date.year, tx.date.monthNumber)
        if (ym in byMonth) byMonth[ym] = byMonth.getValue(ym) + tx.amount.amount
    }
    return months.map { ym ->
        AtharMonthlyBar(ym, Money.of(byMonth.getValue(ym), aggregates.reportingCurrency))
    }
}

/**
 * The frozen membership rule of one bucket key — the SAME rule for the current-window
 * rows, the twelve-month trend, and month-in-bucket drilldowns. For [OTHER_KEY] the
 * excluded top keys are frozen from the visible breakdown once, never recomputed
 * month-specifically.
 */
internal fun bucketMembership(key: String, aggregates: TrendsAggregates): (Transaction) -> Boolean {
    val topKeys = aggregates.expenseBreakdown
        .filter { it.key != OTHER_KEY }
        .mapTo(mutableSetOf()) { it.key }
    return when (key) {
        UNCATEGORIZED_KEY -> { tx -> tx.categoryId == null }
        OTHER_KEY -> { tx -> tx.categoryId != null && tx.categoryId !in topKeys }
        else -> { tx -> tx.categoryId == key }
    }
}
