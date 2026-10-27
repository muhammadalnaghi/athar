package com.athar.core.domain.calc.wealth

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.WealthMoneyPrecision
import com.athar.core.domain.model.wealth.GoalAssumptions
import com.athar.core.domain.model.wealth.GoalInputProblem
import com.athar.core.domain.model.wealth.GoalInputProblemCode
import com.athar.core.domain.model.wealth.GoalNotReachableReason
import com.athar.core.domain.model.wealth.GoalPlanOutcome
import com.athar.core.domain.model.wealth.GoalProjection
import com.athar.core.domain.model.wealth.GoalScenario
import com.athar.core.domain.model.wealth.GoalTarget
import com.athar.core.domain.model.wealth.RequiredContribution
import com.athar.core.domain.model.wealth.RequiredMonths
import com.athar.core.domain.model.wealth.RequiredRate
import com.athar.core.domain.model.wealth.ContributionTiming
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/**
 * Long-horizon goal solver (WM028-03). Pure calc: no repository, no clock, no hidden
 * return assumptions. All assumptions come from the [GoalScenario]; see
 * `docs/specs/WM028-03-goal-solver.md` for the declared conventions.
 *
 * Every solve returns a typed [GoalPlanOutcome]: `Available` payloads carry the
 * [METHOD] tag so results are self-describing; shortfalls report
 * `NotReachable` with a stable reason; invalid inputs report `InvalidInputs` and are
 * never coerced into numbers. Independent of [com.athar.core.domain.calc.WishlistCalc]:
 * the 1200-month technical horizon here is a labeled model bound, not the wishlist
 * 36-month presentation cap, and says nothing about financial feasibility.
 */
object GoalPlanCalc {

    /** Method/version tag carried by every Available payload. */
    const val METHOD = "GoalPlanCalc/3"

    /** Technical scan limit (100 years of months). Labeled technical, not financial. */
    const val TECHNICAL_HORIZON_LIMIT_MONTHS = GoalPlanEngine.TECHNICAL_HORIZON_LIMIT_MONTHS

    /**
     * Balance at [horizonMonths] under the scenario's explicit assumptions.
     * Always solvable when inputs are valid; the target is reported only for reference.
     */
    fun projectFutureAmount(
        scenario: GoalScenario,
        horizonMonths: Int,
    ): GoalPlanOutcome<GoalProjection> {
        val problems = validate(scenario, horizonMonths)
        if (problems.isNotEmpty()) return GoalPlanOutcome.InvalidInputs(problems)

        val currency = scenario.target.amount.currency
        val moneyScale = moneyScale(scenario)
        val factor = GoalPlanEngine.monthlyGrowthFactor(scenario.assumptions)
        val future = GoalPlanEngine.projectBalance(
            currentAllocation = scenario.currentAllocation.amount,
            monthlyContribution = scenario.monthlyContribution.amount,
            growthFactor = factor,
            months = horizonMonths,
            timing = scenario.contributionTiming,
        )
        val futureMoney = money(future, currency, moneyScale)
        val totalContributions = scenario.monthlyContribution * horizonMonths
        val growth = futureMoney - scenario.currentAllocation - totalContributions
        val nominalTarget = money(GoalPlanEngine.nominalTargetAt(scenario.target, horizonMonths), currency, moneyScale)
        return GoalPlanOutcome.Available(
            GoalProjection(
                method = METHOD,
                horizonMonths = horizonMonths,
                futureAmount = futureMoney,
                totalContributions = totalContributions,
                growthAmount = growth,
                nominalTargetAtHorizon = nominalTarget,
            ),
        )
    }

