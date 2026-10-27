package com.athar.feature.plan

import androidx.compose.runtime.Immutable
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.feature.plan.budget.BudgetComparison
import com.athar.feature.plan.budget.BudgetEvidence
import com.athar.feature.plan.budget.BudgetEvidenceRequest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Period selector for the Plan screen. Mirrors the Trends one but drops the
 * "vs previous" key (irrelevant for budget vs actual) and keeps the month / 3-month /
 * year / custom buckets.
 */
enum class PlanPeriodKey { MONTH, MONTHS_3, YEAR, CUSTOM }

@Immutable
data class PlanState(
    val month: YearMonth,
    val periodKey: PlanPeriodKey,
    val period: Period?,
    val customStart: LocalDate?,
    val customEnd: LocalDate?,
    /** Target multiplier — when period spans N months the per-category monthly target is scaled to N×. */
    val targetMultiplier: Double,
    val rows: ImmutableList<BudgetRow>,
    val totalTarget: Money,
    val totalActual: Money,
    /** Display-currency actuals whose category is null or unknown (WM032 remainder). */
    val remainder: Money = Money.zero(),
    val remainderCount: Int = 0,
    val isLoading: Boolean,
    // WM032 — optional two-independent-period comparison + evidence drilldown.
    val compareEnabled: Boolean = false,
    /** Explicit independent bounds of comparison panel B (inclusive). */
    val compareBStart: LocalDate? = null,
    val compareBEnd: LocalDate? = null,
    val comparison: BudgetComparison? = null,
    val evidence: BudgetEvidence? = null,
    val targetSaveStatus: TargetSaveStatus = TargetSaveStatus.IDLE,
) {
    companion object {
        fun empty(month: YearMonth): PlanState = PlanState(
            month = month,
            periodKey = PlanPeriodKey.MONTH,
            period = null,
            customStart = null,
            customEnd = null,
            targetMultiplier = 1.0,
            rows = persistentListOf(),
            totalTarget = Money.zero(),
            totalActual = Money.zero(),
            isLoading = true,
        )
    }
}

data class BudgetRow(
    val category: Category,
    val actual: Money,
    val target: Money?,
) {
    val variance: Money? = target?.let { actual - it }
    val isOver: Boolean = variance?.isPositive() == true
    val isUnder: Boolean = variance?.isNegative() == true

    val limit: LimitState = when {
        target == null || target.isZero() -> LimitState.None
        actual.amount > target.amount -> LimitState.Over
        // WM032: exact cross-multiplied thresholds. `actual ≥ 90% × target` is decided by
        // `actual × 100 ≥ 90 × target` in BigDecimal — never by a Double ratio that rounds
        // 89 999 999 999 999 999.99 / 100 000 000 000 000 000 up to exactly 0.9.
        actual.amount.multiply(HUNDRED).compareTo(TIGHT_PCT.multiply(target.amount)) >= 0 -> LimitState.Tight
        actual.amount.multiply(HUNDRED).compareTo(WATCH_PCT.multiply(target.amount)) >= 0 -> LimitState.Watch
        else -> LimitState.Healthy
    }
}

enum class LimitState { None, Healthy, Watch, Tight, Over }
enum class TargetSaveStatus { IDLE, SAVING, SAVED, FAILED, UNSUPPORTED_PRECISION }

/** Exact BigDecimal thresholds — a Double drawing fraction never decides a monetary limit. */
private val HUNDRED = BigDecimal.valueOf(100)
private val TIGHT_PCT = BigDecimal.valueOf(90)
private val WATCH_PCT = BigDecimal.valueOf(70)

sealed interface PlanEvent {
    data class OpenTargetEditor(val categoryId: String) : PlanEvent
    data class SaveTarget(val categoryId: String, val targetMinor: Long?, val currency: String? = null) : PlanEvent
    data object DismissTargetEditor : PlanEvent
    data class SelectPeriod(val key: PlanPeriodKey) : PlanEvent
    data class SelectCustomRange(val start: LocalDate, val end: LocalDate) : PlanEvent
    // WM032 — optional comparison + read-only evidence.
    data class SetCompareEnabled(val enabled: Boolean) : PlanEvent
    data class SetCompareRange(val start: LocalDate, val end: LocalDate) : PlanEvent
    data class OpenEvidence(val request: BudgetEvidenceRequest) : PlanEvent
    data object DismissEvidence : PlanEvent
}
