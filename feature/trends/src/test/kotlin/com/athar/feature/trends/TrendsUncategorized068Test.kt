package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.YearMonth

class TrendsUncategorized068Test {
    private val today = LocalDate(2026, 10, 25)
    private val priorDate = LocalDate(2026, 9, 1)
    private val amounts = listOf("100.01", "90.01", "80.01", "70.01", "60.01", "50.01", "40.01", "30.01", "20.01", "10.01")
    private val known = amounts.mapIndexed { i, amount -> tx("known-${i + 1}", amount, "cat-${i + 1}") }

    @Test
    fun `uncategorized remains explicit below eight larger ranked categories`() {
        val current = known + tx("uncategorized", "7.07", null)
        val result = aggregate(current)
        val uncategorized = result.expenseBreakdown.singleOrNull { it.key == UNCATEGORIZED_KEY }
        assertThat(uncategorized).isNotNull()
        assertThat(uncategorized!!.total.amount).isEqualTo(BigDecimal("7.07"))
        assertThat(uncategorized.transactionIds).containsExactly("uncategorized")
        val other = result.expenseBreakdown.single { it.key == OTHER_KEY }
        assertThat(other.total.amount).isEqualTo(BigDecimal("30.02"))
        assertThat(other.transactionIds).containsExactly("known-9", "known-10")
        val memberships = result.expenseBreakdown.flatMap { it.transactionIds }
        assertThat(memberships).containsExactlyElementsIn(current.map { it.id })
        assertThat(memberships.distinct()).hasSize(memberships.size)
        assertThat(result.expenseBreakdown.fold(BigDecimal.ZERO) { sum, row -> sum + row.total.amount })
            .isEqualTo(BigDecimal("557.17"))
    }

    @Test
    fun `Other monthly evidence excludes prior uncategorized even when current has none`() {
        val prior = listOf(
            tx("prior-tail", "11.11", "cat-9", priorDate),
            tx("prior-uncategorized", "13.13", null, priorDate),
            tx("prior-visible", "22.22", "cat-1", priorDate),
        )
        val result = aggregate(known, prior)
        val evidence = buildEvidence(
            TrendsEvidenceRequest.MonthInBucket(YearMonth.of(2026, 9), OTHER_KEY),
            result, emptyList(), "SAR",
        )!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("prior-tail")
        assertThat(evidence.total.amount).isEqualTo(BigDecimal("11.11"))
        val bucket = buildEvidence(TrendsEvidenceRequest.Bucket(OTHER_KEY), result, emptyList(), "SAR")!!
        assertThat(bucket.transactions.map { it.id }).containsExactly("known-9", "known-10")
        assertThat(bucket.monthlyBars!!.single { it.month == YearMonth.of(2026, 9) }.amount.amount)
            .isEqualTo(BigDecimal("11.11"))
    }

    @Test
    fun `zero ranked slots still preserve standalone uncategorized and assigned Other`() {
        val result = aggregate(listOf(tx("uncategorized", "7.07", null), tx("assigned", "3.14", "cat-a")), topN = 0)
        assertThat(result.expenseBreakdown.map { it.key }).containsExactly(UNCATEGORIZED_KEY, OTHER_KEY).inOrder()
        assertThat(result.expenseBreakdown[0].transactionIds).containsExactly("uncategorized")
        assertThat(result.expenseBreakdown[1].transactionIds).containsExactly("assigned")
        assertThat(result.expenseBreakdown[1].total.amount).isEqualTo(BigDecimal("3.14"))
    }

    private fun aggregate(current: List<Transaction>, prior: List<Transaction> = emptyList(), topN: Int = 8) =
        aggregateTrends(
            currentTxs = current, priorTxs = prior, monthlyTxs = current + prior,
            months = listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)),
            categories = emptyList(), filters = TrendsFilters(), reportingCurrency = "SAR",
            windows = computeTrendsWindows(TrendsQuery(), today), topN = topN,
        )

    private fun tx(id: String, amount: String, category: String?, date: LocalDate = today): Transaction {
        val time = Instant.parse("2026-10-25T09:00:00Z")
        return Transaction(id, "qa-account", TxType.EXPENSE, Money.of(amount), date, null,
            "QA068 $id", "qa068 $id", category, null, IngestSource.MANUAL, "qa068-$id",
            TxStatus.CONFIRMED, null, time, time)
    }
}