    /**
     * Smallest scenario-money-granular monthly contribution whose forward projection at
     * [horizonMonths] meets the nominal target. The raw solve is rounded conservatively
     * UP to money scale and verified against the real model at the returned value, so
     * money granularity can leave a small verified excess — never a shortfall.
     * `AlreadyFunded` only when the declared model's NO-CONTRIBUTION projection covers
     * the HORIZON nominal target (WM028-03 F3B): current-day funding alone never
     * shortcuts, because inflation or negative net growth can erode today's surplus,
     * so a growth-alone gap close reports AlreadyFunded rather than a solved zero.
     */
    fun requiredMonthlyContribution(
        scenario: GoalScenario,
        horizonMonths: Int,
    ): GoalPlanOutcome<RequiredContribution> {
        val problems = validate(scenario, horizonMonths)
        if (problems.isNotEmpty()) return GoalPlanOutcome.InvalidInputs(problems)
        if (isHorizonFundedWithoutContributions(scenario, horizonMonths)) {
            return GoalPlanOutcome.AlreadyFunded
        }

        val currency = scenario.target.amount.currency
        val moneyScale = moneyScale(scenario)
        val factor = GoalPlanEngine.monthlyGrowthFactor(scenario.assumptions)
        val nominalTarget = GoalPlanEngine.nominalTargetAt(scenario.target, horizonMonths)
        val principalAtHorizon = scenario.currentAllocation.amount
            .setScale(maxOf(GoalPlanEngine.INTERNAL_SCALE, moneyScale), RoundingMode.HALF_EVEN)
            .multiply(factor.pow(horizonMonths))
            .setScale(maxOf(GoalPlanEngine.INTERNAL_SCALE, moneyScale), RoundingMode.HALF_EVEN)
        val annuity = GoalPlanEngine.annuityAccumulator(factor, horizonMonths, scenario.contributionTiming)
        val rawContribution = nominalTarget
            .subtract(principalAtHorizon)
            .divide(annuity, maxOf(GoalPlanEngine.INTERNAL_SCALE, moneyScale), RoundingMode.HALF_EVEN)
        val contribution = verifiedContribution(
            rawContribution = rawContribution,
            currentAllocation = scenario.currentAllocation.amount,
            nominalTarget = nominalTarget,
            growthFactor = factor,
            months = horizonMonths,
            timing = scenario.contributionTiming,
            currency = currency,
            moneyScale = moneyScale,
        )
        return GoalPlanOutcome.Available(
            RequiredContribution(
                method = METHOD,
                horizonMonths = horizonMonths,
                monthlyContribution = contribution,
                nominalTargetAtHorizon = money(nominalTarget, currency, moneyScale),
            ),
        )
    }

    /**
     * First month at which the balance meets the (possibly inflating) nominal target,
     * with the calendar month computed from the caller-provided [startMonth] — the
     * solver owns no clock and never asks for "now".
     */
    fun requiredMonthsToDate(
        scenario: GoalScenario,
        startMonth: YearMonth,
    ): GoalPlanOutcome<RequiredMonths> {
        val problems = validate(scenario, horizonMonths = null)
        if (problems.isNotEmpty()) return GoalPlanOutcome.InvalidInputs(problems)
        if (isAlreadyFunded(scenario)) return GoalPlanOutcome.AlreadyFunded

        val currency = scenario.target.amount.currency
        val moneyScale = moneyScale(scenario)
        val factor = GoalPlanEngine.monthlyGrowthFactor(scenario.assumptions)
        val contribution = scenario.monthlyContribution.amount
            .setScale(maxOf(GoalPlanEngine.INTERNAL_SCALE, moneyScale), RoundingMode.HALF_EVEN)
        var balance = scenario.currentAllocation.amount
            .setScale(maxOf(GoalPlanEngine.INTERNAL_SCALE, moneyScale), RoundingMode.HALF_EVEN)
        for (month in 1..GoalPlanEngine.TECHNICAL_HORIZON_LIMIT_MONTHS) {
            balance = GoalPlanEngine.stepBalance(balance, contribution, factor, scenario.contributionTiming)
            val nominalTarget = GoalPlanEngine.nominalTargetAt(scenario.target, month)
            if (balance.compareTo(nominalTarget) >= 0) {
                return GoalPlanOutcome.Available(
                    RequiredMonths(
                        method = METHOD,
                        months = month,
                        reachMonth = startMonth.plusMonths(month.toLong()),
                        nominalTargetAtReach = money(nominalTarget, currency, moneyScale),
                    ),
                )
            }
        }
        return if (contribution.signum() == 0 && factor <= BigDecimal.ONE) {
            GoalPlanOutcome.NotReachable(
                reason = GoalNotReachableReason.NEVER_REACHES,
                detail = "balance is non-increasing and contributions are zero; the target can never be reached",
            )
        } else {
            GoalPlanOutcome.NotReachable(
                reason = GoalNotReachableReason.BEYOND_TECHNICAL_HORIZON,
                detail = "still short after the technical scan limit of " +
                    "$TECHNICAL_HORIZON_LIMIT_MONTHS months; no conclusion is drawn beyond that window",
            )
        }
    }

