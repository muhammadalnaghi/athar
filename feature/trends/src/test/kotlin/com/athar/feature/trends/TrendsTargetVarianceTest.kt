package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Target-vs-actual reconciliation on [MonthlySeries]: the twelve-month averages and
 * their exact gaps to the category monthly targets, computed purely with BigDecimal
 * (HALF_EVEN, scale 2). Currency-mismatched targets never participate.
 */
class TrendsTargetVarianceTest {

    /** 12 months ending March 2026 — matches a 2026-03-30 "today". */
    private val months: List<YearMonth> =
        (0..11).map { YearMonth.of(2026, 3).minusMonths(it.toLong()) }.reversed()

    private val windows = computeTrendsWindows(
        TrendsQuery(periodKey = PeriodKey.CUSTOM, customRange = Period.Custom(LocalDate(2026, 3, 1), LocalDate(2026, 4, 1))),
        LocalDate(2026, 3, 30),
    )

    private fun tx(
        id: String,
        amount: String,
        date: LocalDate,
        type: TxType = TxType.EXPENSE,
    ): Transaction = Fixtures.transaction(
        id = id,
        amount = Money.of(amount, Money.SAR),
        merchant = "M$id",
        status = TxStatus.CONFIRMED,
    ).copy(
        date = date,
        type = type,
        accountId = "acc-1",
        categoryId = if (type == TxType.EXPENSE) "cat-exp" else "cat-inc",
    )

    private fun aggregate(monthlyTxs: List<Transaction>, categories: List<Category>) = aggregateTrends(
        currentTxs = emptyList(),
        priorTxs = emptyList(),
        monthlyTxs = monthlyTxs,
        months = months,
        categories = categories,
        filters = TrendsFilters(),
        reportingCurrency = Money.SAR,
        windows = windows,
    )

    @Test
    fun `twelve month averages round half even to two decimals`() {
        // 11 months at 10.00 and March at 10.06 → total 120.06 → exact half 10.005,
        // which HALF_EVEN rounds to 10.00 (even), never 10.01.
        val expenseTxs = months.dropLast(1).mapIndexed { i, ym ->
            tx("e-$i", "10.00", LocalDate(ym.year, ym.monthValue, 10))
        } + tx("e-mar", "10.06", LocalDate(2026, 3, 10))
        // 11 months at 20.00 and March at 20.05 → total 240.05 → 20.004166… → 20.00.
        val incomeTxs = months.dropLast(1).mapIndexed { i, ym ->
            tx("i-$i", "20.00", LocalDate(ym.year, ym.monthValue, 12), type = TxType.INCOME)
        } + tx("i-mar", "20.05", LocalDate(2026, 3, 12), type = TxType.INCOME)

        val monthly = aggregate(expenseTxs + incomeTxs, emptyList()).monthly

        assertThat(monthly.averageExpense.amount).isEqualTo(BigDecimal("10.00"))
        assertThat(monthly.averageIncome.amount).isEqualTo(BigDecimal("20.00"))
        // Savings average derives from the same rounded month bars: 20.00 − 10.00.
        assertThat(monthly.averageSavings.amount).isEqualTo(BigDecimal("10.00"))
    }

    @Test
    fun `target variance is exactly the twelve month average minus the monthly target`() {
        val expenseTxs = months.mapIndexed { i, ym -> tx("e-$i", "10.00", LocalDate(ym.year, ym.monthValue, 10)) }
        val incomeTxs = months.mapIndexed { i, ym -> tx("i-$i", "20.00", LocalDate(ym.year, ym.monthValue, 12), type = TxType.INCOME) }
        val categories = listOf(
            Fixtures.category(id = "cat-exp", name = "Expenses", nameAr = "مصاريف")
                .copy(monthlyTarget = Money.of("12.00", Money.SAR)),
            Fixtures.category(id = "cat-inc", name = "Income", nameAr = "دخل", kind = CategoryKind.INCOME)
                .copy(monthlyTarget = Money.of("18.00", Money.SAR)),
        )

        val monthly = aggregate(expenseTxs + incomeTxs, categories).monthly

        assertThat(monthly.targetExpense!!.amount).isEqualTo(BigDecimal("12.00"))
        assertThat(monthly.targetIncome!!.amount).isEqualTo(BigDecimal("18.00"))
        assertThat(monthly.targetSavings!!.amount).isEqualTo(BigDecimal("6.00"))
        // averageExpense 10.00 − target 12.00; averageIncome 20.00 − target 18.00;
        // averageSavings 10.00 − target 6.00. Exact subtraction, no Float/Double.
        assertThat(monthly.expenseTargetVariance!!.amount).isEqualTo(BigDecimal("-2.00"))
        assertThat(monthly.incomeTargetVariance!!.amount).isEqualTo(BigDecimal("2.00"))
        assertThat(monthly.savingsTargetVariance!!.amount).isEqualTo(BigDecimal("4.00"))
    }

    @Test
    fun `variance is null when no target participates in the reporting currency`() {
        val expenseTxs = months.mapIndexed { i, ym -> tx("e-$i", "10.00", LocalDate(ym.year, ym.monthValue, 10)) }
        val categories = listOf(
            // USD target: excluded outright (no FX conversion), so no expense variance exists.
            Fixtures.category(id = "cat-exp", name = "Expenses", nameAr = "مصاريف")
                .copy(monthlyTarget = Money.of("12.00", "USD")),
        )

        val monthly = aggregate(expenseTxs, categories).monthly

        assertThat(monthly.targetExpense).isNull()
        assertThat(monthly.expenseTargetVariance).isNull()
        assertThat(monthly.incomeTargetVariance).isNull()
        assertThat(monthly.savingsTargetVariance).isNull()
    }
}
