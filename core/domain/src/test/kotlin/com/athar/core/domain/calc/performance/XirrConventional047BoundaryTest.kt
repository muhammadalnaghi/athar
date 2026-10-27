package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrRejection
import com.athar.core.domain.model.performance.XirrResult
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class XirrConventional047BoundaryTest {
    private fun input(opening: String, closing: String) = XirrInput(
        flows = listOf(
            DatedCashFlow("capital", LocalDate(2023, 1, 1), Money.of(opening)),
            DatedCashFlow("proceeds", LocalDate(2024, 1, 1), Money.of(closing)),
        ),
    )

    @Test
    fun `exact lower endpoint is retained without moving the bounded window`() {
        val result = XirrCalc.annualRate(input("-1000000000000", "1"))
        assertThat(result).isInstanceOf(XirrResult.Rate::class.java)
        assertThat((result as XirrResult.Rate).annualRateRatio)
            .isEqualTo(BigDecimal("-0.999999999999"))
    }

    @Test
    fun `conventional root beyond upper bound is withheld rather than clamped`() {
        val result = XirrCalc.annualRate(input("-100", "10200"))
        assertThat(result).isInstanceOf(XirrResult.Rejected::class.java)
        assertThat((result as XirrResult.Rejected).reason).isEqualTo(XirrRejection.NO_SIGN_CHANGE)
    }
}
