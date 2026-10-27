package com.athar.feature.plan.position

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class PositionTotalsTest {

    private val doc = PositionDocument(
        asOfDate = "2026-10-01",
        entries = listOf(
            PositionEntryDocument("e1", "جاري", PositionSide.ASSET, "SAR", "15000.00", "شخصي"),
            PositionEntryDocument("e2", "استثمار", PositionSide.ASSET, "SAR", "2500.10", "شخصي"),
            PositionEntryDocument("e3", "قرض", PositionSide.LIABILITY, "SAR", "4200.60", "شخصي"),
            PositionEntryDocument("e4", "حساب أجنبي", PositionSide.ASSET, "USD", "1000", "شخصي"),
            PositionEntryDocument("e5", "بطاقة", PositionSide.LIABILITY, "USD", "250.25", "عائلي"),
        ),
    )

    @Test
    fun `totals are exact per literal currency with no cross currency total`() {
        val totals = PositionTotalsCalc.compute(doc, LocalDate(2026, 10, 24))
        assertThat(totals.perCurrency).hasSize(2)
        val sar = totals.perCurrency.first()
        assertThat(sar.currency).isEqualTo("SAR")
        assertThat(sar.assets.toPlainString()).isEqualTo("17500.10")
        assertThat(sar.liabilities.toPlainString()).isEqualTo("4200.60")
        assertThat(sar.net.toPlainString()).isEqualTo("13299.50")
        val usd = totals.perCurrency.last()
        assertThat(usd.assets.toPlainString()).isEqualTo("1000")
        assertThat(usd.liabilities.toPlainString()).isEqualTo("250.25")
        assertThat(usd.net.toPlainString()).isEqualTo("749.75")
        assertThat(totals.entryCount).isEqualTo(5)
    }

    @Test
    fun `freshness age is exact days between declaration and viewing`() {
        val totals = PositionTotalsCalc.compute(doc, LocalDate(2026, 10, 24))
        assertThat(totals.asOfDate).isEqualTo("2026-10-01")
        assertThat(totals.ageDays).isEqualTo(23)
        assertThat(PositionTotalsCalc.compute(doc, LocalDate(2026, 10, 1)).ageDays).isEqualTo(0)
    }

    @Test
    fun `an empty document yields no fabricated currency rows`() {
        val totals = PositionTotalsCalc.compute(PositionDocument("2026-10-01"), LocalDate(2026, 10, 24))
        assertThat(totals.perCurrency).isEmpty()
        assertThat(totals.entryCount).isEqualTo(0)
    }
}
