package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class InvestmentAllocationDecimalTest {
    @Test
    fun `one final division preserves enormous exact recorded endpoints`() {
        val result = InvestmentsCalc.shareReturn(
            Money.of("900000000000000000000.03"), Money.of("1"), Money.of("3"),
        )
        assertThat(result.amount.compareTo(BigDecimal("300000000000000000000.01"))).isEqualTo(0)
    }

    @Test
    fun `KWD allocation uses three digits and JPY does not invent cents`() {
        val kwd = InvestmentsCalc.shareReturn(
            Money.of("4.05", "KWD"), Money.of("1", "KWD"), Money.of("2", "KWD"),
        )
        assertThat(kwd.amount.compareTo(BigDecimal("2.025"))).isEqualTo(0)
        val jpy = InvestmentsCalc.shareReturn(
            Money.of("10", "JPY"), Money.of("1", "JPY"), Money.of("2", "JPY"),
        )
        assertThat(jpy.amount).isEqualTo(BigDecimal("5"))
    }

    @Test
    fun `greater observed decimal precision is preserved`() {
        val result = InvestmentsCalc.shareReturn(
            Money.of("0.000000126", "USD"), Money.of("1", "USD"), Money.of("2", "USD"),
        )
        assertThat(result.amount.compareTo(BigDecimal("0.000000063"))).isEqualTo(0)
    }

    @Test
    fun `currency mismatch and zero corpus cannot allocate a return`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10"), Money.of("1", "USD"), Money.of("2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10"), Money.zero(), Money.zero())
        }
    }

    @Test
    fun `losses retain their sign and final halves follow bankers rounding`() {
        assertThat(InvestmentsCalc.shareReturn(Money.of("-10"), Money.of("1"), Money.of("2")).amount)
            .isEqualTo(BigDecimal("-5.00"))
        assertThat(InvestmentsCalc.shareReturn(Money.of("0.01"), Money.of("1"), Money.of("2")).amount)
            .isEqualTo(BigDecimal("0.00"))
        assertThat(InvestmentsCalc.shareReturn(Money.of("0.03"), Money.of("1"), Money.of("2")).amount)
            .isEqualTo(BigDecimal("0.02"))
    }

    @Test
    fun `unsupported currency and invalid contribution are rejected without coercion`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10", "XXX"), BigDecimal("0.5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10"), Money.of("-1"), Money.of("2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10"), Money.of("3"), Money.of("2"))
        }
    }

    @Test
    fun `percentage entry remains decimal and signed`() {
        val positive = InvestmentsCalc.returnFromPercent(Money.of("100000001.01"), BigDecimal("25"))
        assertThat(positive.amount.compareTo(BigDecimal("25000000.25"))).isEqualTo(0)
        val loss = InvestmentsCalc.returnFromPercent(Money.of("100"), BigDecimal("-2.5"))
        assertThat(loss.amount).isEqualTo(BigDecimal("-2.50"))
    }

    @Test
    fun `decimal share compatibility cannot accept NaN or out of range fractions`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10"), Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.shareReturn(Money.of("10"), BigDecimal("1.1"))
        }
    }
}
