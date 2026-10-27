package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Spec §9 required monthly accounting example:
 * salary 12_000 on ANB, three 1_000 ANB→D360 transfers, ANB spend 400, D360 spend 2_400.
 */
class MonthlyAccountingExampleTest {

    private val anb = "acc-anb"
    private val d360 = "acc-d360"
    private val config = MonthlyAccountingConfig(
        ownedAccountIds = setOf(anb, d360),
        expenseAccountIds = setOf(anb, d360),
        incomeAccountIds = setOf(anb),
        incomeEligibleSenders = setOf("ANB", "ANBBank"),
    )

    @Test
    fun `required monthly totals exclude internal ANB to D360 transfers`() {
        val rows = listOf(
            row("salary", anb, TxType.INCOME, "12000", "ANB", "Salary credited", t(0)),
            row("t-out-1", anb, TxType.TRANSFER, "1000", "ANB", "transfer to D360", t(1)),
            row("t-in-1", d360, TxType.INCOME, "1000", "D360 Bank", "Incoming Transfer", t(1) + 60_000),
            row("t-out-2", anb, TxType.TRANSFER, "1000", "ANB", "transfer to D360", t(2)),
            row("t-in-2", d360, TxType.INCOME, "1000", "D360 Bank", "Incoming Transfer", t(2) + 60_000),
            row("t-out-3", anb, TxType.TRANSFER, "1000", "ANB", "transfer to D360", t(3)),
            row("t-in-3", d360, TxType.INCOME, "1000", "D360 Bank", "Incoming Transfer", t(3) + 60_000),
            row("anb-buy", anb, TxType.EXPENSE, "400", "ANB", "purchase", t(4)),
            row("d360-buy", d360, TxType.EXPENSE, "2400", "D360 Bank", "purchase", t(5)),
        )

        val summary = MonthlyAccounting.summarize(rows, config)

        assertThat(summary.totalIncome).isEqualTo(Money.of("12000"))
        assertThat(summary.totalExpenses).isEqualTo(Money.of("2800"))
        assertThat(summary.internalTransfers).isEqualTo(Money.of("3000"))
        assertThat(summary.netCashFlow).isEqualTo(Money.of("9200"))
        assertThat(summary.expensesByAccount[anb]).isEqualTo(Money.of("400"))
        assertThat(summary.expensesByAccount[d360]).isEqualTo(Money.of("2400"))
        assertThat(summary.matchedTransferPairs).hasSize(3)
        // Must not report 15_000 income or 5_800 expenses.
        assertThat(summary.totalIncome.amount.toPlainString()).isNotEqualTo("15000.00")
        assertThat(summary.totalExpenses.amount.toPlainString()).isNotEqualTo("5800.00")
    }

    @Test
    fun `identical one-thousand transfers match one-to-one without double counting`() {
        val rows = listOf(
            row("o1", anb, TxType.TRANSFER, "1000", "ANB", "out", t(10)),
            row("o2", anb, TxType.TRANSFER, "1000", "ANB", "out", t(20)),
            row("i1", d360, TxType.INCOME, "1000", "D360 Bank", "in", t(10) + 30_000),
            row("i2", d360, TxType.INCOME, "1000", "D360 Bank", "in", t(20) + 30_000),
        )
        val summary = MonthlyAccounting.summarize(rows, config)
        assertThat(summary.matchedTransferPairs).hasSize(2)
        assertThat(summary.internalTransfers).isEqualTo(Money.of("2000"))
        assertThat(summary.totalIncome).isEqualTo(Money.zero())
        assertThat(summary.totalExpenses).isEqualTo(Money.zero())
    }

    @Test
    fun `unpaired credit on spending account needs review and is not income`() {
        val rows = listOf(
            row("credit", d360, TxType.INCOME, "500", "D360 Bank", "Account Funding", t(0)),
        )
        val summary = MonthlyAccounting.summarize(rows, config)
        assertThat(summary.totalIncome).isEqualTo(Money.zero())
        assertThat(summary.internalTransfers).isEqualTo(Money.zero())
        assertThat(summary.needsReviewIds).contains("credit")
    }

    @Test
    fun `confirmed salary on income account counts without transfer pairing`() {
        val rows = listOf(
            row("salary", anb, TxType.INCOME, "12000", "ANB", "Salary credited", t(0)),
        )
        val summary = MonthlyAccounting.summarize(rows, config)
        assertThat(summary.totalIncome).isEqualTo(Money.of("12000"))
        assertThat(summary.needsReviewIds).isEmpty()
    }

    @Test
    fun `missing incoming counterpart leaves outgoing transfer in review`() {
        val rows = listOf(
            row("orphan-out", anb, TxType.TRANSFER, "1000", "ANB", "transfer", t(0)),
            row("anb-buy", anb, TxType.EXPENSE, "400", "ANB", "purchase", t(1)),
        )
        val summary = MonthlyAccounting.summarize(rows, config)
        assertThat(summary.internalTransfers).isEqualTo(Money.zero())
        assertThat(summary.totalExpenses).isEqualTo(Money.of("400"))
        assertThat(summary.needsReviewIds).contains("orphan-out")
    }

    @Test
    fun `refunds are excluded from income and expenses`() {
        val rows = listOf(
            row("salary", anb, TxType.INCOME, "12000", "ANB", "Salary", t(0)),
            AccountingRow(
                id = "refund",
                accountId = anb,
                type = TxType.INCOME,
                amount = Money.of("50"),
                status = TxStatus.CONFIRMED,
                occurredAt = t(1),
                sender = "ANB",
                bodyHints = "Refund",
                isRefund = true,
            ),
        )
        val summary = MonthlyAccounting.summarize(rows, config)
        assertThat(summary.totalIncome).isEqualTo(Money.of("12000"))
    }

    private fun t(hourOffset: Long): Instant =
        Instant.fromEpochMilliseconds(1_700_000_000_000L + hourOffset * 3_600_000L)

    private operator fun Instant.plus(millis: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + millis)

    private fun row(
        id: String,
        accountId: String,
        type: TxType,
        amount: String,
        sender: String,
        body: String,
        at: Instant,
    ) = AccountingRow(
        id = id,
        accountId = accountId,
        type = type,
        amount = Money.of(amount),
        status = TxStatus.CONFIRMED,
        occurredAt = at,
        sender = sender,
        bodyHints = body,
    )
}
