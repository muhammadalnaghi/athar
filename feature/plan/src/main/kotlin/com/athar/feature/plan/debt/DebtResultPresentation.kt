package com.athar.feature.plan.debt

import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.StrategyRun
import kotlinx.datetime.LocalDate

/** Rank complete, same-currency results only; preserve exact total amounts. */
internal fun debtCheaperStrategy(avalanche: StrategyRun, snowball: StrategyRun): DebtStrategy? {
    if (!avalanche.allSettled || !snowball.allSettled) return null
    if (avalanche.totalPaid.currency != snowball.totalPaid.currency) return null
    return when (avalanche.totalPaid.amount.compareTo(snowball.totalPaid.amount)) {
        -1 -> DebtStrategy.AVALANCHE
        1 -> DebtStrategy.SNOWBALL
        else -> null
    }
}

/** A real date belongs to a settled strategy, never an extrapolation of partial totals. */
internal fun debtPayoffDate(run: StrategyRun): LocalDate? = run.finalPayoffDate.takeIf { run.allSettled }
