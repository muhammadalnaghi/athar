package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.domain.accounting.accountingConfig
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.time.YearMonth

/**
 * Verifies Trends [aggregateTrends] uses the shared MonthlyAccounting path for the §9 example.
 */
class TrendsAggregatorAccountingTest {

    private val anb = "acc-anb"
    private val d360 = "acc-d360"
    private val accounting = accountingConfig(
        ownedAccountIds = setOf(anb, d360),
        incomeAccountIds = setOf(anb),
        incomeEligibleSenders = setOf("ANB"),
    )

    @Test
    fun `trends headlines match required monthly accounting example`() {
        val day = LocalDate(2026, 6, 10)
        val txs = listOf(
            tx("salary", anb, TxType.INCOME, "12000", day, t(0)),
            tx("t-out-1", anb, TxType.TRANSFER, "1000", day, t(1)),
            tx("t-in-1", d360, TxType.INCOME, "1000", day, t(1) + 60_000),
            tx("t-out-2", anb, TxType.TRANSFER, "1000", day, t(2)),
            tx("t-in-2", d360, TxType.INCOME, "1000", day, t(2) + 60_000),
            tx("t-out-3", anb, TxType.TRANSFER, "1000", day, t(3)),
            tx("t-in-3", d360, TxType.INCOME, "1000", day, t(3) + 60_000),
            tx("anb-buy", anb, TxType.EXPENSE, "400", day, t(4)),
            tx("d360-buy", d360, TxType.EXPENSE, "2400", day, t(5)),
            tx("pending", d360, TxType.INCOME, "999", day, t(6), TxStatus.PENDING),
        )
        val today = LocalDate(2026, 6, 15)
        val windows = computeTrendsWindows(TrendsQuery(periodKey = PeriodKey.MONTH), today)
        val aggregates = aggregateTrends(
            currentTxs = txs,
            priorTxs = emptyList(),
            monthlyTxs = txs,
            months = listOf(YearMonth.of(2026, 6)),
            categories = emptyList(),
            filters = TrendsFilters(),
            reportingCurrency = Money.SAR,
            windows = windows,
            accounting = accounting,
        )

        assertThat(aggregates.totalIncome).isEqualTo(Money.of("12000"))
        assertThat(aggregates.totalExpense).isEqualTo(Money.of("2800"))
        assertThat(aggregates.internalTransfers).isEqualTo(Money.of("3000"))
        assertThat(aggregates.savings).isEqualTo(Money.of("9200"))
    }

    private fun t(hour: Long): Instant =
        Instant.fromEpochMilliseconds(1_700_000_000_000L + hour * 3_600_000L)

    private operator fun Instant.plus(ms: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + ms)

    private fun tx(
        id: String,
        accountId: String,
        type: TxType,
        amount: String,
        date: LocalDate,
        at: Instant,
        status: TxStatus = TxStatus.CONFIRMED,
    ) = Transaction(
        id = id,
        accountId = accountId,
        type = type,
        amount = Money.of(amount),
        date = date,
        occurredAt = at,
        merchant = id,
        merchantNormalized = id.lowercase(),
        categoryId = null,
        notes = null,
        source = IngestSource.SMS,
        sourceRefId = "ref-$id",
        status = status,
        confidence = 0.9f,
        createdAt = at,
        updatedAt = at,
    )
}
