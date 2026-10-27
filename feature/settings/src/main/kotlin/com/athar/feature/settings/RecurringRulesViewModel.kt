package com.athar.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.domain.calc.RecurringSchedule
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.RecurringRuleRepository
import com.athar.core.domain.repo.RecurringSuggestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RecurringRulesViewModel @Inject constructor(
    private val rules: RecurringRuleRepository,
    private val suggestionRepo: RecurringSuggestionRepository,
    private val categoryRepo: CategoryRepository,
    private val accountRepo: AccountRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<List<RecurringRule>> =
        rules.observeAll(includeInactive = true)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggestions: StateFlow<List<RecurringSuggestion>> =
        suggestionRepo.observeSuggestions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<com.athar.core.domain.model.Category>> =
        categoryRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<com.athar.core.domain.model.Account>> =
        accountRepo.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _materializeStatus = MutableStateFlow(0)
    val lastMaterializeCount: StateFlow<Int> = _materializeStatus.asStateFlow()

    private val _lastAccepted = MutableStateFlow<String?>(null)
    val lastAccepted: StateFlow<String?> = _lastAccepted.asStateFlow()

    fun clearLastAccepted() { _lastAccepted.value = null }

    fun add(
        displayName: String,
        merchant: String,
        amountText: String,
        currency: String,
        type: TxType,
        accountId: String?,
        categoryId: String?,
        cadence: Cadence,
        dayOfMonth: Int?,
        startDate: LocalDate,
    ) {
        val amount = runCatching { Money.of(BigDecimal(amountText.trim()), currency) }.getOrNull() ?: return
        val now = clock.now()
        val rule = RecurringRule(
            id = UUID.randomUUID().toString(),
            displayName = displayName.trim().ifBlank { merchant },
            merchant = merchant.trim(),
            amount = amount,
            type = type,
            accountId = accountId.orManualAccount(),
            categoryId = categoryId,
            cadence = cadence,
            dayOfMonth = dayOfMonth,
            dayOfWeek = null,
            monthOfYear = null,
            nextRunDate = startDate,
            lastRunDate = null,
            isActive = true,
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch { rules.upsert(rule) }
    }

    fun toggle(id: String, active: Boolean) {
        viewModelScope.launch { rules.setActive(id, active) }
    }

    fun delete(id: String) {
        viewModelScope.launch { rules.delete(id) }
    }

    fun updateRouting(
        id: String,
        accountId: String?,
        categoryId: String?,
    ) {
        viewModelScope.launch {
            val current = rules.get(id) ?: return@launch
            rules.upsert(
                current.copy(
                    accountId = accountId.orManualAccount(current.accountId),
                    categoryId = categoryId,
                    updatedAt = clock.now(),
                ),
            )
        }
    }

    fun materializeNow() {
        viewModelScope.launch {
            val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            _materializeStatus.value = rules.materializeDue(today)
        }
    }

    /**
     * Create a rule from a suggestion using user-confirmed overrides from the confirm sheet.
     * Falls back to auto-detected values when the user accepts the defaults. The detected
     * cadence and its anchors (day-of-week / day-of-month / month) are preserved so real
     * weekly, quarterly, and yearly patterns materialize on their true schedule.
     */
    fun acceptSuggestion(
        suggestion: RecurringSuggestion,
        notes: String?,
        cadence: Cadence,
        dayOfMonth: Int?,
        dayOfWeek: Int? = null,
        monthOfYear: Int? = null,
        accountId: String?,
        categoryId: String?,
    ) {
        val now = clock.now()
        val acceptedDayOfMonth = if (cadence != Cadence.WEEKLY) dayOfMonth else null
        val acceptedDayOfWeek = if (cadence == Cadence.WEEKLY) dayOfWeek else null
        val acceptedMonthOfYear = if (cadence == Cadence.YEARLY) monthOfYear else null
        val anchorsChanged = cadence != suggestion.cadence ||
            acceptedDayOfMonth != suggestion.typicalDayOfMonth ||
            acceptedDayOfWeek != suggestion.dayOfWeek ||
            acceptedMonthOfYear != suggestion.monthOfYear
        val nextRunDate = if (anchorsChanged) {
            // suggestedNextRun encodes the DETECTED cadence and anchors. When the user
            // overrides them at acceptance, recompute the next run from the suggestion's
            // lastSeen with the accepted anchors instead of trusting the stale estimate.
            val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
            var cursor = suggestion.lastSeen
            var steps = 0
            while (cursor <= today && steps < ACCEPTED_NEXT_RUN_CATCH_UP_STEPS) {
                cursor = RecurringSchedule.nextRunAfter(
                    fromDate = cursor,
                    cadence = cadence,
                    dayOfMonth = acceptedDayOfMonth,
                    dayOfWeek = acceptedDayOfWeek,
                    monthOfYear = acceptedMonthOfYear,
                )
                steps++
            }
            cursor
        } else {
            suggestion.suggestedNextRun
        }
        val rule = RecurringRule(
            id = UUID.randomUUID().toString(),
            displayName = suggestion.merchant,
            merchant = suggestion.merchant,
            amount = suggestion.amount,
            type = suggestion.type,
            accountId = accountId.orManualAccount(suggestion.suggestedAccountId),
            categoryId = categoryId ?: suggestion.suggestedCategoryId,
            cadence = cadence,
            dayOfMonth = acceptedDayOfMonth,
            dayOfWeek = acceptedDayOfWeek,
            monthOfYear = acceptedMonthOfYear,
            nextRunDate = nextRunDate,
            lastRunDate = null,
            isActive = true,
            notes = notes,
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch {
            rules.upsert(rule)
            _lastAccepted.value = suggestion.merchant
        }
    }

    private fun String?.orManualAccount(fallback: String? = null): String =
        this?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: MANUAL_ACCOUNT_ID
}

/** Upper bound for walking a re-anchored schedule from lastSeen to the next future run. */
private const val ACCEPTED_NEXT_RUN_CATCH_UP_STEPS = 40
