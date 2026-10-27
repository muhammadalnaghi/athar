package com.athar.feature.plan

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.core.domain.model.TxType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate

data class BillsState(
    val today: LocalDate?,
    val horizonEnd: LocalDate?,
    /**
     * Outgoing total for the next 30 days, one entry per currency. Currencies are never
     * summed together or relabeled into the display currency (no 1:1 no-FX projection).
     */
    val outgoingNext30ByCurrency: ImmutableList<CurrencyTotal>,
    val calendarDays: ImmutableList<BillCalendarDay>,
    val items: ImmutableList<BillItem>,
    /** Genuine recurring patterns detected in history — proposals only, not obligations. */
    val suggestedCommitments: ImmutableList<SuggestedCommitment>,
    /**
     * Honest bounded-coverage reporting, or null while loading. UI coverage describes
     * the displayed occurrence list; match-guard coverage describes the wider window
     * the paid-matching uniqueness graph accounted for. Truncation never implies a bill
     * was missed or left unpaid.
     */
    val coverage: BillsCoverage?,
    val remindersEnabled: Boolean,
    /** Explicit manual-commitment editor, or null while the sheet is closed. */
    val manualRuleEditor: ManualRuleEditorState? = null,
    val isLoading: Boolean,
) {
    companion object {
        fun initial(): BillsState = BillsState(
            today = null,
            horizonEnd = null,
            outgoingNext30ByCurrency = persistentListOf(),
            calendarDays = persistentListOf(),
            items = persistentListOf(),
            suggestedCommitments = persistentListOf(),
            coverage = null,
            remindersEnabled = false,
            manualRuleEditor = null,
            isLoading = true,
        )
    }
}

/**
 * Bounded coverage of the Bills view. [uiHistoryTruncated]/[uiFutureTruncated] report
 * the displayed list (older/newer occurrences exist beyond the shown window, with
 * [uiHistoryWindowStart] as its start); [matchGuardWindowStart]/[matchGuardComplete]
 * report the matching guard universe separately. Neither kind of truncation means an
 * actual bill was missed or left unpaid — payment status is only ever derived from real
 * confirmed evidence.
 */
data class BillsCoverage(
    val uiHistoryWindowStart: LocalDate?,
    val uiHistoryTruncated: Boolean,
    val uiFutureTruncated: Boolean,
    val matchGuardWindowStart: LocalDate?,
    val matchGuardComplete: Boolean,
)

data class CurrencyTotal(
    val currency: String,
    val total: Money,
)

data class BillCalendarDay(
    val date: LocalDate,
    val count: Int,
)

data class BillItem(
    val id: String,
    /** Owning rule — the target of pause/resume/cancel actions from the detail sheet. */
    val ruleId: String,
    val title: String,
    val detail: String,
    val amount: Money,
    val type: TxType,
    val dueDate: LocalDate,
    val cadence: BillCadenceLabel?,
    val status: BillStatus,
    /** Confirmed transaction backing a [BillStatus.PAID] status — traceable matching evidence. */
    val evidenceTransactionId: String? = null,
    /** Charge date of the evidence transaction, when paid. */
    val evidenceDate: LocalDate? = null,
    /**
     * The exact matching stored transaction, when paid — same object the one-to-one
     * matcher bound, never a synthesized or capped record. Basis for the drilldown.
     */
    val evidence: BillEvidenceRecord? = null,
)

enum class BillStatus {
    /** Scheduled occurrence in the future. */
    UPCOMING,
    /** Scheduled occurrence due today. */
    DUE_TODAY,
    /** Past occurrence with exactly one matching confirmed charge. */
    PAID,
    /**
     * Past occurrence whose payment evidence is absent or ambiguous. This is a review
     * state — Athar never asserts that a charge was missed or left unpaid.
     */
    UNCONFIRMED,
}

