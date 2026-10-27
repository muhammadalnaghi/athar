package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Regression tests for WM028-P: `averageMonthly` must round at the standard minor-unit
 * scale of the literal currency (ISO-4217 default fraction digits) instead of a constant
 * two decimals, while preserving `BigDecimal`/`RoundingMode.HALF_EVEN`, the positive-months
 * guard, and all existing SAR behavior.
 *
 * Every expected value below was verified independently with exact decimal arithmetic
 * (`decimal.ROUND_HALF_EVEN`, the Python twin of `java.math.RoundingMode.HALF_EVEN`).
 *
 * Note: Truth (not AssertJ) is the assertion library here, so numeric equality is
 * asserted as `assertThat(actual.compareTo(expected)).isEqualTo(0)`; scale contracts
 * are pinned separately via `BigDecimal.scale()`.
 */
class GoalCurrencyPrecisionTest {

    @Test
    fun `kwd total averages at three minor units`() {
        val average = GoalCalc.averageMonthly(Money.of("30.369", "KWD"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("10.123"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(3)
        assertThat(average.currency).isEqualTo("KWD")
    }

    @Test
    fun `bhd total averages at three minor units`() {
        val average = GoalCalc.averageMonthly(Money.of("30.369", "BHD"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("10.123"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(3)
        assertThat(average.currency).isEqualTo("BHD")
    }

    @Test
    fun `jpy total averages at zero minor units`() {
        val average = GoalCalc.averageMonthly(Money.of("1001", "JPY"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("334"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(0)
        assertThat(average.currency).isEqualTo("JPY")
    }

    @Test
    fun `jpy averaging rounds half even at yen granularity`() {
        // 5 / 2 = 2.5 exact; HALF_EVEN rounds the tie to the even neighbor 2.
        val average = GoalCalc.averageMonthly(Money.of("5", "JPY"), months = 2)

        assertThat(average.amount.compareTo(BigDecimal("2"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(0)
    }

    @Test
    fun `sar averaging keeps existing two minor units and half even rounding`() {
        val average = GoalCalc.averageMonthly(Money.of("10000", "SAR"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("3333.33"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(2)
        assertThat(average.currency).isEqualTo("SAR")
    }

    @Test
    fun `sar averaging rounds exact ties half even`() {
        // 100.125 / 3 = 33.375 exact; HALF_EVEN rounds the tie to 33.38 (even neighbor).
        val average = GoalCalc.averageMonthly(Money.of("100.125", "SAR"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("33.38"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(2)
    }

    @Test
    fun `meaningful extra input precision participates before rounding`() {
        // 100.005 / 3 = 33.335 exact -> HALF_EVEN -> 33.34. Pre-truncating the input to
        // 100.00 before dividing would instead yield 33.33; the third input digit must
        // legitimately influence the rounded result.
        val average = GoalCalc.averageMonthly(Money.of("100.005", "SAR"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("33.34"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(2)
    }

    @Test
    fun `unknown currency falls back to two minor units without relabeling`() {
        val average = GoalCalc.averageMonthly(Money.of("100", "XYZ"), months = 3)

        assertThat(average.amount.compareTo(BigDecimal("33.33"))).isEqualTo(0)
        assertThat(average.amount.scale()).isEqualTo(2)
        assertThat(average.currency).isEqualTo("XYZ")
    }

    @Test
    fun `averaging rejects zero months`() {
        assertThrows<IllegalArgumentException> {
            GoalCalc.averageMonthly(Money.of("9000", "SAR"), months = 0)
        }
    }

    @Test
    fun `averaging rejects negative months`() {
        assertThrows<IllegalArgumentException> {
            GoalCalc.averageMonthly(Money.of("9000", "SAR"), months = -3)
        }
    }
}
