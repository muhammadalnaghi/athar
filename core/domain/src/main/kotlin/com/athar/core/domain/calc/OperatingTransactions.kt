package com.athar.core.domain.calc

import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isReconciliation

/**
 * Operating income/expense selection shared by Today, Trends and the widget loaders.
 *
 * A transfer is money movement between the user's own accounts, not consumption:
 * it lands in neither list. Reconciliation adjustments only true-up balances and
 * never count as income/expense.
 */
data class OperatingTransactions(
    val income: List<Transaction>,
    val expenses: List<Transaction>,
)

/**
 * Selects the operating rows from [transactions]: only [TxStatus.CONFIRMED] rows
 * that are not reconciliations (see [isReconciliation]), split by explicit
 * [TxType.INCOME] and [TxType.EXPENSE]. Transfers are excluded from both lists.
 *
 * Pure and order-preserving: rows keep their input-relative order, currency and
 * object identity, and the input is never mutated. Category and classifier
 * confidence are deliberately ignored here; currency grouping and financial-event
 * interpretation remain upstream/downstream responsibilities.
 */
fun selectOperatingTransactions(transactions: List<Transaction>): OperatingTransactions {
    val eligible = transactions.filter {
        it.status == TxStatus.CONFIRMED && !it.isReconciliation()
    }
    return OperatingTransactions(
        income = eligible.filter { it.type == TxType.INCOME },
        expenses = eligible.filter { it.type == TxType.EXPENSE },
    )
}
