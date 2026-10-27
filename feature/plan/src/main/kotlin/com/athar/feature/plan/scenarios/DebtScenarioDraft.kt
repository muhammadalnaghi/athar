package com.athar.feature.plan.scenarios

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.debt.DebtPayoffCalculator
import com.athar.core.domain.model.debt.DebtId
import com.athar.core.domain.model.debt.DebtLoan
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtPayoffRequest
import com.athar.core.domain.model.debt.TermEvidence
import java.math.RoundingMode

internal data class DebtLoanDraft(
    val id: Int = 1,
    val name: String = "",
    val principal: String = "",
    val annualPercent: String = "",
    val minimum: String = "",
    val fee: String = "",
)

internal data class DebtScenarioDraft(
    val currency: String = "SAR",
    val firstDueDate: String = "",
    val horizon: String = "120",
    val budget: String = "",
    val supportedTermsAcknowledged: Boolean = false,
    val loans: List<DebtLoanDraft> = listOf(DebtLoanDraft()),
)

internal fun evaluateDebtDraft(draft: DebtScenarioDraft): ScenarioDraftResult<DebtPayoffOutcome> {
    val errors = debtDraftErrors(draft)
    if (errors.isNotEmpty()) return ScenarioDraftResult(errors)
    val due = requireNotNull(parseScenarioDate(draft.firstDueDate))
    val horizon = requireNotNull(parseScenarioDecimal(draft.horizon)?.let {
        try { it.intValueExact() } catch (_: ArithmeticException) { null }
    }?.takeIf { it in 1..1200 })
    val budget = requireNotNull(parseScenarioDecimal(draft.budget)?.takeIf { it.signum() >= 0 })
    val loans = draft.loans.map { loan ->
        DebtLoan(
            id = DebtId(loan.name.trim()),
            principal = Money.of(requireNotNull(parseScenarioDecimal(loan.principal)), draft.currency),
            nominalAnnualApr = requireNotNull(parseScenarioDecimal(loan.annualPercent)).movePointLeft(2),
            minimumPayment = Money.of(requireNotNull(parseScenarioDecimal(loan.minimum)), draft.currency),
            fixedMonthlyFee = Money.of(requireNotNull(parseScenarioDecimal(loan.fee)), draft.currency),
            evidence = if (draft.supportedTermsAcknowledged) {
                TermEvidence.SupportedNominalMonthly(due, due.dayOfMonth)
            } else TermEvidence.Missing,
        )
    }
    return ScenarioDraftResult(outcome = DebtPayoffCalculator.compare(DebtPayoffRequest(
        loans = loans,
        monthlyBudget = Money.of(budget, draft.currency),
        currency = draft.currency,
        interestRounding = RoundingMode.HALF_EVEN,
        horizonCycles = horizon,
        attestsCompleteCycleStart = draft.supportedTermsAcknowledged,
    )))
}

/** Shared validation for compute and load; the exact error keys are part of the tested contract. */
internal fun debtDraftErrors(draft: DebtScenarioDraft): Set<String> {
    val errors = linkedSetOf<String>()
    if (!validScenarioCurrency(draft.currency)) errors += "currency"
    val due = parseScenarioDate(draft.firstDueDate)
    if (due == null) errors += "firstDueDate"
    val horizon = parseScenarioDecimal(draft.horizon)?.let {
        try { it.intValueExact() } catch (_: ArithmeticException) { null }
    }?.takeIf { it in 1..1200 }
    if (horizon == null) errors += "horizon"
    val budget = parseScenarioDecimal(draft.budget)?.takeIf { it.signum() >= 0 }
    if (budget == null) errors += "budget"
    if (draft.loans.size !in 1..12 || draft.loans.map { it.id }.distinct().size != draft.loans.size) errors += "loans"
    val names = draft.loans.map { it.name.trim() }
    val duplicateNames = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicateNames.isNotEmpty()) {
        errors += "loans"
        draft.loans.filter { it.name.trim() in duplicateNames }.forEach { errors += "loan-${it.id}-name" }
    }
    draft.loans.forEach { loan ->
        val prefix = "loan-${loan.id}-"
        if (parseScenarioDecimal(loan.principal)?.signum()?.let { it >= 0 } != true) errors += prefix + "principal"
        if (parseScenarioDecimal(loan.annualPercent)?.signum()?.let { it >= 0 } != true) errors += prefix + "annualPercent"
        if (parseScenarioDecimal(loan.minimum)?.signum()?.let { it >= 0 } != true) errors += prefix + "minimum"
        if (parseScenarioDecimal(loan.fee)?.signum()?.let { it >= 0 } != true) errors += prefix + "fee"
        if (loan.name.isBlank() || loan.name.length > 64) errors += prefix + "name"
    }
    return errors
}
