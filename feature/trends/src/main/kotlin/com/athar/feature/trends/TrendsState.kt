package com.athar.feature.trends

import androidx.compose.runtime.Immutable
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.designsystem.component.AtharBarItem
import com.athar.core.designsystem.component.AtharMonthlyBar
import com.athar.core.designsystem.component.AtharSplitSegment
import com.athar.core.domain.model.Transaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class PeriodKey { WEEK, MONTH, MONTHS_3, YEAR, MONTH_VS_PREVIOUS, CUSTOM }

/** Per-category row in the period-vs-comparison table (Excel "Historical Comparison" parity). */
@Immutable
data class CategoryDeltaRow(
    val categoryId: String,
    val labelAr: String,
    val labelEn: String,
    val currentTotal: Money,
    val previousTotal: Money,
) {
    val deltaAmount: Money = currentTotal - previousTotal

    /** Null on a zero or negative baseline — an absolute delta is shown instead of a fake percentage. */
    val deltaPercent: Double? = if (!previousTotal.isPositive()) null else
        ((currentTotal.amount.toDouble() - previousTotal.amount.toDouble()) / previousTotal.amount.toDouble()) * 100.0
}

/** 12-month series for the Dashboard charts (Income / Expenses / Savings by Month). */
@Immutable
data class MonthlySeries(
    val income: ImmutableList<AtharMonthlyBar>,
    val expense: ImmutableList<AtharMonthlyBar>,
    val savings: ImmutableList<AtharMonthlyBar>,
    val averageIncome: Money,
    val averageExpense: Money,
    val averageSavings: Money,
    val targetIncome: Money?,
    val targetExpense: Money?,
    val targetSavings: Money?,
) {
    /**
     * Target-vs-actual reconciliation at 12-month granularity: the exact gap between the
     * twelve-month average and the monthly target (positive = average above target).
     * Null when no target participates. Pure BigDecimal — never Float/Double.
     */
    val incomeTargetVariance: Money? get() = targetIncome?.let { averageIncome - it }
    val expenseTargetVariance: Money? get() = targetExpense?.let { averageExpense - it }
    val savingsTargetVariance: Money? get() = targetSavings?.let { averageSavings - it }

    companion object {
        fun empty(): MonthlySeries = MonthlySeries(
            income = persistentListOf(),
            expense = persistentListOf(),
            savings = persistentListOf(),
            averageIncome = Money.zero(),
            averageExpense = Money.zero(),
            averageSavings = Money.zero(),
            targetIncome = null,
            targetExpense = null,
            targetSavings = null,
        )
    }
}

/** One selectable option in the filter sheet (real category, uncategorized, source, …). */
@Immutable
data class FilterOption(
    val id: String,
    val labelAr: String,
    val labelEn: String,
)

@Immutable
data class TrendsState(
    val periodKey: PeriodKey,
    val period: Period?,
    val totalExpense: Money,
    val totalIncome: Money,
    val previousExpense: Money,
    val previousIncome: Money,
    val savings: Money,
    val previousSavings: Money,
    val categories: ImmutableList<AtharBarItem>,
    val categoryDeltas: ImmutableList<CategoryDeltaRow>,
    val categorySplit: ImmutableList<AtharSplitSegment>,
    val monthly: MonthlySeries,
    val customStart: kotlinx.datetime.LocalDate?,
    val customEnd: kotlinx.datetime.LocalDate?,
    val isLoading: Boolean,
    // Query context — visible on screen and in every evidence sheet.
    val reportingCurrency: String = Money.SAR,
    val currencyOverride: String? = null,
    val filters: TrendsFilters = TrendsFilters(),
    val filterAccountIds: ImmutableList<String> = persistentListOf(),
    /**
     * accountId → user-provided display name, observed live from AccountRepository
     * (archived included). An absent key is a missing/deleted account — the UI renders
     * its localized unknown label and NEVER the raw id. Keys stay the actual ids.
     */
    val accountNames: Map<String, String> = emptyMap(),
    val filterCategoryOptions: ImmutableList<FilterOption> = persistentListOf(),
    val previousWindow: Period? = null,
    val previousMatchedElapsed: Boolean = false,
    val customPrior: Period.Custom? = null,
    val mixedCurrency: Boolean = false,
    val evidence: TrendsEvidenceState? = null,
    /** True when the open evidence sits on top of a previous drill (back returns to it). */
    val evidenceCanGoBack: Boolean = false,
    val detailTransaction: Transaction? = null,
    // F1B — equal-population comparison surface.
    val comparisonExpense: Money = Money.zero(),
    val comparisonIncome: Money = Money.zero(),
    val comparisonSavings: Money = Money.zero(),
    /** Equal-elapsed slice of the current window the deltas use; null = full current. */
    val comparisonWindow: Period? = null,
    /** Current tail excluded from the delta, disclosed instead of hidden. */
    val unmatchedTail: Period? = null,
    val futureRange: Boolean = false,
    val today: kotlinx.datetime.LocalDate? = null,
    val customRangeError: TrendsRangeError? = null,
    val comparisonRangeError: TrendsRangeError? = null,
) {
    // Deltas compare the equal-elapsed comparison populations — never the full headline
    // against a matched prior window. Zero or negative baselines show absolute deltas only.
    val expenseDelta: Money? =
        if (previousExpense.isZero() && comparisonExpense.isZero()) null else (comparisonExpense - previousExpense)

    val expenseDeltaPercent: Double? = if (previousExpense.isPositive())
        ((comparisonExpense.amount.toDouble() - previousExpense.amount.toDouble()) / previousExpense.amount.toDouble()) * 100.0
    else null

    val incomeDelta: Money? =
        if (previousIncome.isZero() && comparisonIncome.isZero()) null else (comparisonIncome - previousIncome)

    val incomeDeltaPercent: Double? = if (previousIncome.isPositive())
        ((comparisonIncome.amount.toDouble() - previousIncome.amount.toDouble()) / previousIncome.amount.toDouble()) * 100.0
    else null

    val savingsDelta: Money? =
        if (previousSavings.isZero() && comparisonSavings.isZero()) null else (comparisonSavings - previousSavings)

    val savingsRate: Double? =
        if (totalIncome.isZero()) null
        else savings.amount.toDouble() / totalIncome.amount.toDouble() * 100.0

    val previousSavingsRate: Double? =
        if (previousIncome.isZero()) null
        else previousSavings.amount.toDouble() / previousIncome.amount.toDouble() * 100.0

    val expensePercentOfIncome: Double? =
        if (totalIncome.isZero()) null
        else totalExpense.amount.toDouble() / totalIncome.amount.toDouble() * 100.0

    companion object {
        fun initial(): TrendsState = TrendsState(
            periodKey = PeriodKey.MONTH,
            period = null,
            totalExpense = Money.zero(),
            totalIncome = Money.zero(),
            previousExpense = Money.zero(),
            previousIncome = Money.zero(),
            savings = Money.zero(),
            previousSavings = Money.zero(),
            categories = persistentListOf(),
            categoryDeltas = persistentListOf(),
            categorySplit = persistentListOf(),
            monthly = MonthlySeries.empty(),
            customStart = null,
            customEnd = null,
            isLoading = true,
        )
    }
}
