package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.smsimport.SmsSenderIds
import kotlinx.datetime.Instant

/**
 * One ledger row enriched for monthly personal-finance aggregation.
 */
data class AccountingRow(
    val id: String,
    val accountId: String,
    val type: TxType,
    val amount: Money,
    val status: TxStatus,
    val occurredAt: Instant?,
    /** SMS sender when known (ingest / audit). */
    val sender: String? = null,
    val bodyHints: String = "",
    val bankReference: String? = null,
    /** Manual override: force count as income. */
    val userForcedIncome: Boolean = false,
    /** Manual override: force internal transfer. */
    val userForcedTransfer: Boolean = false,
    val isRefund: Boolean = false,
    val isReconciliation: Boolean = false,
)

data class MonthlyAccountingConfig(
    val ownedAccountIds: Set<String>,
    /** Account IDs whose purchases count as expenses (typically all owned). */
    val expenseAccountIds: Set<String>,
    /** Account IDs allowed to contribute confirmed income. */
    val incomeAccountIds: Set<String>,
    /** Exact SMS senders eligible for income (empty = none automatic). */
    val incomeEligibleSenders: Set<String> = emptySet(),
    val transferWindowMillis: Long = InternalTransferMatcher.DEFAULT_WINDOW_MILLIS,
)

data class MonthlyAccountingSummary(
    val totalIncome: Money,
    val totalExpenses: Money,
    val internalTransfers: Money,
    val netCashFlow: Money,
    val expensesByAccount: Map<String, Money>,
    val needsReviewIds: List<String>,
    val matchedTransferPairs: List<TransferMatch>,
)

/**
 * Monthly operating picture for owned multi-account setups.
 *
 * Matched internal transfers are excluded from income and expenses.
 * Net cash flow = income − expenses (distinct from bank balances).
 */
object MonthlyAccounting {

    fun summarize(
        rows: List<AccountingRow>,
        config: MonthlyAccountingConfig,
        zeroCurrency: String = Money.SAR,
    ): MonthlyAccountingSummary {
        val zero = Money.zero(zeroCurrency)
        val confirmed = rows.filter { it.status == TxStatus.CONFIRMED && !it.isReconciliation }

        val legs = mutableListOf<TransferLeg>()
        val preExcluded = mutableSetOf<String>() // refunds / forced transfers without pair yet

        for (row in confirmed) {
            if (row.isRefund) {
                preExcluded += row.id
                continue
            }
            if (row.userForcedTransfer) {
                val direction = if (row.accountId in config.incomeAccountIds) {
                    TransferDirection.OUT
                } else {
                    TransferDirection.IN
                }
                legs += TransferLeg(
                    id = row.id,
                    accountId = row.accountId,
                    direction = direction,
                    amount = row.amount,
                    occurredAt = row.occurredAt,
                    type = row.type,
                    bankReference = row.bankReference,
                )
                continue
            }
            when (row.type) {
                TxType.TRANSFER -> {
                    if (row.accountId !in config.ownedAccountIds) continue
                    val direction = if (row.accountId in config.incomeAccountIds) {
                        TransferDirection.OUT
                    } else {
                        TransferDirection.IN
                    }
                    legs += TransferLeg(
                        id = row.id,
                        accountId = row.accountId,
                        direction = direction,
                        amount = row.amount,
                        occurredAt = row.occurredAt,
                        type = row.type,
                        bankReference = row.bankReference,
                    )
                }
                TxType.INCOME -> {
                    // Incoming credit on a non-income account (e.g. D360 funded from ANB)
                    // is a transfer-IN candidate, never operating income.
                    val senderOk = row.sender != null &&
                        SmsSenderIds.matches(row.sender, config.incomeEligibleSenders)
                    val onIncomeAccount = row.accountId in config.incomeAccountIds
                    if (!row.userForcedIncome && (!senderOk || !onIncomeAccount) &&
                        row.accountId in config.ownedAccountIds
                    ) {
                        legs += TransferLeg(
                            id = row.id,
                            accountId = row.accountId,
                            direction = TransferDirection.IN,
                            amount = row.amount,
                            occurredAt = row.occurredAt,
                            type = row.type,
                            bankReference = row.bankReference,
                        )
                    }
                }
                TxType.EXPENSE -> Unit
            }
        }

        val matchResult = InternalTransferMatcher.match(
            legs = legs.distinctBy { it.id },
            ownedAccountIds = config.ownedAccountIds,
            windowMillis = config.transferWindowMillis,
        )
        val matchedIds = matchResult.matches
            .flatMap { listOf(it.outgoingId, it.incomingId) }
            .toSet()

        var income = zero
        var expenses = zero
        var transfers = zero
        val byAccount = mutableMapOf<String, Money>()
        val needsReview = matchResult.unmatchedNeedsReviewIds.toMutableList()

        for (pair in matchResult.matches) {
            transfers += pair.amount
        }

        for (row in confirmed) {
            if (row.id in matchedIds || row.id in preExcluded || row.userForcedTransfer) continue

            when (row.type) {
                TxType.INCOME -> {
                    if (row.userForcedIncome) {
                        income += row.amount
                        continue
                    }
                    val decision = IncomeClassification.decide(
                        parsedType = TxType.INCOME,
                        sender = row.sender.orEmpty(),
                        body = row.bodyHints,
                        accountId = row.accountId,
                        incomeEligibleSenders = config.incomeEligibleSenders,
                        ownedAccountIds = config.ownedAccountIds,
                        matchedAsInternalTransfer = false,
                        userForcedIncome = false,
                        userForcedTransfer = false,
                    )
                    when (decision) {
                        IncomeDecision.COUNT_AS_INCOME -> {
                            if (row.accountId in config.incomeAccountIds) {
                                income += row.amount
                            } else {
                                needsReview += row.id
                            }
                        }
                        IncomeDecision.NEEDS_REVIEW -> needsReview += row.id
                        IncomeDecision.INTERNAL_TRANSFER,
                        IncomeDecision.REFUND_OR_REVERSAL,
                        IncomeDecision.NOT_INCOME,
                        -> Unit
                    }
                }
                TxType.EXPENSE -> {
                    if (row.accountId in config.expenseAccountIds) {
                        expenses += row.amount
                        byAccount[row.accountId] =
                            (byAccount[row.accountId] ?: zero) + row.amount
                    }
                }
                TxType.TRANSFER -> needsReview += row.id
            }
        }

        return MonthlyAccountingSummary(
            totalIncome = income,
            totalExpenses = expenses,
            internalTransfers = transfers,
            netCashFlow = income - expenses,
            expensesByAccount = byAccount,
            needsReviewIds = needsReview.distinct(),
            matchedTransferPairs = matchResult.matches,
        )
    }
}
