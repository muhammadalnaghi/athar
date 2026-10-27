package com.athar.core.domain.accounting

import com.athar.core.domain.model.TxType
import com.athar.core.domain.smsimport.SmsSenderIds

/**
 * Outcome of post-parse income eligibility checks.
 *
 * Sender selection only establishes eligibility. Direction, transfer matching,
 * refunds, and user overrides still apply downstream.
 */
enum class IncomeDecision {
    /** Count as confirmed income when the row is CONFIRMED. */
    COUNT_AS_INCOME,
    /** Own-account move — never income. */
    INTERNAL_TRANSFER,
    /** Merchant refund / reversal — not salary or new income. */
    REFUND_OR_REVERSAL,
    /** Ambiguous credit — exclude from confirmed income until resolved. */
    NEEDS_REVIEW,
    /** Not an incoming credit. */
    NOT_INCOME,
}

object IncomeClassification {

    private val refundHints = listOf(
        "refund", "reversal", "chargeback", "استرداد", "مرتجع", "عكس",
    )
    private val salaryHints = listOf(
        "salary", "payroll", "راتب", "رواتب", "أجر",
    )

    /**
     * @param incomeEligibleSenders exact SMS sender IDs the user selected as income sources.
     *        Empty means no automatic income — every credit needs review.
     * @param ownedAccountIds accounts belonging to the same person (for transfer demotion).
     */
    fun decide(
        parsedType: TxType,
        sender: String,
        body: String,
        accountId: String,
        incomeEligibleSenders: Set<String>,
        ownedAccountIds: Set<String>,
        matchedAsInternalTransfer: Boolean,
        userForcedIncome: Boolean = false,
        userForcedTransfer: Boolean = false,
    ): IncomeDecision {
        if (userForcedTransfer || matchedAsInternalTransfer) return IncomeDecision.INTERNAL_TRANSFER
        if (userForcedIncome) return IncomeDecision.COUNT_AS_INCOME

        if (parsedType != TxType.INCOME && parsedType != TxType.TRANSFER) {
            return IncomeDecision.NOT_INCOME
        }

        val normalizedBody = body.lowercase()
        if (refundHints.any { normalizedBody.contains(it) }) {
            return IncomeDecision.REFUND_OR_REVERSAL
        }

        if (parsedType == TxType.TRANSFER) {
            return if (accountId in ownedAccountIds) {
                IncomeDecision.INTERNAL_TRANSFER
            } else {
                IncomeDecision.NEEDS_REVIEW
            }
        }

        // INCOME path — closed default: no configured sources ⇒ needs review.
        if (incomeEligibleSenders.isEmpty()) {
            return IncomeDecision.NEEDS_REVIEW
        }
        if (!SmsSenderIds.matches(sender, incomeEligibleSenders)) {
            // Incoming credit from a non-income sender (e.g. D360 funding from ANB).
            return if (accountId in ownedAccountIds) {
                IncomeDecision.INTERNAL_TRANSFER
            } else {
                IncomeDecision.NEEDS_REVIEW
            }
        }

        // Eligible sender: salary-like language can count; otherwise still needs review
        // so we never auto-count every ANB credit as salary.
        return if (salaryHints.any { normalizedBody.contains(it) }) {
            IncomeDecision.COUNT_AS_INCOME
        } else {
            IncomeDecision.NEEDS_REVIEW
        }
    }
}
