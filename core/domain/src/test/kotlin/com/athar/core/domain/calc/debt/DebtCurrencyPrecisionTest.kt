package com.athar.core.domain.calc.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.DebtId
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.StrategyRun
import com.athar.core.domain.model.debt.TermEvidence
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/** Independent Decimal oracles; contractual interest, not principal/payment, is quantized. */
class DebtCurrencyPrecisionTest {
    private val due = LocalDate(2027, 1, 31)

    private fun loan(
        id: String,
        currency: String,
        principal: String,
        minimum: String = "0",
        fee: String = "0",
        apr: String = "0",
    ) = DebtLoan(
        DebtId(id), Money.of(principal, currency), BigDecimal(apr),
        Money.of(minimum, currency), Money.of(fee, currency),
        TermEvidence.SupportedNominalMonthly(due, 31),
    )

    private fun request(
        loans: List<DebtLoan>,
        currency: String,
        budget: String,
        rounding: RoundingMode = RoundingMode.HALF_EVEN,
    ) = DebtPayoffRequest(loans, Money.of(budget, currency), currency, rounding, 1, true)

    private fun runs(input: DebtPayoffRequest): List<StrategyRun> {
        val result = DebtPayoffCalculator.compare(input)
        assertThat(result).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
        result as DebtPayoffOutcome.Compared
        return listOf(result.avalanche, result.snowball)
    }

    private fun amount(money: Money, value: String, currency: String) {
        assertThat(money.currency).isEqualTo(currency)
        assertThat(money.amount).isEquivalentAccordingToCompareTo(BigDecimal(value))
    }

    @Test
    fun `KWD fees and final payment surplus conserve every fils across both strategies`() {
        val input = request(
            listOf(
                loan("A", "KWD", "1.005", "0.500", "0.001"),
                loan("B", "KWD", "2.007", "0.500", "0.002"),
            ), "KWD", "3.500",
        )
        // Decimal: 1.005 + .001 + 2.007 + .002 = 3.015; 3.500 - 3.015 = .485.
        for (run in runs(input)) {
            val cycle = run.ledger.single()
            assertThat(cycle.dueDate).isEqualTo(due)
            assertThat(run.allSettled).isTrue()
            assertThat(run.finalPayoffDate).isEqualTo(due)
            amount(run.totalPaid, "3.015", "KWD")
            amount(run.totalFees, "0.003", "KWD")
            amount(cycle.unusedBudget, "0.485", "KWD")
            val entries = cycle.entries.associateBy { it.debtId.value }
            amount(entries.getValue("A").payment, "1.006", "KWD")
            amount(entries.getValue("B").payment, "2.009", "KWD")
            amount(entries.getValue("A").principalPaid, "1.005", "KWD")
            amount(entries.getValue("B").principalPaid, "2.007", "KWD")
            for (entry in cycle.entries) amount(entry.closingBalance, "0", "KWD")
            assertThat(run.totalPaid.amount + cycle.unusedBudget.amount)
                .isEquivalentAccordingToCompareTo(input.monthlyBudget.amount)
        }
    }

    @Test
    fun `KWD half fils interest respects caller policy without rounding principal`() {
        val loans = listOf(loan("A", "KWD", "1.100", apr = "0.06"))
        // Decimal exact interest .00550: .006 HALF_EVEN, .005 HALF_DOWN.
        for ((rounding, expectedInterest, expectedPaid) in listOf(
            Triple(RoundingMode.HALF_EVEN, "0.006", "1.106"),
            Triple(RoundingMode.HALF_DOWN, "0.005", "1.105"),
        )) {
            for (run in runs(request(loans, "KWD", "2.000", rounding))) {
                amount(run.totalInterest, expectedInterest, "KWD")
                amount(run.totalPaid, expectedPaid, "KWD")
                amount(run.summaries.single().totalPrincipalPaid, "1.100", "KWD")
                amount(run.ledger.single().entries.single().closingBalance, "0", "KWD")
            }
        }
    }

    @Test
    fun `JPY half yen interest stays whole and preserves explicit rounding choice`() {
        val loans = listOf(loan("A", "JPY", "100", fee = "1", apr = "0.06"))
        // Decimal exact interest .50: 0 HALF_EVEN, 1 HALF_UP at whole-yen quantum.
        for ((rounding, interest, paid) in listOf(
            Triple(RoundingMode.HALF_EVEN, "0", "101"),
            Triple(RoundingMode.HALF_UP, "1", "102"),
        )) {
            for (run in runs(request(loans, "JPY", "200", rounding))) {
                amount(run.totalInterest, interest, "JPY")
                amount(run.totalFees, "1", "JPY")
                amount(run.totalPaid, paid, "JPY")
                amount(run.summaries.single().totalPrincipalPaid, "100", "JPY")
                assertThat(run.totalPaid.amount.stripTrailingZeros().scale()).isAtMost(0)
                assertThat(run.totalPaid.amount + run.ledger.single().unusedBudget.amount)
                    .isEquivalentAccordingToCompareTo(BigDecimal("200"))
            }
        }
    }

    @Test
    fun `sub fils inputs are rejected independently while cosmetic zeros are accepted`() {
        val valid = loan("A", "KWD", "1.0000", "0.5000", "0.0000")
        val base = request(listOf(valid), "KWD", "2.0000")
        for (run in runs(base)) amount(run.totalPaid, "1.0000", "KWD")
        val invalids = listOf(
            request(listOf(valid.copy(principal = Money.of("1.0001", "KWD"))), "KWD", "2.0000"),
            request(listOf(valid.copy(minimumPayment = Money.of("0.0001", "KWD"))), "KWD", "2.0000"),
            request(listOf(valid.copy(fixedMonthlyFee = Money.of("0.0001", "KWD"))), "KWD", "2.0000"),
            request(listOf(valid), "KWD", "2.0001"),
        )
        for (input in invalids) {
            val result = DebtPayoffCalculator.compare(input)
            assertThat(result).isInstanceOf(DebtPayoffOutcome.InvalidInput::class.java)
            assertThat((result as DebtPayoffOutcome.InvalidInput).errors).hasSize(1)
            assertThat(result.errors.single()).contains("KWD allows 3 fraction digits")
        }
    }
}
