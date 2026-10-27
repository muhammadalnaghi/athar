package com.athar.feature.plan.goals

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.wealth.GoalPlanCalc
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalInputProblemCode
import com.athar.core.domain.model.wealth.GoalNotReachableReason
import com.athar.core.domain.model.wealth.GoalProjection
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Transient, UI-owned scenario draft for the WM028-P-goals lane. It is a what-if form
 * state ONLY: nothing here is persisted; [GoalScenarioUi.result] always corresponds to
 * the visible [GoalScenarioDraft] because it is dropped on every edit and recomputed
 * explicitly. All solver interaction goes through the accepted read-only `GoalPlanCalc`;
 * this adapter adds no financial assumptions of its own.
 */
enum class GoalTargetBasis { NOMINAL, TODAY_PRICE }

/** Declared growth model. [CASH_ONLY] is the honest zero-return baseline. */
enum class GoalAssumptionsKind { CASH_ONLY, FIXED_APR }

/** Which accepted solver operation answers the user's question. */
enum class GoalSolveMode {
    PROJECT_FUTURE_AMOUNT,
    REQUIRED_MONTHLY_CONTRIBUTION,
    REQUIRED_MONTHS,
    REQUIRED_NOMINAL_APR,
}

/** Draft fields that can carry a local (pre-solver) input error. */
enum class GoalField {
    TARGET_AMOUNT,
    TARGET_INFLATION,
    CURRENT_ALLOCATION,
    MONTHLY_CONTRIBUTION,
    HORIZON_MONTHS,
    APR_PERCENT,
    FEE_PERCENT,
}

data class GoalScenarioDraft(
    val targetAmountText: String = "",
    val targetBasis: GoalTargetBasis = GoalTargetBasis.NOMINAL,
    val inflationPercentText: String = "",
    val currentAllocationText: String = "",
    val monthlyContributionText: String = "",
    val horizonMonthsText: String = "",
    val assumptionsKind: GoalAssumptionsKind = GoalAssumptionsKind.CASH_ONLY,
    val aprPercentText: String = "",
    val feePercentText: String = "",
    val timing: ContributionTiming = ContributionTiming.END_OF_MONTH,
    val solveMode: GoalSolveMode = GoalSolveMode.PROJECT_FUTURE_AMOUNT,
) {
    companion object {
        fun empty(): GoalScenarioDraft = GoalScenarioDraft()

        /**
         * Opt-in seeding from the user's ACTUAL figures: liquid balance and average
         * monthly savings. Negative savings cannot be a contribution, so it seeds zero
         * (the real negative stays visible on the savings-rate card). Provenance is
         * labeled in the UI; both fields remain editable.
         */
        fun seeded(liquidBalance: Money, monthlySavings: Money): GoalScenarioDraft {
            val currency = liquidBalance.currency
            val contribution =
                if (monthlySavings.currency == currency && !monthlySavings.isNegative()) {
                    monthlySavings
                } else {
                    Money.zero(currency)
                }
            val allocation = if (liquidBalance.isNegative()) Money.zero(currency) else liquidBalance
            return GoalScenarioDraft(
                currentAllocationText = moneyText(allocation),
                monthlyContributionText = moneyText(contribution),
            )
        }

        /**
         * Exact literal amount, no scale rounding: KWD's 3-dp amounts (and averaged
         * savings) keep their exact value end to end because [GoalScenarioDraft.solve]
         * re-parses this text verbatim. Never FX-converted here.
         */
        private fun moneyText(money: Money): String = money.amount.toPlainString()
    }
}

/** UI projection of one accepted solver outcome. Never synthesizes numbers. */
sealed interface GoalSolveUi {
    data class Projected(
        val projection: GoalProjection,
        /** Labeled cash-only twin of the same scenario; null when the model is already cash-only. */
        val cashOnlyFutureAmount: Money?,
    ) : GoalSolveUi

