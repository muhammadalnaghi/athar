package com.athar.core.domain.calc.debt

import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.DebtStrategy
import java.time.DateTimeException

object DebtPayoffCalculator {
    fun compare(request: DebtPayoffRequest): DebtPayoffOutcome {
        DebtRequestValidator.validate(request)?.let { return it }
        val rejections = DebtRequestValidator.termRejections(request)
        if (rejections.isNotEmpty()) return DebtPayoffOutcome.UnsupportedTerms(rejections)
        val calendar = DebtRequestValidator.commonAnchors(request)
        if (request.loans.any { it.principal.isPositive() } && calendar == null) {
            return DebtPayoffOutcome.InvalidInput(listOf("Outstanding debts need a common first due date and explicit anchor"))
        }
        return try {
            DebtRequestValidator.budgetBelowMinimums(request)?.let { return it }
            val avalanche = DebtStrategySimulation.run(request, DebtStrategy.AVALANCHE, calendar)
            val snowball = DebtStrategySimulation.run(request, DebtStrategy.SNOWBALL, calendar)
            if (avalanche.allSettled && snowball.allSettled) {
                DebtPayoffOutcome.Compared(avalanche, snowball)
            } else {
                DebtPayoffOutcome.HorizonExhausted(request.horizonCycles, avalanche, snowball)
            }
        } catch (_: ArithmeticException) {
            DebtPayoffOutcome.InvalidInput(listOf("Contractual interest is not representable under the requested rounding policy"))
        } catch (_: DateTimeException) {
            DebtPayoffOutcome.InvalidInput(listOf("Projected due date is outside the supported calendar range"))
        }
    }
}
