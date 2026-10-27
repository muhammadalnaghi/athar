package com.athar.core.domain.calc.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.CycleLedger
import com.athar.core.domain.model.debt.DebtCycleEntry
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.DebtRunSummary
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.StrategyRun
import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import java.time.YearMonth

/** Validated-input simulation. All working mutation is local to one strategy run. */
internal object DebtStrategySimulation {
    fun run(
        request: DebtPayoffRequest,
        strategy: DebtStrategy,
        calendar: Pair<LocalDate, Int>?,
    ): StrategyRun {
        val balances = request.loans.sortedBy { it.id.value }.map { Balance(it) }
        val ledger = mutableListOf<CycleLedger>()
        val scale = requireNotNull(DebtMath.fractionDigits(request.currency))
        for (cycle in 1..request.horizonCycles) {
            val active = balances.filter { it.amount.signum() > 0 }
            if (active.isEmpty()) break
            val date = dueDate(requireNotNull(calendar), cycle)
            val charges = active.map { balance ->
                val interest = DebtMath.monthlyInterest(
                    balance.amount, requireNotNull(balance.loan.nominalAnnualApr), scale, request.interestRounding,
                )
                Charge(balance, interest, requireNotNull(balance.loan.fixedMonthlyFee).amount)
            }
            var unused = request.monthlyBudget.amount - charges.fold(BigDecimal.ZERO) { sum, charge -> sum + charge.payment }
            check(unused.signum() >= 0) { "Validated fixed minimums must fit the shared budget" }
            val priority = when (strategy) {
                DebtStrategy.AVALANCHE -> compareByDescending<Charge> { requireNotNull(it.balance.loan.nominalAnnualApr) }
                DebtStrategy.SNOWBALL -> compareBy<Charge> { it.opening }
            }.thenBy { it.balance.loan.id.value }
            for (charge in charges.sortedWith(priority)) {
                val extra = unused.min(charge.owed - charge.payment)
                charge.payment += extra
                unused -= extra
            }
            val entries = charges.map { charge ->
                val closing = charge.owed - charge.payment
                val balance = charge.balance
                balance.amount = closing
                balance.interest += charge.interest
                balance.fees += charge.fee
                balance.paid += charge.payment
                if (closing.signum() == 0) {
                    balance.payoffCycle = cycle
                    balance.payoffDate = date
                }
                DebtCycleEntry(
                    debtId = balance.loan.id,
                    openingBalance = Money.of(charge.opening, request.currency),
                    interest = Money.of(charge.interest, request.currency),
                    fee = Money.of(charge.fee, request.currency),
                    payment = Money.of(charge.payment, request.currency),
                    principalPaid = Money.of(charge.payment - charge.interest - charge.fee, request.currency),
                    closingBalance = Money.of(closing, request.currency),
                )
            }
            ledger += CycleLedger(cycle, date, entries, Money.of(unused, request.currency))
        }
        val summaries = balances.map { balance ->
            DebtRunSummary(
                debtId = balance.loan.id,
                settledWithinHorizon = balance.amount.signum() == 0,
                payoffDate = balance.payoffDate,
                cyclesToPayoff = balance.payoffCycle,
                totalPrincipalPaid = Money.of(balance.loan.principal.amount - balance.amount, request.currency),
                totalInterest = Money.of(balance.interest, request.currency),
                totalFees = Money.of(balance.fees, request.currency),
                totalPaid = Money.of(balance.paid, request.currency),
            )
        }
        val allSettled = balances.all { it.amount.signum() == 0 }
        return StrategyRun(
            strategy = strategy,
            ledger = ledger,
            summaries = summaries,
            totalInterest = Money.of(balances.fold(BigDecimal.ZERO) { sum, balance -> sum + balance.interest }, request.currency),
            totalFees = Money.of(balances.fold(BigDecimal.ZERO) { sum, balance -> sum + balance.fees }, request.currency),
            totalPaid = Money.of(balances.fold(BigDecimal.ZERO) { sum, balance -> sum + balance.paid }, request.currency),
            allSettled = allSettled,
            finalPayoffDate = if (allSettled) balances.mapNotNull { it.payoffDate }.maxOrNull() else null,
        )
    }

    private fun dueDate(calendar: Pair<LocalDate, Int>, cycle: Int): LocalDate {
        val (first, anchorDay) = calendar
        val month = YearMonth.of(first.year, first.monthNumber).plusMonths((cycle - 1).toLong())
        return LocalDate(month.year, month.monthValue, minOf(anchorDay, month.lengthOfMonth()))
    }

    private class Balance(val loan: DebtLoan) {
        var amount: BigDecimal = loan.principal.amount
        var interest: BigDecimal = BigDecimal.ZERO
        var fees: BigDecimal = BigDecimal.ZERO
        var paid: BigDecimal = BigDecimal.ZERO
        var payoffCycle: Int? = if (amount.signum() == 0) 0 else null
        var payoffDate: LocalDate? = null
    }

    private class Charge(val balance: Balance, val interest: BigDecimal, val fee: BigDecimal) {
        val opening: BigDecimal = balance.amount
        val owed: BigDecimal = opening + interest + fee
        var payment: BigDecimal = requireNotNull(balance.loan.minimumPayment).amount.min(owed)
    }
}
