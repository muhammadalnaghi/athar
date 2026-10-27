package com.athar.feature.plan.scenarios

import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class DebtScenarioDraftTest {
    @Test fun `one debt with explicit zero costs clears in two real monthly cycles`() {
        val result = evaluateDebtDraft(validDebtDraft())
        assertThat(result.errors).isEmpty()
        val outcome = result.outcome as DebtPayoffOutcome.Compared
        for (run in listOf(outcome.avalanche, outcome.snowball)) {
            assertThat(run.ledger).hasSize(2)
            assertThat(run.totalPaid.amount.compareTo(BigDecimal("100"))).isEqualTo(0)
            assertThat(run.totalPaid.currency).isEqualTo("SAR")
            assertThat(run.finalPayoffDate).isEqualTo(LocalDate(2026, 12, 21))
        }
    }
    @Test fun `KWD principal fees and payment retain every fils`() {
        val base = validDebtDraft()
        val draft = base.copy(currency = "KWD", budget = "1.006", loans = listOf(base.loans.single().copy(
            principal = "1.005", minimum = "1.006", fee = "0.001")))
        val outcome = evaluateDebtDraft(draft).outcome as DebtPayoffOutcome.Compared
        assertThat(outcome.avalanche.totalPaid.amount.toPlainString()).isEqualTo("1.006")
        assertThat(outcome.avalanche.totalFees.amount.toPlainString()).isEqualTo("0.001")
        assertThat(outcome.avalanche.totalPaid.currency).isEqualTo("KWD")
    }
    @Test fun `annual percentage becomes a nominal fraction exactly once`() {
        val base = validDebtDraft()
        val draft = base.copy(currency = "KWD", budget = "1.106", loans = listOf(base.loans.single().copy(
            principal = "1.100", minimum = "1.106", annualPercent = "6")))
        val outcome = evaluateDebtDraft(draft).outcome as DebtPayoffOutcome.Compared
        assertThat(outcome.avalanche.totalInterest.amount.toPlainString()).isEqualTo("0.006")
        assertThat(outcome.avalanche.ledger).hasSize(1)
    }
    @Test fun `JPY subminor principal is rejected by the unchanged debt authority`() {
        val base = validDebtDraft()
        val result = evaluateDebtDraft(base.copy(currency = "JPY", loans = listOf(base.loans.single().copy(principal = "100.1"))))
        assertThat(result.outcome).isInstanceOf(DebtPayoffOutcome.InvalidInput::class.java)
    }
    @Test fun `unchecked terms never invent a payoff date`() {
        val outcome = evaluateDebtDraft(validDebtDraft().copy(supportedTermsAcknowledged = false)).outcome
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.UnsupportedTerms::class.java)
    }
    @Test fun `blank required amounts are errors not zeros`() {
        val base = validDebtDraft()
        val result = evaluateDebtDraft(base.copy(budget = "", loans = listOf(base.loans.single().copy(fee = ""))))
        assertThat(result.outcome).isNull()
        assertThat(result.errors).containsAtLeast("budget", "loan-1-fee")
    }
    @Test fun `duplicate names invalid dates and fractional horizons are blocked`() {
        val base = validDebtDraft()
        val result = evaluateDebtDraft(base.copy(firstDueDate = "2026-02-30", horizon = "1.5",
            loans = base.loans + base.loans.single().copy(id = 2)))
        assertThat(result.outcome).isNull()
        assertThat(result.errors).containsAtLeast("firstDueDate", "horizon", "loans")
    }
    @Test fun `Arabic money reaches the actual calculator without coercion`() {
        val base = validDebtDraft()
        val result = evaluateDebtDraft(base.copy(budget = "٥٠", loans = listOf(base.loans.single().copy(principal = "١٠٠"))))
        assertThat(result.outcome).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
    }
    @Test fun `budget below minimums remains a distinct calculator result`() {
        assertThat(evaluateDebtDraft(validDebtDraft().copy(budget = "40")).outcome)
            .isInstanceOf(DebtPayoffOutcome.BudgetBelowMinimums::class.java)
    }
    @Test fun `exponent money oversized loan population and unknown currency fail closed`() {
        val base = validDebtDraft()
        for (draft in listOf(base.copy(budget = "1e100"), base.copy(currency = "XYZ"),
            base.copy(loans = (1..13).map { base.loans.single().copy(id = it, name = "Debt$it") }))) {
            assertThat(evaluateDebtDraft(draft).outcome).isNull()
        }
    }
}
