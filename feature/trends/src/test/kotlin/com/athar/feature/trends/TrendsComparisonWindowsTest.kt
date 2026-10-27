package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.time.YearMonth

/**
 * F1B — equal-population comparisons, as-of bounds, and comparison evidence.
 *
 * The headline stays the full as-of actual period, but every DELTA compares equal
 * elapsed populations: the current side is truncated to the same number of elapsed
 * days as the prior window, and the excluded current tail is exposed — never summed
 * into a delta while being labeled as matched.
 */
class TrendsComparisonWindowsTest {

    private fun tx(
        id: String,
        amount: String,
        date: LocalDate,
        type: TxType = TxType.EXPENSE,
        categoryId: String? = "cat-1",
        currency: String = Money.SAR,
        status: TxStatus = TxStatus.CONFIRMED,
    ): Transaction = Fixtures.transaction(
        id = id,
        amount = Money.of(amount, currency),
        merchant = "M$id",
        status = status,
    ).copy(
        date = date,
        type = type,
        accountId = "acc-1",
        categoryId = categoryId,
        sourceRefId = "sms-$id",
    )

    /** 12 months ending March 2026 — matches a 2026-03-30 "today". */
    private val monthsToMarch: List<YearMonth> =
        (0..11).map { YearMonth.of(2026, 3).minusMonths(it.toLong()) }.reversed()

    // ------------------------------------------------------------------ windows