    /**
     * Nominal APR (percent) that funds the target at [horizonMonths], found by bounded
     * bisection and returned at the supported 4-decimal precision only after the real
     * model verifies it (bumping by 0.0001pp while short), so the returned rate can
     * leave a small verified excess — never a shortfall.
     *
     * The returned rate is GROSS (WM028-03 F3B): the declared fee is netted inside the
     * model during the search — the candidate monthly factor for a gross APR `a` is
     * exactly `growthFactorFromAprPercent(a − fee)`, i.e. 1 + (a − fee)/1200, the
     * factor `FixedNominalApr(a, fee)` projects with — and the returned rate is
     * verified by re-projecting that full declared-fee model, never a fee-free twin.
     * The guarantee: re-projecting `FixedNominalApr(returnedRate, declaredFee)` meets
     * the horizon target. [GoalAssumptions.CashOnly] is solved as the declared zero-fee
     * counterfactual: the fixed APR with no fee that would fund the horizon.
     *
     * The solver searches gross APR in the declared −99.99%..+1000% bounds. Where the
     * declared fee would make the monthly growth factor non-positive, the lower bound
     * is clamped up to the smallest gross rate with positive growth so the bisection
     * stays monotone; if even the upper bound leaves no positive-growth rate, or cannot
     * reach the target, the outcome is `NotReachable(BEYOND_SOLVER_RATE_BOUNDS)` —
     * never an extrapolated rate. When even the (clamped) floor reaches the target, the
     * floor itself is the conservative sufficient answer: the need is below the bounds,
     * not unreachable. `AlreadyFunded` is reported only when the goal is met today AND
     * the declared model's no-contribution horizon projection already covers the
     * horizon target — never from today's allocation alone.
     */
    fun requiredNominalApr(
        scenario: GoalScenario,
        horizonMonths: Int,
    ): GoalPlanOutcome<RequiredRate> {
        val problems = validate(scenario, horizonMonths)
        if (problems.isNotEmpty()) return GoalPlanOutcome.InvalidInputs(problems)
        if (isAlreadyFunded(scenario) && isHorizonFundedWithoutContributions(scenario, horizonMonths)) {
            return GoalPlanOutcome.AlreadyFunded
        }

        val currency = scenario.target.amount.currency
        val moneyScale = moneyScale(scenario)
        val contribution = scenario.monthlyContribution.amount
        val nominalTarget = GoalPlanEngine.nominalTargetAt(scenario.target, horizonMonths)
        val timing = scenario.contributionTiming
        val fee = when (val assumptions = scenario.assumptions) {
            GoalAssumptions.CashOnly -> BigDecimal.ZERO
            is GoalAssumptions.FixedNominalApr -> assumptions.annualFeePercent
        }

        // Model factor of FixedNominalApr(gross, fee): 1 + (gross − fee)/1200 at
        // INTERNAL_SCALE — arithmetically identical to the netMonthlyAprRate path, so a
        // solved gross rate round-trips through the real declared-fee model exactly.
        fun factorFor(grossAprPercent: BigDecimal): BigDecimal =
            GoalPlanEngine.growthFactorFromAprPercent(grossAprPercent.subtract(fee))

        val highApr = GoalPlanEngine.SOLVER_MAX_NOMINAL_APR_PERCENT
        if (factorFor(highApr).signum() <= 0) {
            return GoalPlanOutcome.NotReachable(
                reason = GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS,
                detail = "declared fee $fee% leaves no positive-growth gross APR within the solver " +
                    "bounds ${GoalPlanEngine.SOLVER_MIN_NOMINAL_APR_PERCENT}..$highApr% APR: netting " +
                    "the fee keeps the monthly growth factor non-positive at every gross rate",
            )
        }
        // Clamp the floor up to the smallest gross rate with positive monthly growth so
        // the bisection bracket never contains a non-positive factor (monotonicity).
        val lowApr = GoalPlanEngine.SOLVER_MIN_NOMINAL_APR_PERCENT
            .max(fee.subtract(BigDecimal(1200)).add(RATE_STEP))
            .min(highApr)
        val balanceAtLow = GoalPlanEngine.projectBalance(
            scenario.currentAllocation.amount, contribution, factorFor(lowApr), horizonMonths, timing,
        )
        if (balanceAtLow.compareTo(nominalTarget) >= 0) {
            // The target is met even at the admissible floor rate (balanceAtLow IS the
            // projection at the floor), so the floor itself is the conservative
            // sufficient gross rate: genuinely reachable, reported as Available.
            return GoalPlanOutcome.Available(
                RequiredRate(
                    method = METHOD,
                    horizonMonths = horizonMonths,
                    nominalAprPercent = lowApr.setScale(RATE_OUTPUT_SCALE, RoundingMode.HALF_EVEN),
                    nominalTargetAtHorizon = money(nominalTarget, currency, moneyScale),
                ),
            )
        }
        val balanceAtHigh = GoalPlanEngine.projectBalance(
            scenario.currentAllocation.amount, contribution, factorFor(highApr), horizonMonths, timing,
        )
        if (balanceAtHigh.compareTo(nominalTarget) < 0) {
            return GoalPlanOutcome.NotReachable(
                reason = GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS,
                detail = "required gross nominal APR exceeds the solver upper bound " +
                    "$highApr% APR with the declared fee $fee%",
            )
        }
        val factor = GoalPlanEngine.bisectGrowthFactor(
            currentAllocation = scenario.currentAllocation.amount,
            monthlyContribution = contribution,
            nominalTarget = nominalTarget,
            months = horizonMonths,
            timing = timing,
            growthFactorLow = factorFor(lowApr),
            growthFactorHigh = factorFor(highApr),
        )
        // Convert the solved factor back to a GROSS rate (fee added back) and verify at
        // the returned precision: re-feed the rate through the full declared-fee model
        // and bump by 0.0001pp while the projection is still short.
        var apr = GoalPlanEngine.aprPercentFromGrowthFactor(factor).add(fee)
        var bumps = 0
        while (apr.compareTo(highApr) <= 0) {
            val projected = GoalPlanEngine.projectBalance(
                scenario.currentAllocation.amount,
                contribution,
                factorFor(apr),
                horizonMonths,
                timing,
            )
            if (projected.compareTo(nominalTarget) >= 0) {
                return GoalPlanOutcome.Available(
                    RequiredRate(
                        method = METHOD,
                        horizonMonths = horizonMonths,
                        nominalAprPercent = apr,
                        nominalTargetAtHorizon = money(nominalTarget, currency, moneyScale),
                    ),
                )
            }
            apr = apr.add(RATE_STEP)
            bumps++
            if (bumps > VERIFY_BUMP_LIMIT) break
        }
        return if (apr.compareTo(highApr) > 0) {
            GoalPlanOutcome.NotReachable(
                reason = GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS,
                detail = "the required gross nominal APR sits within a rounding step of the solver upper " +
                    "bound $highApr% APR with the declared fee $fee%; no gross APR at the supported " +
                    "4-decimal precision within the solver bounds reaches the target",
            )
        } else {
            throw GoalPlanSolverInvariantException(
                "rate verification failed: $VERIFY_BUMP_LIMIT bumps of $RATE_STEP still do not fund " +
                    "the nominal target at horizon $horizonMonths — solver invariant violation, " +
                    "not an input problem",
            )
        }
    }

