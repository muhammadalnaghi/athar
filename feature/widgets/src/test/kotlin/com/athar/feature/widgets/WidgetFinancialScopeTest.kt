package com.athar.feature.widgets

import com.athar.core.common.money.Money
import com.athar.core.domain.model.*
import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import com.athar.core.domain.model.position.DeclaredPositionStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class WidgetFinancialScopeTest {
    private fun row(id: String, amount: String, currency: String = "SAR", type: TxType = TxType.EXPENSE,
        status: TxStatus = TxStatus.CONFIRMED, ref: String? = null): Transaction {
        val now = Instant.parse("2026-10-24T00:00:00Z")
        return Transaction(id, "synthetic", type, Money.of(amount, currency), LocalDate(2026,10,24), null,
            "Synthetic", "synthetic", null, null, IngestSource.MANUAL, ref, status, null, now, now)
    }
    @Test fun `literal currency confirmed operating scope excludes foreign pending transfers and reconciliation`() {
        val flow = widgetFlow(listOf(row("a","100000001.01"),row("b","10",type=TxType.INCOME),
            row("foreign","500000000","USD"),row("pending","9",status=TxStatus.PENDING),
            row("transfer","8",type=TxType.TRANSFER),row("adjustment","7",ref="reconcile-synthetic")),"SAR")
        assertThat(flow.expense.amount.toPlainString()).isEqualTo("100000001.01")
        assertThat(flow.income.amount.compareTo(java.math.BigDecimal.TEN)).isEqualTo(0)
        assertThat(flow.net.amount.compareTo(java.math.BigDecimal.TEN-java.math.BigDecimal("100000001.01"))).isEqualTo(0)
    }
    @Test fun `confirmed uncategorized money is included and absent scope has known zero`() {
        assertThat(widgetFlow(listOf(row("uncategorized","1.01")),"SAR").expense.amount.toPlainString()).isEqualTo("1.01")
        assertThat(widgetFlow(listOf(row("foreign","5.25","USD")),"SAR").net.amount.signum()).isEqualTo(0)
    }
    @Test fun `latest declared document is selected not summed and absent currency stays missing`() {
        fun position(id: String,date: String,net: String) = DeclaredPositionSnapshot(id,id,2,date,1,
            DeclaredPositionStatus.USER_DECLARED,false,1,emptyMap(),mapOf("SAR" to "1000.02"),mapOf("SAR" to net))
        val rows = listOf(position("old","2026-10-20","10"),position("new","2026-10-24","-1000.02"))
        assertThat(latestDeclaredPositionView(rows,"SAR")!!.net.amount.toPlainString()).isEqualTo("-1000.02")
        assertThat(latestDeclaredPositionView(rows,"USD")).isNull()
    }
    @Test fun `failure is unavailable not zero`() = runTest {
        assertThat(loadWidgetState<Any> { error("synthetic failure") }).isEqualTo(WidgetLoad.Unavailable)
    }
    @Test fun `cancellation propagates unchanged`() = runTest {
        val expected = CancellationException("synthetic")
        var caught: CancellationException? = null
        try { loadWidgetState<Any> { throw expected } } catch (e: CancellationException) { caught = e }
        assertThat(caught).isSameInstanceAs(expected)
    }
    @Test fun `large cented money retains full grouped exact value and literal currency`() {
        val text = widgetMoneyText(Money.of("100000001.01","SAR"),"en-US")
        assertThat(text).contains("100,000,001.01")
        assertThat(text).endsWith("SAR")
    }
}