fun Cadence.billLabelKey(): BillCadenceLabel = when (this) {
    Cadence.MONTHLY -> BillCadenceLabel.MONTHLY
    Cadence.WEEKLY -> BillCadenceLabel.WEEKLY
    Cadence.QUARTERLY -> BillCadenceLabel.QUARTERLY
    Cadence.YEARLY -> BillCadenceLabel.YEARLY
}

enum class BillCadenceLabel {
    MONTHLY,
    WEEKLY,
    QUARTERLY,
    YEARLY,
}

/**
 * The exact stored transaction that settled a PAID occurrence. Carried straight from
 * the matcher's bound [com.athar.core.domain.model.Transaction] — no recent-N cap, no
 * synthesized rows, no currency relabeling.
 */
data class BillEvidenceRecord(
    val transactionId: String,
    val date: LocalDate,
    val amount: Money,
    val accountId: String,
    val merchant: String,
)

/** User-authored draft for the explicit manual commitment editor. Never auto-filled. */
data class ManualCommitmentDraft(
    val name: String = "",
    val amountText: String = "",
    val currency: String = "SAR",
    val cadence: BillCadenceLabel = BillCadenceLabel.MONTHLY,
    /** ISO-8601 local date text as typed by the user (e.g. 2026-12-01). */
    val nextDateText: String = "",
    /** Optional editable detail — stored verbatim on the rule when non-blank. */
    val notes: String = "",
)

/** Typed, user-facing refusal reasons for a manual commitment save. */
enum class BillsManualRuleError {
    NAME_REQUIRED,
    AMOUNT_INVALID,
    AMOUNT_NOT_POSITIVE,
    /** Meaningful scale beyond the persisted minor-unit scale — refused, never truncated. */
    AMOUNT_PRECISION_UNSUPPORTED,
    CURRENCY_INVALID,
    DATE_INVALID,
    /** The repository write itself failed; the draft is kept so the user can retry. */
    SAVE_FAILED,
}

sealed interface ManualRuleStatus {
    data object Editing : ManualRuleStatus
    data object Saving : ManualRuleStatus
    data class Failed(val error: BillsManualRuleError) : ManualRuleStatus
}

/** Editor sheet state: the live draft plus where the save attempt stands. */
data class ManualRuleEditorState(
    val draft: ManualCommitmentDraft,
    val status: ManualRuleStatus = ManualRuleStatus.Editing,
)

/**
 * Display projection of one detected pattern. [source] carries the full detected
 * evidence so "create commitment" materializes an explicit rule without a round
 * trip through Settings — proposals only ever become obligations by explicit action.
 */
data class SuggestedCommitment(
    val merchant: String,
    val amount: Money,
    val cadence: BillCadenceLabel,
    val nextRun: LocalDate,
    val source: RecurringSuggestion,
)

sealed interface BillsEvent {
    data class SetRemindersEnabled(val enabled: Boolean) : BillsEvent

    /** Turn a detected pattern into an explicit active recurring rule (user action). */
    data class CreateRuleFromSuggestion(val suggestion: RecurringSuggestion) : BillsEvent

    /** Pause or resume the rule behind an expected occurrence. */
    data class SetRuleActive(val ruleId: String, val active: Boolean) : BillsEvent

    /** Delete the rule behind an expected occurrence — permanent cancel, confirmed in UI. */
    data class CancelRule(val ruleId: String) : BillsEvent

    /** Re-read the clock so a past-midnight or clock-advance day rollover is picked up. */
    data object RefreshDate : BillsEvent

    /** Open the explicit manual-commitment editor (empty draft, display currency seeded). */
    data object OpenManualRuleEditor : BillsEvent

    /** Close the manual-commitment editor, discarding the draft. */
    data object DismissManualRuleEditor : BillsEvent

    /** Replace the editor draft as the user types. */
    data class UpdateManualRuleDraft(val draft: ManualCommitmentDraft) : BillsEvent

    /**
     * Validate and save the current draft. Also the retry path after a failed save —
     * the draft is preserved verbatim, so resubmitting retries the same commitment.
     */
    data object SubmitManualRule : BillsEvent
}