    /**
     * Today's nominal target is covered by the current allocation. Current-day,
     * earliest-time semantics — [requiredMonthsToDate] only, where being funded at
     * month 0 is a valid answer. Fixed-horizon solves use
     * [isHorizonFundedWithoutContributions] instead (WM028-03 F3B).
     */
    private fun isAlreadyFunded(scenario: GoalScenario): Boolean =
        GoalPlanEngine.nominalTargetAt(scenario.target, 0)
            .compareTo(scenario.currentAllocation.amount) <= 0

    /**
     * Horizon-aware funding test (WM028-03 F3B): the declared model, projected from
     * the current allocation with NO further contributions, covers the nominal target
     * at [horizonMonths]. Deliberately not a today comparison: a goal met at today's
     * prices can still fall short at the horizon under inflation or negative net growth.
     */
    private fun isHorizonFundedWithoutContributions(
        scenario: GoalScenario,
        horizonMonths: Int,
    ): Boolean = GoalPlanEngine.projectBalance(
        currentAllocation = scenario.currentAllocation.amount,
        monthlyContribution = BigDecimal.ZERO,
        growthFactor = GoalPlanEngine.monthlyGrowthFactor(scenario.assumptions),
        months = horizonMonths,
        timing = scenario.contributionTiming,
    ).compareTo(GoalPlanEngine.nominalTargetAt(scenario.target, horizonMonths)) >= 0

