package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class OperatingTransactionsTest {

    @Test
    fun `empty input yields empty operating lists`() {
        val result = selectOperatingTransactions(emptyList())

        assertThat(result.income).isEmpty()
        assertThat(result.expenses).isEmpty()
    }

    @Test
    fun `confirmed income only lands in income`() {
        val row = tx(id = "salary", type = TxType.INCOME, amount = "5000")

        val result = selectOperatingTransactions(listOf(row))

        assertThat(result.income).containsExactly(row)
        assertThat(result.expenses).isEmpty()
    }

    @Test
    fun `confirmed expense only lands in expenses`() {
        val row = tx(id = "grocery", type = TxType.EXPENSE, amount = "180")

        val result = selectOperatingTransactions(listOf(row))

        assertThat(result.income).isEmpty()
        assertThat(result.expenses).containsExactly(row)
    }

    @Test
    fun `confirmed transfer only is excluded from both lists`() {
        val row = tx(id = "move", type = TxType.TRANSFER, amount = "500")

        val result = selectOperatingTransactions(listOf(row))

        assertThat(result.income).isEmpty()
        assertThat(result.expenses).isEmpty()
    }

    @Test
    fun `mixed confirmed kinds split by explicit type in input order`() {
        val income1 = tx(id = "in-1", type = TxType.INCOME, amount = "100")
        val expense1 = tx(id = "out-1", type = TxType.EXPENSE, amount = "20")
        val transfer = tx(id = "move-1", type = TxType.TRANSFER, amount = "50")
        val income2 = tx(id = "in-2", type = TxType.INCOME, amount = "200")
        val expense2 = tx(id = "out-2", type = TxType.EXPENSE, amount = "30")

        val result = selectOperatingTransactions(listOf(income1, expense1, transfer, income2, expense2))

        assertThat(result.income).containsExactly(income1, income2).inOrder()
        assertThat(result.expenses).containsExactly(expense1, expense2).inOrder()
    }

    @Test
    fun `pending and dismissed rows of every type are excluded`() {
        val rows = TxType.entries.flatMap { type ->
            listOf(
                tx(id = "pending-$type", type = type, status = TxStatus.PENDING),
                tx(id = "dismissed-$type", type = type, status = TxStatus.DISMISSED),
            )
        }

        val result = selectOperatingTransactions(rows)

        assertThat(result.income).isEmpty()
        assertThat(result.expenses).isEmpty()
    }

    @Test
    fun `manual reconciliation income and expense are excluded`() {
        val reconcileIncome = tx(
            id = "reconcile-in",
            type = TxType.INCOME,
            source = IngestSource.MANUAL,
            sourceRefId = "reconcile-2026-06",
        )
        val reconcileExpense = tx(
            id = "reconcile-out",
            type = TxType.EXPENSE,
            source = IngestSource.MANUAL,
            sourceRefId = "reconcile-2026-06",
        )

        val result = selectOperatingTransactions(listOf(reconcileIncome, reconcileExpense))

        assertThat(result.income).isEmpty()
        assertThat(result.expenses).isEmpty()
    }

    @Test
    fun `null category and zero confidence do not affect selection`() {
        val uncategorized = tx(id = "no-cat", type = TxType.EXPENSE, categoryId = null)
        val zeroConfidence = tx(id = "no-confidence", type = TxType.EXPENSE, confidence = 0f)

        val result = selectOperatingTransactions(listOf(uncategorized, zeroConfidence))

        assertThat(result.expenses).containsExactly(uncategorized, zeroConfidence).inOrder()
        assertThat(result.income).isEmpty()
    }

    @Test
    fun `non-manual source with reconcile-prefixed ref is not excluded`() {
        val smsRow = tx(
            id = "sms-reconcile-like",
            type = TxType.EXPENSE,
            source = IngestSource.SMS,
            sourceRefId = "reconcile-not-really",
        )
        val importRow = tx(
            id = "import-reconcile-like",
            type = TxType.INCOME,
            source = IngestSource.IMPORT,
            sourceRefId = "reconcile-import-7",
        )

        val result = selectOperatingTransactions(listOf(smsRow, importRow))

        assertThat(result.expenses).containsExactly(smsRow)
        assertThat(result.income).containsExactly(importRow)
    }

    @Test
    fun `row order currency and values are preserved`() {
        val usdIncome = tx(id = "usd-salary", type = TxType.INCOME, amount = "77.50", currency = "USD")
        val sarExpense = tx(id = "sar-coffee", type = TxType.EXPENSE, amount = "18.25", currency = Money.SAR)

        val result = selectOperatingTransactions(listOf(sarExpense, usdIncome))

        assertThat(result.expenses).containsExactly(sarExpense).inOrder()
        assertThat(result.income).containsExactly(usdIncome).inOrder()
        assertThat(result.income.single()).isSameInstanceAs(usdIncome)
        assertThat(result.expenses.single()).isSameInstanceAs(sarExpense)
        assertThat(result.income.single().amount.currency).isEqualTo("USD")
        assertThat(result.expenses.single().amount).isEqualTo(Money.of("18.25"))
    }

    @Test
    fun `repeated pure calls leave input and output unchanged`() {
        val rows = listOf(
            tx(id = "in-1", type = TxType.INCOME, amount = "100"),
            tx(id = "out-1", type = TxType.EXPENSE, amount = "20"),
            tx(id = "move-1", type = TxType.TRANSFER, amount = "50"),
            tx(
                id = "reconcile-1",
                type = TxType.INCOME,
                source = IngestSource.MANUAL,
                sourceRefId = "reconcile-1",
            ),
        )
        val snapshot = rows.toList()

        val first = selectOperatingTransactions(rows)
        val second = selectOperatingTransactions(rows)

        assertThat(second).isEqualTo(first)
        assertThat(second.income).containsExactlyElementsIn(first.income)
        assertThat(second.expenses).containsExactlyElementsIn(first.expenses)
        assertThat(rows).isEqualTo(snapshot)
    }

    /**
     * Local factory so `core:domain` tests need no dependency on `core:testing`
     * (which itself depends on this module). Fills every [Transaction] field.
     */
    private fun tx(
        id: String,
        type: TxType,
        amount: String = "100",
        status: TxStatus = TxStatus.CONFIRMED,
        source: IngestSource = IngestSource.SMS,
        sourceRefId: String? = "ref-$id",
        categoryId: String? = "cat-1",
        confidence: Float? = 0.9f,
        currency: String = Money.SAR,
    ): Transaction = Transaction(
        id = id,
        accountId = "acc-1",
        type = type,
        amount = Money.of(amount, currency),
        date = LocalDate(2026, 6, 13),
        occurredAt = Instant.parse("2026-06-13T10:00:00Z"),
        merchant = id,
        merchantNormalized = id.lowercase(),
        categoryId = categoryId,
        notes = null,
        source = source,
        sourceRefId = sourceRefId,
        status = status,
        confidence = confidence,
        createdAt = Instant.parse("2026-06-13T10:00:00Z"),
        updatedAt = Instant.parse("2026-06-13T10:00:00Z"),
    )
}
