package com.athar.feature.trends

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import java.util.Locale.US

class TrendsPercentage071Test {

    private fun arabicDecimal(): DecimalFormat = NumberFormat.getNumberInstance(Locale("ar")) as DecimalFormat

    @Test
    fun `english number keeps one decimal and no percent sign`() {
        assertThat(TrendsPercentage071.number(44.3, US)).isEqualTo("44.3")
        assertThat(TrendsPercentage071.number(55.7, US)).isEqualTo("55.7")
    }

    @Test
    fun `values are already percentages and are never re-scaled`() {
        // 44.3 must never become 4,430% — the input is divided only because the
        // JDK percent instance multiplies by 100.
        assertThat(TrendsPercentage071.token(44.3, US, fractionDigits = 1)).isEqualTo("44.3%")
        assertThat(TrendsPercentage071.number(0.0, US)).isEqualTo("0")
    }

    @Test
    fun `large tokens group thousands in the locale`() {
        assertThat(TrendsPercentage071.magnitudeToken(215240017.0, US)).isEqualTo("215,240,017%")
        assertThat(TrendsPercentage071.token(-9999855.8, US, fractionDigits = 1)).isEqualTo("-9,999,855.8%")
    }

    @Test
    fun `signed token keeps an explicit plus for positive deltas`() {
        assertThat(TrendsPercentage071.token(25.0, US, alwaysSigned = true)).isEqualTo("+25%")
        assertThat(TrendsPercentage071.token(-25.0, US, alwaysSigned = true)).isEqualTo("-25%")
    }

    @Test
    fun `arabic locale uses arabic digits and arabic percent sign`() {
        val number = TrendsPercentage071.number(44.3, Locale("ar"))
        val token = TrendsPercentage071.token(1099.0, Locale("ar"))
        assertThat(number).isNotNull()
        assertThat(token).isNotNull()
        // No Latin digits leak into Arabic copy, and the Arabic percent sign is used.
        assertThat(number!!).doesNotContain("4")
        assertThat(token!!).contains(arabicDecimal().decimalFormatSymbols.percent.toString())
        // Positive assertion (regression for ICU ignoring zeroDigit on device):
        // at least one Arabic-Indic digit must be present in each rendered value.
        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        assertThat(number.any { it in arabicIndic }).isTrue()
        assertThat(token.any { it in arabicIndic }).isTrue()
    }

    @Test
    fun `nonfinite inputs return null so callers withhold meaningless ratios`() {
        assertThat(TrendsPercentage071.number(Double.NaN, US)).isNull()
        assertThat(TrendsPercentage071.number(Double.POSITIVE_INFINITY, US)).isNull()
        assertThat(TrendsPercentage071.token(Double.NEGATIVE_INFINITY, US)).isNull()
        assertThat(TrendsPercentage071.magnitudeToken(Double.NaN, US)).isNull()
    }
}