    /**
     * Collects every violated input contract. No fast-fail: callers see all problems
     * at once. Never throws — invalid inputs are data, not crashes.
     */
    private fun validate(scenario: GoalScenario, horizonMonths: Int?): List<GoalInputProblem> {
        val problems = mutableListOf<GoalInputProblem>()
        if (scenario.name.isBlank()) {
            problems += GoalInputProblem(GoalInputProblemCode.BLANK_SCENARIO_NAME, "scenario name must not be blank")
        }
        val currency = scenario.target.amount.currency
        if (scenario.currentAllocation.currency != currency || scenario.monthlyContribution.currency != currency) {
            problems += GoalInputProblem(
                GoalInputProblemCode.CURRENCY_MISMATCH,
                "target, current allocation and contribution must share one currency " +
                    "(got ${scenario.target.amount.currency}, ${scenario.currentAllocation.currency}, " +
                    "${scenario.monthlyContribution.currency})",
            )
        }
        if (scenario.target.amount.amount.signum() <= 0) {
            problems += GoalInputProblem(GoalInputProblemCode.TARGET_NOT_POSITIVE, "target amount must be positive")
        }
        if (scenario.currentAllocation.amount.signum() < 0) {
            problems += GoalInputProblem(
                GoalInputProblemCode.NEGATIVE_CURRENT_ALLOCATION,
                "current allocation must not be negative",
            )
        }
        if (scenario.monthlyContribution.amount.signum() < 0) {
            problems += GoalInputProblem(
                GoalInputProblemCode.NEGATIVE_MONTHLY_CONTRIBUTION,
                "monthly contribution must not be negative",
            )
        }
        if (horizonMonths != null && horizonMonths !in 1..TECHNICAL_HORIZON_LIMIT_MONTHS) {
            problems += GoalInputProblem(
                GoalInputProblemCode.HORIZON_OUT_OF_TECHNICAL_BOUNDS,
                "horizon must be in 1..$TECHNICAL_HORIZON_LIMIT_MONTHS months " +
                    "(technical model bound, not a financial judgment), got $horizonMonths",
            )
        }
        when (val assumptions = scenario.assumptions) {
            is GoalAssumptions.CashOnly -> Unit
            is GoalAssumptions.FixedNominalApr -> {
                if (assumptions.annualFeePercent.signum() < 0) {
                    problems += GoalInputProblem(GoalInputProblemCode.NEGATIVE_FEE, "annual fee percent must not be negative")
                }
                if (GoalPlanEngine.netMonthlyAprRate(assumptions) <= BigDecimal("-1")) {
                    problems += GoalInputProblem(
                        GoalInputProblemCode.APR_MONTHLY_RATE_OUT_OF_RANGE,
                        "net monthly rate must stay above -100%; got APR ${assumptions.annualAprPercent}% " +
                            "with fee ${assumptions.annualFeePercent}%",
                    )
                }
            }
        }
        when (val target = scenario.target) {
            is GoalTarget.TodayPrice -> {
                if (GoalPlanEngine.monthlyInflationRate(target.annualInflationPercent) <= BigDecimal("-1")) {
                    problems += GoalInputProblem(
                        GoalInputProblemCode.INFLATION_MONTHLY_RATE_OUT_OF_RANGE,
                        "monthly inflation must stay above -100%; got ${target.annualInflationPercent}% annually",
                    )
                }
            }
            is GoalTarget.Nominal -> Unit
        }
        return problems
    }

