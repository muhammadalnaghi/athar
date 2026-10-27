package com.athar.feature.plan.scenarios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.feature.plan.cashforecast.CASH_SCENARIO_PAYLOAD_VERSION
import com.athar.feature.plan.cashforecast.CashComparisonEntry
import com.athar.feature.plan.cashforecast.CashDocumentClassification
import com.athar.feature.plan.cashforecast.buildCashComparisonRows
import com.athar.feature.plan.cashforecast.classifyCashDocument
import com.athar.feature.plan.cashforecast.encodeCashScenarioDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@HiltViewModel
internal class CashScenarioViewModel @Inject constructor(
    private val savedPlans: SavedFinancialPlanRepository,
    private val clock: Clock,
) : ViewModel() {
    private val mutable = MutableStateFlow(CashScenarioState())
    val state = mutable.asStateFlow()
    private var dispatcher: CoroutineDispatcher = Dispatchers.Default
    private var solver: suspend (CashScenarioDraft) -> ScenarioDraftResult<CashForecastOutcome> = { evaluateCashDraft(it) }
    private var generation = 0L
    private var compareGeneration = 0L
    private var sessionEpoch = 0L
    private var job: Job? = null
    private var nextId = 1

    internal constructor(
        dispatcher: CoroutineDispatcher,
        draft: CashScenarioDraft,
        solver: suspend (CashScenarioDraft) -> ScenarioDraftResult<CashForecastOutcome> = { evaluateCashDraft(it) },
        savedPlans: SavedFinancialPlanRepository,
        clock: Clock,
    ) : this(savedPlans, clock) {
        this.dispatcher = dispatcher
        this.solver = solver
        mutable.value = CashScenarioState(draft)
        nextId = (draft.flows.maxOfOrNull { it.id } ?: 0) + 1
    }

    init {
        viewModelScope.launch {
            savedPlans.observe(SavedFinancialPlanKind.CASH)
                .map { plans -> classifySavedPlans(plans) }
                .flowOn(dispatcher)
                .collect { listing ->
                    mutable.value = mutable.value.copy(
                        savedList = listing,
                        compare = mutable.value.compare.let { compare ->
                            val known = listing.summaries.map { it.id }.toSet()
                            compare.copy(
                                selected = compare.selected.intersect(known),
                                rows = compare.rows.filter { it.id in known },
                            )
                        },
                    )
                }
        }
    }

    fun onEvent(event: CashScenarioEvent) {
        val draft = mutable.value.draft
        when (event) {
            is CashScenarioEvent.Edit -> if (event.field == CashField.CURRENCY && draft.currency != event.text) {
                // A currency change re-enters the form as a new scenario: the loaded
                // document is detached so no later save can relabel it in place.
                change(CashScenarioDraft(currency = event.text), detachSaved = true)
            } else {
                change(when (event.field) {
                    CashField.CURRENCY -> draft
                    CashField.OPENING_DATE -> draft.copy(openingDate = event.text)
                    CashField.OPENING_CASH -> draft.copy(openingCash = event.text)
                    CashField.RESERVE -> draft.copy(reserve = event.text)
                    CashField.HORIZON_END -> draft.copy(horizonEnd = event.text)
                })
            }
            is CashScenarioEvent.EditFlow -> change(draft.copy(flows = draft.flows.map { row ->
                if (row.id != event.id) row else when (event.field) {
                    CashFlowField.DATE -> row.copy(date = event.text)
                    CashFlowField.AMOUNT -> row.copy(amount = event.text)
                }
            }))
            is CashScenarioEvent.Direction -> change(draft.copy(flows = draft.flows.map {
                if (it.id == event.id) it.copy(direction = event.direction) else it
            }))
            is CashScenarioEvent.ScopeComplete -> change(draft.copy(scopeComplete = event.checked))
            is CashScenarioEvent.AnchorFresh -> change(draft.copy(anchorFresh = event.checked))
            CashScenarioEvent.AddFlow -> if (draft.flows.size < 24) change(draft.copy(flows = draft.flows + CashFlowDraft(nextId++)))
            is CashScenarioEvent.RemoveFlow -> change(draft.copy(flows = draft.flows.filterNot { it.id == event.id }))
            CashScenarioEvent.Reset -> change(CashScenarioDraft(currency = draft.currency), detachSaved = true)
            CashScenarioEvent.Compute -> compute()
            is CashScenarioEvent.OpenPath -> showPage(event.currency, 0)
            is CashScenarioEvent.PathPage -> mutable.value.path?.let { showPage(it.currency, event.page) }
            CashScenarioEvent.BackToResult -> mutable.value = mutable.value.copy(path = null)
            is CashScenarioEvent.SaveTitle ->
                mutable.value = mutable.value.copy(saved = mutable.value.saved.copy(titleDraft = event.text.take(80)))
            CashScenarioEvent.SaveScenario -> saveScenario()
            is CashScenarioEvent.LoadSaved -> loadSaved(event.id)
            is CashScenarioEvent.DeleteSaved ->
                mutable.value = mutable.value.copy(confirmDeleteId = event.id, saved = mutable.value.saved.copy(message = null))
            CashScenarioEvent.ConfirmDeleteSaved -> deleteConfirmed()
            CashScenarioEvent.CancelDeleteSaved -> mutable.value = mutable.value.copy(confirmDeleteId = null)
            CashScenarioEvent.CompareOpen ->
                mutable.value = mutable.value.copy(compare = CashCompareUiState(active = true))
            CashScenarioEvent.CompareClose -> mutable.value = mutable.value.copy(compare = CashCompareUiState())
            is CashScenarioEvent.CompareToggle -> toggleCompare(event.id)
            CashScenarioEvent.CompareRun -> runComparison()
        }
    }

    /**
     * Structural draft edits clear the transient outcome immediately. A currency change
     * or full reset also detaches the loaded document: re-entered inputs are a new
     * scenario, never a silent currency relabel of a saved one.
     */
    private fun change(draft: CashScenarioDraft, detachSaved: Boolean = false) {
        generation++
        job?.cancel()
        val current = mutable.value
        mutable.value = CashScenarioState(
            draft = draft,
            savedList = current.savedList,
            saved = if (detachSaved) CashSavedUiState() else current.saved.copy(message = null),
            compare = current.compare,
            confirmDeleteId = current.confirmDeleteId,
        )
        if (detachSaved) {
            nextId = 1
            sessionEpoch++
        }
    }

    private fun compute() {
        job?.cancel()
        val ticket = ++generation
        val captured = mutable.value.draft
        mutable.value = mutable.value.copy(computing = true, errors = emptySet(), outcome = null, path = null)
        job = viewModelScope.launch {
            try {
                val result = withContext(dispatcher) { solver(captured) }
                if (ticket == generation && captured == mutable.value.draft) {
                    mutable.value = mutable.value.copy(errors = result.errors, outcome = result.outcome)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) mutable.value = mutable.value.copy(errors = setOf("calculation"))
            } finally {
                if (ticket == generation) mutable.value = mutable.value.copy(computing = false)
            }
        }
    }

    /** Saves validated inputs only (a recomputed outcome must exist); outcomes are never stored. */
    private fun saveScenario() {
        val current = mutable.value
        if (current.saved.saving || current.outcome == null) return
        val title = current.saved.titleDraft.trim()
        if (!validSavedTitle(title)) {
            mutable.value = current.copy(saved = current.saved.copy(message = CashSaveMessage.TITLE_REQUIRED))
            return
        }
        val document = documentOf(current.draft)
        val loaded = current.saved.loaded?.takeIf { it.title == title }
        val session = sessionEpoch
        mutable.value = current.copy(saved = current.saved.copy(saving = true, message = null))
        viewModelScope.launch {
            try {
                val payload = withContext(dispatcher) { encodeCashScenarioDocument(document) }
                val now = clock.now().toEpochMilliseconds()
                val plan = if (loaded == null) {
                    SavedFinancialPlan(
                        id = UUID.randomUUID().toString(),
                        kind = SavedFinancialPlanKind.CASH,
                        title = title,
                        currency = document.currency,
                        payloadVersion = CASH_SCENARIO_PAYLOAD_VERSION,
                        payloadJson = payload,
                        revision = 0L,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    SavedFinancialPlan(
                        id = loaded.id,
                        kind = SavedFinancialPlanKind.CASH,
                        title = title,
                        currency = document.currency,
                        payloadVersion = CASH_SCENARIO_PAYLOAD_VERSION,
                        payloadJson = payload,
                        revision = 0L,
                        createdAtEpochMillis = loaded.createdAtEpochMillis,
                        updatedAtEpochMillis = now,
                    )
                }
                val result = withContext(dispatcher) { savedPlans.save(plan, loaded?.revision) }
                if (session != sessionEpoch) return@launch
                mutable.value = mutable.value.copy(
                    saved = mutable.value.saved.copy(
                        saving = false,
                        loaded = summaryOf(result, document),
                        titleDraft = mutable.value.saved.titleDraft.takeIf { it != title } ?: result.title,
                        message = if (loaded == null) CashSaveMessage.SAVED else CashSaveMessage.UPDATED,
                    ),
                )
            } catch (_: SavedFinancialPlanConflictException) {
                if (session != sessionEpoch) return@launch
                mutable.value = mutable.value.copy(
                    saved = mutable.value.saved.copy(saving = false, message = CashSaveMessage.CONFLICT),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (session != sessionEpoch) return@launch
                // Validation and unexpected storage failures share one safe constant; storage
                // state is refreshed by the observing collect, never exposing stored JSON.
                mutable.value = mutable.value.copy(
                    saved = mutable.value.saved.copy(saving = false, message = CashSaveMessage.STORAGE),
                )
            }
        }
    }

    /** Reopens saved inputs verbatim, then recomputes the outcome from those inputs only. */
    private fun loadSaved(id: String) {
        job?.cancel()
        val ticket = ++generation
        mutable.value = mutable.value.copy(computing = true, saved = mutable.value.saved.copy(message = null))
        viewModelScope.launch {
            val plan = withContext(dispatcher) { savedPlans.get(id) }
            if (ticket != generation) return@launch
            val classified = withContext(dispatcher) { plan?.let { classifyCashDocument(it.payloadJson) } }
            val document = (classified as? CashDocumentClassification.CashScenario)?.document
            if (plan == null || document == null) {
                mutable.value = mutable.value.copy(
                    computing = false,
                    saved = mutable.value.saved.copy(message = CashSaveMessage.MISSING),
                )
                return@launch
            }
            val draft = draftOf(document)
            sessionEpoch++
            nextId = (draft.flows.maxOfOrNull { it.id } ?: 0) + 1
            mutable.value = mutable.value.copy(
                draft = draft,
                errors = emptySet(),
                outcome = null,
                path = null,
                saved = mutable.value.saved.copy(
                    loaded = summaryOf(plan, document),
                    titleDraft = plan.title,
                ),
            )
            compute()
        }
    }

    /** Deletion requires an armed confirmation and the exact captured revision. */
    private fun deleteConfirmed() {
        val current = mutable.value
        val id = current.confirmDeleteId ?: return
        val summary = current.savedList.summaries.firstOrNull { it.id == id } ?: run {
            mutable.value = current.copy(confirmDeleteId = null, saved = current.saved.copy(message = CashSaveMessage.MISSING))
            return
        }
        val expectedRevision = current.saved.loaded?.takeIf { it.id == id }?.revision ?: summary.revision
        mutable.value = current.copy(saved = current.saved.copy(message = null))
        viewModelScope.launch {
            try {
                withContext(dispatcher) { savedPlans.delete(id, expectedRevision) }
                val saved = mutable.value.saved
                val wasLoaded = saved.loaded?.id == id
                mutable.value = mutable.value.copy(
                    confirmDeleteId = null,
                    saved = saved.copy(
                        message = CashSaveMessage.DELETED,
                        loaded = saved.loaded?.takeIf { !wasLoaded },
                        titleDraft = if (wasLoaded) "" else saved.titleDraft,
                    ),
                )
            } catch (_: SavedFinancialPlanConflictException) {
                mutable.value = mutable.value.copy(
                    confirmDeleteId = null,
                    saved = mutable.value.saved.copy(message = CashSaveMessage.CONFLICT),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    confirmDeleteId = null,
                    saved = mutable.value.saved.copy(message = CashSaveMessage.STORAGE),
                )
            }
        }
    }

    /** Selection is refused across different opening sources/currencies/dates: incomparable. */
    private fun toggleCompare(id: String) {
        val current = mutable.value
        val summary = current.savedList.summaries.firstOrNull { it.id == id } ?: return
        val selected = current.compare.selected
        val updated = when {
            id in selected -> selected - id
            selected.isEmpty() -> setOf(id)
            else -> {
                val anchor = current.savedList.summaries.firstOrNull { it.id == selected.first() }
                if (anchor != null && anchor.compareKey() == summary.compareKey()) selected + id else selected
            }
        }
        mutable.value = current.copy(
            compare = current.compare.copy(selected = updated, rows = emptyList(), empty = false),
        )
    }

    /** Rebuilds every selected document and recomputes outcomes off Main; never stored results. */
    private fun runComparison() {
        val current = mutable.value
        if (current.compare.running) return
        val selected = current.compare.selected
        if (selected.size < 2) {
            mutable.value = current.copy(compare = current.compare.copy(empty = true, rows = emptyList()))
            return
        }
        val ticket = ++compareGeneration
        mutable.value = current.copy(compare = current.compare.copy(running = true, empty = false, rows = emptyList()))
        viewModelScope.launch {
            val entries = selected.mapNotNull { id ->
                val plan = withContext(dispatcher) { savedPlans.get(id) } ?: return@mapNotNull null
                val classified = withContext(dispatcher) { classifyCashDocument(plan.payloadJson) }
                val document = (classified as? CashDocumentClassification.CashScenario)?.document ?: return@mapNotNull null
                CashComparisonEntry(
                    id = id,
                    title = plan.title,
                    flowCount = document.flows.size,
                    result = withContext(dispatcher) { solver(draftOf(document)) },
                )
            }
            val rows = buildCashComparisonRows(entries)
            if (ticket == compareGeneration) {
                mutable.value = mutable.value.copy(
                    compare = mutable.value.compare.copy(running = false, rows = rows, empty = entries.size < 2),
                )
            }
        }
    }

    private fun showPage(currency: String, requested: Int) {
        val result = (mutable.value.outcome as? CashForecastOutcome.Forecast)?.result ?: return
        val partition = result.partitions.singleOrNull { it.currency == currency } ?: return
        if (partition.path.isEmpty()) return
        val page = requested.coerceIn(0, (partition.path.size - 1) / POINTS_PER_PAGE)
        val numbersById = mutable.value.draft.flows.mapIndexed { index, flow -> flowEffectId(flow.id) to index + 1 }.toMap()
        val points = partition.path.drop(page * POINTS_PER_PAGE).take(POINTS_PER_PAGE).map { day ->
            CashPathPoint(day, day.supportingEffects.mapNotNull { numbersById[it.id] }.distinct())
        }
        mutable.value = mutable.value.copy(path = CashPathPage(currency, partition.status, points,
            partition.path.size, page, page > 0, (page + 1) * POINTS_PER_PAGE < partition.path.size))
    }
}

private const val POINTS_PER_PAGE = 6
