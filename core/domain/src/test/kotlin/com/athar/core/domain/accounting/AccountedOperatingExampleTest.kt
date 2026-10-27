package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * Exercises the shared Today/Trends path [selectAccountedOperating] for the §9 example.
 */
class AccountedOperatingExampleTest {

    private val anb = "acc-anb"
    private val d360 = "acc-d360"
    private val config = accountingConfig(
        ownedAccountIds = setOf(anb, d360),
        incomeAccountIds = setOf(anb),
        incomeEligibleSenders = setOf("ANB"),
    )

    @Test
    fun `shared accounted operating matches required monthly totals`() {
        val txs = listOf(
            tx("salary", anb, TxType.INCOME, "12000", t(0)),
            tx("t-out-1", anb, TxType.TRANSFER, "1000", t(1)),
            tx("t-in-1", d360, TxType.INCOME, "1000", t(1) + 60_000),
            tx("t-out-2", anb, TxType.TRANSFER, "1000", t(2)),
            tx("t-in-2", d360, TxType.INCOME, "1000", t(2) + 60_000),
            tx("t-out-3", anb, TxType.TRANSFER, "1000", t(3)),
            tx("t-in-3", d360, TxType.INCOME, "1000", t(3) + 60_000),
            tx("anb-buy", anb, TxType.EXPENSE, "400", t(4)),
            tx("d360-buy", d360, TxType.EXPENSE, "2400", t(5)),
            // Pending ambiguous credit must not inflate confirmed totals.
            tx("pending-credit", d360, TxType.INCOME, "999", t(6), status = TxStatus.PENDING),
            // Duplicate notification with same source would be deduped upstream;
            // a second confirmed transfer pair still one-to-ones.
        )

        val accounted = selectAccountedOperating(txs, config)
        val s = accounted.summary

        assertThat(s.totalIncome).isEqualTo(Money.of("12000"))
        assertThat(s.totalExpenses).isEqualTo(Money.of("2800"))
        assertThat(s.internalTransfers).isEqualTo(Money.of("3000"))
        assertThat(s.netCashFlow).isEqualTo(Money.of("9200"))
        assertThat(s.expensesByAccount[anb]).isEqualTo(Money.of("400"))
        assertThat(s.expensesByAccount[d360]).isEqualTo(Money.of("2400"))

        assertThat(Money.sumAmounts(accounted.operating.income.map { it.amount }))
            .isEqualTo(Money.of("12000"))
        assertThat(Money.sumAmounts(accounted.operating.expenses.map { it.amount }))
            .isEqualTo(Money.of("2800"))
        assertThat(accounted.operating.income.map { it.id }).containsExactly("salary")
        assertThat(accounted.matchedTransferIds).hasSize(6)
    }

    @Test
    fun `amount alone across same window without second owned account does not match`() {
        val solo = accountingConfig(
            ownedAccountIds = setOf(anb),
            incomeAccountIds = setOf(anb),
        )
        val txs = listOf(
            tx("out", anb, TxType.TRANSFER, "1000", t(0)),
            tx("in", anb, TxType.INCOME, "1000", t(0) + 10_000),
        )
        val accounted = selectAccountedOperating(txs, solo)
        assertThat(accounted.summary.matchedTransferPairs).isEmpty()
        assertThat(accounted.summary.internalTransfers).isEqualTo(Money.zero())
    }

    private fun t(hourOffset: Long): Instant =
        Instant.fromEpochMilliseconds(1_700_000_000_000L + hourOffset * 3_600_000L)

    private operator fun Instant.plus(millis: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + millis)

    private fun tx(
        id: String,
        accountId: String,
        type: TxType,
        amount: String,
        at: Instant,
        status: TxStatus = TxStatus.CONFIRMED,
    ): Transaction = Transaction(
        id = id,
        accountId = accountId,
        type = type,
        amount = Money.of(amount),
        date = LocalDate(2023, 11, 14),
        occurredAt = at,
        merchant = id,
        merchantNormalized = id.lowercase(),
        categoryId = if (type == TxType.EXPENSE) "cat-other-expense" else null,
        notes = null,
        source = IngestSource.SMS,
        sourceRefId = "ref-$id",
        status = status,
        confidence = 0.9f,
        createdAt = at,
        updatedAt = at,
    )
}
