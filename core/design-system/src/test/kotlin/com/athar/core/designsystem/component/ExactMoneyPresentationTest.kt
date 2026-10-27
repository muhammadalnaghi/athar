package com.athar.core.designsystem.component

import com.athar.core.designsystem.display.MoneyTextFormat
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

class ExactMoneyPresentationTest {
    @Test
    fun `large amount retains units and cents without float quantization`() {
        val text = MoneyTextFormat.format(BigDecimal("100000001.01"), "SAR", Locale.US)
        assertThat(text).contains("100,000,001.01")
        assertThat(text).endsWith("\u2069\u00A0ر.س")
    }

    @Test
    fun `three decimal money and significant quote precision are never rounded away`() {
        assertThat(MoneyTextFormat.format(BigDecimal("0.125"), "KWD", Locale.US)).contains("0.125")
        assertThat(MoneyTextFormat.format(BigDecimal("0.000125"), "USD", Locale.US)).contains("0.000125")
        assertThat(MoneyTextFormat.format(BigDecimal("12500.000"), "JPY", Locale.US)).contains("12,500")
    }

    @Test
    fun `negative digits are isolated and currency remains the actual original currency`() {
        val text = MoneyTextFormat.format(BigDecimal("-39.50"), "USD", Locale.US)
        assertThat(text).isEqualTo("\u2066-39.5\u2069\u00A0$")
    }

    @Test
    fun `Arabic locale determines digits independently of process default`() {
        val text = MoneyTextFormat.format(BigDecimal("0.125"), "KWD", Locale.forLanguageTag("ar-SA"))
        assertThat(text).contains("٠٫١٢٥")
        assertThat(text).endsWith("د.ك")
    }

    @Test
    fun `known zero is displayed as zero rather than missing`() {
        assertThat(MoneyTextFormat.format(BigDecimal("0.00"), "SAR", Locale.US))
            .isEqualTo("\u20660\u2069\u00A0ر.س")
    }

    @Test
    fun `animation preserves exact endpoints and uses decimal amount arithmetic`() {
        val from = BigDecimal("-100000001.01")
        val to = BigDecimal("100000001.03")
        assertThat(MoneyAnimation.amountAt(from, to, BigDecimal.ZERO)).isEqualTo(from)
        assertThat(MoneyAnimation.amountAt(from, to, BigDecimal.ONE)).isEqualTo(to)
        assertThat(MoneyAnimation.amountAt(from, to, BigDecimal("0.5")).compareTo(BigDecimal("0.01")))
            .isEqualTo(0)
    }

    @Test
    fun `animation rejects out of range progress instead of inventing extrapolated money`() {
        assertThrows(IllegalArgumentException::class.java) {
            MoneyAnimation.amountAt(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal("1.1"))
        }
    }
}
