package com.athar.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isReconciliation
import com.athar.core.domain.repo.RecurringRuleRepository
import com.athar.core.domain.repo.RecurringSuggestionRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.feature.plan.bills.ManualRuleValidation
import com.athar.feature.plan.bills.validateManualRuleDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(
    private val rules: RecurringRuleRepository,
    transactions: TransactionRepository,
    suggestions: RecurringSuggestionRepository,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    /**
     * Bumped by [BillsEvent.RefreshDate] so a day rollover (midnight, clock advance)
     * re-derives the state from a fresh clock reading even when no data flow emits.
     */
    private val dateRefresh = MutableStateFlow(0)

    /** Manual-commitment editor sheet state, or null while the sheet is closed. */
    private val manualRuleEditor = MutableStateFlow<ManualRuleEditorState?>(null)

    /**
     * Latest display currency seen from preferences — seeds a freshly opened editor
     * draft so the user never has to retype their currency.
     */
    @Volatile
    private var lastDisplayCurrency: String = Money.SAR

    private val obligationsAndDiscovery = combine(
        rules.observeAll(),
        transactions.observeAll(),
        suggestions.observeSuggestions(),
    ) { recurringRules, allTransactions, detectedSuggestions ->
        Inputs(recurringRules, allTransactions, detectedSuggestions)
    }

    private val preferences = combine(
        prefs.displayCurrency(),
        prefs.billRemindersEnabled(),
        dateRefresh,
    ) { displayCurrency, remindersEnabled, refreshTick ->
        lastDisplayCurrency = displayCurrency
        PreferencesSnapshot(displayCurrency, remindersEnabled, refreshTick)
    }

    val state: StateFlow<BillsState> = combine(
        obligationsAndDiscovery,
        preferences,
        manualRuleEditor,
    ) { inputs, snapshot, editor ->
        derive(inputs, snapshot, editor)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillsState.initial())

    fun onEvent(event: BillsEvent) {
        when (event) {
            is BillsEvent.SetRemindersEnabled -> viewModelScope.launch {
                prefs.setBillRemindersEnabled(event.enabled)
            }
            BillsEvent.RefreshDate -> dateRefresh.value += 1
            is BillsEvent.CreateRuleFromSuggestion -> createRuleFrom(event.suggestion)
            is BillsEvent.SetRuleActive -> viewModelScope.launch {
                rules.setActive(event.ruleId, event.active)
            }
            is BillsEvent.CancelRule -> viewModelScope.launch {
                rules.delete(event.ruleId)
            }
            BillsEvent.OpenManualRuleEditor -> manualRuleEditor.value = ManualRuleEditorState(
                draft = ManualCommitmentDraft(
                    currency = lastDisplayCurrency,
                    nextDateText = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),
                ),
            )
            BillsEvent.DismissManualRuleEditor -> manualRuleEditor.value = null
            is BillsEvent.UpdateManualRuleDraft -> {
                val current = manualRuleEditor.value ?: return
                manualRuleEditor.value = current.copy(
                    draft = event.draft,
                    // Editing after a failure is a fresh correction attempt, not a retry.
                    status = if (current.status is ManualRuleStatus.Failed) {
                        ManualRuleStatus.Editing
                    } else {
                        current.status
                    },
                )
            }
            BillsEvent.SubmitManualRule -> submitManualRule()
        }
    }

    /**
     * Explicit creation: the detected proposal becomes an active rule preserving its
     * real detected cadence and anchors, suggested routing, and the detector's
     * next-run estimate. Materializing the rule with the same identity fields the
     * detector grouped on also suppresses the proposal (typed suppression key), so
     * the pattern moves from "possible commitment" to the expected list in one step.
     */
    private fun createRuleFrom(suggestion: RecurringSuggestion) {
        val now = clock.now()
        val rule = RecurringRule(
            id = UUID.randomUUID().toString(),
            displayName = suggestion.merchant,
            merchant = suggestion.merchant,
            amount = suggestion.amount,
            type = suggestion.type,
            accountId = suggestion.suggestedAccountId
                ?.takeIf { it.isNotBlank() }
                ?: MANUAL_ACCOUNT_ID,
            categoryId = suggestion.suggestedCategoryId?.takeIf { it.isNotBlank() },
            cadence = suggestion.cadence,
            dayOfMonth = if (suggestion.cadence != Cadence.WEEKLY) suggestion.typicalDayOfMonth else null,
            dayOfWeek = if (suggestion.cadence == Cadence.WEEKLY) suggestion.dayOfWeek else null,
            monthOfYear = if (suggestion.cadence == Cadence.YEARLY) suggestion.monthOfYear else null,
            nextRunDate = suggestion.suggestedNextRun,
            lastRunDate = null,
            isActive = true,
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch { rules.upsert(rule) }
    }

    /**
     * Validate and save the current editor draft. The in-flight guard is checked and
     * set synchronously here — before any coroutine launch — so a duplicate submit
     * while a save is running can never enqueue a second write. Validation failures
     * never touch storage and never clear the draft; a storage failure surfaces a
     * typed retryable error with the draft intact; only a successful upsert closes
     * the editor (the rule then arrives via `observeAll()` — no local shadow copy).
     */
    private fun submitManualRule() {
        val current = manualRuleEditor.value ?: return
        if (current.status is ManualRuleStatus.Saving) return
        when (val validation = validateManualRuleDraft(current.draft)) {
            is ManualRuleValidation.Invalid ->
                manualRuleEditor.value =
                    current.copy(status = ManualRuleStatus.Failed(validation.error))
            is ManualRuleValidation.Valid -> {
                val saving = current.copy(status = ManualRuleStatus.Saving)
                manualRuleEditor.value = saving
                viewModelScope.launch {
                    try {
                        rules.upsert(buildManualRule(validation, clock.now(), saving.draft))
                        // Only close the sheet this save belongs to: if the user dismissed
                        // (or replaced the editor) while the write was in flight, success
                        // must not resurrect a closed editor from a stale snapshot.
                        if (manualRuleEditor.value === saving) manualRuleEditor.value = null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Storage failed: typed retryable error, draft preserved verbatim.
                        // Same identity guard — a dismissed sheet stays dismissed.
                        if (manualRuleEditor.value === saving) {
                            manualRuleEditor.value =
                                saving.copy(status = ManualRuleStatus.Failed(BillsManualRuleError.SAVE_FAILED))
                        }
                    }
                }
            }
        }
    }

    /**
     * Explicit manual commitment → one [RecurringRule] through the real repository.
     * Anchors derive from the chosen next date exactly as `canonicalScheduleRule`
     * expects explicit fields; the blank-account convention mirrors suggestion
     * creation ([MANUAL_ACCOUNT_ID]); account and category are never mandatory.
     * Nothing is created automatically and no charge is synthesized — the rule only
     * produces expected occurrences and PENDING tray entries via the materializer.
     */
    private fun buildManualRule(
        valid: ManualRuleValidation.Valid,
        now: Instant,
        draft: ManualCommitmentDraft,
    ): RecurringRule {
        val next = valid.nextRunDate
        return RecurringRule(
            id = UUID.randomUUID().toString(),
            displayName = valid.displayName,
            merchant = valid.displayName,
            amount = Money.of(valid.amount.setScale(2, RoundingMode.UNNECESSARY), valid.currency),
            type = TxType.EXPENSE,
            accountId = MANUAL_ACCOUNT_ID,
            categoryId = null,
            cadence = valid.cadence,
            dayOfMonth = if (valid.cadence != Cadence.WEEKLY) next.dayOfMonth else null,
            dayOfWeek = if (valid.cadence == Cadence.WEEKLY) next.dayOfWeek.value else null,
            monthOfYear = if (valid.cadence == Cadence.YEARLY) next.monthNumber else null,
            nextRunDate = next,
            lastRunDate = null,
            isActive = true,
            notes = draft.notes.trim().takeIf { it.isNotEmpty() },
            createdAt = now,
            updatedAt = now,
        )
    }

    private data class Inputs(
        val recurringRules: List<RecurringRule>,
        val transactions: List<Transaction>,
        val suggestions: List<RecurringSuggestion>,
    )

    private data class PreferencesSnapshot(
        val displayCurrency: String,
        val remindersEnabled: Boolean,
        val refreshTick: Int,
    )

    private fun derive(inputs: Inputs, snapshot: PreferencesSnapshot, editor: ManualRuleEditorState?): BillsState {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val horizonEnd = today.plus(DatePeriod(days = HORIZON_DAYS))
        val next30End = today.plus(DatePeriod(days = TOTAL_DAYS))

        val evidence = inputs.transactions
            .filter { it.status == TxStatus.CONFIRMED }
            .filterNot { it.isReconciliation() }

        // The displayed list stays the bounded UI projection around today. Matching runs
        // over the wider guard universe: occurrences hidden from the list (pre-anchor
        // history, cap-overflowed windows) join the same consume-at-most-once graph, so
        // a charge with two real candidate owners can never confirm one of them by
        // accident, and evidence dated after today (asOf) can settle nothing.
        val universe = buildMatchingUniverse(
            rules = inputs.recurringRules,
            today = today,
            uiHorizonDays = HORIZON_DAYS,
        )
        val observedMatches = resolveObservedMatches(universe.matchOccurrences, evidence, asOf = today)

        val items = universe.uiOccurrences
            .map { (rule, dueDate) ->
                val key = occurrenceKey(rule, dueDate)
                val match = observedMatches[key]
                BillItem(
                    id = key,
                    ruleId = rule.id,
                    title = rule.displayName.ifBlank { rule.merchant },
                    detail = rule.merchant
                        .takeUnless { it.equals(rule.displayName, ignoreCase = true) }
                        .orEmpty(),
                    amount = rule.amount,
                    type = rule.type,
                    dueDate = dueDate,
                    cadence = rule.cadence.billLabelKey(),
                    status = billStatusFor(dueDate, today, match),
                    evidenceTransactionId = match?.id,
                    evidenceDate = match?.date,
                    // The matcher's own bound transaction, carried verbatim for the
                    // drilldown — no recent-N cap, no synthesized rows.
                    evidence = match?.let { bound ->
                        BillEvidenceRecord(
                            transactionId = bound.id,
                            date = bound.date,
                            amount = bound.amount,
                            accountId = bound.accountId,
                            merchant = bound.merchant,
                        )
                    },
                )
            }
            .sortedWith(
                compareBy<BillItem> { it.dueDate }
                    .thenBy { it.status.ordinal }
                    .thenBy { it.title.lowercase() },
            )

        val outgoingNext30ByCurrency = items
            .asSequence()
            .filter { it.type == TxType.EXPENSE }
            // Half-open [today, today+30): the summary aggregates exactly its advertised
            // 30-day bound (2026-10-24 → 2026-11-23 on device). The wider 60-day list
            // window stays on the bounds line; settled/older occurrences are never reserved.
            .filter { it.dueDate >= today && it.dueDate < next30End }
            .filter { it.status != BillStatus.PAID }
            .groupBy { it.amount.currency }
            .map { (currency, currencyItems) ->
                CurrencyTotal(
                    currency = currency,
                    total = currencyItems.fold(Money.zero(currency)) { acc, item -> acc + item.amount },
                )
            }
            .sortedBy { it.currency }
            .toImmutableList()

        return BillsState(
            today = today,
            horizonEnd = horizonEnd,
            outgoingNext30ByCurrency = outgoingNext30ByCurrency,
            calendarDays = buildCalendarDays(today, items).toImmutableList(),
            items = items.toImmutableList(),
            suggestedCommitments = inputs.suggestions
                .take(MAX_SUGGESTED_SHOWN)
                .map { suggestion -> suggestion.toUi() }
                .toImmutableList(),
            coverage = BillsCoverage(
                uiHistoryWindowStart = universe.uiHistoryWindowStart,
                uiHistoryTruncated = universe.uiHistoryTruncated,
                uiFutureTruncated = universe.uiFutureTruncated,
                matchGuardWindowStart = universe.matchGuardWindowStart,
                matchGuardComplete = universe.matchGuardComplete,
            ),
            remindersEnabled = snapshot.remindersEnabled,
            manualRuleEditor = editor,
            isLoading = false,
        )
    }

    private fun RecurringSuggestion.toUi(): SuggestedCommitment = SuggestedCommitment(
        merchant = merchant,
        amount = amount,
        cadence = cadence.billLabelKey(),
        nextRun = suggestedNextRun,
        source = this,
    )

    private fun buildCalendarDays(today: LocalDate, items: List<BillItem>): List<BillCalendarDay> =
        (0 until CALENDAR_DAYS).map { offset ->
            val date = today.plus(DatePeriod(days = offset))
            BillCalendarDay(
                date = date,
                count = items.count { it.dueDate == date },
            )
        }

    companion object {
        private const val HORIZON_DAYS = 60
        private const val TOTAL_DAYS = 30
        private const val CALENDAR_DAYS = 14
        private const val MAX_SUGGESTED_SHOWN = 3
    }
}
