package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Counterexamples independently computed with decimal arithmetic, not the implementation. */
class InvestmentMoneyExactnessTest {
    @Test
    fun `large monetary return never inherits binary Double noise`() {
        val result = InvestmentsCalc.shareReturn(Money.of("100000000000000000001.01"), 0.2)
        assertThat(result.amount.compareTo(BigDecimal("20000000000000000000.20"))).isEqualTo(0)
        assertThat(result.currency).isEqualTo("SAR")
    }

    @Test
    fun `huge valid monetary endpoints still yield a finite dimensionless share`() {
        val fraction = InvestmentsCalc.sharePercent(Money.of("1E+399"), Money.of("1E+400"))
        assertThat(fraction.isFinite()).isTrue()
        assertThat(fraction).isWithin(0.000000000001).of(0.1)
    }

    @Test
    fun `a same-number different-currency corpus is not an ownership denominator`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentsCalc.sharePercent(Money.of("100", "USD"), Money.of("100", "SAR"))
        }
    }

    @Test
    fun `KWD return keeps its meaningful third decimal`() {
        val result = InvestmentsCalc.shareReturn(Money.of("4.05", "KWD"), 0.5)
        assertThat(result.amount.compareTo(BigDecimal("2.025"))).isEqualTo(0)
        assertThat(result.currency).isEqualTo("KWD")
    }
}
