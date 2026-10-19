package com.athar.core.domain.model

import com.athar.core.common.money.Money
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

data class Transaction(
    val id: String,
    val accountId: String,
    val type: TxType,
    val amount: Money,
    val date: LocalDate,
    val occurredAt: Instant?,
    val merchant: String,
    val merchantNormalized: String,
    val categoryId: String?,
    val notes: String?,
    val source: IngestSource,
    val sourceRefId: String?,
    val status: TxStatus,
    val confidence: Float?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Which provenance produced [categoryId]; null for legacy rows and UNKNOWN. */
    val categorySource: CategorySource? = null,
)

const val RECONCILE_REF_PREFIX = "reconcile-"

/**
 * Reconciliation adjustments inserted by AccountRepository.reconcile() are signed
 * transactions whose only job is to make the running balance match the bank. They
 * MUST NOT count as real income/expense in monthly totals, trends, or plan actuals —
 * they would inflate "spent this month" by the balance gap (often tens of thousands).
 * They still affect account balance + net worth (that's the whole point).
 */
fun Transaction.isReconciliation(): Boolean =
    source == IngestSource.MANUAL && sourceRefId?.startsWith(RECONCILE_REF_PREFIX) == true
