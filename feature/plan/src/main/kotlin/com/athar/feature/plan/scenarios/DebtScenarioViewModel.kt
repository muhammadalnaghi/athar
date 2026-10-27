package com.athar.feature.plan.scenarios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.StrategyRun
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import com.athar.feature.plan.debt.DEBT_DOCUMENT_VERSION
import com.athar.feature.plan.debt.decodeDebtScenarioDocument
import com.athar.feature.plan.debt.encodeDebtScenarioDocument
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

@HiltViewModel
internal class DebtScenarioViewModel @Inject constructor(
    private val savedPlans: SavedFinancialPlanRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(DebtScenarioState())
    val state = mutable.asStateFlow()
    private var dispatcher: CoroutineDispatcher = Dispatchers.Default
    private var solver: suspend (DebtScenarioDraft) -> ScenarioDraftResult<DebtPayoffOutcome> = { evaluateDebtDraft(it) }
    private var newPlanId: () -> String = { UUID.randomUUID().toString() }
    private var generation = 0L
    private var job: Job? = null
    private var persistence: Job? = null
    private var nextId = 2

    internal constructor(
        dispatcher: CoroutineDispatcher,
        draft: DebtScenarioDraft,
        savedPlans: SavedFinancialPlanRepository,
        solver: suspend (DebtScenarioDraft) -> ScenarioDraftResult<DebtPayoffOutcome> = { evaluateDebtDraft(it) },
        newPlanId: () -> String = { UUID.randomUUID().toString() },
    ) : this(savedPlans) {
        this.dispatcher = dispatcher
        this.solver = solver
        this.newPlanId = newPlanId
        mutable.value = DebtScenarioState(draft = draft)
        nextId = (draft.loans.maxOfOrNull { it.id } ?: 0) + 1
    }

    init {
        viewModelScope.launch {
            savedPlans.observe(SavedFinancialPlanKind.DEBT)
                .map { plans -> plans.map(::savedEntry) }
                .flowOn(dispatcher)
                .collect { entries -> mutable.value = mutable.value.copy(saved = entries) }
        }
    }

    /** Codec availability only; unsupported or invalid documents stay listed and retained. */
    private fun savedEntry(plan: SavedFinancialPlan): DebtSavedEntry {
        if (plan.kind != SavedFinancialPlanKind.DEBT) return DebtSavedEntry(plan, available = false)
        val decoded = if (plan.payloadVersion == DEBT_DOCUMENT_VERSION) decodeDebtScenarioDocument(plan.payloadJson) else null
        return DebtSavedEntry(plan, available = decoded != null && debtDraftErrors(decoded).isEmpty())
    }

    fun onEvent(event: DebtScenarioEvent) {
        val draft = mutable.value.draft
        when (event) {
            is DebtScenarioEvent.Edit -> when (event.field) {
                DebtField.CURRENCY -> if (event.text != draft.currency) {
                    change(DebtScenarioDraft(currency = event.text), severSaved = true)
                }
                DebtField.FIRST_DUE -> change(draft.copy(firstDueDate = event.text))
                DebtField.HORIZON -> change(draft.copy(horizon = event.text))
                DebtField.BUDGET -> change(draft.copy(budget = event.text))
            }
            is DebtScenarioEvent.EditLoan -> change(draft.copy(loans = draft.loans.map { loan ->
                if (loan.id != event.id) loan else when (event.field) {
                    DebtLoanField.NAME -> loan.copy(name = event.text)
                    DebtLoanField.PRINCIPAL -> loan.copy(principal = event.text)
                    DebtLoanField.ANNUAL_PERCENT -> loan.copy(annualPercent = event.text)
                    DebtLoanField.MINIMUM -> loan.copy(minimum = event.text)
                    DebtLoanField.FEE -> loan.copy(fee = event.text)
                }
            }))
            is DebtScenarioEvent.AcknowledgeTerms -> change(draft.copy(supportedTermsAcknowledged = event.checked))
            DebtScenarioEvent.AddLoan -> if (draft.loans.size < 12) change(draft.copy(loans = draft.loans + DebtLoanDraft(nextId++)))
            is DebtScenarioEvent.RemoveLoan -> if (draft.loans.size > 1) change(draft.copy(loans = draft.loans.filterNot { it.id == event.id }))
            DebtScenarioEvent.Reset -> change(DebtScenarioDraft(currency = draft.currency), severSaved = true)
            DebtScenarioEvent.Compute -> compute()
            is DebtScenarioEvent.OpenSchedule -> showPage(event.strategy, 0)
            is DebtScenarioEvent.SchedulePage -> mutable.value.schedule?.let { showPage(it.strategy, event.page) }
            DebtScenarioEvent.BackToResult -> mutable.value = mutable.value.copy(schedule = null)
            is DebtScenarioEvent.EditSaveTitle -> mutable.value = mutable.value.copy(saveTitle = event.text.take(65), message = null)
            DebtScenarioEvent.SaveAsNew -> saveAsNew()
            DebtScenarioEvent.UpdateSaved -> updateSaved()
            is DebtScenarioEvent.LoadSaved -> load(event.id)
            is DebtScenarioEvent.RenameSaved -> rename(event.id, event.title)
            is DebtScenarioEvent.DeleteSaved -> delete(event.id)
            DebtScenarioEvent.DismissMessage -> mutable.value = mutable.value.copy(message = null)
        }
    }

    private fun change(draft: DebtScenarioDraft, severSaved: Boolean = false) {
        generation++
        job?.cancel()
        val previous = mutable.value
        mutable.value = DebtScenarioState(
            draft = draft,
            saved = previous.saved,
            loaded = if (severSaved) null else previous.loaded,
            saveTitle = previous.saveTitle,
        )
    }

    private fun compute() {
        job?.cancel()
        val ticket = ++generation
        val captured = mutable.value.draft
        mutable.value = mutable.value.copy(computing = true, errors = emptySet(), outcome = null, schedule = null)
        job = viewModelScope.launch {
            try {
                val result = withContext(dispatcher) { solver(captured) }
                if (ticket == generation && mutable.value.draft == captured) {
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

    // Saved-terms persistence. Each operation captures its request immutably and is
    // fenced by the same generation as the solver: a late save/load completion never
    // overwrites a session the user edited meanwhile, and a currency edit or reset
    // severs the loaded binding so no stale update can relabel another currency.

    private fun saveAsNew() {
        if (mutable.value.saving) return
        val draft = mutable.value.draft
        val title = mutable.value.saveTitle.trim()
        if (title.isEmpty() || title.length > 64) {
            mutable.value = mutable.value.copy(message = DebtSaveMessage.TITLE_INVALID)
            return
        }
        if (debtDraftErrors(draft).isNotEmpty()) {
            mutable.value = mutable.value.copy(message = DebtSaveMessage.FAILED)
            return
        }
        persist(severOnConflictId = null, run = {
            savedPlans.save(SavedFinancialPlan(
                id = newPlanId(),
                kind = SavedFinancialPlanKind.DEBT,
                title = title,
                currency = draft.currency,
                payloadVersion = DEBT_DOCUMENT_VERSION,
                payloadJson = encodeDebtScenarioDocument(draft),
                revision = 0L,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
            ), expectedRevision = null)
        }, applied = { savedNow ->
            mutable.value = mutable.value.copy(
                message = DebtSaveMessage.SAVED,
                saveTitle = "",
                loaded = DebtLoadedPlan(savedNow.id, savedNow.title, savedNow.revision, savedNow.currency),
            )
        })
    }

    /** Update keeps the loaded identity and exactly its captured revision; currency never relabels. */
    private fun updateSaved() {
        if (mutable.value.saving) return
        val loaded = mutable.value.loaded ?: return
        val draft = mutable.value.draft
        if (debtDraftErrors(draft).isNotEmpty()) {
            mutable.value = mutable.value.copy(message = DebtSaveMessage.FAILED)
            return
        }
        persist(severOnConflictId = loaded.id, run = {
            val current = savedPlans.get(loaded.id) ?: throw SavedFinancialPlanValidationException()
            savedPlans.save(current.copy(
                title = loaded.title,
                payloadVersion = DEBT_DOCUMENT_VERSION,
                payloadJson = encodeDebtScenarioDocument(draft),
            ), expectedRevision = loaded.revision)
        }, applied = { savedNow ->
            mutable.value = mutable.value.copy(
                message = DebtSaveMessage.UPDATED,
                loaded = DebtLoadedPlan(savedNow.id, savedNow.title, savedNow.revision, savedNow.currency),
            )
        })
    }

    private fun rename(id: String, rawTitle: String) {
        if (mutable.value.saving) return
        val title = rawTitle.trim()
        if (title.isEmpty() || title.length > 64) {
            mutable.value = mutable.value.copy(message = DebtSaveMessage.TITLE_INVALID)
            return
        }
        persist(severOnConflictId = id, run = {
            val current = savedPlans.get(id) ?: throw SavedFinancialPlanValidationException()
            savedPlans.save(current.copy(title = title), expectedRevision = current.revision)
        }, applied = { savedNow ->
            val loaded = mutable.value.loaded
            mutable.value = mutable.value.copy(
                message = DebtSaveMessage.RENAMED,
                loaded = if (loaded?.id == savedNow.id) loaded.copy(title = savedNow.title, revision = savedNow.revision) else loaded,
            )
        })
    }

    private fun delete(id: String) {
        if (mutable.value.saving) return
        val entry = mutable.value.saved.firstOrNull { it.plan.id == id } ?: return
        val revision = entry.plan.revision
        mutable.value = mutable.value.copy(saving = true, message = null)
        persistence?.cancel()
        persistence = viewModelScope.launch {
            try {
                withContext(dispatcher) { savedPlans.delete(id, revision) }
                mutable.value = mutable.value.copy(
                    saving = false,
                    message = DebtSaveMessage.DELETED,
                    loaded = if (mutable.value.loaded?.id == id) null else mutable.value.loaded,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SavedFinancialPlanConflictException) {
                persistenceFinished(DebtSaveMessage.CONFLICT, severId = id)
            } catch (_: Exception) {
                persistenceFinished(DebtSaveMessage.FAILED, severId = null)
            }
        }
    }

    private fun load(id: String) {
        val entry = mutable.value.saved.firstOrNull { it.plan.id == id } ?: return
        if (!entry.available) {
            mutable.value = mutable.value.copy(message = DebtSaveMessage.UNAVAILABLE)
            return
        }
        val ticket = ++generation
        job?.cancel()
        mutable.value = mutable.value.copy(message = null)
        viewModelScope.launch {
            try {
                val plan = withContext(dispatcher) { savedPlans.get(id) }
                val decoded = plan?.let {
                    if (it.kind == SavedFinancialPlanKind.DEBT && it.payloadVersion == DEBT_DOCUMENT_VERSION) {
                        withContext(dispatcher) {
                            decodeDebtScenarioDocument(it.payloadJson)?.takeIf { draft -> debtDraftErrors(draft).isEmpty() }
                        }
                    } else {
                        null
                    }
                }
                if (plan == null || decoded == null) {
                    if (ticket == generation) mutable.value = mutable.value.copy(message = DebtSaveMessage.UNAVAILABLE)
                    return@launch
                }
                if (ticket != generation) return@launch // the user edited or loaded again; keep their session
                mutable.value = DebtScenarioState(
                    draft = decoded,
                    saved = mutable.value.saved,
                    loaded = DebtLoadedPlan(plan.id, plan.title, plan.revision, plan.currency),
                    saveTitle = mutable.value.saveTitle,
                )
                nextId = (decoded.loans.maxOfOrNull { loan -> loan.id } ?: 0) + 1
                compute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) mutable.value = mutable.value.copy(message = DebtSaveMessage.UNAVAILABLE)
            }
        }
    }

    /** One persistence ticket; encoding and repository work stay off Main. */
    private fun persist(severOnConflictId: String?, run: suspend () -> SavedFinancialPlan, applied: (SavedFinancialPlan) -> Unit) {
        val ticket = generation
        mutable.value = mutable.value.copy(saving = true, message = null)
        persistence?.cancel()
        persistence = viewModelScope.launch {
            try {
                val savedNow = withContext(dispatcher) { run() }
                if (ticket == generation) applied(savedNow)
                mutable.value = mutable.value.copy(saving = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SavedFinancialPlanConflictException) {
                persistenceFinished(DebtSaveMessage.CONFLICT, severId = severOnConflictId)
            } catch (_: Exception) {
                persistenceFinished(DebtSaveMessage.FAILED, severId = null)
            }
        }
    }

    /** Failures never touch the draft; only the conflicting binding is detached. */
    private fun persistenceFinished(message: DebtSaveMessage, severId: String?) {
        val loaded = mutable.value.loaded
        mutable.value = mutable.value.copy(
            saving = false,
            message = message,
            loaded = if (severId != null && loaded?.id == severId) null else loaded,
        )
    }

    private fun run(strategy: DebtStrategy): StrategyRun? = when (val outcome = mutable.value.outcome) {
        is DebtPayoffOutcome.Compared -> if (strategy == DebtStrategy.AVALANCHE) outcome.avalanche else outcome.snowball
        is DebtPayoffOutcome.HorizonExhausted -> if (strategy == DebtStrategy.AVALANCHE) outcome.avalanche else outcome.snowball
        else -> null
    }

    private fun showPage(strategy: DebtStrategy, requested: Int) {
        val run = run(strategy) ?: return
        val lastPage = ((run.ledger.size - 1).coerceAtLeast(0)) / CYCLES_PER_PAGE
        val page = requested.coerceIn(0, lastPage)
        mutable.value = mutable.value.copy(schedule = DebtSchedulePage(
            strategy, run.ledger.drop(page * CYCLES_PER_PAGE).take(CYCLES_PER_PAGE), run.ledger.size,
            page, page > 0, page < lastPage,
        ))
    }
}

private const val CYCLES_PER_PAGE = 6
