package com.athar.core.designsystem.component

import com.athar.core.designsystem.display.MoneyTextFormat
import java.math.BigDecimal
import java.math.MathContext
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Pure geometry + label math for [AtharMonthlyChartWithLines], [AtharComparisonBars] and
 * [AtharProportionBar]. Deliberately free of Compose/Android imports so it stays
 * unit-testable on the JVM (signed-scale regression cases live in
 * AtharMonthlyChartGeometryTest).
 *
 * Money never leaves BigDecimal here: min/max/span and every division run in BigDecimal
 * under [MathContext.DECIMAL128]; Float appears only as the final 0f..1f coordinate
 * projection. No raw monetary Double is ever summed, compared or divided.
 */
internal object AtharMonthlyChartGeometry {

    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val MINUS_ONE: BigDecimal = BigDecimal.valueOf(-1)
    private val TWO: BigDecimal = BigDecimal.valueOf(2)
    private val DIVISION_CONTEXT: MathContext = MathContext.DECIMAL128

    /** Signed value window that always contains zero. Fractions measured from plot top. */
    data class SignedRange(val min: BigDecimal, val max: BigDecimal) {
        val span: BigDecimal = max.subtract(min)

        /**
         * [value] as a 0f..1f fraction of plot height, measured from the top. The
         * BigDecimal quotient is clamped before the Float projection so even
         * astronomical magnitudes (1E+1000-scale spans) stay finite — never Inf/NaN.
         */
        fun fractionOf(value: BigDecimal): Float {
            if (span.signum() <= 0) return 0.5f
            val quotient = max.subtract(value).divide(span, DIVISION_CONTEXT)
            return quotient.coerceIn(MINUS_ONE, TWO).toFloat().coerceIn(0f, 1f)
        }
    }

    /** Bar rectangle as fractions of plot height, anchored at the zero baseline. */
    data class BarGeometry(val topFraction: Float, val heightFraction: Float) {
        val isVisible: Boolean get() = heightFraction > 0f
    }

    /**
     * Range covering zero plus every plotted value (bars, target line, average line,
     * comparison pairs).
     * - All-positive data → [0, max]: zero baseline at the plot bottom.
     * - All-negative data → [min, 0]: zero baseline at the plot top.
     * - Mixed data → full signed span with zero strictly inside.
     * - All-zero (or empty) → symmetric unit range, so the baseline still renders and
     *   no division by zero can occur.
     */
    fun rangeOf(values: List<BigDecimal>): SignedRange {
        if (values.isEmpty() || values.all { it.signum() == 0 }) {
            return SignedRange(MINUS_ONE, BigDecimal.ONE)
        }
        val lo = values.reduce(BigDecimal::min).min(ZERO)
        val hi = values.reduce(BigDecimal::max).max(ZERO)
        return if (hi.subtract(lo).signum() <= 0) {
            SignedRange(MINUS_ONE, BigDecimal.ONE)
        } else {
            SignedRange(lo, hi)
        }
    }

    /**
     * Zero renders nothing (no fake minimum bar). Positive grows up from the zero
     * baseline; negative hangs below it.
     */
    fun barGeometry(value: BigDecimal, range: SignedRange): BarGeometry {
        val zeroY = range.fractionOf(ZERO)
        val valueY = range.fractionOf(value)
        return BarGeometry(
            topFraction = minOf(zeroY, valueY),
            heightFraction = abs(valueY - zeroY),
        )
    }

    /** [part]/[total] as a 0f..1f stacked-bar weight; BigDecimal division, finite. */
    fun shareFraction(part: BigDecimal, total: BigDecimal): Float {
        if (total.signum() <= 0 || part.signum() <= 0) return 0f
        return part.divide(total, DIVISION_CONTEXT)
            .coerceIn(ZERO, BigDecimal.ONE)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    /** [part]/[total] as a 0..100 percent for legends; BigDecimal division, finite. */
    fun sharePercent(part: BigDecimal, total: BigDecimal): Double {
        if (total.signum() <= 0 || part.signum() <= 0) return 0.0
        return part.divide(total, DIVISION_CONTEXT)
            .coerceIn(ZERO, BigDecimal.ONE)
            .movePointRight(2)
            .toDouble()
            .coerceIn(0.0, 100.0)
    }

    /** Saveable month key: proleptic month index (year * 12 + monthValue - 1). */
    fun encodeEpochMonth(month: YearMonth): Long = month.year * 12L + (month.monthValue - 1)

    fun decodeEpochMonth(encoded: Long): YearMonth =
        YearMonth.of((encoded / 12).toInt(), (encoded % 12).toInt() + 1)

    /**
     * Full localized month + year, e.g. "October 2026" / "أكتوبر 2026". Month name comes
     * from the locale; the year uses the locale's digits so the label is stable
     * regardless of numbering system.
     */
    fun monthLabel(month: YearMonth, locale: Locale): String =
        "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}"

    /**
     * Full localized amount label with EXACT money presentation — a thin delegate to
     * [MoneyTextFormat.format], the frozen authority also behind [AtharNumber], so a
     * chart label can never drift from the rest of the app. Axis-rounding disclosure:
     * chart labels never round. The amount's own precision is preserved (KWD 10.123
     * keeps its three decimals; SAR 100000001.01 keeps its cents and grouping); only
     * trailing zeros are stripped and non-positive scales (1E+3 style) collapse to
     * grouped integers — exactly as everywhere else in Athar. Sign and currency symbol
     * are bidi-isolated so Arabic and Latin runs interleave safely; TalkBack reads the
     * full exact value in the UI locale's digits.
     */
    fun amountLabel(amount: BigDecimal, currency: String, locale: Locale): String =
        MoneyTextFormat.format(amount, currency, locale)

    /**
     * Length fraction for a horizontal proportion bar ([AtharBarChart]): the
     * BigDecimal-exact share of [value] against [scaleMax], sign-faithful. A positive
     * value only ever measures against a positive scale; a negative value only against
     * a negative scale; zero, sign-mismatched or zero-scale inputs render nothing (0f)
     * instead of faking a bar. Division runs in BigDecimal under DECIMAL128 and is
     * clamped to a finite 0f..1f — Float is only ever a dimensionless fraction here,
     * never a financial amount.
     */
    fun barShareFraction(value: BigDecimal, scaleMax: BigDecimal): Float {
        if (scaleMax.signum() == 0) return 0f
        val sameSide = (value.signum() > 0 && scaleMax.signum() > 0) ||
            (value.signum() < 0 && scaleMax.signum() < 0)
        if (!sameSide) return 0f
        return value.abs().divide(scaleMax.abs(), DIVISION_CONTEXT)
            .coerceIn(ZERO, BigDecimal.ONE)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}
