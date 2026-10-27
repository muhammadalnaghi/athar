package com.athar.core.domain.calc.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.DebtId
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.TermEvidence
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class DebtPayoffOracleTest {
    private fun calculate(principal: String, apr: String, payment: String, rounding: RoundingMode): DebtPayoffOutcome.Compared {
        val loan = DebtLoan(
            DebtId("oracle"), Money.of(principal, "SAR"), BigDecimal(apr),
            Money.of(payment, "SAR"), Money.of("0", "SAR"),
            TermEvidence.SupportedNominalMonthly(LocalDate(2027, 1, 1), 1),
        )
        val request = DebtPayoffRequest(listOf(loan), Money.of(payment, "SAR"), "SAR", rounding, 24, true)
        return DebtPayoffCalculator.compare(request) as DebtPayoffOutcome.Compared
    }

    @Test
    fun `positive nominal APR matches an independent rounded-decimal amortization oracle`() {
        // Python Decimal recurrence, independent of Kotlin production functions:
        // round_HALF_EVEN(opening *0.12 /12, cents), payment=min(110, owed).
        val result = calculate("1000", "0.12", "110", RoundingMode.HALF_EVEN)
        for (run in listOf(result.avalanche, result.snowball)) {
            assertThat(run.ledger).hasSize(10)
            assertThat(run.totalInterest.amount).isEquivalentAccordingToCompareTo(BigDecimal("53.78"))
            assertThat(run.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("1053.78"))
            assertThat(run.ledger.last().entries.single().payment.amount)
                .isEquivalentAccordingToCompareTo(BigDecimal("63.78"))
            assertThat(run.ledger.last().entries.single().closingBalance.isZero()).isTrue()
        }
    }

    @Test
    fun `caller rounding policy controls exact half-cent interest ties`() {
        val even = calculate("1", "0.06", "1.01", RoundingMode.HALF_EVEN)
        val up = calculate("1", "0.06", "1.01", RoundingMode.HALF_UP)
        assertThat(even.avalanche.totalInterest.amount).isEquivalentAccordingToCompareTo(BigDecimal("0.00"))
        assertThat(up.avalanche.totalInterest.amount).isEquivalentAccordingToCompareTo(BigDecimal("0.01"))
        assertThat(even.avalanche.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("1.00"))
        assertThat(up.avalanche.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("1.01"))
    }
}