    data class RequiredContributionUi(val value: com.athar.core.domain.model.wealth.RequiredContribution) : GoalSolveUi
    data class RequiredMonthsUi(val value: com.athar.core.domain.model.wealth.RequiredMonths) : GoalSolveUi
    data class RequiredRateUi(val value: com.athar.core.domain.model.wealth.RequiredRate) : GoalSolveUi
    data object AlreadyFunded : GoalSolveUi
    data class NotReachable(val reason: GoalNotReachableReason) : GoalSolveUi
    data class Invalid(
        val fieldErrors: Set<GoalField>,
        val problems: List<GoalInputProblemCode>,
    ) : GoalSolveUi
}

/**
 * Transient scenario section state: the editable draft plus its latest honest result.
 * [currency] is the literal display currency this scenario was created under — blank
 * until preferences bind it. A loaded display-currency change discards the whole
 * scenario instead of relabeling [draft]/[result] under a new code; figures are never
 * FX-converted.
 */
data class GoalScenarioUi(
    val draft: GoalScenarioDraft = GoalScenarioDraft.empty(),
    val result: GoalSolveUi? = null,
    val currency: String = "",
) {
    companion object {
        fun initial(): GoalScenarioUi = GoalScenarioUi()
    }
}

internal object NumericText {
    private const val MAX_INPUT_CHARS = 128
    private val plainNumber = Regex("[+-]?(?:(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]*)?|\\.[0-9]+)")

    /** Accept only bounded plain numbers and valid three-digit grouping; preserve internal spaces as errors. */
    fun normalize(raw: String): String {
        if (raw.length > MAX_INPUT_CHARS) return ""
        val normalized = raw.trim().map { c ->
            when (c) {
                '٫' -> '.'
                '٬' -> ','
                else -> Character.digit(c, 10).takeIf { it >= 0 }?.let { '0' + it } ?: c
            }
        }.joinToString("")
        return if (plainNumber.matches(normalized)) normalized.replace(",", "") else ""
    }
}

internal fun parseDecimal(raw: String): BigDecimal? =
    NumericText.normalize(raw).takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

private const val SCENARIO_NAME = "goal"

/**
 * Validates the draft, builds the declared [GoalScenario] in [displayCurrency], and maps
 * the accepted solver's typed outcome 1:1 onto [GoalSolveUi]. Invalid inputs never reach
 * the solver as coerced numbers: local parse failures surface as [GoalField] errors, and
 * solver-rejected values surface as [GoalInputProblemCode]s, never as fabricated results.
 */