    @Test
    fun `month end mismatch matches common elapsed days and exposes the excluded tail`() {
        // Today is Mar 30: current = Mar 1–31 (30 elapsed days), previous = Feb (28 days).
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 3, 30))

        assertThat(windows.previousMatchedElapsed).isTrue()
        assertThat(windows.previous!!.start).isEqualTo(LocalDate(2026, 2, 1))
        assertThat(windows.previous.endExclusive).isEqualTo(LocalDate(2026, 3, 1))
        // Current side of the delta is truncated to the same 28 days.
        assertThat(windows.comparisonCurrent).isEqualTo(Period.Custom(LocalDate(2026, 3, 1), LocalDate(2026, 3, 29)))
        // The excluded tail is exactly the rest of the elapsed current window.
        assertThat(windows.unmatchedTail).isEqualTo(Period.Custom(LocalDate(2026, 3, 29), LocalDate(2026, 3, 31)))
        assertThat(windows.asOfExclusive).isEqualTo(LocalDate(2026, 3, 31))
    }

    @Test
    fun `mid month comparison keeps the whole elapsed current without a tail`() {
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 2, 14))

        assertThat(windows.previousMatchedElapsed).isTrue()
        assertThat(windows.previous!!.start).isEqualTo(LocalDate(2026, 1, 1))
        assertThat(windows.previous.endExclusive).isEqualTo(LocalDate(2026, 1, 15))
        assertThat(windows.comparisonCurrent).isNull()
        assertThat(windows.unmatchedTail).isNull()
    }

    @Test
    fun `completed custom period compares full previous without slices`() {
        val query = TrendsQuery(
            periodKey = PeriodKey.CUSTOM,
            customRange = Period.Custom(LocalDate(2026, 1, 1), LocalDate(2026, 2, 1)),
        )
        val windows = computeTrendsWindows(query, LocalDate(2026, 2, 14))

        assertThat(windows.previousMatchedElapsed).isFalse()
        assertThat(windows.comparisonCurrent).isNull()
        assertThat(windows.unmatchedTail).isNull()
        assertThat(windows.previous!!.endExclusive).isEqualTo(LocalDate(2026, 1, 1))
    }

    @Test
    fun `future starting explicit range is flagged instead of producing negative durations`() {
        val query = TrendsQuery(
            periodKey = PeriodKey.CUSTOM,
            customRange = Period.Custom(LocalDate(2026, 3, 1), LocalDate(2026, 4, 1)),
        )
        val windows = computeTrendsWindows(query, LocalDate(2026, 2, 14))

        assertThat(windows.futureRange).isTrue()
        assertThat(windows.comparisonCurrent).isNull()
        assertThat(windows.unmatchedTail).isNull()
    }

    // --------------------------------------------------------------- aggregation

    @Test
    fun `aggregation compares equal populations and keeps the full headline`() {
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 3, 30))
        val currentTxs = (1..30).map { d -> tx("d$d", "10", LocalDate(2026, 3, d)) }
        val priorTxs = (1..28).map { d -> tx("f$d", "1", LocalDate(2026, 2, d)) }

        val aggregates = aggregateTrends(
            currentTxs = currentTxs,
            priorTxs = priorTxs,
            monthlyTxs = currentTxs + priorTxs,
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        // Headline: the full as-of actual period (Mar 1–30).
        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(300)
        // Delta populations: 28 current days vs 28 prior days.
        assertThat(aggregates.comparisonExpense.amount.toInt()).isEqualTo(280)
        assertThat(aggregates.previousExpense.amount.toInt()).isEqualTo(28)
        assertThat(aggregates.comparisonSavings.amount.toInt()).isEqualTo(-280)
        val delta = aggregates.deltaRows.single()
        assertThat(delta.currentTotal.amount.toInt()).isEqualTo(280)
        assertThat(delta.previousTotal.amount.toInt()).isEqualTo(28)
    }

    @Test
    fun `future actuals are excluded from current comparison and monthly populations`() {
        val query = TrendsQuery(
            periodKey = PeriodKey.CUSTOM,
            customRange = Period.Custom(LocalDate(2026, 2, 1), LocalDate(2026, 4, 30)),
            comparison = ComparisonSelection(
                mode = ComparisonMode.CUSTOM_PRIOR,
                customPrior = Period.Custom(LocalDate(2026, 2, 1), LocalDate(2026, 2, 10)),
            ),
        )
        val windows = computeTrendsWindows(query, LocalDate(2026, 2, 14))
        assertThat(windows.asOfExclusive).isEqualTo(LocalDate(2026, 2, 15))

        val currentTxs = listOf(
            tx("feb5", "100", LocalDate(2026, 2, 5)),
            tx("feb20", "40", LocalDate(2026, 2, 20)), // future actual vs as-of
            tx("mar5", "30", LocalDate(2026, 3, 5)), // future actual vs as-of
        )
        val priorTxs = listOf(
            tx("feb3", "5", LocalDate(2026, 2, 3)),
            tx("feb7", "7", LocalDate(2026, 2, 7)),
            tx("feb21", "999", LocalDate(2026, 2, 21)), // future actual inside the prior window too
        )

        val aggregates = aggregateTrends(
            currentTxs = currentTxs,
            priorTxs = priorTxs,
            monthlyTxs = currentTxs + priorTxs,
            months = (0..11).map { YearMonth.of(2026, 2).minusMonths(it.toLong()) }.reversed(),
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        assertThat(aggregates.totalExpense.amount.toInt()).isEqualTo(100)
        assertThat(aggregates.comparisonExpense.amount.toInt()).isEqualTo(100)
        assertThat(aggregates.previousExpense.amount.toInt()).isEqualTo(12)
        // The monthly series keeps past actuals but must not pick up future ones either.
        assertThat(aggregates.monthlyOperating.expenses.map { it.id }).containsExactly("feb5", "feb3", "feb7")
    }

    @Test
    fun `future range aggregation yields zero populations without negative durations`() {
        val query = TrendsQuery(
            periodKey = PeriodKey.CUSTOM,
            customRange = Period.Custom(LocalDate(2026, 3, 1), LocalDate(2026, 4, 1)),
        )
        val windows = computeTrendsWindows(query, LocalDate(2026, 2, 14))
        val aggregates = aggregateTrends(
            currentTxs = listOf(tx("mar5", "50", LocalDate(2026, 3, 5))),
            priorTxs = emptyList(),
            monthlyTxs = emptyList(),
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        assertThat(aggregates.totalExpense.isZero()).isTrue()
        assertThat(aggregates.comparisonExpense.isZero()).isTrue()
        assertThat(aggregates.previousExpense.isZero()).isTrue()
    }

    // ------------------------------------------------------------------ evidence

    @Test
    fun `comparison evidence carries both exact populations and reconciles with deltas`() {
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 3, 30))
        val currentTxs = (1..30).map { d -> tx("d$d", "10", LocalDate(2026, 3, d)) }
        val priorTxs = (1..28).map { d -> tx("f$d", "1", LocalDate(2026, 2, d)) }
        val aggregates = aggregateTrends(
            currentTxs = currentTxs,
            priorTxs = priorTxs,
            monthlyTxs = currentTxs + priorTxs,
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        // Full headline drilldown stays full (Mar 1–30).
        val full = buildEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE), aggregates, emptyList(), Money.SAR)!!
        assertThat(full.comparison).isNull()
        assertThat(full.transactions.map { it.id }).containsExactlyElementsIn((1..30).map { "d$it" })
        assertThat(full.total.amount.toInt()).isEqualTo(300)

        // Comparison evidence: BOTH sides, exactly the matched populations.
        val comparison = buildEvidence(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.EXPENSE), aggregates, emptyList(), Money.SAR)!!
        val c = checkNotNull(comparison.comparison)
        assertThat(c.currentRows.map { it.id }).containsExactlyElementsIn((1..28).map { "d$it" })
        assertThat(c.currentTotal.amount.toInt()).isEqualTo(280)
        assertThat(c.currentWindow).isEqualTo(windows.comparisonCurrent)
        assertThat(c.priorRows.map { it.id }).containsExactlyElementsIn((1..28).map { "f$it" })
        assertThat(c.priorTotal.amount.toInt()).isEqualTo(28)
        assertThat(c.priorWindow).isEqualTo(windows.previous)
        // Reconciliation: the evidence totals are the delta inputs, not the headline.
        assertThat(comparison.total.amount.toInt()).isEqualTo(aggregates.comparisonExpense.amount.toInt())
        assertThat(c.priorTotal.amount.toInt()).isEqualTo(aggregates.previousExpense.amount.toInt())
    }

    // ------------------------------------------------------------------- baselines

    @Test
    fun `zero and negative baselines show absolute delta without a percentage`() {
        val zeroBaseline = CategoryDeltaRow("cat-1", "", "", Money.of("30", Money.SAR), Money.zero(Money.SAR))
        assertThat(zeroBaseline.deltaPercent).isNull()
        assertThat(zeroBaseline.deltaAmount.amount.toInt()).isEqualTo(30)

        val negativeBaseline = CategoryDeltaRow("cat-1", "", "", Money.of("10", Money.SAR), Money.of("-5", Money.SAR))
        assertThat(negativeBaseline.deltaPercent).isNull()
        assertThat(negativeBaseline.deltaAmount.amount.toInt()).isEqualTo(15)

        val positiveBaseline = CategoryDeltaRow("cat-1", "", "", Money.of("30", Money.SAR), Money.of("50", Money.SAR))
        assertThat(positiveBaseline.deltaPercent)!!.isWithin(0.001).of(-40.0)
    }

    // ------------------------------------------------- category comparison evidence

    @Test
    fun `category comparison evidence shows both exact compared populations per category`() {
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 3, 30))
        val currentTxs = listOf(
            tx("c1", "40", LocalDate(2026, 3, 5), categoryId = "cat-1"),
            tx("c2", "60", LocalDate(2026, 3, 20), categoryId = "cat-1"),
            // Inside the unmatched tail — excluded from the delta population.
            tx("c3", "10", LocalDate(2026, 3, 29), categoryId = "cat-1"),
            tx("o1", "7", LocalDate(2026, 3, 10), categoryId = "cat-2"),
        )
        val priorTxs = listOf(
            tx("p1", "5", LocalDate(2026, 2, 3), categoryId = "cat-1"),
            tx("p2", "9", LocalDate(2026, 2, 9), categoryId = "cat-2"),
        )
        val aggregates = aggregateTrends(
            currentTxs = currentTxs,
            priorTxs = priorTxs,
            monthlyTxs = currentTxs + priorTxs,
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        val evidence = buildEvidence(TrendsEvidenceRequest.CategoryComparison("cat-1"), aggregates, emptyList(), Money.SAR)!!
        val comparison = checkNotNull(evidence.comparison)
        // Date-desc; the tail row c3 is NOT part of the compared population.
        assertThat(comparison.currentRows.map { it.id }).containsExactly("c2", "c1").inOrder()
        assertThat(comparison.currentTotal.amount.toInt()).isEqualTo(100)
        assertThat(comparison.priorRows.map { it.id }).containsExactly("p1")
        assertThat(comparison.priorTotal.amount.toInt()).isEqualTo(5)
        assertThat(comparison.currentWindow).isEqualTo(windows.comparisonCurrent)
        assertThat(comparison.priorWindow).isEqualTo(windows.previous)
        // The sheet total reconciles with the delta row the user tapped — never the headline.
        val deltaRow = aggregates.deltaRows.first { it.categoryId == "cat-1" }
        assertThat(evidence.total.amount.toInt()).isEqualTo(deltaRow.currentTotal.amount.toInt())
    }

    @Test
    fun `month in bucket drilldown is scoped to exactly that bucket and month`() {
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 3, 30))
        val monthlyTxs = listOf(
            tx("jan-a", "11", LocalDate(2026, 1, 4), categoryId = "cat-1"),
            tx("jan-b", "13", LocalDate(2026, 1, 9), categoryId = "cat-2"),
            tx("jan-c", "17", LocalDate(2026, 1, 17), categoryId = null),
            tx("feb-a", "19", LocalDate(2026, 2, 6), categoryId = "cat-1"),
            tx("feb-b", "23", LocalDate(2026, 2, 14), categoryId = "cat-2"),
        )
        val aggregates = aggregateTrends(
            currentTxs = emptyList(),
            priorTxs = emptyList(),
            monthlyTxs = monthlyTxs,
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        val janCat1 = buildEvidence(
            TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 1), "cat-1"),
            aggregates,
            emptyList(),
            Money.SAR,
        )!!
        assertThat(janCat1.transactions.map { it.id }).containsExactly("jan-a")
        assertThat(janCat1.total.amount.toInt()).isEqualTo(11)

        val febCat1 = buildEvidence(
            TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 2), "cat-1"),
            aggregates,
            emptyList(),
            Money.SAR,
        )!!
        assertThat(febCat1.transactions.map { it.id }).containsExactly("feb-a")
        assertThat(febCat1.total.amount.toInt()).isEqualTo(19)

        // Uncategorized never absorbs the categorized rows of the same month.
        val janUnc = buildEvidence(
            TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 1), UNCATEGORIZED_KEY),
            aggregates,
            emptyList(),
            Money.SAR,
        )!!
        assertThat(janUnc.transactions.map { it.id }).containsExactly("jan-c")
        assertThat(janUnc.total.amount.toInt()).isEqualTo(17)
    }

    @Test
    fun `other membership is frozen from the breakdown and stays consistent across months`() {
        val windows = computeTrendsWindows(TrendsQuery(
            periodKey = PeriodKey.CUSTOM,
            customRange = Period.Custom(LocalDate(2026, 1, 1), LocalDate(2026, 2, 1)),
        ), LocalDate(2026, 3, 30))
        // 10 distinct keys in January → top 8 stay visible, cat-2 (2) and cat-1 (1) drop
        // into Other; uncategorized (23) ranks inside the top 8 and stays its own bucket.
        val monthlyTxs = (1..9).map { i -> tx("jan-$i", i.toString(), LocalDate(2026, 1, i), categoryId = "cat-$i") } +
            tx("jan-9b", "50", LocalDate(2026, 1, 20), categoryId = "cat-9") + // cat-9 total 59
            tx("jan-u", "23", LocalDate(2026, 1, 21), categoryId = null) +
            tx("feb-u", "29", LocalDate(2026, 2, 3), categoryId = null)
        val aggregates = aggregateTrends(
            currentTxs = monthlyTxs.filter { it.date.monthNumber == 1 },
            priorTxs = emptyList(),
            monthlyTxs = monthlyTxs,
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        assertThat(aggregates.expenseBreakdown.map { it.key }).contains(OTHER_KEY)

        // Other in January is EXACTLY the two lowest-ranked categories, by frozen rule.
        val otherJan = buildEvidence(
            TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 1), OTHER_KEY),
            aggregates,
            emptyList(),
            Money.SAR,
        )!!
        assertThat(otherJan.transactions.map { it.id }).containsExactly("jan-2", "jan-1").inOrder()
        assertThat(otherJan.total.amount.toInt()).isEqualTo(3)

        // Uncategorized is its own bucket, never folded into Other.
        val uncJan = buildEvidence(
            TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 1), UNCATEGORIZED_KEY),
            aggregates,
            emptyList(),
            Money.SAR,
        )!!
        assertThat(uncJan.transactions.map { it.id }).containsExactly("jan-u")
        assertThat(uncJan.total.amount.toInt()).isEqualTo(23)

        // The Other bucket's 12-month trend bar agrees with the month drilldown.
        val otherBucket = buildEvidence(TrendsEvidenceRequest.Bucket(OTHER_KEY), aggregates, emptyList(), Money.SAR)!!
        val janTrendBar = checkNotNull(otherBucket.monthlyBars).first { it.month == YearMonth.of(2026, 1) }
        assertThat(janTrendBar.amount.amount.toInt()).isEqualTo(3)
    }

    @Test
    fun `evidence populations keep active filters because they come from the filtered aggregates`() {
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), LocalDate(2026, 3, 30))
        val currentTxs = listOf(
            tx("m1", "40", LocalDate(2026, 3, 5), categoryId = "cat-1"), // merchant "Mm1"
            tx("m2", "60", LocalDate(2026, 3, 20), categoryId = "cat-1"), // merchant "Mm2"
        )
        val priorTxs = listOf(
            tx("mp1", "5", LocalDate(2026, 2, 3), categoryId = "cat-1")
                .copy(merchant = "Mm1 prior", merchantNormalized = "mm1 prior"),
            tx("mp2", "500", LocalDate(2026, 2, 3), categoryId = "cat-1"),
        )
        val aggregates = aggregateTrends(
            currentTxs = currentTxs,
            priorTxs = priorTxs,
            monthlyTxs = emptyList(),
            months = monthsToMarch,
            categories = emptyList(),
            filters = TrendsFilters(merchantQuery = "m1"), // matches only "Mm1"
            reportingCurrency = Money.SAR,
            windows = windows,
        )

        val evidence = buildEvidence(TrendsEvidenceRequest.CategoryComparison("cat-1"), aggregates, emptyList(), Money.SAR)!!
        val comparison = checkNotNull(evidence.comparison)
        assertThat(comparison.currentRows.map { it.id }).containsExactly("m1")
        assertThat(comparison.currentTotal.amount.toInt()).isEqualTo(40)
        assertThat(comparison.priorRows.map { it.id }).containsExactly("mp1")
        assertThat(comparison.priorTotal.amount.toInt()).isEqualTo(5)
    }
}
