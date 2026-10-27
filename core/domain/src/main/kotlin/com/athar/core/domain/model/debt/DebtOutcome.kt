package com.athar.core.domain.model.debt

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import com.athar.core.common.money.Money

/** One debt's dated activity inside one monthly cycle. */
data class DebtCycleEntry(
    val debtId: DebtId,
    val openingBalance: Money,
    val interest: Money,
    val fee: Money,
    val payment: Money,
    /** Signed net reduction: payment minus interest/fee, negative when charges capitalize. */
    val principalPaid: Money,
    val closingBalance: Money,
)

/** A whole dated monthly cycle across all outstanding debts. */
data class CycleLedger(
    val cycle: Int,
    val dueDate: LocalDate,
    val entries: PersistentList<DebtCycleEntry>,
    val unusedBudget: Money,
) {
    constructor(
        cycle: Int,
        dueDate: LocalDate,
        entries: List<DebtCycleEntry>,
        unusedBudget: Money,
    ) : this(cycle, dueDate, entries.toPersistentList(), unusedBudget)
}

/** Per-debt outcome of one strategy run. */
data class DebtRunSummary(
    val debtId: DebtId,
    val settledWithinHorizon: Boolean,
    val payoffDate: LocalDate?,
    val cyclesToPayoff: Int?,
    val totalPrincipalPaid: Money,
    val totalInterest: Money,
    val totalFees: Money,
    val totalPaid: Money,
)

/** Complete, immutable result of one strategy over the request horizon. */
data class StrategyRun(
    val strategy: DebtStrategy,
    val ledger: PersistentList<CycleLedger>,
    val summaries: PersistentList<DebtRunSummary>,
    val totalInterest: Money,
    val totalFees: Money,
    val totalPaid: Money,
    val allSettled: Boolean,
    val finalPayoffDate: LocalDate?,
) {
    constructor(
        strategy: DebtStrategy,
        ledger: List<CycleLedger>,
        summaries: List<DebtRunSummary>,
        totalInterest: Money,
        totalFees: Money,
        totalPaid: Money,
        allSettled: Boolean,
        finalPayoffDate: LocalDate?,
    ) : this(
        strategy,
        ledger.toPersistentList(),
        summaries.toPersistentList(),
        totalInterest,
        totalFees,
        totalPaid,
        allSettled,
        finalPayoffDate,
    )
}

/** Each debt's minimum obligation as counted for the below-budget verdict. */
data class DebtMinimumShare(
    val debtId: DebtId,
    val cappedMinimum: Money,
)

/** A typed term rejection for one debt. */
data class DebtTermRejection(
    val debtId: DebtId,
    val reason: UnsupportedTermReason,
)

/**
 * Distinct, honest outcomes. No case ever invents a payoff date: dates exist
 * only for debts actually settled within the caller's horizon. One strategy
 * may settle while the other yields an overall [HorizonExhausted] comparison.
 */
sealed interface DebtPayoffOutcome {
    data class Compared(val avalanche: StrategyRun, val snowball: StrategyRun) : DebtPayoffOutcome

    /**
     * Horizon ran out before both strategies cleared every debt. This is a
     * statement about the horizon only — not proof a debt is unpayable.
     */
    data class HorizonExhausted(
        val horizonCycles: Int,
        val avalanche: StrategyRun,
        val snowball: StrategyRun,
    ) : DebtPayoffOutcome

    data class BudgetBelowMinimums(
        val budget: Money,
        val requiredMinimumsTotal: Money,
        val byDebt: PersistentList<DebtMinimumShare>,
    ) : DebtPayoffOutcome {
        constructor(
            budget: Money,
            requiredMinimumsTotal: Money,
            byDebt: List<DebtMinimumShare>,
        ) : this(budget, requiredMinimumsTotal, byDebt.toPersistentList())
    }

    data class UnsupportedTerms(val rejections: PersistentList<DebtTermRejection>) : DebtPayoffOutcome {
        constructor(rejections: List<DebtTermRejection>) : this(rejections.toPersistentList())
    }

    data class InvalidInput(val errors: PersistentList<String>) : DebtPayoffOutcome {
        constructor(errors: List<String>) : this(errors.toPersistentList())
    }
}
