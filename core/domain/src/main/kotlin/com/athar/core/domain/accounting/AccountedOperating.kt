package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.OperatingTransactions
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isReconciliation
import com.athar.core.domain.smsimport.SmsSenderIds

/**
 * Shared Today/Trends operating selection that applies [MonthlyAccounting]
 * (internal-transfer pairing) instead of raw type splits alone.
 */
data class AccountedOperating(
    val operating: OperatingTransactions,
    val summary: MonthlyAccountingSummary,
    val matchedTransferIds: Set<String>,
)

/**
 * Builds a dashboard config from owned accounts and income-source account IDs.
 * When [incomeAccountIds] is empty, credits are never auto-paired as transfer-IN
 * (they only pair when typed [TxType.TRANSFER] or user-forced).
 */
fun accountingConfig(
    ownedAccountIds: Set<String>,
    incomeAccountIds: Set<String>,
    incomeEligibleSenders: Set<String> = emptySet(),
    expenseAccountIds: Set<String> = ownedAccountIds,
): MonthlyAccountingConfig = MonthlyAccountingConfig(
    ownedAccountIds = ownedAccountIds,
    expenseAccountIds = expenseAccountIds,
    incomeAccountIds = incomeAccountIds,
    incomeEligibleSenders = incomeEligibleSenders,
)

/**
 * Maps active accounts + income-source SMS senders to a [MonthlyAccountingConfig].
 * Income accounts are those whose `smsSenders` aliases match an eligible income sender.
 */
fun buildAccountingConfig(
    activeAccounts: List<Account>,
    incomeSenders: Set<String>,
): MonthlyAccountingConfig {
    val owned = activeAccounts.map { it.id }.toSet()
    val incomeAccounts = if (incomeSenders.isEmpty()) {
        emptySet()
    } else {
        activeAccounts.filter { account ->
            account.smsSenders.any { alias -> SmsSenderIds.matches(alias, incomeSenders) }
        }.map { it.id }.toSet()
    }
    return accountingConfig(
        ownedAccountIds = owned,
        incomeAccountIds = incomeAccounts,
        incomeEligibleSenders = incomeSenders,
    )
}

fun Transaction.toAccountingRow(
    userForcedTransfer: Boolean = merchantNormalized.contains("تحويل داخلي") ||
        notes?.contains("تحويل بين حساباتك") == true,
    isRefund: Boolean = merchantNormalized.contains("refund") ||
        merchantNormalized.contains("استرداد") ||
        categoryId == "cat-tax-refund" ||
        categoryId == "cat-reimbursements",
): AccountingRow = AccountingRow(
    id = id,
    accountId = accountId,
    type = type,
    amount = amount,
    status = status,
    occurredAt = occurredAt,
    sender = null,
    bodyHints = listOfNotNull(merchant, notes).joinToString(" "),
    bankReference = null,
    userForcedIncome = false,
    userForcedTransfer = userForcedTransfer && type != TxType.EXPENSE,
    isRefund = isRefund,
    isReconciliation = isReconciliation(),
)

/**
 * Single aggregation path for Today and Trends headlines.
 *
 * Only [TxStatus.CONFIRMED] non-reconciliation rows in [currency] participate.
 * Matched internal transfers and unpaired transfer-candidate credits are excluded
 * from income/expense lists.
 */
fun selectAccountedOperating(
    transactions: List<Transaction>,
    config: MonthlyAccountingConfig,
    currency: String = Money.SAR,
): AccountedOperating {
    val inCurrency = transactions.filter { it.amount.currency == currency }
    val rows = inCurrency.map { it.toAccountingRow() }
    val summary = MonthlyAccounting.summarize(rows, config, currency)
    val matched = summary.matchedTransferPairs
        .flatMap { listOf(it.outgoingId, it.incomingId) }
        .toSet()
    val excluded = matched + summary.needsReviewIds.toSet()

    val confirmed = inCurrency.filter {
        it.status == TxStatus.CONFIRMED && !it.isReconciliation() && it.id !in excluded
    }
    return AccountedOperating(
        operating = OperatingTransactions(
            income = confirmed.filter { it.type == TxType.INCOME },
            expenses = confirmed.filter { it.type == TxType.EXPENSE },
        ),
        summary = summary,
        matchedTransferIds = matched,
    )
}
