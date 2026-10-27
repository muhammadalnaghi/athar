package com.athar.core.domain.calc.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.DebtId
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.TermEvidence
import com.athar.core.domain.model.debt.UnsupportedTermReason
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class DebtPayoffBoundaryTest {
    private fun loan(id: String = "A", principal: String = "100") = DebtLoan(
        DebtId(id), Money.of(principal, "SAR"), BigDecimal.ZERO,
        Money.of("100", "SAR"), Money.of("0", "SAR"),
        TermEvidence.SupportedNominalMonthly(LocalDate(2027, 1, 1), 1),
    )

    private fun request(
        loans: List<DebtLoan> = listOf(loan()),
        budget: String = "100",
        horizon: Int = 24,
        rounding: RoundingMode = RoundingMode.HALF_EVEN,
        attests: Boolean = true,
    ) = DebtPayoffRequest(loans, Money.of(budget, "SAR"), "SAR", rounding, horizon, attests)

    @Test
    fun `missing numerical terms never need fabricated zero placeholders`() {
        for (partial in listOf(
            loan().copy(nominalAnnualApr = null),
            loan().copy(minimumPayment = null),
            loan().copy(fixedMonthlyFee = null),
        )) {
            val result = DebtPayoffCalculator.compare(request(listOf(partial)))
            assertThat(result).isInstanceOf(DebtPayoffOutcome.UnsupportedTerms::class.java)
            assertThat((result as DebtPayoffOutcome.UnsupportedTerms).rejections.single().reason)
                .isEqualTo(UnsupportedTermReason.MISSING_TERMS)
        }
    }

    @Test
    fun `already zero needs neither invented future terms nor a cycle date`() {
        val zero = loan(principal = "0").copy(
            nominalAnnualApr = null, minimumPayment = null, fixedMonthlyFee = null,
            evidence = TermEvidence.Missing,
        )
        val result = DebtPayoffCalculator.compare(request(listOf(zero), budget = "0", attests = false))
            as DebtPayoffOutcome.Compared
        for (run in listOf(result.avalanche, result.snowball)) {
            assertThat(run.ledger).isEmpty()
            assertThat(run.allSettled).isTrue()
            assertThat(run.finalPayoffDate).isNull()
            assertThat(run.summaries.single().cyclesToPayoff).isEqualTo(0)
            assertThat(run.summaries.single().payoffDate).isNull()
        }
    }

    @Test
    fun `settled unknown terms do not block an independently known active debt`() {
        val zero = loan("Z", "0").copy(evidence = TermEvidence.Missing)
        val result = DebtPayoffCalculator.compare(request(listOf(zero, loan()))) as DebtPayoffOutcome.Compared
        assertThat(result.avalanche.ledger).hasSize(1)
        assertThat(result.avalanche.ledger.single().entries.single().debtId).isEqualTo(DebtId("A"))
    }

    @Test
    fun `negative budget is invalid rather than an infeasible positive budget`() {
        val result = DebtPayoffCalculator.compare(request(budget = "-1")) as DebtPayoffOutcome.InvalidInput
        assertThat(result.errors.joinToString()).contains("nonnegative")
    }

    @Test
    fun `first due date cannot contradict its explicit anchor`() {
        val bad = loan().copy(evidence = TermEvidence.SupportedNominalMonthly(LocalDate(2027, 1, 31), 30))
        val result = DebtPayoffCalculator.compare(request(listOf(bad))) as DebtPayoffOutcome.InvalidInput
        assertThat(result.errors.joinToString()).contains("anchor")
    }

    @Test
    fun `invalid anchor is rejected before calendar construction`() {
        val bad = loan().copy(evidence = TermEvidence.SupportedNominalMonthly(LocalDate(2027, 1, 1), 0))
        val result = DebtPayoffCalculator.compare(request(listOf(bad))) as DebtPayoffOutcome.InvalidInput
        assertThat(result.errors.joinToString()).contains("anchorDay")
    }

    @Test
    fun `explicit 31 survives a clamped leap day starting anchor`() {
        val debt = loan(principal = "200").copy(
            evidence = TermEvidence.SupportedNominalMonthly(LocalDate(2028, 2, 29), 31),
        )
        val result = DebtPayoffCalculator.compare(request(listOf(debt))) as DebtPayoffOutcome.Compared
        assertThat(result.avalanche.ledger.map { it.dueDate }).containsExactly(
            LocalDate(2028, 2, 29), LocalDate(2028, 3, 31),
        ).inOrder()
    }

    @Test
    fun `request and invalid result defensively snapshot caller collections`() {
        val mutable = mutableListOf(loan())
        val input = request(mutable)
        mutable.clear()
        assertThat(input.loans).hasSize(1)
        assertThat(DebtPayoffCalculator.compare(input)).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
        val errors = mutableListOf("original")
        val result = DebtPayoffOutcome.InvalidInput(errors)
        errors.clear()
        assertThat(result.errors).containsExactly("original")
    }

    @Test
    fun `large money and fractional units are never projected through binary floats`() {
        val debt = loan(principal = "100000001.01")
        val result = DebtPayoffCalculator.compare(request(listOf(debt), budget = "100000001.01"))
            as DebtPayoffOutcome.Compared
        assertThat(result.avalanche.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("100000001.01"))
        assertThat(result.avalanche.ledger.single().unusedBudget.isZero()).isTrue()
    }

    @Test
    fun `unrepresentable interest under explicit unnecessary rounding is typed invalid`() {
        val debt = loan(principal = "1").copy(nominalAnnualApr = BigDecimal("0.1"))
        val result = DebtPayoffCalculator.compare(request(listOf(debt), rounding = RoundingMode.UNNECESSARY))
        assertThat(result).isInstanceOf(DebtPayoffOutcome.InvalidInput::class.java)
    }

    @Test
    fun `unrepresentable future calendar produces no partial payoff prediction`() {
        val debt = loan(principal = "2").copy(
            minimumPayment = Money.of("1", "SAR"),
            evidence = TermEvidence.SupportedNominalMonthly(LocalDate(999999999, 12, 31), 31),
        )
        val result = DebtPayoffCalculator.compare(request(listOf(debt), budget = "1", horizon = 2))
        assertThat(result).isInstanceOf(DebtPayoffOutcome.InvalidInput::class.java)
    }

    @Test
    fun `unsettled summaries have neither a payoff date nor a fake cycle count`() {
        val debt = loan().copy(minimumPayment = Money.of("0", "SAR"))
        val result = DebtPayoffCalculator.compare(request(listOf(debt), budget = "0", horizon = 2))
            as DebtPayoffOutcome.HorizonExhausted
        for (run in listOf(result.avalanche, result.snowball)) {
            assertThat(run.finalPayoffDate).isNull()
            assertThat(run.summaries.single().payoffDate).isNull()
            assertThat(run.summaries.single().cyclesToPayoff).isNull()
        }
    }

    @Test
    fun `input order cannot change deterministic schedules or summaries`() {
        val loans = listOf(loan("B", "200"), loan("A", "100"))
        val forward = DebtPayoffCalculator.compare(request(loans, budget = "200"))
        val reverse = DebtPayoffCalculator.compare(request(loans.reversed(), budget = "200"))
        assertThat(forward).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
        assertThat(reverse).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
        assertThat(forward).isEqualTo(reverse)
    }

    @Test
    fun `unknown lowercase and non-circulating currencies are never normalized`() {
        for (currency in listOf("XYZ", "sar", "XXX")) {
            val debt = DebtLoan(
                DebtId("A"), Money.of("1", currency), BigDecimal.ZERO,
                Money.of("1", currency), Money.of("0", currency),
                TermEvidence.SupportedNominalMonthly(LocalDate(2027, 1, 1), 1),
            )
            val input = DebtPayoffRequest(listOf(debt), Money.of("1", currency), currency, RoundingMode.HALF_EVEN, 1, true)
            val result = DebtPayoffCalculator.compare(input) as DebtPayoffOutcome.InvalidInput
            assertThat(result.errors.joinToString()).contains("currency")
        }
    }

    @Test
    fun `full supported horizon is executed rather than silently truncated`() {
        val debt = loan(principal = "1").copy(minimumPayment = Money.of("0", "SAR"))
        val result = DebtPayoffCalculator.compare(request(listOf(debt), budget = "0", horizon = 1200))
            as DebtPayoffOutcome.HorizonExhausted
        assertThat(result.avalanche.ledger).hasSize(1200)
        assertThat(result.snowball.ledger).hasSize(1200)
    }
}