    private fun moneyScale(scenario: GoalScenario): Int = WealthMoneyPrecision.scale(
        scenario.target.amount.currency,
        scenario.target.amount.amount,
        scenario.currentAllocation.amount,
        scenario.monthlyContribution.amount,
    )

    private fun money(amount: BigDecimal, currency: String, moneyScale: Int): Money =
        Money.of(amount.setScale(moneyScale, RoundingMode.HALF_EVEN), currency)

    /**
     * Smallest scenario-money-scale contribution whose forward projection actually meets
     * the nominal target: starts from the raw solve rounded conservatively UP (CEILING),
     * verifies with the real model at the candidate, and bumps by one money quantum while short.
     * Arithmetic bounds the bumps (the model is monotone in the contribution with slope
     * positive per money quantum), so the bounded loop cannot spin: exhausting it is a
     * solver invariant violation, reported as a typed failure — never an infinite bump.
     */
    private fun verifiedContribution(
        rawContribution: BigDecimal,
        currentAllocation: BigDecimal,
        nominalTarget: BigDecimal,
        growthFactor: BigDecimal,
        months: Int,
        timing: ContributionTiming,
        currency: String,
        moneyScale: Int,
    ): Money {
        val quantum = BigDecimal.ONE.movePointLeft(moneyScale)
        var candidate = if (rawContribution.signum() <= 0) {
            BigDecimal.ZERO
        } else {
            rawContribution.setScale(moneyScale, RoundingMode.CEILING)
        }
        repeat(VERIFY_BUMP_LIMIT + 1) {
            val projected = GoalPlanEngine.projectBalance(
                currentAllocation,
                candidate,
                growthFactor,
                months,
                timing,
            )
            if (projected.compareTo(nominalTarget) >= 0) return money(candidate, currency, moneyScale)
            candidate = candidate.add(quantum)
        }
        throw GoalPlanSolverInvariantException(
            "contribution verification failed: $VERIFY_BUMP_LIMIT money-quantum bumps still do not fund the " +
                "nominal target at horizon $months — solver invariant violation, not an input problem",
        )
    }

    private val RATE_STEP = BigDecimal("0.0001")
    private const val VERIFY_BUMP_LIMIT = 4
    private const val RATE_OUTPUT_SCALE = 4
}

/**
 * Raised only when a bounded solver verification loop exhausts its bumps — an
 * arithmetic invariant break inside the solver, never an input problem.
 */
class GoalPlanSolverInvariantException(message: String) : IllegalStateException(message)
