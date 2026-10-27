package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.AssetClass
import java.math.BigDecimal
import java.math.RoundingMode

/** A per-class allocation goal: a single target fraction, or an acceptable range. */
sealed interface AllocationGoal {
    /** Target fraction in (0, 1]. */
    data class PointTarget(val fraction: BigDecimal) : AllocationGoal

    /** Acceptable band [minFraction, maxFraction], both within [0, 1], min ≤ max. */
    data class RangeTarget(val minFraction: BigDecimal, val maxFraction: BigDecimal) : AllocationGoal
}

/** Typed, self-explaining goal validation failures. */
sealed interface AllocationGoalError {
    /** All-point goal sets must sum to exactly 1 (100%). */
    data class TargetsDoNotSumToOne(val sum: BigDecimal) : AllocationGoalError

    /** A point target of zero or less. */
    data class NonPositiveTarget(val assetClass: AssetClass) : AllocationGoalError

    /** A point target above 100%. */
    data class InvalidPointTarget(val assetClass: AssetClass, val fraction: BigDecimal) : AllocationGoalError

    /** A range that is negative, inverted, or above 100%. */
    data class InvalidRange(val assetClass: AssetClass, val min: BigDecimal, val max: BigDecimal) : AllocationGoalError
}

enum class RangePosition { BELOW, WITHIN, ABOVE }

sealed interface GoalStatus {
    /** actual − target (8dp). */
    data class PointGap(val gapFraction: BigDecimal) : GoalStatus
    data class RangeStatus(val position: RangePosition) : GoalStatus
}

/**
 * Allocation measured from **actual priced market values** (holdings + portfolio cash).
 *
 * Unclassified holdings are priced and included in [totalMeasured] but reported
 * separately ([unknownClassValue]/[unknownSecurityIds]) — they are never silently
 * assigned to a class. When any holding is unpriced, [isPartial] is true and
 * [explanation] says so; fractions computed from a partial valuation may change once
 * prices exist.
 */
data class AllocationReport(
    val totalMeasured: Money,
    val isPartial: Boolean,
    val unpricedSecurityIds: List<String>,
    val classValues: Map<AssetClass, Money>,
    /** Actual fractions at 8dp HALF_EVEN over [totalMeasured]. */
    val fractions: Map<AssetClass, BigDecimal>,
    val unknownClassValue: Money?,
    val unknownSecurityIds: List<String>,
    val statuses: Map<AssetClass, GoalStatus>,
    val explanation: String,
)

/** Evaluates allocation vs targets from a completed [PortfolioAccounting]. */
object AllocationCalc {

    private const val FRACTION_SCALE = 8

    fun evaluate(
        accounting: PortfolioAccounting,
        goals: Map<AssetClass, AllocationGoal>,
    ): Outcome<AllocationReport, AllocationGoalError> {
        // Shape validation first, with the offending class named.
        for ((assetClass, goal) in goals) {
            when (goal) {
                is AllocationGoal.PointTarget -> when {
                    goal.fraction.signum() <= 0 -> return Outcome.Failure(
                        AllocationGoalError.NonPositiveTarget(assetClass),
                    )

                    goal.fraction > BigDecimal.ONE -> return Outcome.Failure(
                        AllocationGoalError.InvalidPointTarget(assetClass, goal.fraction),
                    )
                }

                is AllocationGoal.RangeTarget -> when {
                    goal.minFraction.signum() < 0 ||
                        goal.maxFraction.signum() < 0 ||
                        goal.minFraction > goal.maxFraction ||
                        goal.maxFraction > BigDecimal.ONE -> return Outcome.Failure(
                        AllocationGoalError.InvalidRange(assetClass, goal.minFraction, goal.maxFraction),
                    )
                }
            }
        }

        // All-point goal sets must sum to exactly one; sets containing range goals are
        // shape-validated only (a range has no single point to sum) — explained in the report.
        val pointTargets = goals.values.filterIsInstance<AllocationGoal.PointTarget>()
        if (goals.isNotEmpty() && pointTargets.size == goals.size) {
            val sum = pointTargets.fold(BigDecimal.ZERO) { acc, target -> acc.add(target.fraction) }
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                return Outcome.Failure(AllocationGoalError.TargetsDoNotSumToOne(sum))
            }
        }

        // Class values from actual priced market values; unknown classes kept separate.
        val base = accounting.baseCurrency
        val classValues = AssetClass.entries.associateWith { Money.zero(base) }.toMutableMap()
        val unknownSecurityIds = mutableListOf<String>()
        var unknownValue = BigDecimal.ZERO
        for (holding in accounting.valuation.holdings) {
            val marketValue = holding.marketValue ?: continue // unpriced: disclosed, not invented
            val assetClass = accounting.securities[holding.securityId]?.assetClass
            if (assetClass == null) {
                unknownSecurityIds += holding.securityId
                unknownValue = unknownValue.add(marketValue.amount)
            } else {
                val current = classValues.getValue(assetClass)
                classValues[assetClass] = Money.of(current.amount.add(marketValue.amount), base)
            }
        }
        classValues[AssetClass.CASH] = Money.of(
            classValues.getValue(AssetClass.CASH).amount.add(accounting.cash.amount),
            base,
        )

        val totalMeasured = Money.of(
            accounting.valuation.pricedHoldingsValue.amount.add(accounting.cash.amount),
            base,
        )
        val fractions: Map<AssetClass, BigDecimal> = if (totalMeasured.amount.signum() == 0) {
            AssetClass.entries.associateWith { BigDecimal.ZERO.setScale(FRACTION_SCALE) }
        } else {
            classValues.mapValues { it.value.amount.divide(totalMeasured.amount, FRACTION_SCALE, RoundingMode.HALF_EVEN) }
        }

        val statuses: Map<AssetClass, GoalStatus> = goals.mapValues { (assetClass, goal) ->
            val actual = fractions[assetClass] ?: BigDecimal.ZERO.setScale(FRACTION_SCALE)
            when (goal) {
                is AllocationGoal.PointTarget -> GoalStatus.PointGap(actual.subtract(goal.fraction))

                is AllocationGoal.RangeTarget -> GoalStatus.RangeStatus(
                    when {
                        actual < goal.minFraction -> RangePosition.BELOW
                        actual > goal.maxFraction -> RangePosition.ABOVE
                        else -> RangePosition.WITHIN
                    },
                )
            }
        }

        val notes = mutableListOf<String>()
        if (accounting.valuation.isPartial) {
            notes += "Partial valuation: no usable dated quote for " +
                accounting.valuation.unpricedSecurityIds.joinToString(", ") +
                "; fractions may change once priced."
        }
        if (unknownSecurityIds.isNotEmpty()) {
            notes += "Holdings without an asset class (" + unknownSecurityIds.joinToString(", ") +
                ") are included in the measured total but have no target."
        }
        if (goals.values.any { it is AllocationGoal.RangeTarget }) {
            notes += "Range goals are excluded from the sum-to-one validation."
        }

        return Outcome.Success(
            AllocationReport(
                totalMeasured = totalMeasured,
                isPartial = accounting.valuation.isPartial,
                unpricedSecurityIds = accounting.valuation.unpricedSecurityIds,
                classValues = classValues,
                fractions = fractions,
                unknownClassValue = if (unknownSecurityIds.isEmpty()) null else Money.of(unknownValue, base),
                unknownSecurityIds = unknownSecurityIds.toList(),
                statuses = statuses,
                explanation = notes.joinToString(" "),
            ),
        )
    }
}
