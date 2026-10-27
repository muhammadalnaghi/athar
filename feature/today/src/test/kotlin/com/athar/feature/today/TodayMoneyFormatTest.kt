package com.athar.feature.today

import androidx.compose.ui.unit.dp
import com.athar.core.common.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Money must survive narrow widths, RTL, and font scaling: the numeric run is an
 * LTR isolate and is joined to the currency symbol with a no-break space, so the
 * number itself can never wrap or reorder. The stacking decision for the three
 * summary pills is a pure width × fontScale rule.
 */
class TodayMoneyFormatTest {

    @Test
    fun `formats grouped positive amount with LTR isolate and no-break currency`() {
        val formatted = formatStableMoney(Money.of("1234.5"), Locale.US)

        assertThat(formatted).isEqualTo("\u20661,234.5\u2069\u00A0ر.س")
    }

    @Test
    fun `negative sign stays inside the LTR isolate`() {
        val formatted = formatStableMoney(Money.of("-200"), Locale.US)

        assertThat(formatted).isEqualTo("\u2066-200\u2069\u00A0ر.س")
    }

    @Test
    fun `zero renders without forced decimals`() {
        val formatted = formatStableMoney(Money.zero(), Locale.US)

        assertThat(formatted).isEqualTo("\u20660\u2069\u00A0ر.س")
    }

    @Test
    fun `currency comes from the Money itself not the display preference`() {
        val formatted = formatStableMoney(Money.of("5", "USD"), Locale.US)

        assertThat(formatted).isEqualTo("\u20665\u2069\u00A0$")
    }

    @Test
    fun `unknown currency code falls back to the code`() {
        val formatted = formatStableMoney(Money.of("7", "XYZ"), Locale.US)

        assertThat(formatted).isEqualTo("\u20667\u2069\u00A0XYZ")
    }

    @Test
    fun `formatted money contains no breakable space`() {
        val formatted = formatStableMoney(Money.of("987654.31"), Locale.US)

        assertThat(formatted).doesNotContain(" ")
        assertThat(formatted).contains("\u00A0")
    }

    @Test
    fun `wide layout keeps pills in a row`() {
        assertThat(shouldStackSummaryPills(availableWidth = 400.dp, fontScale = 1.0f)).isFalse()
    }

    @Test
    fun `narrow layout stacks pills`() {
        assertThat(shouldStackSummaryPills(availableWidth = 320.dp, fontScale = 1.0f)).isTrue()
    }

    @Test
    fun `row width threshold itself stays unstacked`() {
        assertThat(shouldStackSummaryPills(availableWidth = 360.dp, fontScale = 1.0f)).isFalse()
    }

    @Test
    fun `large font scale stacks pills even on a wide screen`() {
        assertThat(shouldStackSummaryPills(availableWidth = 400.dp, fontScale = 1.5f)).isTrue()
    }

    @Test
    fun `small font scale widens the row window`() {
        assertThat(shouldStackSummaryPills(availableWidth = 330.dp, fontScale = 0.85f)).isFalse()
    }
}
