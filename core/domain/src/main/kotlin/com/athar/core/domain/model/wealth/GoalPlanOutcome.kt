package com.athar.core.domain.model.wealth

import com.athar.core.common.money.Money
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Outcome of a goal-solver operation (WM028-03). Every operation reports a typed
 * outcome — the solver never fabricates a zero or a default when a value is unknown,
 * unreachable, or the inputs are invalid.
 */
sealed interface GoalPlanOutcome<out T> {

    /** Solved; [value] carries the numbers and the method/version that produced them. */
    data class Available<T>(val value: T) : GoalPlanOutcome<T>

    /**
     * Today's nominal target is already covered by the current allocation, so no
     * saving (or rate) is required. Distinct from a solved zero: it means the goal
     * needs nothing at month 0, not that a search returned zero.
     */
    data object AlreadyFunded : GoalPlanOutcome<Nothing>

    /**
     * The target is not reachable within the declared model bounds. [reason] says
     * which bound; [detail] explains it in stable, testable wording.
     */
    data class NotReachable(
        val reason: GoalNotReachableReason,
        val detail: String,
    ) : GoalPlanOutcome<Nothing>

    /** Inputs did not satisfy the model contract. Never coerced to a numeric result. */
    data class InvalidInputs(val problems: List<GoalInputProblem>) : GoalPlanOutcome<Nothing>
}

/**
 * Why a target is not reachable. The horizon reason is explicitly a TECHNICAL scan
 * limit of the model (1200 months = 100 years), not a judgment about financial
 * feasibility, and is independent of WishlistCalc's 36-month presentation cap.
 */
enum class GoalNotReachableReason {

    /** Still short after the full 1200-month technical scan window. */
    BEYOND_TECHNICAL_HORIZON,

    /** Provable never: non-positive growth and zero contribution cannot close the gap. */
    NEVER_REACHES,

    /** Required nominal APR falls outside the declared solver bounds. */
    BEYOND_SOLVER_RATE_BOUNDS,
}

/** Machine-readable code for an invalid input. */
enum class GoalInputProblemCode {
    BLANK_SCENARIO_NAME,
    CURRENCY_MISMATCH,
    TARGET_NOT_POSITIVE,
    NEGATIVE_CURRENT_ALLOCATION,
    NEGATIVE_MONTHLY_CONTRIBUTION,
    HORIZON_OUT_OF_TECHNICAL_BOUNDS,
    APR_MONTHLY_RATE_OUT_OF_RANGE,
    NEGATIVE_FEE,
    INFLATION_MONTHLY_RATE_OUT_OF_RANGE,
}

/** One invalid input finding: a stable code plus human-readable detail. */
data class GoalInputProblem(
    val code: GoalInputProblemCode,
    val detail: String,
)

/** Projection of the balance at a stated horizon. */
data class GoalProjection(
    val method: String,
    val horizonMonths: Int,
    val futureAmount: Money,
    val totalContributions: Money,
    val growthAmount: Money,
    val nominalTargetAtHorizon: Money,
)

/**
 * The smallest cent-granular monthly contribution that funds the nominal target at
 * the stated horizon. Verified by projecting the forward model at the returned value,
 * so cent rounding can leave a small verified excess — never a shortfall.
 */
data class RequiredContribution(
    val method: String,
    val horizonMonths: Int,
    val monthlyContribution: Money,
    val nominalTargetAtHorizon: Money,
)

/** First month index at which the balance meets the (possibly inflating) nominal target. */
data class RequiredMonths(
    val method: String,
    val months: Int,
    val reachMonth: YearMonth,
    val nominalTargetAtReach: Money,
)

/**
 * Smallest nominal APR (percent) at the supported 4-decimal precision that funds the
 * nominal target when the forward model is projected at the returned rate; may leave
 * a small verified excess, never a shortfall. The solver floor rate is returned when
 * contributions alone already fund the target.
 */
data class RequiredRate(
    val method: String,
    val horizonMonths: Int,
    val nominalAprPercent: BigDecimal,
    val nominalTargetAtHorizon: Money,
)
