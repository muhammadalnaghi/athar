package com.athar.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.calc.GoalCalc
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isReconciliation
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.feature.plan.goals.GoalSavedDocCodec
import com.athar.feature.plan.goals.GoalSavedDocResult
import com.athar.feature.plan.goals.GoalScenarioDraft
import com.athar.feature.plan.goals.GoalScenarioUi
import com.athar.feature.plan.goals.solve
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
    private val savedPlans: SavedFinancialPlanRepository,
) : ViewModel() {

    /**
     * Test seam; production's injected constructor keeps the solver, the JSON codec and
     * all repository work off Main (project idiom, cf. MerchantCatalogRepositoryImpl).
     */
    internal constructor(
        transactions: TransactionRepository,
        accounts: AccountRepository,
        prefs: UserPreferencesRepository,
        clock: Clock,
        savedPlans: SavedFinancialPlanRepository,
        solveDispatcher: CoroutineDispatcher,
    ) : this(transactions, accounts, prefs, clock, savedPlans) {
        this.workDispatcher = solveDispatcher
    }

    /** Solver AND persistence/codec dispatcher: no JSON parse or repository call on Main. */
    private var workDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Transient what-if scenario: never persisted, retained with the ViewModel. */
    private val scenario = MutableStateFlow(GoalScenarioUi.initial())

    /** Title editor text and the latest fixed saved-goal notice. */
    private val savedControls = MutableStateFlow(SavedGoalControls())

    /**
     * Conditional saved-vs-current comparison snapshot; null when nothing is compared.
     * Discarded by any later edit/reset/load/currency change (see fencing below).
     */
    private val savedCompare = MutableStateFlow<SavedGoalCompareUi?>(null)

    /**
     * The saved document currently loaded into the scenario, with the revision captured
     * when it was loaded/created: updates and the implicit clear use exactly this
     * captured revision, so a document edited elsewhere in between refuses, never
     * overwrites.
     */
    private var activeSavedGoal: ActiveSavedGoal? = null

    /**
     * The literal display currency once preferences have actually loaded; null before
     * that. Solving and seeding never fall back to [GoalsState]'s default ("SAR").
     */
    private val boundCurrency = MutableStateFlow<String?>(null)

    /**
     * Main-confined generation of scenario computations. Every edit, reset, or currency
     * rebinding bumps it, so a late completion of a superseded solve commits nothing.
     */
    private var computeGeneration = 0L

    /**
     * Main-confined generation of the VISIBLE scenario session. Every draft edit,
     * reset, currency rebind, and applied load bumps it, so a stale async LOAD (whose
     * decoded payload may arrive after the user already kept typing) is recognized as
     * superseded and applies nothing.
     */
    private var draftGeneration = 0L

    /**
     * Monotonic token for explicit saved-goal requests (save/load). Only the NEWEST
     * request may publish notices or move the active-session pointer, so a save that
     * completes after a newer load writes its own captured document but can never
     * steal the loaded session or rewrite the document the user just loaded.
     */
    private var requestSequence = 0L

    /** A queued save cannot cancel a load already selecting the visible session. */
    private var loadSequence = 0L

    /**
     * Identity of the loaded-document session: bumped when a load applies and when a
     * currency rebind ends the session. A save captures it at tap time; if it moved
     * while the save was in flight, the save's visible effects are dropped (the
     * storage write of its immutable captured payload already happened and stays).
     */
    private var sessionEpoch = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<GoalsState> =
        prefs.displayCurrency()
            .flatMapLatest { currency ->
                bindScenarioTo(currency)
                // Row classification decodes every observed payload; that JSON work and
                // the upstream observation run off Main. The currency is fixed per
                // binding, so the pipeline hands the combine precomputed rows.
                val savedSummaries = savedPlans.observe(SavedFinancialPlanKind.GOAL)
                    .map { docs -> savedGoalSummaries(docs, currency) }
                    .flowOn(workDispatcher)
                combine(
                    combine(
                        combine(
                            transactions.observeByPeriod(last3MonthsPeriod(), status = TxStatus.CONFIRMED),
                            accounts.observeNetWorth(currency),
                            prefs.savingsRateTargetPercent(),
                            prefs.emergencyFundTargetMonths(),
                        ) { txs, netWorth, savingsTarget, emergencyMonths ->
                            CoreInputs(txs, netWorth, savingsTarget, emergencyMonths)
                        },
                        scenario,
                        savedSummaries,
                        savedControls,
                    ) { inputs, scenarioUi, summaries, controls ->
                        derive(
                            txs = inputs.txs,
                            netWorth = inputs.netWorth,
                            currency = currency,
                            savingsTarget = inputs.savingsTarget,
                            emergencyMonths = inputs.emergencyMonths,
                        ).copy(
                            currency = currency,
                            scenario = scenarioUi,
                            savedGoals = summaries,
                            savedGoalTitleText = controls.title,
                            savedGoalSaving = controls.saving,
                            activeSavedGoalId = activeSavedGoal?.id,
                            savedGoalNotice = controls.notice,
                        )
                    },
                    savedCompare,
                ) { base, compare ->
                    base.copy(savedGoalCompare = compare)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsState.initial())

    fun onEvent(event: GoalsEvent) {
        when (event) {
            is GoalsEvent.SetSavingsRateTarget -> viewModelScope.launch {
                prefs.setSavingsRateTargetPercent(event.percent)
            }
            is GoalsEvent.SetEmergencyFundTarget -> viewModelScope.launch {
                prefs.setEmergencyFundTargetMonths(event.months)
            }
            is GoalsEvent.UpdateScenario -> {
                invalidatePendingSolve()
                draftGeneration += 1
                savedCompare.value = null // the compared live draft no longer exists
                scenario.value = GoalScenarioUi(
                    draft = event.draft,
                    result = null,
                    currency = scenario.value.currency,
                )
            }
            GoalsEvent.ComputeScenario -> {
                // No compute before preferences load: the state default ("SAR")
                // is never used to solve.
                val currency = boundCurrency.value ?: return
                val startMonth = currentStartMonth()
                val generation = ++computeGeneration
                val draft = scenario.value.draft
                viewModelScope.launch {
                    val result = try {
                        withContext(workDispatcher) { draft.solve(currency, startMonth) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation // scope teardown is not a solver failure
                    } catch (unexpected: Exception) {
                        null // unexpected failures commit no fabricated result
                    }
                    // Commit only if this is still the newest computation and neither
                    // the draft nor the bound currency changed while it ran.
                    if (computeGeneration == generation &&
                        boundCurrency.value == currency &&
                        scenario.value.draft == draft
                    ) {
                        scenario.value = GoalScenarioUi(
                            draft = draft,
                            result = result,
                            currency = currency,
                        )
                    }
                }
            }
            GoalsEvent.ResetScenario -> {
                // No seeding before preferences load, for the same reason.
                val currency = boundCurrency.value ?: return
                val current = state.value
                if (current.isLoading || current.currency != currency) return
                invalidatePendingSolve()
                draftGeneration += 1
                savedCompare.value = null
                val liquid = current.liquidBalance
                    .takeIf { it.currency == currency } ?: Money.zero(currency)
                val savings = current.monthlySavings
                    .takeIf { it.currency == currency } ?: Money.zero(currency)
                scenario.value = GoalScenarioUi(
                    draft = GoalScenarioDraft.seeded(liquid, savings),
                    result = null,
                    currency = currency,
                )
            }
            is GoalsEvent.SetSavedGoalTitle -> savedControls.update { controls ->
                controls.copy(
                    title = event.title,
                    // Typing after a refused save clears the stale refusal, not a success note.
                    notice = controls.notice.takeIf { it !is SavedGoalNotice.NeedsTitle },
                )
            }
            GoalsEvent.SaveCurrentGoal -> {
                if (savedControls.value.saving) return
                // No persisted goal before a real currency is bound: the state default
                // ("SAR") never stamps a document.
                val currency = boundCurrency.value ?: return
                val title = savedControls.value.title.trim()
                if (title.isEmpty()) {
                    savedControls.update { it.copy(notice = SavedGoalNotice.NeedsTitle) }
                    return
                }
                // Immutable request snapshot, taken synchronously at tap time: the
                // draft, title, currency and update target this save writes can no
                // longer be changed by a faster typist, a queued load, or a currency
                // rebind landing before the coroutine body runs.
                val draft = scenario.value.draft
                val activeAtRequest = activeSavedGoal
                savedControls.update { it.copy(saving = true) }
                val request = ++requestSequence
                val session = sessionEpoch
                viewModelScope.launch {
                    try {
                        val now = clock.now().toEpochMilliseconds()
                        val saved = withContext(workDispatcher) {
                            val loaded = activeAtRequest
                            if (loaded == null) {
                                // Create-only: the repository assigns identity revision and times.
                                savedPlans.save(
                                    SavedFinancialPlan(
                                        id = UUID.randomUUID().toString(),
                                        kind = SavedFinancialPlanKind.GOAL,
                                        title = title,
                                        currency = currency,
                                        payloadVersion = GoalSavedDocCodec.PAYLOAD_VERSION,
                                        payloadJson = GoalSavedDocCodec.encode(draft),
                                        revision = 0L,
                                        createdAtEpochMillis = now,
                                        updatedAtEpochMillis = now,
                                    ),
                                    expectedRevision = null,
                                )
                            } else {
                                val current = savedPlans.get(loaded.id)
                                if (current == null) {
                                    // The loaded document disappeared underneath this session.
                                    null
                                } else {
                                    // Identity/kind/createdAt are immutable; the revision guard
                                    // is the CAPTURED revision, so an edit made elsewhere refuses.
                                    savedPlans.save(
                                        current.copy(
                                            title = title,
                                            payloadVersion = GoalSavedDocCodec.PAYLOAD_VERSION,
                                            payloadJson = GoalSavedDocCodec.encode(draft),
                                            updatedAtEpochMillis = now,
                                        ),
                                        expectedRevision = loaded.revision,
                                    )
                                }
                            }
                        }
                        if (ownsUi(request, session)) {
                            if (saved == null) {
                                activeSavedGoal = null
                                savedControls.update { it.copy(notice = SavedGoalNotice.LoadFailed) }
                            } else {
                                activeSavedGoal = ActiveSavedGoal(saved.id, saved.revision)
                                savedControls.update {
                                    it.copy(
                                        notice = if (activeAtRequest == null) {
                                            SavedGoalNotice.Saved
                                        } else {
                                            SavedGoalNotice.Updated
                                        },
                                    )
                                }
                            }
                        }
                    } catch (conflict: SavedFinancialPlanConflictException) {
                        if (ownsUi(request, session)) {
                            savedControls.update { it.copy(notice = SavedGoalNotice.Conflict) }
                        }
                    } catch (refused: SavedFinancialPlanValidationException) {
                        if (ownsUi(request, session)) {
                            savedControls.update { it.copy(notice = SavedGoalNotice.SaveFailed) }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation // scope teardown is not a persistence failure
                    } catch (unexpected: Exception) {
                        if (ownsUi(request, session)) {
                            savedControls.update { it.copy(notice = SavedGoalNotice.SaveFailed) }
                        }
                    } finally {
                        savedControls.update { it.copy(saving = false) }
                    }
                }
            }
            is GoalsEvent.LoadSavedGoal -> {
                // No load before preferences bind a literal currency: there is nothing
                // the document's currency could be compared against.
                val currency = boundCurrency.value ?: return
                val request = ++loadSequence
                val draftGen = draftGeneration
                viewModelScope.launch {
                    try {
                        val outcome = withContext(workDispatcher) {
                            val doc = savedPlans.get(event.id)
                            when {
                                doc == null -> LoadOutcome.Missing
                                else -> when (val decoded = decodeSavedGoal(doc)) {
                                    GoalSavedDocResult.Unsupported -> LoadOutcome.Unsupported
                                    is GoalSavedDocResult.V1 ->
                                        if (doc.currency != currency) {
                                            // Literal currency: no relabel, no FX, no silent swap.
                                            LoadOutcome.CurrencyMismatch
                                        } else {
                                            LoadOutcome.Loaded(doc, decoded.draft)
                                        }
                                }
                            }
                        }
                        // Apply only if this is still the newest saved-goal request and
                        // no newer edit, reset, or currency rebind changed the visible
                        // scenario meanwhile: a stale async load never overwrites a
                        // newer draft.
                        if (loadSequence == request &&
                            draftGeneration == draftGen &&
                            boundCurrency.value == currency
                        ) {
                            when (outcome) {
                                LoadOutcome.Missing ->
                                    savedControls.update { it.copy(notice = SavedGoalNotice.LoadFailed) }
                                LoadOutcome.Unsupported ->
                                    // The document stays stored and listed; the visible draft
                                    // is never mutated by a refused load.
                                    savedControls.update { it.copy(notice = SavedGoalNotice.UnsupportedDocument) }
                                LoadOutcome.CurrencyMismatch ->
                                    savedControls.update { it.copy(notice = SavedGoalNotice.CurrencyMismatch) }
                                is LoadOutcome.Loaded -> {
                                    invalidatePendingSolve()
                                    draftGeneration += 1
                                    sessionEpoch += 1
                                    savedCompare.value = null
                                    scenario.value = GoalScenarioUi(
                                        draft = outcome.draft,
                                        result = null, // inputs are restored; outcomes are recomputed
                                        currency = outcome.doc.currency,
                                    )
                                    activeSavedGoal = ActiveSavedGoal(outcome.doc.id, outcome.doc.revision)
                                    savedControls.update { controls ->
                                        controls.copy(title = outcome.doc.title, notice = SavedGoalNotice.Loaded)
                                    }
                                }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (unexpected: Exception) {
                        if (loadSequence == request && boundCurrency.value == currency) {
                            savedControls.update { it.copy(notice = SavedGoalNotice.LoadFailed) }
                        }
                    }
                }
            }
            is GoalsEvent.DeleteSavedGoal -> viewModelScope.launch {
                try {
                    // Revision from the VISIBLE snapshot: deleting what the user saw.
                    val visible = state.value.savedGoals.firstOrNull { it.id == event.id } ?: return@launch
                    savedPlans.delete(event.id, expectedRevision = visible.revision)
                    if (activeSavedGoal?.id == event.id) activeSavedGoal = null
                    if (savedCompare.value?.savedId == event.id) savedCompare.value = null
                    savedControls.update { it.copy(notice = SavedGoalNotice.Deleted) }
                } catch (conflict: SavedFinancialPlanConflictException) {
                    savedControls.update { it.copy(notice = SavedGoalNotice.Conflict) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (unexpected: Exception) {
                    savedControls.update { it.copy(notice = SavedGoalNotice.DeleteFailed) }
                }
            }
            GoalsEvent.DismissSavedGoalNotice -> savedControls.update { it.copy(notice = null) }

            is GoalsEvent.CompareSavedGoal -> {
                val currency = boundCurrency.value ?: return
                val liveDraft = scenario.value.draft
                val generation = ++computeGeneration
                val startMonth = currentStartMonth()
                viewModelScope.launch {
                    try {
                        val outcome = withContext(workDispatcher) {
                            val doc = savedPlans.get(event.id)
                            when {
                                doc == null -> CompareOutcome.Missing
                                else -> when (val decoded = decodeSavedGoal(doc)) {
                                    GoalSavedDocResult.Unsupported -> CompareOutcome.Unsupported
                                    is GoalSavedDocResult.V1 ->
                                        if (doc.currency != currency) {
                                            CompareOutcome.CurrencyMismatch
                                        } else {
                                            // Both sides through the accepted solver; a
                                            // comparison writes nothing anywhere.
                                            CompareOutcome.Ready(
                                                SavedGoalCompareUi(
                                                    savedId = doc.id,
                                                    savedTitle = doc.title,
                                                    savedCurrency = doc.currency,
                                                    savedDraft = decoded.draft,
                                                    liveDraft = liveDraft,
                                                    savedResult = decoded.draft.solve(currency, startMonth),
                                                    liveResult = liveDraft.solve(currency, startMonth),
                                                ),
                                            )
                                        }
                                }
                            }
                        }
                        // Only the newest scenario computation may publish, and only
                        // under the currency it was solved in; an edit, reset, load or
                        // rebind meanwhile bumped the generation and discards it.
                        if (computeGeneration == generation && boundCurrency.value == currency) {
                            when (outcome) {
                                CompareOutcome.Missing ->
                                    savedControls.update { it.copy(notice = SavedGoalNotice.LoadFailed) }
                                CompareOutcome.Unsupported ->
                                    savedControls.update { it.copy(notice = SavedGoalNotice.UnsupportedDocument) }
                                CompareOutcome.CurrencyMismatch ->
                                    savedControls.update { it.copy(notice = SavedGoalNotice.CurrencyMismatch) }
                                is CompareOutcome.Ready -> savedCompare.value = outcome.compare
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (unexpected: Exception) {
                        if (computeGeneration == generation && boundCurrency.value == currency) {
                            savedControls.update { it.copy(notice = SavedGoalNotice.LoadFailed) }
                        }
                    }
                }
            }
            GoalsEvent.DismissSavedGoalCompare -> savedCompare.value = null
        }
    }

    /**
     * Whether this still-newest save request, made inside the session it captured, may
     * publish its notice and move the active-document pointer.
     */
    private fun ownsUi(request: Long, session: Long): Boolean =
        requestSequence == request && sessionEpoch == session

    private fun currentStartMonth(): YearMonth {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return YearMonth.of(today.year, today.monthNumber)
    }

    private fun invalidatePendingSolve() {
        computeGeneration += 1
    }

    /**
     * Literal currency binding: the first loaded preference stamps the blank scenario;
     * a loaded display-currency change discards the whole transient scenario — result
     * AND seeded inputs — because figures are never silently relabeled across
     * currencies and never FX-converted. Re-entry or re-seed is explicit. The loaded
     * document session ends too; the stored document itself is untouched and stays
     * listed under its own literal currency.
     */
    private fun bindScenarioTo(currency: String) {
        boundCurrency.value = currency
        val current = scenario.value
        scenario.value = when {
            current.currency.isEmpty() -> current.copy(currency = currency)
            current.currency != currency -> {
                invalidatePendingSolve()
                draftGeneration += 1
                sessionEpoch += 1
                // A display-currency change ends the loaded-document session.
                activeSavedGoal = null
                savedCompare.value = null
                GoalScenarioUi(currency = currency)
            }
            else -> current
        }
    }

    /** Outer row metadata and inner payload must both describe a supported GOAL input. */
    private fun decodeSavedGoal(doc: SavedFinancialPlan): GoalSavedDocResult =
        if (doc.kind != SavedFinancialPlanKind.GOAL || doc.payloadVersion != GoalSavedDocCodec.PAYLOAD_VERSION) {
            GoalSavedDocResult.Unsupported
        } else {
            GoalSavedDocCodec.decode(doc.payloadJson)
        }

    /** Classifies every observed GOAL document against the live display currency. */
    private fun savedGoalSummaries(
        docs: List<SavedFinancialPlan>,
        currency: String,
    ): List<SavedGoalSummary> = docs.map { doc ->
        val docState = when {
            decodeSavedGoal(doc) !is GoalSavedDocResult.V1 -> SavedGoalDocState.UNSUPPORTED
            doc.currency != currency -> SavedGoalDocState.OTHER_CURRENCY
            else -> SavedGoalDocState.LOADABLE
        }
        SavedGoalSummary(
            id = doc.id,
            title = doc.title,
            currency = doc.currency,
            revision = doc.revision,
            updatedAtEpochMillis = doc.updatedAtEpochMillis,
            state = docState,
        )
    }

    private fun derive(
        txs: List<Transaction>,
        netWorth: NetWorth,
        currency: String,
        savingsTarget: Int,
        emergencyMonths: Int,
    ): GoalsState {
        val operating = txs.filterNot { it.isReconciliation() }
        // No-FX policy: Money.sumAmounts relabels raw amounts 1:1, so anything not
        // already in the display currency is EXCLUDED from totals and disclosed via
        // GoalsState.excludedCurrencies, never converted. observeNetWorth's currency
        // label does not make its constituent account balances homogeneous.
        val incomeAmounts = operating
            .filter { it.type == TxType.INCOME && it.amount.currency == currency }
            .map { it.amount }
        val expenseAmounts = operating
            .filter { it.type == TxType.EXPENSE && it.amount.currency == currency }
            .map { it.amount }
        val totalIncome = Money.sumAmounts(incomeAmounts, currency)
        val totalExpense = Money.sumAmounts(expenseAmounts, currency)

        val monthlyIncome = GoalCalc.averageMonthly(totalIncome, LOOKBACK_MONTHS)
        val monthlyExpense = GoalCalc.averageMonthly(totalExpense, LOOKBACK_MONTHS)
        val monthlySavings = monthlyIncome - monthlyExpense
        val savingsRate = GoalCalc.savingsRatePercent(monthlyIncome, monthlyExpense)
        val liquidAccounts = netWorth.accounts.filter { it.account.type in LIQUID_ACCOUNT_TYPES }
        val liquidBalance = liquidBalance(liquidAccounts, currency)
        // The NetWorth boundary exposes account balances derived from openings plus
        // confirmed flows, with NO dated balance anchor. Per the durable-input contract
        // a flow sum is never presented as current liquidity: the figure stays an
        // internal seeding input only, and the UI withholds liquidity and funded
        // coverage honestly until a certified dated balance exists (cash lane anchor).
        val liquidityCertified = false
        val emergencyTargetAmount = GoalCalc.emergencyTargetAmount(monthlyExpense, emergencyMonths)
        val emergencyCovered = GoalCalc.emergencyMonthsCovered(liquidBalance, monthlyExpense)
        val excludedCurrencies = sortedSetOf<String>()
        operating.forEach { tx ->
            if (tx.amount.currency != currency) excludedCurrencies.add(tx.amount.currency)
        }
        liquidAccounts.forEach { balance ->
            if (balance.current.currency != currency) excludedCurrencies.add(balance.current.currency)
        }

        return GoalsState(
            monthlyIncome = monthlyIncome,
            monthlyExpense = monthlyExpense,
            monthlySavings = monthlySavings,
            savingsRatePercent = savingsRate,
            savingsRateTargetPercent = savingsTarget,
            savingsRateProgress = GoalCalc.savingsRateProgress(savingsRate, savingsTarget).toProgressFloat(),
            liquidBalance = liquidBalance,
            liquidityCertified = liquidityCertified,
            emergencyTargetAmount = emergencyTargetAmount,
            emergencyMonthsCovered = emergencyCovered,
            emergencyFundTargetMonths = emergencyMonths,
            emergencyFundProgress = GoalCalc.emergencyProgress(emergencyCovered, emergencyMonths).toProgressFloat(),
            isLoading = false,
            excludedCurrencies = excludedCurrencies,
        )
    }

    private fun liquidBalance(balances: List<AccountBalance>, currency: String): Money {
        val total = Money.sumAmounts(
            balances
                .filter { it.account.type in LIQUID_ACCOUNT_TYPES && it.current.currency == currency }
                .map { it.current },
            currency,
        )
        return total // Signed liquid balances remain facts; only optional goal funding is clamped.
    }

    private fun last3MonthsPeriod(): Period {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return Period.Last(months = LOOKBACK_MONTHS, endingAt = today)
    }

    private fun BigDecimal.toProgressFloat(): Float =
        toFloat().coerceIn(0f, 1f)

    companion object {
        private const val LOOKBACK_MONTHS = 3
        private val LIQUID_ACCOUNT_TYPES = setOf(
            AccountType.CHECKING,
            AccountType.SAVINGS,
            AccountType.CASH,
        )
    }

    private data class CoreInputs(
        val txs: List<Transaction>,
        val netWorth: NetWorth,
        val savingsTarget: Int,
        val emergencyMonths: Int,
    )

    private data class SavedGoalControls(
        val title: String = "",
        val saving: Boolean = false,
        val notice: SavedGoalNotice? = null,
    )

    private data class ActiveSavedGoal(val id: String, val revision: Long)

    /** Classified result of a load request, decoded off Main and applied only if current. */
    private sealed interface LoadOutcome {
        data object Missing : LoadOutcome
        data object Unsupported : LoadOutcome
        data object CurrencyMismatch : LoadOutcome
        data class Loaded(val doc: SavedFinancialPlan, val draft: GoalScenarioDraft) : LoadOutcome
    }

    /** Classified result of a comparison request, decoded and solved off Main. */
    private sealed interface CompareOutcome {
        data object Missing : CompareOutcome
        data object Unsupported : CompareOutcome
        data object CurrencyMismatch : CompareOutcome
        data class Ready(val compare: SavedGoalCompareUi) : CompareOutcome
    }
}
