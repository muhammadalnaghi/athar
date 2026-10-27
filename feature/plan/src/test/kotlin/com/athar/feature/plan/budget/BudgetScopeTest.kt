package com.athar.feature.plan.budget

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.feature.plan.BudgetRow
import com.athar.feature.plan.LimitState
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class BudgetScopeTest {
    private val start = LocalDate.parse("2026-10-01")
    private val end = LocalDate.parse("2026-10-31")
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private fun category(target: Money? = Money.of("100.00")) = Category(
        "food", "Food", "الطعام", CategoryKind.EXPENSE, null, target, false, 0,
    )
    private fun tx(id: String, amount: String, currency: String = "SAR", category: String? = "food",
        status: TxStatus = TxStatus.CONFIRMED, type: TxType = TxType.EXPENSE,
        date: LocalDate = LocalDate.parse("2026-10-20"), ref: String? = null) = Transaction(
        id, "synthetic-account", type, Money.of(amount, currency), date, now, "Public Shop", "public shop",
        category, null, IngestSource.MANUAL, ref, status, null, now, now,
    )
    private fun panel(txs: List<Transaction>, cats: List<Category> = listOf(category())) =
        deriveBudgetPanel(start, end, 1.0, cats, txs, "SAR")

    @Test fun `foreign actuals are not one to one SAR amounts`() {
        val result = panel(listOf(tx("sar", "10.00"), tx("usd", "90.00", "USD")))
        assertThat(result.totalActual).isEqualTo(Money.of("10.00"))
        assertThat(result.rows.single().actual).isEqualTo(Money.of("10.00"))
    }
    @Test fun `foreign target is unavailable for SAR comparison without a crash`() {
        val result = panel(listOf(tx("sar", "10.00")), listOf(category(Money.of("50.00", "USD"))))
        assertThat(result.rows.single().target).isNull()
        assertThat(result.totalTarget).isEqualTo(Money.zero())
    }
    @Test fun `only confirmed operating records enter the same budget scope`() {
        val result = panel(listOf(tx("ok", "3.00"), tx("pending", "5.00", status = TxStatus.PENDING),
            tx("dismissed", "6.00", status = TxStatus.DISMISSED), tx("transfer", "7.00", type = TxType.TRANSFER),
            tx("reconcile", "8.00", ref = "reconcile-public")))
        assertThat(result.totalActual).isEqualTo(Money.of("3.00"))
    }
    @Test fun `category and unknown remainder reconcile exactly without foreign additions`() {
        val result = panel(listOf(tx("known", "10.00"), tx("none", "5.00", category = null),
            tx("unknown", "7.00", category = "deleted-id"), tx("foreign", "999.00", "USD", category = null)))
        assertThat(result.remainder).isEqualTo(Money.of("12.00"))
        assertThat(result.remainderCount).isEqualTo(2)
        assertThat(result.totalActual).isEqualTo(Money.of("22.00"))
        assertThat(result.reconciledTotal).isEqualTo(result.totalActual)
    }
    @Test fun `evidence contains exactly the same currency scoped records as its panel`() {
        val records = listOf(tx("sar", "10.00"), tx("usd", "90.00", "USD"))
        val a = panel(records)
        val evidence = checkNotNull(budgetEvidence(a, null, records, emptyList(), listOf(category()),
            BudgetEvidenceRequest(ComparePanel.A, BudgetBucket.Total)))
        assertThat(evidence.records.map { it.id }).containsExactly("sar")
        assertThat(evidence.total).isEqualTo(Money.of("10.00"))
    }
    @Test fun `full history beyond a recent list cap remains in totals and evidence`() {
        val records = List(251) { tx("history-$it", "1.00") }
        val a = panel(records)
        val evidence = checkNotNull(budgetEvidence(a, null, records, emptyList(), listOf(category()),
            BudgetEvidenceRequest(ComparePanel.A, BudgetBucket.Total)))
        assertThat(evidence.records).hasSize(251)
        assertThat(evidence.total.amount.toPlainString()).isEqualTo("251.00")
    }
    @Test fun `separate panels apply independent inclusive date bounds`() {
        val records = listOf(tx("oct", "10.00"), tx("sep", "20.00", date = LocalDate.parse("2026-09-20")))
        val a = panel(records)
        val b = deriveBudgetPanel(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), 1.0,
            listOf(category()), records, "SAR")
        assertThat(a.totalActual).isEqualTo(Money.of("10.00"))
        assertThat(b.totalActual).isEqualTo(Money.of("20.00"))
        val evidence = checkNotNull(budgetEvidence(a, b, records, records, listOf(category()),
            BudgetEvidenceRequest(ComparePanel.B, BudgetBucket.Total)))
        assertThat(evidence.records.map { it.id }).containsExactly("sep")
    }
    @Test fun `empty scope has an honest exact zero and empty evidence`() {
        val a = panel(emptyList())
        assertThat(a.totalActual).isEqualTo(Money.zero())
        assertThat(checkNotNull(budgetEvidence(a, null, emptyList(), emptyList(), listOf(category()),
            BudgetEvidenceRequest(ComparePanel.A, BudgetBucket.Total))).records).isEmpty()
    }
    @Test fun `one halala below a large ninety percent boundary is not tight`() {
        val row = BudgetRow(category(), Money.of("89999999999999999.99"), Money.of("100000000000000000.00"))
        assertThat(row.limit).isEqualTo(LimitState.Watch)
    }
}
