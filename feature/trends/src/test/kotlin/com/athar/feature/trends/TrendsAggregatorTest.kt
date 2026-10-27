package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.time.YearMonth

/**
 * Pure aggregation/evidence tests for the WM028-01 Trends query pipeline.
 * Windows are explicit inputs, so these tests are timezone-free.
 */
class TrendsAggregatorTest {

    private val cats: List<Category> = (1..9).map { i ->
        Fixtures.category(id = "cat-$i", name = "Cat $i", nameAr = "صنف $i")
    } + Fixtures.category(id = "cat-income", name = "Salary", nameAr = "راتب", kind = CategoryKind.INCOME)

    private fun tx(
        id: String,
        amount: String,
        date: LocalDate = LocalDate(2026, 2, 10),
        type: TxType = TxType.EXPENSE,
        accountId: String = "acc-1",
        categoryId: String? = "cat-1",
        merchant: String = "STARBUCKS",
        currency: String = Money.SAR,
        status: TxStatus = TxStatus.CONFIRMED,
        sourceRefId: String? = "sms-1",
    ): Transaction = Fixtures.transaction(
        id = id,
        amount = Money.of(amount, currency),
        merchant = merchant,
        status = status,
    ).copy(
        date = date,
        type = type,
        accountId = accountId,
        categoryId = categoryId,
        sourceRefId = sourceRefId,
    )

    private val feb: Period = Period.Custom(LocalDate(2026, 2, 1), LocalDate(2026, 3, 1))
    private val febWindows = TrendsWindows(
        current = feb,
        previous = Period.Custom(LocalDate(2026, 1, 1), LocalDate(2026, 2, 1)),
        previousMatchedElapsed = false,
    )
    private val months: List<YearMonth> = (0..11).map { YearMonth.of(2026, 2).minusMonths(it.toLong()) }.reversed()
    private val noFilters = TrendsFilters()

    @Test
    fun `account filter applies to full aggregates`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("a1", "100", accountId = "acc-1"),
                tx("a2", "250", accountId = "acc-2"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters.copy(accountIds = setOf("acc-2")),
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(250)
        assertThat(aggregates.currentOperating.expenses.map { it.id }).containsExactly("a2")
    }

    @Test
    fun `merchant filter matches normalized name and applies to aggregates`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("m1", "30", merchant = "STARBUCKS"),
                tx("m2", "70", merchant = "PANDA"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters.copy(merchantQuery = "panda"),
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(70)
    }

