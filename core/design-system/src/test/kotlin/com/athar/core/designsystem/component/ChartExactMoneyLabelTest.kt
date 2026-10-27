package com.athar.core.designsystem.component

import com.athar.core.designsystem.display.MoneyTextFormat
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * 028-P-CHARTS regression: chart amount labels are identical to the frozen exact-money
 * authority [MoneyTextFormat] — no chart-local rounding, no fraction-digit cap, no
 * float quantization — across magnitudes, currency scales and locales; and bar share
 * fractions stay BigDecimal-exact, sign-faithful and finite (Float = dimensionless
 * fraction only, never a financial endpoint).
 */
class ChartExactMoneyLabelTest {

    private fun bd(value: String) = BigDecimal(value)

    // ---- amountLabel parity with the exact-money authority ----

    @Test
    fun `hundred million SAR keeps full cents and grouping like the authority`() {
        val expected = MoneyTextFormat.format(bd("100000001.01"), "SAR", Locale.US)
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("100000001.01"), "SAR", Locale.US))
            .isEqualTo(expected)
        assertThat(expected).contains("100,000,001.01")
        assertThat(expected).endsWith("\u2069\u00A0ر.س")
    }

    @Test
    fun `KWD three decimals are never rounded away`() {
        val label = AtharMonthlyChartGeometry.amountLabel(bd("10.123"), "KWD", Locale.US)
        assertThat(label).isEqualTo(MoneyTextFormat.format(bd("10.123"), "KWD", Locale.US))
        assertThat(label).contains("10.123")
        assertThat(label).endsWith("\u2069\u00A0د.ك")
    }

    @Test
    fun `scale beyond the old six digit cap is preserved instead of rounded`() {
        // Old implementation capped fraction digits at 6 and silently rounded this to "0".
        val label = AtharMonthlyChartGeometry.amountLabel(bd("0.000000125"), "USD", Locale.US)
        assertThat(label).isEqualTo(MoneyTextFormat.format(bd("0.000000125"), "USD", Locale.US))
        assertThat(label).contains("0.000000125")
    }

    @Test
    fun `trailing zeros follow the authority and quote precision is not padded`() {
        val label = AtharMonthlyChartGeometry.amountLabel(bd("12500.000"), "JPY", Locale.US)
        assertThat(label).isEqualTo(MoneyTextFormat.format(bd("12500.000"), "JPY", Locale.US))
        assertThat(label).contains("12,500")
    }

    @Test
    fun `negative sign is isolated and currency stays the original one`() {
        val label = AtharMonthlyChartGeometry.amountLabel(bd("-39.50"), "USD", Locale.US)
        assertThat(label).isEqualTo(MoneyTextFormat.format(bd("-39.50"), "USD", Locale.US))
        assertThat(label).isEqualTo("\u2066-39.5\u2069\u00A0$")
    }

    @Test
    fun `known zero is shown as zero and negative scale collapses to grouped integers`() {
        assertThat(AtharMonthlyChartGeometry.amountLabel(bd("0.00"), "SAR", Locale.US))
            .isEqualTo("\u20660\u2069\u00A0ر.س")
        val thousands = AtharMonthlyChartGeometry.amountLabel(bd("1E+3"), "SAR", Locale.US)
        assertThat(thousands).isEqualTo(MoneyTextFormat.format(bd("1E+3"), "SAR", Locale.US))
        assertThat(thousands).contains("1,000")
    }

    @Test
    fun `Arabic locale determines digits independently of process default`() {
        val locale = Locale.forLanguageTag("ar-SA")
        val label = AtharMonthlyChartGeometry.amountLabel(bd("0.125"), "KWD", locale)
        assertThat(label).isEqualTo(MoneyTextFormat.format(bd("0.125"), "KWD", locale))
        assertThat(label).contains("٠٫١٢٥")
        assertThat(label).endsWith("د.ك")
    }

    // ---- barShareFraction (BigDecimal-exact drawn proportions) ----

    @Test
    fun `near equal large values preserve distinct money even when drawn fractions coincide`() {
        val big = bd("100000001.01")
        val near = bd("100000000.99")
        assertThat(AtharMonthlyChartGeometry.barShareFraction(big, big)).isEqualTo(1f)
        // Float is a drawing projection: this exact ratio legitimately rounds to 1f.
        assertThat(AtharMonthlyChartGeometry.barShareFraction(near, big)).isEqualTo(1f)
        val bigLabel = AtharMonthlyChartGeometry.amountLabel(big, "SAR", Locale.US)
        val nearLabel = AtharMonthlyChartGeometry.amountLabel(near, "SAR", Locale.US)
        assertThat(bigLabel).contains("100,000,001.01")
        assertThat(nearLabel).contains("100,000,000.99")
        assertThat(bigLabel).isNotEqualTo(nearLabel)
    }

    @Test
    fun `sign mismatched zero or zero-scale values render no bar`() {
        assertThat(AtharMonthlyChartGeometry.barShareFraction(bd("-5"), bd("100"))).isEqualTo(0f)
        assertThat(AtharMonthlyChartGeometry.barShareFraction(bd("5"), bd("-100"))).isEqualTo(0f)
        assertThat(AtharMonthlyChartGeometry.barShareFraction(BigDecimal.ZERO, bd("100"))).isEqualTo(0f)
        assertThat(AtharMonthlyChartGeometry.barShareFraction(bd("5"), BigDecimal.ZERO)).isEqualTo(0f)
    }

    @Test
    fun `all negative categories still draw proportional bars`() {
        val fraction = AtharMonthlyChartGeometry.barShareFraction(bd("-400"), bd("-1000"))
        assertThat(fraction).isWithin(0.000001f).of(0.4f)
    }

    @Test
    fun `over-scale shares clamp to a finite full bar`() {
        assertThat(AtharMonthlyChartGeometry.barShareFraction(bd("400"), bd("100"))).isEqualTo(1f)
    }
}
