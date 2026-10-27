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
import org.junit.jupiter.api.Assertions.assertThrows
import java.math.BigDecimal
import java.math.RoundingMode

class DebtPayoffGuardTest {

    private fun loan(
        id: String,
        principal: String = "100",
        apr: String = "0",
        minimum: String = "100",
        fee: String = "0",
        currency: String = "SAR",
        firstDue: LocalDate = LocalDate(2027, 1, 1),
        anchor: Int = 1,
        evidence: TermEvidence = TermEvidence.SupportedNominalMonthly(firstDue, anchor),
    ) = DebtLoan(
        id = DebtId(id),
        principal = Money.of(BigDecimal(principal), currency),
        nominalAnnualApr = BigDecimal(apr),
        minimumPayment = Money.of(BigDecimal(minimum), currency),
        fixedMonthlyFee = Money.of(BigDecimal(fee), currency),
        evidence = evidence,
    )

    private fun request(
        loans: List<DebtLoan>,
        budget: String = "100",
        horizon: Int = 60,
        currency: String = "SAR",
        attests: Boolean = true,
    ) = DebtPayoffRequest(
        loans = loans,
        monthlyBudget = Money.of(BigDecimal(budget), currency),
        currency = currency,
        interestRounding = RoundingMode.HALF_EVEN,
        horizonCycles = horizon,
        attestsCompleteCycleStart = attests,
    )

