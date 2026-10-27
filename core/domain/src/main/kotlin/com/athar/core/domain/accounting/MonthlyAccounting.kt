package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
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
    /**
     * Owned accounts for expense breakdowns / future scoped views.
     * Headline expense totals count every confirmed EXPENSE (see [summarize]).
     */
    val expenseAccountIds: Set<String>,
    /**
     * Account IDs treated as income landing accounts (e.g. ANB).
     * Credits on other owned accounts may pair as transfer-IN when an owned
     * TRANSFER-OUT counterpart exists — ownership mapping, not sender demotion.
     */
    val incomeAccountIds: Set<String>,
    /** Exact SMS senders eligible for income (empty = none automatic at ingest). */
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
 *
 * Persistence limitation: pairs are recomputed in memory each call. No
 * `linkedTransferGroupId` is stored yet — identical amounts outside the time
 * window, or missing counterparts, stay in [MonthlyAccountingSummary.needsReviewIds].
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
        val preExcluded = mutableSetOf<String>()

        for (row in confirmed) {
            if (row.isRefund) {
                preExcluded += row.id
                continue
            }
            if (row.userForcedTransfer) {
                legs += TransferLeg(
                    id = row.id,
                    accountId = row.accountId,
                    direction = directionForForced(row, config),
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
                    legs += TransferLeg(
                        id = row.id,
                        accountId = row.accountId,
                        direction = if (row.accountId in config.incomeAccountIds) {
                            TransferDirection.OUT
                        } else {
                            TransferDirection.IN
                        },
                        amount = row.amount,
                        occurredAt = row.occurredAt,
                        type = row.type,
                        bankReference = row.bankReference,
                    )
                }
                TxType.INCOME -> {
                    // Ownership-mapped transfer-IN candidate: credit on an owned
                    // non-income account (e.g. D360). Requires incomeAccountIds to be
                    // configured so we do not treat every owned credit as a transfer.
                    if (!row.userForcedIncome &&
                        config.incomeAccountIds.isNotEmpty() &&
                        row.accountId in config.ownedAccountIds &&
                        row.accountId !in config.incomeAccountIds
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
        val unmatchedLegIds = matchResult.unmatchedNeedsReviewIds.toSet()

        var income = zero
        var expenses = zero
        var transfers = zero
        val byAccount = mutableMapOf<String, Money>()
        val needsReview = unmatchedLegIds.toMutableList()

        for (pair in matchResult.matches) {
            transfers += pair.amount
        }

        for (row in confirmed) {
            if (row.id in matchedIds || row.id in preExcluded || row.userForcedTransfer) continue
            // Unpaired ownership-mapped transfer candidates stay out of operating totals.
            if (row.id in unmatchedLegIds) continue

            when (row.type) {
                TxType.INCOME -> {
                    if (row.userForcedIncome) {
                        income += row.amount
                        continue
                    }
                    // CONFIRMED income that was not a transfer candidate counts.
                    // Ambiguous credits should remain PENDING at ingest and never reach here.
                    income += row.amount
                }
                TxType.EXPENSE -> {
                    // Count all confirmed expenses. expenseAccountIds is reserved for
                    // scoped breakdowns; gating here zeroed Today/Trends when accounts
                    // were empty or tx.accountId lagged the fake account id.
                    expenses += row.amount
                    byAccount[row.accountId] =
                        (byAccount[row.accountId] ?: zero) + row.amount
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

    private fun directionForForced(
        row: AccountingRow,
        config: MonthlyAccountingConfig,
    ): TransferDirection =
        if (row.accountId in config.incomeAccountIds) TransferDirection.OUT
        else TransferDirection.IN
}
