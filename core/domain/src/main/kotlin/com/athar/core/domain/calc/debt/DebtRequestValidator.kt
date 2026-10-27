package com.athar.core.domain.calc.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtMinimumShare
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.DebtTermRejection
import com.athar.core.domain.model.debt.TermEvidence
import com.athar.core.domain.model.debt.UnsupportedTermReason
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

internal object DebtMath {
    fun fractionDigits(code: String): Int? =
        try {
            Currency.getInstance(code).defaultFractionDigits.takeIf { it >= 0 }
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Contractual monthly interest: principal × APR / 12, quantized once. */
    fun monthlyInterest(
        principal: BigDecimal,
        nominalAnnualApr: BigDecimal,
        scale: Int,
        rounding: RoundingMode,
    ): BigDecimal = principal.multiply(nominalAnnualApr).divide(BigDecimal(12), scale, rounding)
}

internal object DebtRequestValidator {
    const val MAX_DEBTS = 100
    const val MAX_HORIZON_CYCLES = 1200

    fun validate(request: DebtPayoffRequest): DebtPayoffOutcome.InvalidInput? {
        val errors = mutableListOf<String>()
        if (request.monthlyBudget.isNegative()) errors += "monthlyBudget must be nonnegative"
        if (request.loans.isEmpty()) errors += "at least one debt is required"
        if (request.loans.size > MAX_DEBTS) {
            errors += "at most $MAX_DEBTS debts are supported, got ${request.loans.size}"
        }
        if (request.horizonCycles !in 1..MAX_HORIZON_CYCLES) {
            errors += "horizonCycles must be 1..$MAX_HORIZON_CYCLES, got ${request.horizonCycles}"
        }
        val ids = request.loans.map { it.id.value }
        if (ids.size != ids.toSet().size) errors += "debt ids must be unique"
        val fd = DebtMath.fractionDigits(request.currency)
        if (fd == null) {
            errors += "unknown or non-circulating currency '${request.currency}'"
        } else {
            checkScale(request.monthlyBudget, request.currency, fd)?.let { errors += it }
            if (request.monthlyBudget.currency != request.currency) {
                errors += "budget currency ${request.monthlyBudget.currency} != request currency ${request.currency}"
            }
            for (loan in request.loans) {
                errors += loanErrors(loan, request, fd)
            }
        }
        return if (errors.isEmpty()) null else DebtPayoffOutcome.InvalidInput(errors)
    }

    private fun loanErrors(loan: DebtLoan, request: DebtPayoffRequest, fd: Int): List<String> {
        val errors = mutableListOf<String>()
        if (loan.principal.currency != request.currency) {
            errors += "debt ${loan.id.value} principal currency ${loan.principal.currency} != ${request.currency}"
        }
        if (loan.minimumPayment != null && loan.minimumPayment.currency != request.currency) {
            errors += "debt ${loan.id.value} minimum currency ${loan.minimumPayment.currency} != ${request.currency}"
        }
        if (loan.fixedMonthlyFee != null && loan.fixedMonthlyFee.currency != request.currency) {
            errors += "debt ${loan.id.value} fee currency ${loan.fixedMonthlyFee.currency} != ${request.currency}"
        }
        if (loan.principal.currency == request.currency) checkScale(loan.principal, request.currency, fd)?.let { errors += it }
        if (loan.minimumPayment != null && loan.minimumPayment.currency == request.currency) {
            checkScale(loan.minimumPayment, request.currency, fd)?.let { errors += it }
        }
        if (loan.fixedMonthlyFee != null && loan.fixedMonthlyFee.currency == request.currency) {
            checkScale(loan.fixedMonthlyFee, request.currency, fd)?.let { errors += it }
        }
        val evidence = loan.evidence as? TermEvidence.SupportedNominalMonthly
        if (evidence != null && loan.principal.isPositive()) {
            if (evidence.anchorDay !in 1..31) {
                errors += "anchorDay must be 1..31 for debt ${loan.id.value}"
            } else {
                val month = java.time.YearMonth.of(evidence.firstDueDate.year, evidence.firstDueDate.monthNumber)
                if (evidence.firstDueDate.dayOfMonth != minOf(evidence.anchorDay, month.lengthOfMonth())) {
                    errors += "first due date does not match explicit anchor for debt ${loan.id.value}"
                }
            }
        }
        return errors
    }

    private fun checkScale(money: Money, currency: String, fractionDigits: Int): String? {
        val stripped = money.amount.stripTrailingZeros()
        return if (stripped.scale() > fractionDigits) {
            "$currency allows $fractionDigits fraction digits, got scale ${stripped.scale()} " +
                "(${money.amount.toPlainString()})"
        } else {
            null
        }
    }

    fun termRejections(request: DebtPayoffRequest): List<DebtTermRejection> {
        val rejections = mutableListOf<DebtTermRejection>()
        if (!request.attestsCompleteCycleStart) {
            request.loans.filter { it.principal.isPositive() }.forEach {
                rejections += DebtTermRejection(it.id, UnsupportedTermReason.TIMING_NOT_COMPLETE_MONTHLY_CYCLE)
            }
        }
        for (loan in request.loans) {
            if (loan.principal.isZero()) continue
            when (val evidence = loan.evidence) {
                is TermEvidence.SupportedNominalMonthly -> {
                    if (loan.nominalAnnualApr == null || loan.minimumPayment == null || loan.fixedMonthlyFee == null) {
                        rejections += DebtTermRejection(loan.id, UnsupportedTermReason.MISSING_TERMS)
                    }
                }
                is TermEvidence.Unsupported -> rejections += DebtTermRejection(loan.id, evidence.reason)
                TermEvidence.Missing -> rejections += DebtTermRejection(loan.id, UnsupportedTermReason.MISSING_TERMS)
            }
        }
        return rejections
    }

    fun commonAnchors(request: DebtPayoffRequest): Pair<kotlinx.datetime.LocalDate, Int>? {
        val outstanding = request.loans.filter { it.principal.isPositive() }
        val supported = outstanding.mapNotNull { it.evidence as? TermEvidence.SupportedNominalMonthly }
        if (supported.isEmpty() || supported.size != outstanding.size) return null
        val first = supported.first()
        val same = supported.all { it.firstDueDate == first.firstDueDate && it.anchorDay == first.anchorDay }
        return if (same) first.firstDueDate to first.anchorDay else null
    }

    /** Strategy-independent first-cycle feasibility check over capped minimums. */
    fun budgetBelowMinimums(request: DebtPayoffRequest): DebtPayoffOutcome.BudgetBelowMinimums? {
        val fd = DebtMath.fractionDigits(request.currency) ?: return null
        val outstanding = request.loans.filter { it.principal.isPositive() }
        if (outstanding.isEmpty()) return null
        var total = BigDecimal.ZERO
        val shares = mutableListOf<DebtMinimumShare>()
        for (loan in outstanding) {
            val interest = DebtMath.monthlyInterest(
                loan.principal.amount,
                requireNotNull(loan.nominalAnnualApr),
                fd,
                request.interestRounding,
            )
            val owed = loan.principal.amount + interest + requireNotNull(loan.fixedMonthlyFee).amount
            val capped = requireNotNull(loan.minimumPayment).amount.min(owed)
            total += capped
            shares += DebtMinimumShare(loan.id, Money.of(capped, request.currency))
        }
        return if (total > request.monthlyBudget.amount) {
            DebtPayoffOutcome.BudgetBelowMinimums(
                budget = request.monthlyBudget,
                requiredMinimumsTotal = Money.of(total, request.currency),
                byDebt = shares,
            )
        } else {
            null
        }
    }
}