    @Test
    fun `mixed currencies select the reporting currency only and flag the excluded ones`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("sar1", "100", currency = "SAR"),
                tx("usd1", "50", currency = "USD"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = "SAR",
            windows = febWindows,
        )
        // Selected currency only: the USD row is excluded, never folded in without FX.
        assertThat(aggregates.totalExpense.currency).isEqualTo("SAR")
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(100)
        assertThat(aggregates.currentOperating.expenses.map { it.id }).containsExactly("sar1")
        assertThat(aggregates.mixedCurrency).isTrue()
    }

    @Test
    fun `prior-window foreign currency is excluded and still flagged`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(tx("cur", "100")),
            priorTxs = listOf(
                tx("prior-sar", "77"),
                tx("prior-usd", "30", currency = "USD"),
            ),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = "SAR",
            windows = febWindows,
        )
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(100)
        assertThat(aggregates.previousExpense.amount.toInt()).isEqualTo(77)
        assertThat(aggregates.priorOperating.expenses.map { it.id }).containsExactly("prior-sar")
        assertThat(aggregates.mixedCurrency).isTrue()
    }

    @Test
    fun `monthly series and evidence include the reporting currency only`() {
        val aggregates = aggregateTrends(
            currentTxs = emptyList(),
            priorTxs = emptyList(),
            monthlyTxs = listOf(
                tx("m-sar", "40", date = LocalDate(2026, 1, 9)),
                tx("m-usd", "12", date = LocalDate(2026, 1, 20), currency = "USD"),
            ),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = "SAR",
            windows = febWindows,
        )
        assertThat(aggregates.monthlyOperating.expenses.map { it.id }).containsExactly("m-sar")
        assertThat(aggregates.monthly.expense.sumOf { it.amount.amount }.toInt()).isEqualTo(40)
        assertThat(aggregates.mixedCurrency).isTrue()

        val evidence = buildEvidence(
            request = TrendsEvidenceRequest.MonthBar(YearMonth.of(2026, 1), TrendsMetric.EXPENSE),
            aggregates = aggregates,
            categories = cats,
            reportingCurrency = "SAR",
        )!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("m-sar")
        assertThat(evidence.total.currency).isEqualTo("SAR")
        assertThat(evidence.total.amount.toInt()).isEqualTo(40)
    }

    @Test
    fun `foreign-currency category target is excluded from the monthly target`() {
        val targetCats = cats +
            Fixtures.category(id = "cat-sar-target", name = "Sar target", nameAr = "هدف")
                .copy(monthlyTarget = Money.of("100", "SAR")) +
            Fixtures.category(id = "cat-usd-target", name = "Usd target", nameAr = "أجنبي")
                .copy(monthlyTarget = Money.of("50", "USD"))
        val aggregates = aggregateTrends(
            currentTxs = emptyList(),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = targetCats,
            filters = noFilters,
            reportingCurrency = "SAR",
            windows = febWindows,
        )
        val target = aggregates.monthly.targetExpense
        assertThat(target).isNotNull()
        assertThat(target!!.currency).isEqualTo("SAR")
        assertThat(target.amount.toInt()).isEqualTo(100)
    }

    @Test
    fun `explicit uncategorized selection matches null-category rows only`() {
        val filters = noFilters.copy(categoryId = UNCATEGORIZED_KEY)
        assertThat(filters.matches(tx("u1", "40", categoryId = null))).isTrue()
        assertThat(filters.matches(tx("c1", "60", categoryId = "cat-1"))).isFalse()
        // Null categoryId still means no category filter at all.
        assertThat(noFilters.matches(tx("u1", "40", categoryId = null))).isTrue()
        assertThat(noFilters.matches(tx("c1", "60", categoryId = "cat-1"))).isTrue()

        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("u1", "40", categoryId = null),
                tx("c1", "60", categoryId = "cat-1"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = filters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        assertThat(aggregates.currentOperating.expenses.map { it.id }).containsExactly("u1")
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(40)
        assertThat(aggregates.expenseBreakdown.single().key).isEqualTo(UNCATEGORIZED_KEY)
    }

    @Test
    fun `uncategorized expenses stay in totals with an explicit bucket`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("u1", "40", categoryId = null),
                tx("u2", "60", categoryId = "cat-2"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(100)
        val uncategorized = aggregates.expenseBreakdown.first { it.key == UNCATEGORIZED_KEY }
        assertThat(uncategorized.total.amount.toInt()).isEqualTo(40)
        assertThat(uncategorized.transactionIds).containsExactly("u1")
    }

    @Test
    fun `transfers and reconciliation rows never count as spend or income`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("t1", "500", type = TxType.TRANSFER),
                tx("r1", "9000", sourceRefId = "reconcile-acc-1", categoryId = null)
                    .copy(source = IngestSource.MANUAL),
                tx("e1", "80"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(80)
        assertThat(aggregates.totalIncome.amount.toInt()).isEqualTo(0)
    }

    @Test
    fun `other bucket membership is exactly the non-top-N categories`() {
        val txs = (1..9).map { i -> tx("o$i", (10 * i).toString(), categoryId = "cat-$i") }
        val aggregates = aggregateTrends(
            currentTxs = txs,
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
            topN = 3,
        )
        val topKeys = aggregates.expenseBreakdown.take(3).map { it.key }
        val other = aggregates.expenseBreakdown.last { it.key == OTHER_KEY }
        val expectedRemaining = (1..9).map { "cat-$it" }.filter { it !in topKeys }.toSet()
        assertThat(topKeys).doesNotContain(OTHER_KEY)
        assertThat(other.transactionIds.map { it })
            .containsExactlyElementsIn(txs.filter { it.categoryId in expectedRemaining }.map { it.id })
    }

    @Test
    fun `zero baseline yields absolute delta without a fake percent`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(tx("z1", "300")),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        val row = aggregates.deltaRows.first { it.categoryId == "cat-1" }
        assertThat(row.currentTotal.amount.toInt()).isEqualTo(300)
        assertThat(row.previousTotal.isZero()).isTrue()
        assertThat(row.deltaPercent).isNull()
        assertThat(row.deltaAmount.amount.toInt()).isEqualTo(300)
    }

    @Test
    fun `contradictory transfer type filter yields an honest empty state`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(tx("c1", "120")),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters.copy(type = TxType.TRANSFER),
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        assertThat(aggregates.totalExpense.isZero()).isTrue()
        assertThat(aggregates.totalIncome.isZero()).isTrue()
        assertThat(aggregates.expenseBreakdown).isEmpty()
        assertThat(aggregates.deltaRows).isEmpty()
    }

    @Test
    fun `monthly buckets ignore transactions outside the twelve-month window`() {
        val aggregates = aggregateTrends(
            currentTxs = emptyList(),
            priorTxs = emptyList(),
            monthlyTxs = listOf(
                tx("in1", "99", date = LocalDate(2025, 3, 14)),
                tx("out1", "999", date = LocalDate(2024, 6, 1)),
            ),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        val mar2025 = aggregates.monthly.expense.first { it.month == YearMonth.of(2025, 3) }
        // Out-of-window rows contribute nothing anywhere (no bar months beyond the window either).
        assertThat(mar2025.amount.amount.toInt()).isEqualTo(99)
        assertThat(aggregates.monthly.expense.sumOf { it.amount.amount }.toInt()).isEqualTo(99)
    }

    @Test
    fun `evidence for a bucket returns exactly that membership with original currencies`() {
        val txs = listOf(
            tx("e1", "10", categoryId = "cat-1"),
            tx("e2", "20", categoryId = "cat-2"),
            tx("e3", "30", categoryId = "cat-3"),
            tx("e4", "5", categoryId = "cat-4"),
        )
        val aggregates = aggregateTrends(
            currentTxs = txs,
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
            topN = 2,
        )
        val otherEvidence = buildEvidence(
            request = TrendsEvidenceRequest.Bucket(OTHER_KEY),
            aggregates = aggregates,
            categories = cats,
            reportingCurrency = Money.SAR,
        )!!
        // topN = 2 → top = cat-3 (30) and cat-2 (20); Other = cat-1 + cat-4, exactly.
        assertThat(otherEvidence.transactions.map { it.id }).containsExactly("e1", "e4")
        assertThat(otherEvidence.total.amount.toInt()).isEqualTo(15)

        val catEvidence = buildEvidence(
            request = TrendsEvidenceRequest.Bucket("cat-1"),
            aggregates = aggregates,
            categories = cats,
            reportingCurrency = Money.SAR,
        )!!
        assertThat(catEvidence.transactions.map { it.id }).containsExactly("e1")

        val headline = buildEvidence(
            request = TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE),
            aggregates = aggregates,
            categories = cats,
            reportingCurrency = Money.SAR,
        )!!
        assertThat(headline.transactions.map { it.id }).containsExactly("e1", "e2", "e3", "e4").inOrder()
    }

    @Test
    fun `evidence for a month bar returns that month only`() {
        val aggregates = aggregateTrends(
            currentTxs = emptyList(),
            priorTxs = emptyList(),
            monthlyTxs = listOf(
                tx("jan1", "11", date = LocalDate(2025, 12, 3)),
                tx("jan2", "22", date = LocalDate(2026, 1, 9)),
            ),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = Money.SAR,
            windows = febWindows,
        )
        val evidence = buildEvidence(
            request = TrendsEvidenceRequest.MonthBar(YearMonth.of(2026, 1), TrendsMetric.EXPENSE),
            aggregates = aggregates,
            categories = cats,
            reportingCurrency = Money.SAR,
        )!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("jan2")
        assertThat(evidence.total.amount.toInt()).isEqualTo(22)
    }

    @Test
    fun `selected foreign currency rows keep their original currency in evidence`() {
        val aggregates = aggregateTrends(
            currentTxs = listOf(
                tx("usd-row", "12", currency = "USD"),
                tx("sar-row", "30"),
            ),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = months,
            categories = cats,
            filters = noFilters,
            reportingCurrency = "USD",
            windows = febWindows,
        )
        val evidence = buildEvidence(
            request = TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE),
            aggregates = aggregates,
            categories = cats,
            reportingCurrency = "USD",
        )!!
        // Only the reporting-currency row is selected — and it keeps its real currency.
        assertThat(evidence.transactions.map { it.id }).containsExactly("usd-row")
        assertThat(evidence.transactions.single().amount.currency).isEqualTo("USD")
        assertThat(evidence.transactions.single().amount.amount.toInt()).isEqualTo(12)
        assertThat(evidence.total.amount.toInt()).isEqualTo(12)
    }
}
