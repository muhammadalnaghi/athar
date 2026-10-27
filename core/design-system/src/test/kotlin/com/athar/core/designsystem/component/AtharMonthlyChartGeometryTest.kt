package com.athar.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.YearMonth
import java.util.Locale

/**
 * Pure-geometry tests for the signed chart scale (WM028-10). Money stays BigDecimal
 * end to end; the helper only ever emits finite 0f..1f projection fractions and never
 * rounds away observed money precision in labels.
 */
class AtharMonthlyChartGeometryTest {

    private fun bd(value: String) = BigDecimal(value)

    // ---- SignedRange / rangeOf (F1C suite, migrated to BigDecimal helpers) ----

    @Test
    fun `positive only range anchors zero at the bottom`() {
        val r = AtharMonthlyChartGeometry.rangeOf(listOf(bd("100"), bd("500"), bd("25")))
        assertThat(r.min.compareTo(bd("0"))).isEqualTo(0)
        assertThat(r.max.compareTo(bd("500"))).isEqualTo(0)
        assertThat(r.fractionOf(BigDecimal.ZERO)).isEqualTo(1f)
    }

    @Test
    fun `all negative range anchors zero at the top`() {
        val r = AtharMonthlyChartGeometry.rangeOf(listOf(bd("-200"), bd("-50")))
        assertThat(r.max.compareTo(bd("0"))).isEqualTo(0)
        assertThat(r.min.compareTo(bd("-200"))).isEqualTo(0)
        assertThat(r.fractionOf(BigDecimal.ZERO)).isEqualTo(0f)
    }

    @Test
    fun `mixed range spans both signs with zero strictly inside`() {
        val r = AtharMonthlyChartGeometry.rangeOf(listOf(bd("-100"), bd("400")))
        assertThat(r.min.compareTo(bd("-100"))).isEqualTo(0)
        assertThat(r.max.compareTo(bd("400"))).isEqualTo(0)
        val zeroY = r.fractionOf(BigDecimal.ZERO)
        assertThat(zeroY).isGreaterThan(0f)
        assertThat(zeroY).isLessThan(1f)
        assertThat(r.fractionOf(bd("-100"))).isEqualTo(1f)
        assertThat(r.fractionOf(bd("400"))).isEqualTo(0f)
    }

    @Test
    fun `all zero data falls back to unit range without divide by zero`() {
        val r = AtharMonthlyChartGeometry.rangeOf(listOf(bd("0"), bd("0.00"), bd("0")))
        assertThat(r.span.signum()).isGreaterThan(0)
        assertThat(r.fractionOf(BigDecimal.ZERO)).isEqualTo(0.5f)
    }

    @Test
    fun `range covers target and average line extremes`() {
        val r = AtharMonthlyChartGeometry.rangeOf(
            values = listOf(bd("100"), bd("300"), bd("800"), bd("-50")), // bars, target, average
        )
        assertThat(r.max.compareTo(bd("800"))).isEqualTo(0)
        assertThat(r.min.compareTo(bd("-50"))).isEqualTo(0)
    }

    // ---- barGeometry ----

    @Test
    fun `zero value renders no bar`() {
        val range = AtharMonthlyChartGeometry.rangeOf(listOf(bd("0"), bd("200")))
        val g = AtharMonthlyChartGeometry.barGeometry(BigDecimal.ZERO, range)
        assertThat(g.heightFraction).isEqualTo(0f)
        assertThat(g.isVisible).isFalse()
    }

    @Test
    fun `positive bar grows up from the zero baseline`() {
        val range = AtharMonthlyChartGeometry.rangeOf(listOf(bd("500")))
        val zeroY = range.fractionOf(BigDecimal.ZERO)
        val g = AtharMonthlyChartGeometry.barGeometry(bd("250"), range)
        assertThat(g.topFraction).isLessThan(zeroY)
        assertThat(g.topFraction + g.heightFraction).isEqualTo(zeroY)
        assertThat(g.isVisible).isTrue()
    }

    @Test
    fun `negative bar extends below the zero baseline`() {
        val range = AtharMonthlyChartGeometry.rangeOf(listOf(bd("-500")))
        val zeroY = range.fractionOf(BigDecimal.ZERO)
        val g = AtharMonthlyChartGeometry.barGeometry(bd("-250"), range)
        assertThat(g.topFraction).isEqualTo(zeroY)
        assertThat(g.heightFraction).isGreaterThan(0f)
        assertThat(g.topFraction + g.heightFraction).isGreaterThan(zeroY)
    }

    @Test
    fun `every bar geometry stays inside the plot bounds`() {
        val values = listOf(bd("-300"), bd("-50"), bd("0"), bd("120"), bd("900"))
        val range = AtharMonthlyChartGeometry.rangeOf(values)
        values.forEach { v ->
            val g = AtharMonthlyChartGeometry.barGeometry(v, range)
            assertThat(g.topFraction).isAtLeast(0f)
            assertThat(g.topFraction).isAtMost(1f)
            assertThat(g.topFraction + g.heightFraction).isAtMost(1.001f)
        }
    }

    // ---- counterexamples (WM028-10) ----

