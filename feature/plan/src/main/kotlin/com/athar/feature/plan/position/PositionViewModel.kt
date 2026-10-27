package com.athar.feature.plan.position

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * State holder for the saved dated-position workflow. All codec work and
 * repository calls run off Main ([workDispatcher] test seam, project idiom);
 * state is one immutable [PositionState]; storage semantics (create-only saves,
 * exact-revision updates/deletes, typed conflicts) are the frozen
 * `SavedFinancialPlanRepository` contract — conflicts surface a safe constant
 * token and refresh, never overwrite.
 */
@HiltViewModel
internal class PositionViewModel @Inject constructor(
    private val savedPlans: SavedFinancialPlanRepository,
    private val clock: Clock,
) : ViewModel() {

    /** Test seam: deterministic dispatcher and zone for JVM tests (project idiom). */
    internal constructor(
        workDispatcher: CoroutineDispatcher,
        savedPlans: SavedFinancialPlanRepository,
        clock: Clock,
        timeZone: TimeZone,
    ) : this(savedPlans, clock) {
        this.workDispatcher = workDispatcher
        this.timeZone = timeZone
    }

    private val mutable = MutableStateFlow(PositionState.initial())
    val state: StateFlow<PositionState> = mutable.asStateFlow()

    private var workDispatcher: CoroutineDispatcher = Dispatchers.Default
    private var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /** Ticket for async loads; a newer load supersedes an older in-flight result. */
    private var loadGeneration = 0L

    /** Latest CASH snapshot from observe; source of captured revisions/createdAt. */
    private var cashSnapshot: List<SavedFinancialPlan> = emptyList()

    init {
        viewModelScope.launch {
            savedPlans.observe(SavedFinancialPlanKind.CASH)
                .map { plans ->
                    plans.sortedByDescending { it.updatedAtEpochMillis }
                        .map { plan -> plan to PositionDocumentCodec.decode(plan.payloadJson) }
                }
                .flowOn(workDispatcher)
                .collect { classified ->
                    cashSnapshot = classified.map { it.first }
                    mutable.value = mutable.value.copy(rows = rowsOf(classified))
                }
        }
    }

    fun onEvent(event: PositionEvent) {
        if (mutable.value.isBusy && event != PositionEvent.DismissProblem) return
        when (event) {
            PositionEvent.OpenCreateEditor -> openCreateEditor()
            is PositionEvent.OpenUpdateEditor -> openUpdateEditor(event.planId)
            PositionEvent.DismissEditor -> dismissEditor()
            is PositionEvent.EditField -> editField(event.field, event.value)
            PositionEvent.AddEntry -> addEntry()
            is PositionEvent.RemoveEntry -> removeEntry(event.index)
            is PositionEvent.EditEntry -> editEntry(event.index, event.field, event.value)
            PositionEvent.SaveEditor -> saveEditor()
            is PositionEvent.OpenDoc -> openDoc(event.planId)
            PositionEvent.CloseDoc -> { ++loadGeneration; mutable.value = mutable.value.copy(open = null) }
            is PositionEvent.RequestDelete -> requestDelete(event.planId)
            PositionEvent.ConfirmDelete -> confirmDelete()
            PositionEvent.DismissDelete -> mutable.value = mutable.value.copy(confirmDelete = null)
            PositionEvent.DismissProblem -> mutable.value = mutable.value.copy(problem = null)
        }
    }

    /**
     * Only `balance_anchor` documents become rows; foreign cash-scenario siblings
     * are excluded and unsupported/malformed rows stay retained, marked unloadable.
     */
    private fun rowsOf(
        classified: List<Pair<SavedFinancialPlan, PositionDecodeResult>>,
    ) = classified.mapNotNull { (plan, decoded) ->
        when (decoded) {
            PositionDecodeResult.Foreign -> null
            is PositionDecodeResult.Success -> PositionRow(
                planId = plan.id,
                title = plan.title,
                revision = plan.revision,
                currency = plan.currency,
                entryCount = decoded.document.entries.size,
            )
            is PositionDecodeResult.UnsupportedVersion, is PositionDecodeResult.Malformed -> PositionRow(
                planId = plan.id,
                title = plan.title,
                revision = plan.revision,
                currency = plan.currency,
                entryCount = 0,
                unavailable = true,
            )
        }
    }.toPersistentList()

    private fun openCreateEditor() {
        // Opening the create editor invalidates any in-flight update-editor load so
        // a stale async result can never overwrite or resurrect an editor.
        ++loadGeneration
        mutable.value = mutable.value.copy(
            editor = PositionEditorUi(
                planId = null,
                expectedRevision = null,
                asOfDate = today().toString(),
                entries = persistentListOf(PositionEntryDraft(currency = "SAR")),
            ),
            problem = null,
        )
    }

    /** Synchronous dismissal also invalidates any in-flight editor load. */
    private fun dismissEditor() {
        ++loadGeneration
        mutable.value = mutable.value.copy(editor = null, problem = null)
    }

    private fun openUpdateEditor(planId: String) {
        val ticket = ++loadGeneration
        mutable.value = mutable.value.copy(problem = null)
        viewModelScope.launch {
            val plan = withContext(workDispatcher) { savedPlans.get(planId) }
            if (ticket != loadGeneration) return@launch
            val document = plan?.let { decodedDocument(it) }
            if (plan == null || document == null) {
                mutable.value = mutable.value.copy(problem = PositionProblem.UNSUPPORTED_DOCUMENT)
                return@launch
            }
            mutable.value = mutable.value.copy(
                editor = PositionEditorUi(
                    planId = plan.id,
                    expectedRevision = plan.revision,
                    title = plan.title,
                    asOfDate = document.asOfDate,
                    note = document.note.orEmpty(),
                    entries = document.entries
                        .map { PositionEntryDraft(it.label, it.side, it.currency, it.amount, it.ownerScope) }
                        .toPersistentList(),
                ),
            )
        }
    }

    private fun editField(field: PositionTextField, value: String) {
        val editor = mutable.value.editor ?: return
        mutable.value = mutable.value.copy(
            editor = when (field) {
                PositionTextField.TITLE -> editor.copy(title = value)
                PositionTextField.AS_OF_DATE -> editor.copy(asOfDate = value)
                PositionTextField.NOTE -> editor.copy(note = value)
            },
        )
    }

    private fun addEntry() {
        val editor = mutable.value.editor ?: return
        if (editor.entries.size >= POSITION_MAX_ENTRIES) return
        mutable.value = mutable.value.copy(
            editor = editor.copy(entries = (editor.entries + PositionEntryDraft(currency = editor.entries.firstOrNull()?.currency ?: "SAR")).toPersistentList()),
        )
    }

    private fun removeEntry(index: Int) {
        val editor = mutable.value.editor ?: return
        if (index !in editor.entries.indices) return
        mutable.value = mutable.value.copy(
            editor = editor.copy(entries = editor.entries.toMutableList().apply { removeAt(index) }.toPersistentList()),
        )
    }

    private fun editEntry(index: Int, field: PositionEntryField, value: String) {
        val editor = mutable.value.editor ?: return
        if (index !in editor.entries.indices) return
        val current = editor.entries[index]
        val updated = when (field) {
            PositionEntryField.LABEL -> current.copy(label = value)
            PositionEntryField.CURRENCY -> current.copy(currency = value)
            PositionEntryField.AMOUNT -> current.copy(amount = value)
            PositionEntryField.OWNER_SCOPE -> current.copy(ownerScope = value)
            PositionEntryField.SIDE -> {
                val named = PositionSide.entries.firstOrNull { it.name == value } ?: return
                current.copy(side = if (named == PositionSide.ASSET) PositionSide.LIABILITY else PositionSide.ASSET)
            }
        }
        mutable.value = mutable.value.copy(
            editor = editor.copy(entries = editor.entries.toMutableList().apply { this[index] = updated }.toPersistentList()),
        )
    }

    /** Validated-inputs save with the exact expected revision; repeated taps refused synchronously. */
    private fun saveEditor() {
        val current = mutable.value
        val editor = current.editor ?: return
        if (current.isBusy) return
        val document = documentOf(editor)
        if (document == null) {
            mutable.value = current.copy(problem = PositionProblem.INVALID_INPUT)
            return
        }
        val payload = encodeLoadablePosition(document)
        if (payload == null) {
            mutable.value = current.copy(problem = PositionProblem.UNSUPPORTED_DOCUMENT)
            return
        }
        val existing = editor.planId?.let { id -> cashSnapshot.firstOrNull { it.id == id } }
        val now = clock.now().toEpochMilliseconds()
        val plan = SavedFinancialPlan(
            id = existing?.id ?: UUID.randomUUID().toString(),
            kind = SavedFinancialPlanKind.CASH,
            title = editor.title.trim(),
            currency = document.entries.first().currency,
            payloadVersion = POSITION_PAYLOAD_VERSION,
            payloadJson = payload,
            revision = 0L,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        // Saving closes the editor on success; invalidate in-flight editor/doc loads
        // so a stale async result cannot resurrect a dismissed editor or clobber the
        // freshly reopened document.
        ++loadGeneration
        mutable.value = current.copy(isBusy = true, problem = null)
        viewModelScope.launch {
            try {
                val saved = withContext(workDispatcher) { savedPlans.save(plan, editor.expectedRevision) }
                val state = mutable.value
                val refreshedOpen = state.open?.let { open ->
                    if (open.planId == saved.id) reopen(saved, open) else open
                }
                mutable.value = state.copy(isBusy = false, editor = null, open = refreshedOpen, saveSerial = state.saveSerial + 1L)
            } catch (_: SavedFinancialPlanConflictException) {
                // The stored document won; the user retries explicitly after a refresh.
                mutable.value = mutable.value.copy(isBusy = false, problem = PositionProblem.CONFLICT)
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(isBusy = false)
                throw cancelled
            } catch (_: SavedFinancialPlanValidationException) {
                mutable.value = mutable.value.copy(isBusy = false, problem = PositionProblem.UNSUPPORTED_DOCUMENT)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(isBusy = false, problem = PositionProblem.STORAGE)
            }
        }
    }

    private fun openDoc(planId: String) {
        val ticket = ++loadGeneration
        mutable.value = mutable.value.copy(problem = null)
        viewModelScope.launch {
            val plan = withContext(workDispatcher) { savedPlans.get(planId) }
            if (ticket != loadGeneration) return@launch
            val document = plan?.let { decodedDocument(it) }
            if (plan == null || document == null) {
                mutable.value = mutable.value.copy(problem = PositionProblem.UNSUPPORTED_DOCUMENT)
                return@launch
            }
            mutable.value = mutable.value.copy(
                open = PositionOpen(
                    planId = plan.id,
                    title = plan.title,
                    revision = plan.revision,
                    document = document,
                    totals = PositionTotalsCalc.compute(document, today()),
                ),
            )
        }
    }

    private fun requestDelete(planId: String) {
        val row = mutable.value.rows.firstOrNull { it.planId == planId } ?: return
        mutable.value = mutable.value.copy(confirmDelete = row, problem = null)
    }

    /** Deletion needs the armed confirmation and the exact captured revision. */
    private fun confirmDelete() {
        val current = mutable.value
        if (current.isBusy) return
        val row = current.confirmDelete ?: return
        ++loadGeneration
        mutable.value = current.copy(isBusy = true, problem = null)
        viewModelScope.launch {
            try {
                withContext(workDispatcher) { savedPlans.delete(row.planId, row.revision) }
                val state = mutable.value
                mutable.value = state.copy(
                    isBusy = false,
                    confirmDelete = null,
                    open = state.open?.takeIf { it.planId != row.planId },
                    editor = state.editor?.takeIf { it.planId != row.planId },
                )
            } catch (_: SavedFinancialPlanConflictException) {
                mutable.value = mutable.value.copy(
                    isBusy = false,
                    confirmDelete = null,
                    problem = PositionProblem.CONFLICT,
                )
            } catch (cancelled: CancellationException) {
                mutable.value = mutable.value.copy(isBusy = false)
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    isBusy = false,
                    confirmDelete = null,
                    problem = PositionProblem.STORAGE,
                )
            }
        }
    }

    private fun decodedDocument(plan: SavedFinancialPlan): PositionDocument? =
        (PositionDocumentCodec.decode(plan.payloadJson) as? PositionDecodeResult.Success)?.document

    /** Re-derives the open view from the freshly saved stored inputs, never stale results. */
    private fun reopen(saved: SavedFinancialPlan, open: PositionOpen): PositionOpen? {
        val document = decodedDocument(saved) ?: return null
        return open.copy(
            revision = saved.revision,
            document = document,
            totals = PositionTotalsCalc.compute(document, today()),
        )
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(timeZone).date

    /**
     * Exact authoring validation over the draft; oversized or malformed drafts are
     * refused with a safe token, never truncated and never written.
     */
    private fun documentOf(editor: PositionEditorUi): PositionDocument? {
        val title = editor.title.trim()
        if (title.isEmpty() || title.length > POSITION_MAX_TITLE_CHARS) return null
        if (PositionDocumentCodec.dateOrNull(editor.asOfDate) == null) return null
        val note = editor.note.ifEmpty { null }
        if (note != null && note.length > POSITION_MAX_NOTE_CHARS) return null
        if (editor.entries.isEmpty() || editor.entries.size > POSITION_MAX_ENTRIES) return null
        val entries = editor.entries.map { draft ->
            if (draft.label.isEmpty() || draft.label.length > POSITION_MAX_FIELD_CHARS) return null
            if (draft.currency.isEmpty() || draft.currency.length > POSITION_MAX_FIELD_CHARS) return null
            if (draft.ownerScope.length > POSITION_MAX_FIELD_CHARS) return null
            if (draft.amount.length > POSITION_MAX_FIELD_CHARS) return null
            if (PositionDocumentCodec.decimalOrNull(draft.amount) == null) return null
            PositionEntryDocument(
                id = UUID.randomUUID().toString(),
                label = draft.label,
                side = draft.side,
                currency = draft.currency,
                amount = draft.amount,
                ownerScope = draft.ownerScope,
            )
        }
        return PositionDocument(asOfDate = editor.asOfDate, note = note, entries = entries)
    }
}

