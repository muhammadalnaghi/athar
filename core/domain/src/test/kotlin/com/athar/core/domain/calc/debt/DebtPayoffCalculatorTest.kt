package com.athar.core.domain.calc.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.DebtId
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.TermEvidence
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class DebtPayoffCalculatorTest {

    private fun loan(
        id: String,
        principal: String,
        apr: String,
        minimum: String,
        fee: String = "0",
        currency: String = "SAR",
        firstDue: LocalDate = LocalDate(2027, 1, 1),
        anchor: Int = 1,
    ) = DebtLoan(
        id = DebtId(id),
        principal = Money.of(BigDecimal(principal), currency),
        nominalAnnualApr = BigDecimal(apr),
        minimumPayment = Money.of(BigDecimal(minimum), currency),
        fixedMonthlyFee = Money.of(BigDecimal(fee), currency),
        evidence = TermEvidence.SupportedNominalMonthly(firstDue, anchor),
    )

    private fun request(
        loans: List<DebtLoan>,
        budget: String,
        horizon: Int = 60,
        currency: String = "SAR",
    ) = DebtPayoffRequest(
        loans = loans,
        monthlyBudget = Money.of(BigDecimal(budget), currency),
        currency = currency,
        interestRounding = RoundingMode.HALF_EVEN,
        horizonCycles = horizon,
        attestsCompleteCycleStart = true,
    )

    private fun compared(outcome: DebtPayoffOutcome): DebtPayoffOutcome.Compared {
        assertThat(outcome).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
        return outcome as DebtPayoffOutcome.Compared
    }

    @Test
    fun `zero apr pays exactly by fixed minimums with a dated payoff`() {
        val outcome = compared(
            DebtPayoffCalculator.compare(
                request(listOf(loan("L", "1200", "0", "100")), budget = "100"),
            ),
        )
        val run = outcome.avalanche
        assertThat(run.allSettled).isTrue()
        assertThat(run.finalPayoffDate).isEqualTo(LocalDate(2027, 12, 1))
        assertThat(run.ledger).hasSize(12)
        val summary = run.summaries.single()
        assertThat(summary.settledWithinHorizon).isTrue()
        assertThat(summary.cyclesToPayoff).isEqualTo(12)
        assertThat(summary.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("1200"))
        assertThat(summary.totalInterest.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
        assertThat(summary.totalFees.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
    }

    @Test
    fun `known monthly fee accrues and conserves exactly`() {
        val outcome = compared(
            DebtPayoffCalculator.compare(
                request(listOf(loan("L", "100", "0", "35", fee = "5")), budget = "35", horizon = 12),
            ),
        )
        val run = outcome.avalanche
        val first = run.ledger[0].entries.single()
        assertThat(first.openingBalance.amount).isEquivalentAccordingToCompareTo(BigDecimal("100"))
        assertThat(first.interest.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
        assertThat(first.fee.amount).isEquivalentAccordingToCompareTo(BigDecimal("5"))
        assertThat(first.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("35"))
        assertThat(first.principalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("30"))
        assertThat(first.closingBalance.amount).isEquivalentAccordingToCompareTo(BigDecimal("70"))
        assertThat(run.allSettled).isTrue()
        assertThat(run.finalPayoffDate).isEqualTo(LocalDate(2027, 4, 1))
        assertThat(run.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("120"))
        assertThat(run.totalFees.amount).isEquivalentAccordingToCompareTo(BigDecimal("20"))
        assertThat(run.totalInterest.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
    }

    @Test
    fun `avalanche directs surplus to highest apr and beats snowball on total interest`() {
        val loans = listOf(loan("HI", "500", "0.24", "50"), loan("LO", "300", "0.12", "50"))
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "150")))
        val avHi = outcome.avalanche.ledger[0].entries.first { it.debtId.value == "HI" }
        val avLo = outcome.avalanche.ledger[0].entries.first { it.debtId.value == "LO" }
        assertThat(avHi.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("100"))
        assertThat(avLo.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("50"))
        val sbLo = outcome.snowball.ledger[0].entries.first { it.debtId.value == "LO" }
        val sbHi = outcome.snowball.ledger[0].entries.first { it.debtId.value == "HI" }
        assertThat(sbLo.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("100"))
        assertThat(sbHi.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("50"))
        assertThat(outcome.avalanche.totalInterest.amount)
            .isLessThan(outcome.snowball.totalInterest.amount)
    }

    @Test
    fun `ties break by stable id in both strategies`() {
        val loans = listOf(loan("B1", "500", "0.12", "50"), loan("A2", "500", "0.12", "50"))
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "110")))
        for (run in listOf(outcome.avalanche, outcome.snowball)) {
            val surplusEntry = run.ledger[0].entries.first { it.payment.amount.compareTo(BigDecimal("60")) == 0 }
            assertThat(surplusEntry.debtId).isEqualTo(DebtId("A2"))
        }
    }

    @Test
    fun `every dated cycle conserves money exactly`() {
        val loans = listOf(loan("HI", "500", "0.24", "50"), loan("LO", "300", "0.12", "50"))
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "150")))
        for (run in listOf(outcome.avalanche, outcome.snowball)) {
            var pay = BigDecimal.ZERO
            var interest = BigDecimal.ZERO
            var fee = BigDecimal.ZERO
            var principal = BigDecimal.ZERO
            for (ledger in run.ledger) {
                for (entry in ledger.entries) {
                    pay += entry.payment.amount
                    interest += entry.interest.amount
                    fee += entry.fee.amount
                    principal += entry.principalPaid.amount
                }
            }
            assertThat(pay).isEquivalentAccordingToCompareTo(run.totalPaid.amount)
            assertThat(interest).isEquivalentAccordingToCompareTo(run.totalInterest.amount)
            assertThat(fee).isEquivalentAccordingToCompareTo(run.totalFees.amount)
            assertThat(principal + interest + fee).isEquivalentAccordingToCompareTo(pay)
            for (summary in run.summaries) {
                val entries = run.ledger.flatMap { it.entries }.filter { it.debtId == summary.debtId }
                var debtPay = BigDecimal.ZERO
                var debtPrincipal = BigDecimal.ZERO
                entries.forEach {
                    debtPay += it.payment.amount
                    debtPrincipal += it.principalPaid.amount
                }
                assertThat(debtPay).isEquivalentAccordingToCompareTo(summary.totalPaid.amount)
                assertThat(debtPrincipal)
                    .isEquivalentAccordingToCompareTo(summary.totalPrincipalPaid.amount)
                for (i in 0 until entries.size - 1) {
                    assertThat(entries[i].closingBalance.amount)
                        .isEquivalentAccordingToCompareTo(entries[i + 1].openingBalance.amount)
                }
                if (summary.settledWithinHorizon) {
                    assertThat(entries.last().closingBalance.amount)
                        .isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
                }
            }
        }
    }

    @Test
    fun `unused final-payment capacity rolls to next debt in the same cycle`() {
        val loans = listOf(loan("A", "100", "0", "100"), loan("B", "500", "0", "50"))
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "150", horizon = 12)))
        val run = outcome.avalanche
        val firstCycle = run.ledger[0]
        assertThat(firstCycle.entries.map { it.debtId.value }).containsExactly("A", "B").inOrder()
        val secondCycle = run.ledger[1]
        assertThat(secondCycle.entries.map { it.debtId.value }).containsExactly("B")
        val bPayment = secondCycle.entries.single().payment.amount
        assertThat(bPayment).isEquivalentAccordingToCompareTo(BigDecimal("150"))
        assertThat(secondCycle.unusedBudget.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
        assertThat(run.summaries.first { it.debtId.value == "A" }.payoffDate)
            .isEqualTo(LocalDate(2027, 1, 1))
        assertThat(run.finalPayoffDate).isEqualTo(LocalDate(2027, 4, 1))
    }

    @Test
    fun `minimum capped by balance frees budget within the same cycle`() {
        val loans = listOf(loan("A", "60", "0", "100"), loan("B", "500", "0", "50"))
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "150", horizon = 12)))
        val first = outcome.avalanche.ledger[0]
        val a = first.entries.first { it.debtId.value == "A" }
        val b = first.entries.first { it.debtId.value == "B" }
        assertThat(a.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("60"))
        assertThat(a.closingBalance.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
        assertThat(b.payment.amount).isEquivalentAccordingToCompareTo(BigDecimal("90"))
        assertThat(first.unusedBudget.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
    }

    @Test
    fun `anchor day 31 clamps in february and returns on later months`() {
        val loans = listOf(
            loan("L", "300", "0", "100", firstDue = LocalDate(2027, 1, 31), anchor = 31),
        )
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "100", horizon = 12)))
        assertThat(outcome.avalanche.ledger.map { it.dueDate })
            .containsExactly(LocalDate(2027, 1, 31), LocalDate(2027, 2, 28), LocalDate(2027, 3, 31))
            .inOrder()
    }

    @Test
    fun `three-decimal kwd interest quantizes at three fraction digits`() {
        val loans = listOf(
            loan("K", "1000.000", "0.12", "110.000", currency = "KWD"),
        )
        val outcome = compared(
            DebtPayoffCalculator.compare(request(loans, budget = "110.000", currency = "KWD")),
        )
        val entry = outcome.avalanche.ledger[0].entries.single()
        assertThat(entry.interest.currency).isEqualTo("KWD")
        assertThat(entry.interest.amount).isEquivalentAccordingToCompareTo(BigDecimal("10.000"))
        assertThat(entry.principalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("100.000"))
        assertThat(entry.closingBalance.amount).isEquivalentAccordingToCompareTo(BigDecimal("900.000"))
    }

    @Test
    fun `zero-fraction jpy pays off in whole yen`() {
        val loans = listOf(loan("J", "12000", "0", "4000", currency = "JPY"))
        val outcome = compared(
            DebtPayoffCalculator.compare(request(loans, budget = "4000", currency = "JPY")),
        )
        assertThat(outcome.avalanche.allSettled).isTrue()
        assertThat(outcome.avalanche.ledger).hasSize(3)
        assertThat(outcome.avalanche.totalPaid.currency).isEqualTo("JPY")
        assertThat(outcome.avalanche.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal("12000"))
    }

    @Test
    fun `known zero principal is settled without cycles`() {
        val loans = listOf(
            loan("Z", "0", "0.10", "100", fee = "5"),
            loan("P", "100", "0", "100"),
        )
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "100", horizon = 12)))
        val settled = outcome.avalanche.summaries.first { it.debtId.value == "Z" }
        assertThat(settled.settledWithinHorizon).isTrue()
        assertThat(settled.cyclesToPayoff).isEqualTo(0)
        assertThat(settled.payoffDate).isNull()
        assertThat(settled.totalPaid.amount).isEquivalentAccordingToCompareTo(BigDecimal.ZERO)
        val zEntries = outcome.avalanche.ledger.flatMap { it.entries }.filter { it.debtId.value == "Z" }
        assertThat(zEntries).isEmpty()
        assertThat(outcome.avalanche.allSettled).isTrue()
    }

    @Test
    fun `identical budget is respected in every cycle for both strategies`() {
        val loans = listOf(loan("HI", "500", "0.24", "50"), loan("LO", "300", "0.12", "50"))
        val outcome = compared(DebtPayoffCalculator.compare(request(loans, budget = "150")))
        for (run in listOf(outcome.avalanche, outcome.snowball)) {
            for (ledger in run.ledger) {
                var paid = BigDecimal.ZERO
                ledger.entries.forEach { paid += it.payment.amount }
                assertThat(paid).isAtMost(BigDecimal("150"))
                assertThat(paid + ledger.unusedBudget.amount).isEquivalentAccordingToCompareTo(BigDecimal("150"))
                assertThat(ledger.unusedBudget.amount.signum()).isAtLeast(0)
            }
        }
    }
}
