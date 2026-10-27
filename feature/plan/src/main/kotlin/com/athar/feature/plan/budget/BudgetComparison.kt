package com.athar.feature.plan.budget

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isReconciliation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * WM032 — pure derivation of one budget panel (one period's budget-vs-actual view)
 * plus the read-only supporting-record (evidence) drilldown.
 *
 * Money-integrity rules (docs/specs/WM032-budget-evidence-and-safe-targets.md):
 *  - ONE scope for every aggregate — totals, category rows, remainder and evidence all use
 *    exactly the same records: CONFIRMED (`status == CONFIRMED`), operating expense
 *    (`type == EXPENSE`, structurally excluding TRANSFER/INCOME), not a reconciliation
 *    (`!isReconciliation()`), inside `[start, endInclusive]`, AND denominated in the
 *    panel's display currency.
 *  - No recent-N cap: every in-scope record participates, and evidence returns them all.
 *  - Reconciliation invariant: totalActual == Σ(rows.actual) + remainder, exactly
 *    (BigDecimal addition is exact). The remainder bucket holds records whose categoryId
 *    is null OR unknown to the current category table, so the headline equals the literal
 *    sum of the same records the rows show.
 *  - No 1:1 currency relabelling: foreign-currency records are excluded outright, never
 *    summed into the display currency, and never inflate a remainder. No recent-N cap.
 *    Evidence keeps each record's literal amount + currency.
 *  - Targets scale only within their literal currency; a foreign-currency target is
 *    unavailable (null) for the display-currency comparison. Actuals are never scaled.
 */

/** Which comparison panel a request refers to. */
enum class ComparePanel { A, B }

/** Evidence bucket: the panel total, the uncategorized/unknown remainder, or one category. */
sealed interface BudgetBucket {
    data object Total : BudgetBucket
    data object Remainder : BudgetBucket
    data class Category(val id: String) : BudgetBucket
}

data class BudgetEvidenceRequest(
    val panel: ComparePanel,
    val bucket: BudgetBucket,
)

data class BudgetPanelResult(
    val start: LocalDate,
    /** Inclusive last day of the panel (the repo `Period` end is exclusive; UI shows inclusive). */
    val endInclusive: LocalDate,
    val targetMultiplier: Double,
    val rows: List<com.athar.feature.plan.BudgetRow>,
    /** Display-currency actuals whose category is null or unknown to the category table. */
    val remainder: Money,
    val remainderCount: Int,
    /** Exact sum of every in-scope display-currency operating record. */
    val totalActual: Money,
    val totalTarget: Money,
    val currency: String,
) {
    /** Σ(rows.actual) + remainder — structurally equal to [totalActual]; kept for tests/QA. */
    val reconciledTotal: Money =
        Money.sumAmounts(rows.map { it.actual } + remainder, currency)
}

data class BudgetComparison(
    val panelA: BudgetPanelResult,
    val panelB: BudgetPanelResult,
)

data class BudgetEvidence(
    val request: BudgetEvidenceRequest,
    val start: LocalDate,
    val endInclusive: LocalDate,
    val currency: String,
    val total: Money,
    /** Every in-scope record — literal amounts/currencies preserved, no cap. */
    val records: List<Transaction>,
)

/** The single record scope every budget aggregate and every evidence list is built from. */
private fun budgetScope(
    txs: List<Transaction>,
    start: LocalDate,
    endInclusive: LocalDate,
    currency: String,
): List<Transaction> = txs.filter {
    it.type == TxType.EXPENSE &&
        it.status == TxStatus.CONFIRMED &&
        !it.isReconciliation() &&
        it.amount.currency == currency &&
        it.date >= start &&
        it.date <= endInclusive
}

fun deriveBudgetPanel(
    start: LocalDate,
    endInclusive: LocalDate,
    targetMultiplier: Double,
    cats: List<Category>,
    txs: List<Transaction>,
    currency: String,
): BudgetPanelResult {
    val operating = budgetScope(txs, start, endInclusive, currency)
    val knownIds = cats.mapTo(HashSet()) { it.id }
    val byCategory = operating.groupBy { it.categoryId?.takeIf { id -> id in knownIds } }

    val multiplierBd = BigDecimal.valueOf(targetMultiplier).setScale(4, RoundingMode.HALF_EVEN)
    val rows = cats.map { cat ->
        val actual = Money.sumAmounts(
            byCategory[cat.id].orEmpty().map { it.amount },
            currency,
        )
        val scaled = cat.monthlyTarget
            ?.takeIf { mt -> mt.currency == currency }
            ?.let { mt ->
                Money.of(mt.amount.multiply(multiplierBd).setScale(BudgetTargetInput.fractionDigits(mt.currency), RoundingMode.HALF_EVEN), mt.currency)
            }
        com.athar.feature.plan.BudgetRow(category = cat, actual = actual, target = scaled)
    }

    val remainderList = byCategory[null].orEmpty()
    val remainder = Money.sumAmounts(remainderList.map { it.amount }, currency)
    val totalActual = Money.sumAmounts(operating.map { it.amount }, currency)
    val totalTarget = Money.sumAmounts(rows.mapNotNull { it.target }, currency)

    return BudgetPanelResult(
        start = start,
        endInclusive = endInclusive,
        targetMultiplier = targetMultiplier,
        rows = rows,
        remainder = remainder,
        remainderCount = remainderList.size,
        totalActual = totalActual,
        totalTarget = totalTarget,
        currency = currency,
    )
}

/**
 * Read-only supporting records for [request] within the panel's exact scope
 * (same confirmed/expense/non-reconciliation/date/currency filter as the panel totals).
* Returns null only when the request points at panel B while no comparison is active.
 */
fun budgetEvidence(
    panelA: BudgetPanelResult,
    panelB: BudgetPanelResult?,
    txsA: List<Transaction>,
    txsB: List<Transaction>,
    cats: List<Category>,
    request: BudgetEvidenceRequest,
): BudgetEvidence? {
    val panel = when (request.panel) {
        ComparePanel.A -> panelA
        ComparePanel.B -> panelB ?: return null
    }
    val scopeTxs = when (request.panel) {
        ComparePanel.A -> txsA
        ComparePanel.B -> txsB
    }
    val knownIds = cats.mapTo(HashSet()) { it.id }
    val start = panel.start
    val endInclusive = panel.endInclusive
    val inScope = budgetScope(scopeTxs, start, endInclusive, panel.currency)
    val records: List<Transaction> = when (val bucket = request.bucket) {
        BudgetBucket.Total -> inScope
        BudgetBucket.Remainder -> inScope.filter { it.categoryId?.let(knownIds::contains) != true }
        is BudgetBucket.Category -> inScope.filter { it.categoryId == bucket.id }
    }
    val total = when (request.bucket) {
        BudgetBucket.Total -> panel.totalActual
        BudgetBucket.Remainder -> panel.remainder
        is BudgetBucket.Category ->
            Money.sumAmounts(records.map { it.amount }, panel.currency)
    }
    return BudgetEvidence(
        request = request,
        start = start,
        endInclusive = endInclusive,
        currency = panel.currency,
        total = total,
        records = records,
    )
}

/** Shared scaling: identical inclusive bounds always yield identical targets. */
internal fun budgetTargetMonths(start: LocalDate, endInclusive: LocalDate): Double {
    require(start <= endInclusive)
    val end = endInclusive.plus(DatePeriod(days = 1))
    if (start.dayOfMonth == 1 && end.dayOfMonth == 1) {
        return ((end.year - start.year) * 12 + end.monthNumber - start.monthNumber).toDouble()
    }
    return (end.toEpochDays().toLong() - start.toEpochDays().toLong()) / 30.4375
}