    @Test
    fun `negative savings pair hangs below the zero baseline without clamping`() {
        val values = listOf(
            bd("1200"), bd("800"), // income current/prior
            bd("900"), bd("700"), // expense current/prior
            bd("-150"), bd("-100"), // savings current/prior (negative)
        )
        val range = AtharMonthlyChartGeometry.rangeOf(values)
        val zeroY = range.fractionOf(BigDecimal.ZERO)
        assertThat(zeroY).isGreaterThan(0f)
        assertThat(zeroY).isLessThan(1f)
        val neg150 = AtharMonthlyChartGeometry.barGeometry(bd("-150"), range)
        assertThat(neg150.isVisible).isTrue()
        assertThat(neg150.topFraction).isEqualTo(zeroY)
        assertThat(neg150.topFraction + neg150.heightFraction).isGreaterThan(zeroY)
        val neg100 = AtharMonthlyChartGeometry.barGeometry(bd("-100"), range)
        assertThat(neg150.heightFraction).isGreaterThan(neg100.heightFraction)
    }

    @Test
    fun `all zero comparison renders no fake bars`() {
        val r = AtharMonthlyChartGeometry.rangeOf(listOf(BigDecimal.ZERO, BigDecimal.ZERO))
        assertThat(r.span.signum()).isGreaterThan(0)
        val g = AtharMonthlyChartGeometry.barGeometry(BigDecimal.ZERO, r)
        assertThat(g.isVisible).isFalse()
        assertThat(g.heightFraction).isEqualTo(0f)
    }

    @Test
    fun `astronomical magnitudes project to finite valid fractions`() {
        val huge = BigDecimal("1E+1000")
        val r = AtharMonthlyChartGeometry.rangeOf(listOf(huge, BigDecimal.ONE))
        assertThat(r.max.compareTo(huge)).isEqualTo(0)
        val mid = r.fractionOf(BigDecimal.ONE)
        assertThat(mid.isNaN()).isFalse()
        assertThat(mid.isInfinite()).isFalse()
        assertThat(mid).isAtLeast(0f)
        assertThat(mid).isAtMost(1f)
        assertThat(r.fractionOf(huge)).isEqualTo(0f)
        assertThat(r.fractionOf(BigDecimal.ZERO)).isEqualTo(1f)
        // A value indistinguishable from zero at Float resolution honestly renders
        // nothing — never a fake bar.
        assertThat(AtharMonthlyChartGeometry.barGeometry(BigDecimal.ONE, r).isVisible).isFalse()
        val half = AtharMonthlyChartGeometry.barGeometry(BigDecimal("5E+999"), r)
        assertThat(half.isVisible).isTrue()
        assertThat(half.topFraction).isAtLeast(0f)
        assertThat(half.topFraction).isAtMost(0.5f)
        assertThat(half.topFraction + half.heightFraction).isAtMost(1.001f)
    }

    // ---- selected-month saveable encoding ----

    @Test
    fun `epoch month encoding round trips`() {
        val months = listOf(YearMonth.of(2025, 12), YearMonth.of(2026, 1), YearMonth.of(2026, 10))
        months.forEach { m ->
            assertThat(AtharMonthlyChartGeometry.decodeEpochMonth(AtharMonthlyChartGeometry.encodeEpochMonth(m)))
                .isEqualTo(m)
        }
        assertThat(AtharMonthlyChartGeometry.encodeEpochMonth(YearMonth.of(2026, 3)))
            .isEqualTo(2026L * 12 + 2)
    }

    // ---- localized labels ----

    @Test
    fun `month label is full localized month and year`() {
        assertThat(AtharMonthlyChartGeometry.monthLabel(YearMonth.of(2026, 10), Locale.ENGLISH))
            .isEqualTo("October 2026")
        val ar = AtharMonthlyChartGeometry.monthLabel(YearMonth.of(2026, 10), Locale("ar"))
        assertThat(ar).contains("2026")
        assertThat(ar).isNotEqualTo("10 2026")
    }

    @Test
    fun `amount label uses the money currency symbol and keeps sign`() {
        // Exact shared presentation includes sign isolation and a nonbreaking currency gap.
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("1234.5"), "SAR", Locale.ENGLISH))
            .isEqualTo("\u20661,234.5\u2069\u00A0ر.س")
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("-500"), "SAR", Locale.ENGLISH))
            .isEqualTo("\u2066-500\u2069\u00A0ر.س")
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("10"), "USD", Locale.ENGLISH))
            .isEqualTo("\u206610\u2069\u00A0$")
    }

    @Test
    fun `amount label preserves observed money precision for TalkBack`() {
        // KWD 3-decimal precision is never rounded away (the F1C max-2 label rounded 0.125 → 0.13).
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("0.125"), "KWD", Locale.ENGLISH))
            .isEqualTo("\u20660.125\u2069\u00A0د.ك")
        // Strip insignificant trailing zeros consistently with AtharNumber, not value digits.
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("12.500"), "KWD", Locale.ENGLISH))
            .isEqualTo("\u206612.5\u2069\u00A0د.ك")
        // JPY: grouping + symbol, no decimals invented.
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("1234567"), "JPY", Locale.ENGLISH))
            .isEqualTo("\u20661,234,567\u2069\u00A0¥")
        // Huge magnitude: plain grouped digits, no scientific notation.
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("1E+3"), "SAR", Locale.ENGLISH))
            .isEqualTo("\u20661,000\u2069\u00A0ر.س")
        // Arabic label keeps the symbol and full precision without exponent form.
        val ar = AtharMonthlyChartGeometry.amountLabel(bd("12.500"), "KWD", Locale("ar"))
        assertThat(ar).contains("د.ك")
        assertThat(ar).doesNotContain("E")
    }
}
