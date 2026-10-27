package com.athar.core.domain.model.debt

import com.athar.core.common.money.Money
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode

/** Stable, non-blank, unique identifier for one debt. */
@JvmInline
value class DebtId(val value: String) {
    init {
        require(value.isNotBlank()) { "Debt id must be non-blank" }
    }
}

/**
 * Typed evidence that a debt's disclosed terms match the only supported projection
 * convention (nominal annual APR / 12, interest capitalized before each monthly
 * payment, known fixed monthly fee, unrestricted penalty-free prepayment).
 * Explicit typed facts only — never a provenance string.
 */
sealed interface TermEvidence {
    data class SupportedNominalMonthly(
        val firstDueDate: LocalDate,
        val anchorDay: Int,
    ) : TermEvidence

    data class Unsupported(val reason: UnsupportedTermReason) : TermEvidence

    data object Missing : TermEvidence
}

/** Why a debt's terms cannot be projected. Always surfaced, never papered over. */
enum class UnsupportedTermReason {
    MISSING_TERMS,
    TIMING_NOT_COMPLETE_MONTHLY_CYCLE,
    NON_NOMINAL_OR_DAILY_ACCRUAL,
    FLAT_PROFIT_TERMS,
    UNKNOWN_OR_VARIABLE_FEES,
    PREPAYMENT_RESTRICTED,
}

/** One debt with explicitly disclosed terms. Amounts share one currency. */
data class DebtLoan(
    val id: DebtId,
    val principal: Money,
    val nominalAnnualApr: BigDecimal?,
    val minimumPayment: Money?,
    val fixedMonthlyFee: Money?,
    val evidence: TermEvidence,
) {
    init {
        require(!principal.isNegative()) { "Principal must be nonnegative for ${id.value}" }
        require(nominalAnnualApr == null || nominalAnnualApr.signum() >= 0) { "APR must be nonnegative for ${id.value}" }
        minimumPayment?.let {
            require(!it.isNegative()) { "Minimum payment must be nonnegative for ${id.value}" }
            require(it.currency == principal.currency) { "All amounts of debt ${id.value} must share one currency" }
        }
        fixedMonthlyFee?.let {
            require(!it.isNegative()) { "Monthly fee must be nonnegative for ${id.value}" }
            require(it.currency == principal.currency) { "All amounts of debt ${id.value} must share one currency" }
        }
    }
}

/**
 * A full comparison request. The caller explicitly states budget, one shared
 * literal currency, interest-only rounding, horizon, and attests that the
 * projection starts at a complete monthly accrual cycle.
 */
data class DebtPayoffRequest(
    val loans: PersistentList<DebtLoan>,
    val monthlyBudget: Money,
    val currency: String,
    val interestRounding: RoundingMode,
    val horizonCycles: Int,
    val attestsCompleteCycleStart: Boolean,
) {
    constructor(
        loans: List<DebtLoan>,
        monthlyBudget: Money,
        currency: String,
        interestRounding: RoundingMode,
        horizonCycles: Int,
        attestsCompleteCycleStart: Boolean,
    ) : this(loans.toPersistentList(), monthlyBudget, currency, interestRounding, horizonCycles, attestsCompleteCycleStart)
}

/** Order in which surplus above minimums is directed. */
enum class DebtStrategy {
    /** Highest nominal APR first. */
    AVALANCHE,

    /** Lowest positive principal first. */
    SNOWBALL,
}