    @Test
    fun `budget below sum of capped minimums is a distinct outcome`() {
        val loans = listOf(
            loan("A", principal = "10000", minimum = "500"),
            loan("B", principal = "10000", minimum = "500"),
        )
        val outcome = DebtPayoffCalculator.compare(request(loans, budget = "600"))
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.BudgetBelowMinimums::class.java)
        val below = outcome as DebtPayoffOutcome.BudgetBelowMinimums
        assertThat(below.requiredMinimumsTotal.amount).isEquivalentAccordingToCompareTo(BigDecimal("1000"))
        assertThat(below.byDebt).hasSize(2)
    }

    @Test
    fun `missing terms produce typed rejection with no payoff data`() {
        val loans = listOf(
            loan("OK", principal = "100", minimum = "100"),
            loan("UNK", principal = "100", minimum = "100", evidence = TermEvidence.Missing),
        )
        val outcome = DebtPayoffCalculator.compare(request(loans, budget = "200"))
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.UnsupportedTerms::class.java)
        val unsupported = outcome as DebtPayoffOutcome.UnsupportedTerms
        assertThat(unsupported.rejections.map { it.debtId.value }).containsExactly("UNK")
        assertThat(unsupported.rejections.single().reason)
            .isEqualTo(UnsupportedTermReason.MISSING_TERMS)
    }

    @Test
    fun `unsupported term reasons are surfaced per debt`() {
        val loans = listOf(
            loan("D", evidence = TermEvidence.Unsupported(UnsupportedTermReason.NON_NOMINAL_OR_DAILY_ACCRUAL)),
            loan("P", evidence = TermEvidence.Unsupported(UnsupportedTermReason.PREPAYMENT_RESTRICTED)),
            loan("F", evidence = TermEvidence.Unsupported(UnsupportedTermReason.FLAT_PROFIT_TERMS)),
            loan("U", evidence = TermEvidence.Unsupported(UnsupportedTermReason.UNKNOWN_OR_VARIABLE_FEES)),
        )
        val outcome = DebtPayoffCalculator.compare(request(loans, budget = "400"))
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.UnsupportedTerms::class.java)
        val reasons = (outcome as DebtPayoffOutcome.UnsupportedTerms).rejections
        assertThat(reasons.map { it.reason }).containsExactly(
            UnsupportedTermReason.NON_NOMINAL_OR_DAILY_ACCRUAL,
            UnsupportedTermReason.PREPAYMENT_RESTRICTED,
            UnsupportedTermReason.FLAT_PROFIT_TERMS,
            UnsupportedTermReason.UNKNOWN_OR_VARIABLE_FEES,
        ).inOrder()
    }

    @Test
    fun `without complete-cycle attestation timing is rejected`() {
        val loans = listOf(loan("A"), loan("B"))
        val outcome = DebtPayoffCalculator.compare(request(loans, budget = "200", attests = false))
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.UnsupportedTerms::class.java)
        val rejections = (outcome as DebtPayoffOutcome.UnsupportedTerms).rejections
        assertThat(rejections).hasSize(2)
        assertThat(rejections.all { it.reason == UnsupportedTermReason.TIMING_NOT_COMPLETE_MONTHLY_CYCLE })
            .isTrue()
    }

    @Test
    fun `bounds reject rather than truncate`() {
        val empty = DebtPayoffCalculator.compare(request(emptyList(), budget = "100"))
        assertThat((empty as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("at least one debt")
        val zero = DebtPayoffCalculator.compare(request(listOf(loan("A")), budget = "100", horizon = 0))
        assertThat((zero as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("horizonCycles")
        val tooLong = DebtPayoffCalculator.compare(
            request(listOf(loan("A")), budget = "100", horizon = 1201),
        )
        assertThat((tooLong as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("horizonCycles")
        val tooMany = DebtPayoffCalculator.compare(
            request(List(101) { loan("L$it") }, budget = "10100"),
        )
        assertThat((tooMany as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("at most 100")
    }

    @Test
    fun `duplicate ids and inconsistent anchors are invalid input`() {
        val dupes = DebtPayoffCalculator.compare(
            request(listOf(loan("A"), loan("A")), budget = "200"),
        )
        assertThat((dupes as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("unique")
        val anchors = DebtPayoffCalculator.compare(
            request(
                listOf(
                    loan("A", firstDue = LocalDate(2027, 1, 1)),
                    loan("B", firstDue = LocalDate(2027, 2, 1)),
                ),
                budget = "200",
            ),
        )
        assertThat(anchors).isInstanceOf(DebtPayoffOutcome.InvalidInput::class.java)
        assertThat((anchors as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("common first due date")
    }

    @Test
    fun `scale is validated against iso fraction digits`() {
        val sar = DebtPayoffCalculator.compare(
            request(listOf(loan("A", principal = "10.123", minimum = "10.123")), budget = "10.123"),
        )
        assertThat((sar as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("fraction digits")
        val kwd = DebtPayoffCalculator.compare(
            request(
                listOf(loan("A", principal = "10.123", minimum = "10.123", currency = "KWD")),
                budget = "10.123",
                currency = "KWD",
            ),
        )
        assertThat(kwd).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
    }

    @Test
    fun `mixed currencies are rejected without summing`() {
        val loans = listOf(
            loan("S", principal = "100", minimum = "100"),
            loan("U", principal = "100", minimum = "100", currency = "USD"),
        )
        val outcome = DebtPayoffCalculator.compare(request(loans, budget = "200"))
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.InvalidInput::class.java)
        assertThat((outcome as DebtPayoffOutcome.InvalidInput).errors.joinToString())
            .contains("currency")
    }

    @Test
    fun `model constructors reject impossible terms`() {
        assertThrows(IllegalArgumentException::class.java) {
            loan("neg", apr = "-0.01")
        }
        assertThrows(IllegalArgumentException::class.java) { DebtId(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            loan("negp", principal = "-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            loan("negf", fee = "-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DebtLoan(
                id = DebtId("mixed"),
                principal = Money.of(BigDecimal("100"), "SAR"),
                nominalAnnualApr = BigDecimal.ZERO,
                minimumPayment = Money.of(BigDecimal("100"), "USD"),
                fixedMonthlyFee = Money.of(BigDecimal.ZERO, "SAR"),
                evidence = TermEvidence.SupportedNominalMonthly(LocalDate(2027, 1, 1), 1),
            )
        }
    }

    @Test
    fun `non-amortizing minimum leaves horizon exhausted honestly`() {
        val loans = listOf(loan("L", principal = "1000", apr = "0.12", minimum = "12", fee = "5"))
        val outcome = DebtPayoffCalculator.compare(
            request(loans, budget = "12", horizon = 6),
        )
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.HorizonExhausted::class.java)
        val exhausted = outcome as DebtPayoffOutcome.HorizonExhausted
        assertThat(exhausted.horizonCycles).isEqualTo(6)
        for (run in listOf(exhausted.avalanche, exhausted.snowball)) {
            assertThat(run.allSettled).isFalse()
            assertThat(run.ledger).hasSize(6)
            val summary = run.summaries.single()
            assertThat(summary.settledWithinHorizon).isFalse()
            assertThat(summary.payoffDate).isNull()
            val last = run.ledger[5].entries.single()
            // Independent Decimal oracle includes unpaid-interest capitalization.
            assertThat(last.closingBalance.amount).isEquivalentAccordingToCompareTo(BigDecimal("1018.45"))
            assertThat(last.principalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("-3.15"))
            assertThat(run.ledger[0].entries.single().interest.amount)
                .isEquivalentAccordingToCompareTo(BigDecimal("10"))
        }
    }
}
