package com.athar.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.isReconciliation
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.feature.plan.budget.BudgetComparison
import com.athar.feature.plan.budget.BudgetEvidenceRequest
import com.athar.feature.plan.budget.BudgetTargetInput
import com.athar.feature.plan.budget.BudgetPanelResult
import com.athar.feature.plan.budget.budgetEvidence
import com.athar.feature.plan.budget.budgetTargetMonths
import com.athar.feature.plan.budget.deriveBudgetPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedKey = MutableStateFlow(PlanPeriodKey.MONTH)
    private val customRange = MutableStateFlow<Pair<LocalDate, LocalDate>?>(null)

    private val compareEnabled = MutableStateFlow(false)
    private val compareRange = MutableStateFlow<Pair<LocalDate, LocalDate>?>(null)
    private val evidenceRequest = MutableStateFlow<BudgetEvidenceRequest?>(null)
    private val targetSave = MutableStateFlow(TargetSaveStatus.IDLE)

    private data class Inputs(
        val key: PlanPeriodKey,
        val range: Pair<LocalDate, LocalDate>?,
        val compareEnabled: Boolean,
        val compareRange: Pair<LocalDate, LocalDate>?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<PlanState> =
        combine(selectedKey, customRange, compareEnabled, compareRange) { key, range, cmp, crange ->
            Inputs(key, range, cmp, crange)
        }
            .flatMapLatest { p ->
                val period = period(p.key, p.range)
                val bBounds = if (p.compareEnabled) compareBounds(p.compareRange, period.start) else null
                val bPeriod: Period? = bBounds?.let { (s, e) ->
                    Period.Custom(s, e.plus(DatePeriod(days = 1)))
                }
                val txsB: Flow<List<Transaction>> =
                    if (bPeriod != null) transactions.observeByPeriod(bPeriod, TxStatus.CONFIRMED)
                    else flowOf(emptyList())
                combine(
                    categories.observeAll(kind = CategoryKind.EXPENSE),
                    transactions.observeByPeriod(period, TxStatus.CONFIRMED),
                    txsB,
                    prefs.displayCurrency(),
                    evidenceRequest,
                ) { cats, txsA, b, currency, ev ->
                    derive(p, period, bBounds, cats, txsA, b, currency, ev)
                }
            }
            .combine(targetSave) { base, save -> base.copy(targetSaveStatus = save) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanState.empty(currentMonth()))

    fun onEvent(event: PlanEvent) {
        when (event) {
            is PlanEvent.SaveTarget -> saveTarget(event.categoryId, event.targetMinor, event.currency)
            is PlanEvent.OpenTargetEditor, PlanEvent.DismissTargetEditor -> {
                if (targetSave.value != TargetSaveStatus.SAVING) targetSave.value = TargetSaveStatus.IDLE
            }
            is PlanEvent.SelectPeriod -> selectedKey.value = event.key
            is PlanEvent.SelectCustomRange -> if (validRange(event.start, event.end)) {
                customRange.value = event.start to event.end
                selectedKey.value = PlanPeriodKey.CUSTOM
            }
            is PlanEvent.SetCompareEnabled -> {
                if (event.enabled && compareRange.value == null) {
                    compareRange.value = compareBounds(null, period(selectedKey.value, customRange.value).start)
                }
                compareEnabled.value = event.enabled
                if (!event.enabled) evidenceRequest.value = null
            }
            is PlanEvent.SetCompareRange -> if (validRange(event.start, event.end)) {
                compareRange.value = event.start to event.end
            }
            is PlanEvent.OpenEvidence -> evidenceRequest.value = event.request
            PlanEvent.DismissEvidence -> evidenceRequest.value = null
        }
    }

    private fun validRange(start: LocalDate, end: LocalDate): Boolean =
        start <= end && runCatching { end.plus(DatePeriod(days = 1)) }.isSuccess

    private fun saveTarget(categoryId: String, targetMinor: Long?, enteredCurrency: String?) {
        if (targetSave.value == TargetSaveStatus.SAVING) return
        targetSave.value = TargetSaveStatus.SAVING
        viewModelScope.launch {
            try {
                val current = categories.get(categoryId)
                if (current == null || (targetMinor != null && targetMinor < 0)) {
                    targetSave.value = TargetSaveStatus.FAILED
                    return@launch
                }
                val currency = enteredCurrency ?: current.monthlyTarget?.currency ?: prefs.displayCurrency().first()
                val target = targetMinor?.let {
                    Money.of(BigDecimal.valueOf(it).movePointLeft(BudgetTargetInput.fractionDigits(currency)), currency)
                }
                // Existing CategoryMappers stores fixed two-decimal minor units. Refuse
                // an unrepresentable target instead of letting its toLong silently truncate.
                try {
                    target?.amount?.movePointRight(2)?.longValueExact()
                } catch (_: ArithmeticException) {
                    targetSave.value = TargetSaveStatus.UNSUPPORTED_PRECISION
                    return@launch
                }
                categories.upsert(current.copy(monthlyTarget = target))
                targetSave.value = TargetSaveStatus.SAVED
            } catch (cancelled: CancellationException) {
                targetSave.value = TargetSaveStatus.IDLE
                throw cancelled
            } catch (_: Exception) {
                targetSave.value = TargetSaveStatus.FAILED
            }
        }
    }

    private fun period(key: PlanPeriodKey, custom: Pair<LocalDate, LocalDate>?): Period {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val ym = YearMonth.of(now.year, now.monthNumber)
        return when (key) {
            PlanPeriodKey.MONTH -> Period.Month(ym)
            PlanPeriodKey.MONTHS_3 -> Period.Last(months = 3, endingAt = now.date)
            PlanPeriodKey.YEAR -> Period.Year(now.year)
            PlanPeriodKey.CUSTOM -> custom?.let { (s, e) -> Period.Custom(s, e.plus(DatePeriod(days = 1))) }
                ?: Period.Month(ym)
        }
    }

    private fun derive(
        inputs: Inputs,
        period: Period,
        bBounds: Pair<LocalDate, LocalDate>?,
        cats: List<Category>,
        txsA: List<Transaction>,
        txsB: List<Transaction>,
        currency: String,
        request: BudgetEvidenceRequest?,
    ): PlanState {
        val multiplier = budgetTargetMonths(period.start, period.endExclusive.minus(DatePeriod(days = 1)))
        // Panel A (the existing budget) flows through the same WM032 pure derivation as
        // the comparison panel: one confirmed/expense/non-reconciliation/date/currency
        // scope for every row, total and remainder.
        val panelA = deriveBudgetPanel(
            start = period.start,
            endInclusive = period.endExclusive.minus(DatePeriod(days = 1)),
            targetMultiplier = multiplier,
            cats = cats,
            txs = txsA,
            currency = currency,
        )
        val panelB = bBounds?.let { (s, e) ->
            deriveBudgetPanel(s, e, budgetTargetMonths(s, e), cats, txsB, currency)
        }
        return PlanState(
            month = currentMonth(),
            periodKey = inputs.key,
            period = period,
            customStart = inputs.range?.first,
            customEnd = inputs.range?.second,
            targetMultiplier = multiplier,
            rows = panelA.rows.toImmutableList(),
            totalTarget = panelA.totalTarget,
            totalActual = panelA.totalActual,
            remainder = panelA.remainder,
            remainderCount = panelA.remainderCount,
            isLoading = false,
            compareEnabled = inputs.compareEnabled,
            compareBStart = bBounds?.first,
            compareBEnd = bBounds?.second,
            comparison = panelB?.let { BudgetComparison(panelA, it) },
            evidence = request?.let { budgetEvidence(panelA, panelB, txsA, txsB, cats, it) },
        )
    }

    /**
     * Independent second period: explicit user bounds when set, otherwise the calendar
     * month immediately before panel A starts. The bounds persist until comparison is
     * switched off — closing evidence or cancelling the date picker never resets them.
     */
    private fun compareBounds(
        custom: Pair<LocalDate, LocalDate>?,
        aStart: LocalDate,
    ): Pair<LocalDate, LocalDate> {
        custom?.let { return it }
        val prevMonthEnd = aStart.minus(DatePeriod(days = 1))
        val prevMonthStart = LocalDate(prevMonthEnd.year, prevMonthEnd.monthNumber, 1)
        return prevMonthStart to prevMonthEnd
    }

    private fun currentMonth(): YearMonth {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return YearMonth.of(now.year, now.monthNumber)
    }
}