fun GoalScenarioDraft.solve(displayCurrency: String, startMonth: YearMonth): GoalSolveUi {
    val fieldErrors = buildSet {
        val target = parseDecimal(targetAmountText)
        if (target == null || target.signum() <= 0) add(GoalField.TARGET_AMOUNT)
        val allocation = parseDecimal(currentAllocationText)
        if (allocation == null || allocation.signum() < 0) add(GoalField.CURRENT_ALLOCATION)
        val contribution = parseDecimal(monthlyContributionText)
        if (contribution == null || contribution.signum() < 0) add(GoalField.MONTHLY_CONTRIBUTION)
        if (solveMode != GoalSolveMode.REQUIRED_MONTHS) {
            val horizon = NumericText.normalize(horizonMonthsText).toIntOrNull()
            if (horizon == null || horizon !in 1..GoalPlanCalc.TECHNICAL_HORIZON_LIMIT_MONTHS) {
                add(GoalField.HORIZON_MONTHS)
            }
        }
        if (targetBasis == GoalTargetBasis.TODAY_PRICE && parseDecimal(inflationPercentText) == null) {
            add(GoalField.TARGET_INFLATION)
        }
        if (assumptionsKind == GoalAssumptionsKind.FIXED_APR) {
            if (parseDecimal(aprPercentText) == null) add(GoalField.APR_PERCENT)
            if (parseDecimal(feePercentText) == null) add(GoalField.FEE_PERCENT)
        }
    }
    if (fieldErrors.isNotEmpty()) {
        return GoalSolveUi.Invalid(fieldErrors = fieldErrors, problems = emptyList())
    }

    val currency = displayCurrency
    val scenario = GoalScenario(
        name = SCENARIO_NAME,
        target = when (targetBasis) {
            GoalTargetBasis.NOMINAL -> GoalTarget.Nominal(Money.of(parseDecimal(targetAmountText)!!, currency))
            GoalTargetBasis.TODAY_PRICE -> GoalTarget.TodayPrice(
                amount = Money.of(parseDecimal(targetAmountText)!!, currency),
                annualInflationPercent = parseDecimal(inflationPercentText)!!,
            )
        },
        currentAllocation = Money.of(parseDecimal(currentAllocationText)!!, currency),
        monthlyContribution = Money.of(parseDecimal(monthlyContributionText)!!, currency),
        assumptions = when (assumptionsKind) {
            GoalAssumptionsKind.CASH_ONLY -> GoalAssumptions.CashOnly
            GoalAssumptionsKind.FIXED_APR -> GoalAssumptions.FixedNominalApr(
                annualAprPercent = parseDecimal(aprPercentText)!!,
                annualFeePercent = parseDecimal(feePercentText)!!,
            )
        },
        contributionTiming = timing,
    )

    return when (solveMode) {
        GoalSolveMode.PROJECT_FUTURE_AMOUNT -> {
            val horizon = horizonMonths()
            when (val outcome = GoalPlanCalc.projectFutureAmount(scenario, horizon)) {
                is GoalPlanOutcome.Available -> GoalSolveUi.Projected(
                    projection = outcome.value,
                    cashOnlyFutureAmount = if (assumptionsKind == GoalAssumptionsKind.FIXED_APR) {
                        (GoalPlanCalc.projectFutureAmount(
                            scenario.copy(assumptions = GoalAssumptions.CashOnly),
                            horizon,
                        ) as? GoalPlanOutcome.Available)?.value?.futureAmount
                    } else {
                        null
                    },
                )
                else -> outcome.toSolveUi { GoalSolveUi.Projected(it, cashOnlyFutureAmount = null) }
            }
        }
        GoalSolveMode.REQUIRED_MONTHLY_CONTRIBUTION ->
            GoalPlanCalc.requiredMonthlyContribution(scenario, horizonMonths())
                .toSolveUi { GoalSolveUi.RequiredContributionUi(it) }
        GoalSolveMode.REQUIRED_MONTHS ->
            GoalPlanCalc.requiredMonthsToDate(scenario, startMonth)
                .toSolveUi { GoalSolveUi.RequiredMonthsUi(it) }
        GoalSolveMode.REQUIRED_NOMINAL_APR ->
            GoalPlanCalc.requiredNominalApr(scenario, horizonMonths())
                .toSolveUi { GoalSolveUi.RequiredRateUi(it) }
    }
}

private fun GoalScenarioDraft.horizonMonths(): Int =
    NumericText.normalize(horizonMonthsText)
        .toInt()
        .coerceIn(1, GoalPlanCalc.TECHNICAL_HORIZON_LIMIT_MONTHS)

private fun <T> GoalPlanOutcome<T>.toSolveUi(mapAvailable: (T) -> GoalSolveUi): GoalSolveUi =
    when (this) {
        is GoalPlanOutcome.Available -> mapAvailable(value)
        GoalPlanOutcome.AlreadyFunded -> GoalSolveUi.AlreadyFunded
        is GoalPlanOutcome.NotReachable -> GoalSolveUi.NotReachable(reason)
        is GoalPlanOutcome.InvalidInputs ->
            GoalSolveUi.Invalid(fieldErrors = emptySet(), problems = problems.map { it.code }.distinct())
    }
