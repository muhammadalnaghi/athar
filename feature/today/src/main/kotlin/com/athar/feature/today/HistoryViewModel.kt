package com.athar.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.isSpecificMerchantKey
import com.athar.core.domain.model.specificMerchantKey
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject

enum class HistoryStatusFilter { ALL, CONFIRMED, PENDING, DISMISSED }
enum class HistoryTypeFilter { ALL, INCOME, EXPENSE, TRANSFER }
enum class HistorySourceFilter { ALL, SMS, NOTIFICATION, MANUAL, IMPORT, RECURRING, SHARE }
enum class HistoryCategoryFilter { ALL, UNCATEGORIZED, REPEATED_UNCATEGORIZED, CATEGORIZED }

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val rules: CategoryRuleRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _status = MutableStateFlow(HistoryStatusFilter.ALL)
    val status: StateFlow<HistoryStatusFilter> = _status.asStateFlow()

    private val _type = MutableStateFlow(HistoryTypeFilter.ALL)
    val type: StateFlow<HistoryTypeFilter> = _type.asStateFlow()

    private val _source = MutableStateFlow(HistorySourceFilter.ALL)
    val source: StateFlow<HistorySourceFilter> = _source.asStateFlow()

    private val _category = MutableStateFlow(HistoryCategoryFilter.ALL)
    val category: StateFlow<HistoryCategoryFilter> = _category.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _lastBulkCategory = MutableStateFlow<BulkCategoryEvent?>(null)
    val lastBulkCategory: StateFlow<BulkCategoryEvent?> = _lastBulkCategory.asStateFlow()

    val categoryLabels: StateFlow<ImmutableMap<String, CategoryLabel>> =
        categories.observeAll(kind = null, includeArchived = true)
            .map { it.toCategoryLabels() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentMapOf())

    private val activeCategories: StateFlow<List<Category>> =
        categories.observeAll(kind = null, includeArchived = false)
            .map { rows -> rows.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name }) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allTransactions: StateFlow<List<Transaction>> =
        transactions.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<Transaction>> =
        combine(
            allTransactions,
            combine(_query, _status, _type, _source, _category) { q, s, t, source, category ->
                HistoryFilterState(q, s, t, source, category)
            },
        ) { all, filter ->
            filterHistoryTransactions(
                all = all,
                query = filter.query,
                status = filter.status,
                type = filter.type,
                source = filter.source,
                category = filter.category,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val historyRows =
        combine(items, allTransactions) { visibleRows, allRows ->
            HistoryRows(visibleRows = visibleRows, allRows = allRows)
        }

    val repeatedBacklogCounts: StateFlow<Map<String, Int>> =
        combine(items, _category) { visibleRows, category ->
            buildRepeatedBacklogCountById(
                visibleRows = visibleRows,
                category = category,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val bulkCategoryState: StateFlow<HistoryBulkCategoryState> =
        combine(
            historyRows,
            _selectedIds,
            _selectionMode,
            activeCategories,
            _category,
        ) { rows, selectedIds, selectionMode, categories, category ->
            buildHistoryBulkCategoryState(
                visibleRows = rows.visibleRows,
                selectedIds = selectedIds,
                selectionMode = selectionMode,
                activeCategories = categories,
                category = category,
                allRows = rows.allRows,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryBulkCategoryState.Empty)

    fun setQuery(q: String) {
        _query.value = q
        clearSelection()
    }

    fun setStatus(s: HistoryStatusFilter) {
        _status.value = s
        clearSelection()
    }

    fun setType(t: HistoryTypeFilter) {
        _type.value = t
        clearSelection()
    }

    fun setSource(s: HistorySourceFilter) {
        _source.value = s
        clearSelection()
    }

    fun setCategory(c: HistoryCategoryFilter) {
        _category.value = c
        clearSelection()
    }

    private val _lastBackfill = MutableStateFlow<BackfillEvent?>(null)
    /** Emits the count of dismissed/pending rows auto-recategorized by "Always categorize…". */
    val lastBackfill: StateFlow<BackfillEvent?> = _lastBackfill.asStateFlow()

    fun clearBackfill() { _lastBackfill.value = null }

    fun clearBulkCategory() { _lastBulkCategory.value = null }

    fun toggleSelectionMode() {
        val next = !_selectionMode.value
        _selectionMode.value = next
        if (!next) _selectedIds.value = emptySet()
    }

    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    fun clearSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelected(id: String) {
        _selectionMode.value = true
        _selectedIds.value = _selectedIds.value.toggle(id)
    }

    fun selectVisibleRows() {
        val visibleIds = items.value.mapTo(mutableSetOf()) { it.id }
        if (visibleIds.isNotEmpty()) {
            _selectionMode.value = true
            _selectedIds.value = visibleIds
        }
    }

    fun selectTopRepeatedBacklogGroup() {
        val topGroupIds = topRepeatedBacklogGroupIds(
            visibleRows = items.value,
            category = _category.value,
        )
        if (topGroupIds.isNotEmpty()) {
            _selectionMode.value = true
            _selectedIds.value = topGroupIds
        }
    }

    fun selectMatchingSelectedMerchants() {
        val visibleRows = items.value
        val selectedMerchantKeys = visibleRows
            .filter { it.id in _selectedIds.value }
            .mapNotNull { it.merchantSelectionKey() }
            .toSet()
        if (selectedMerchantKeys.isNotEmpty()) {
            _selectionMode.value = true
            _selectedIds.value = visibleRows
                .filter { it.merchantSelectionKey() in selectedMerchantKeys }
                .mapTo(mutableSetOf()) { it.id }
        }
    }

    fun updateTransaction(tx: Transaction, learnRule: Boolean) {
        viewModelScope.launch {
            val now = clock.now()
            // ENH-009: an actual category-changing edit is an explicit USER decision —
            // it overwrites any engine-derived provenance (CATALOG/CLASSIFIER) so the
            // learner never treats engine output as user intent. Edits that keep the
            // same category preserve the original provenance.
            val existing = transactions.get(tx.id)
            val provenance = when {
                existing == null -> tx.categoryId?.let { CategorySource.USER }
                existing.categoryId != tx.categoryId -> tx.categoryId?.let { CategorySource.USER }
                else -> existing.categorySource
            }
            val confirmed = tx.copy(status = existing?.status ?: tx.status, updatedAt = now, categorySource = provenance)
            transactions.upsert(confirmed)
            val categoryId = confirmed.categoryId
            val merchantKey = specificMerchantKey(
                merchantNormalized = confirmed.merchantNormalized,
                merchant = confirmed.merchant,
            )
            if (learnRule && categoryId != null && merchantKey != null) {
                rules.learnFromCorrection(
                    merchantNormalized = merchantKey,
                    categoryId = categoryId,
                    patternType = PatternType.SUBSTRING,
                )
                val backfilled = transactions.applyCategoryToMatching(
                    pattern = merchantKey,
                    categoryId = categoryId,
                )
                _lastBackfill.value = BackfillEvent(
                    pattern = confirmed.merchant.ifBlank { confirmed.merchantNormalized },
                    count = backfilled,
                )
            }
        }
    }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { transactions.delete(id) }
    }

    fun applyBulkCategory(categoryId: String) {
        viewModelScope.launch {
            applyBulkCategoryToIds(ids = _selectedIds.value, categoryId = categoryId)
        }
    }

    fun applyTopRepeatedBacklogSuggestedCategory() {
        viewModelScope.launch {
            val visibleRows = items.value
            val topGroupIds = topRepeatedBacklogGroupIds(
                visibleRows = visibleRows,
                category = _category.value,
            )
            val topGroupRows = visibleRows.filter { it.id in topGroupIds }
            val topCategoryKind = topGroupRows.mapNotNull { it.categoryKind() }.distinct().singleOrNull()
            val activeCategoryIds = activeCategories.value
                .filter { it.kind == topCategoryKind && !it.archived }
                .mapTo(mutableSetOf()) { it.id }
            val suggestion = allTransactions.value.suggestedCategoryForSelectedMerchant(
                selectedRows = topGroupRows,
                categoryKind = topCategoryKind,
                activeCategoryIds = activeCategoryIds,
            ) ?: return@launch
            applyBulkCategoryToIds(
                ids = topGroupIds,
                categoryId = suggestion.categoryId,
                keepSelectionMode = true,
            )
        }
    }

    fun applySafeRepeatedBacklogSuggestedCategories() {
        viewModelScope.launch {
            val groups = repeatedBacklogSuggestedGroups(
                visibleRows = items.value,
                category = _category.value,
                allRows = allTransactions.value,
                activeCategories = activeCategories.value,
            )
            if (groups.isEmpty()) return@launch

            val events = groups.mapNotNull { group ->
                applyBulkCategoryToIdsInternal(ids = group.ids, categoryId = group.categoryId)
            }
            if (events.isEmpty()) return@launch
            _lastBulkCategory.value = BulkCategoryEvent(
                applied = events.sumOf { it.applied },
                skipped = events.sumOf { it.skipped },
                exactRuleLearned = events.any { it.exactRuleLearned },
            )
            _selectionMode.value = true
            _selectedIds.value = emptySet()
        }
    }

    private suspend fun applyBulkCategoryToIds(
        ids: Set<String>,
        categoryId: String,
        keepSelectionMode: Boolean = false,
    ) {
        val event = applyBulkCategoryToIdsInternal(ids = ids, categoryId = categoryId) ?: return
        _lastBulkCategory.value = event
        if (keepSelectionMode) {
            _selectionMode.value = true
            _selectedIds.value = emptySet()
        } else {
            clearSelection()
        }
    }

    private suspend fun applyBulkCategoryToIdsInternal(
        ids: Set<String>,
        categoryId: String,
    ): BulkCategoryEvent? {
        val category = categories.get(categoryId)?.takeUnless { it.archived } ?: return null
        val now = clock.now()
        var applied = 0
        var skipped = 0
        val appliedRows = mutableListOf<Transaction>()
        ids.forEach { id ->
            val tx = transactions.get(id)
            if (tx != null && tx.categoryKind() == category.kind) {
                val updated = tx.copy(
                    categoryId = category.id,
                    categorySource = CategorySource.USER,
                    updatedAt = now,
                )
                transactions.upsert(updated)
                appliedRows += updated
                applied += 1
            } else {
                skipped += 1
            }
        }
        val exactRuleLearned = learnExactRuleForRepeatedMerchant(appliedRows, category.id)
        return BulkCategoryEvent(
            applied = applied,
            skipped = skipped,
            exactRuleLearned = exactRuleLearned,
        )
    }

    private suspend fun learnExactRuleForRepeatedMerchant(
        appliedRows: List<Transaction>,
        categoryId: String,
    ): Boolean {
        if (appliedRows.size < 2) return false
        val merchantKey = appliedRows
            .mapNotNull { it.merchantSelectionKey() }
            .distinct()
            .singleOrNull()
            ?.takeIf { it.isSpecificMerchantKey() }
            ?: return false

        val matchingRules = rules.findMatching(merchantKey)
        val exactMatches = matchingRules.filter {
            it.patternType == PatternType.EXACT &&
                it.pattern.trim().equals(merchantKey, ignoreCase = true)
        }
        if (exactMatches.any { it.categoryId == categoryId }) return false
        if (matchingRules.any { it.learnedFromUser && it.patternType != PatternType.EXACT }) return false

        exactMatches
            .filter { it.learnedFromUser }
            .forEach { rules.delete(it.id) }
        rules.learnFromCorrection(
            merchantNormalized = merchantKey,
            categoryId = categoryId,
            patternType = PatternType.EXACT,
        )
        return true
    }

    data class BackfillEvent(val pattern: String, val count: Int)
    data class BulkCategoryEvent(
        val applied: Int,
        val skipped: Int,
        val exactRuleLearned: Boolean = false,
    )
}

private data class HistoryFilterState(
    val query: String,
    val status: HistoryStatusFilter,
    val type: HistoryTypeFilter,
    val source: HistorySourceFilter,
    val category: HistoryCategoryFilter,
)

private data class HistoryRows(
    val visibleRows: List<Transaction>,
    val allRows: List<Transaction>,
)

internal fun filterHistoryTransactions(
    all: List<Transaction>,
    query: String,
    status: HistoryStatusFilter,
    type: HistoryTypeFilter,
    source: HistorySourceFilter,
    category: HistoryCategoryFilter,
): List<Transaction> {
    val baseRows = all.asSequence()
        .filter { tx ->
            when (status) {
                HistoryStatusFilter.ALL -> true
                HistoryStatusFilter.CONFIRMED -> tx.status == TxStatus.CONFIRMED
                HistoryStatusFilter.PENDING -> tx.status == TxStatus.PENDING
                HistoryStatusFilter.DISMISSED -> tx.status == TxStatus.DISMISSED
            }
        }
        .filter { tx ->
            when (type) {
                HistoryTypeFilter.ALL -> true
                HistoryTypeFilter.INCOME -> tx.type == TxType.INCOME
                HistoryTypeFilter.EXPENSE -> tx.type == TxType.EXPENSE
                HistoryTypeFilter.TRANSFER -> tx.type == TxType.TRANSFER
            }
        }
        .filter { tx ->
            when (source) {
                HistorySourceFilter.ALL -> true
                HistorySourceFilter.SMS -> tx.source == IngestSource.SMS
                HistorySourceFilter.NOTIFICATION -> tx.source == IngestSource.NOTIFICATION
                HistorySourceFilter.MANUAL -> tx.source == IngestSource.MANUAL
                HistorySourceFilter.IMPORT -> tx.source == IngestSource.IMPORT
                HistorySourceFilter.RECURRING -> tx.source == IngestSource.RECURRING
                HistorySourceFilter.SHARE -> tx.source == IngestSource.SHARE
            }
        }
        .filter { tx ->
            if (query.isBlank()) {
                true
            } else {
                tx.merchant.contains(query, ignoreCase = true) ||
                    tx.merchantNormalized.contains(query, ignoreCase = true) ||
                    (tx.notes?.contains(query, ignoreCase = true) == true) ||
                    (tx.sourceRefId?.contains(query, ignoreCase = true) == true)
            }
        }
        .toList()

    if (category != HistoryCategoryFilter.REPEATED_UNCATEGORIZED) {
        return baseRows.filter { tx ->
            when (category) {
                HistoryCategoryFilter.ALL -> true
                HistoryCategoryFilter.UNCATEGORIZED -> tx.categoryId.isNullOrBlank()
                HistoryCategoryFilter.CATEGORIZED -> !tx.categoryId.isNullOrBlank()
                HistoryCategoryFilter.REPEATED_UNCATEGORIZED -> false
            }
        }
    }

    val groupStats = baseRows
        .mapIndexedNotNull { index, tx ->
            tx.uncategorizedMerchantGroupKey()?.let { key -> key to index }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, positions) ->
            RepeatedBacklogGroupStats(
                count = positions.size,
                firstIndex = positions.minOrNull() ?: Int.MAX_VALUE,
            )
        }
        .filterValues { it.count > 1 }

    return baseRows
        .mapIndexedNotNull { index, tx ->
            val key = tx.uncategorizedMerchantGroupKey() ?: return@mapIndexedNotNull null
            val stats = groupStats[key] ?: return@mapIndexedNotNull null
            RepeatedBacklogRow(tx, stats, index)
        }
        .sortedWith(
            compareByDescending<RepeatedBacklogRow> { it.stats.count }
                .thenBy { it.stats.firstIndex }
                .thenBy { it.originalIndex },
        )
        .map { it.transaction }
}

internal fun buildRepeatedBacklogCountById(
    visibleRows: List<Transaction>,
    category: HistoryCategoryFilter,
): Map<String, Int> {
    if (category != HistoryCategoryFilter.REPEATED_UNCATEGORIZED) return emptyMap()
    val rowKeys = visibleRows.mapNotNull { tx ->
        tx.uncategorizedMerchantGroupKey()?.let { key -> tx.id to key }
    }
    val groupCounts = rowKeys
        .groupingBy { it.second }
        .eachCount()
    return rowKeys.mapNotNull { (id, key) ->
        groupCounts[key]
            ?.takeIf { it > 1 }
            ?.let { count -> id to count }
    }.toMap()
}

internal data class RepeatedBacklogSummary(
    val groupCount: Int,
    val transactionCount: Int,
    val largestGroupCount: Int,
) {
    companion object {
        val Empty = RepeatedBacklogSummary(groupCount = 0, transactionCount = 0, largestGroupCount = 0)
    }
}

internal fun buildRepeatedBacklogSummary(
    visibleRows: List<Transaction>,
    category: HistoryCategoryFilter,
): RepeatedBacklogSummary {
    if (category != HistoryCategoryFilter.REPEATED_UNCATEGORIZED) return RepeatedBacklogSummary.Empty
    val groupCounts = visibleRows
        .mapNotNull { it.uncategorizedMerchantGroupKey() }
        .groupingBy { it }
        .eachCount()
        .values
        .filter { it > 1 }
    if (groupCounts.isEmpty()) return RepeatedBacklogSummary.Empty
    return RepeatedBacklogSummary(
        groupCount = groupCounts.size,
        transactionCount = groupCounts.sum(),
        largestGroupCount = groupCounts.maxOrNull() ?: 0,
    )
}

internal fun topRepeatedBacklogGroupIds(
    visibleRows: List<Transaction>,
    category: HistoryCategoryFilter,
): Set<String> {
    if (category != HistoryCategoryFilter.REPEATED_UNCATEGORIZED) return emptySet()
    return visibleRows
        .mapIndexedNotNull { index, tx ->
            tx.uncategorizedMerchantGroupKey()?.let { key ->
                RepeatedBacklogCandidate(tx = tx, key = key, originalIndex = index)
            }
        }
        .groupBy { it.key }
        .mapNotNull { (_, rows) ->
            rows.takeIf { it.size > 1 }?.let {
                RepeatedBacklogCandidateGroup(
                    rows = it,
                    count = it.size,
                    firstIndex = it.minOf { row -> row.originalIndex },
                )
            }
        }
        .minWithOrNull(
            compareByDescending<RepeatedBacklogCandidateGroup> { it.count }
                .thenBy { it.firstIndex },
        )
        ?.rows
        ?.sortedBy { it.originalIndex }
        ?.mapTo(mutableSetOf()) { it.tx.id }
        .orEmpty()
}

data class HistoryBulkCategoryState(
    val selectionMode: Boolean,
    val selectedIds: Set<String>,
    val selectedCount: Int,
    val visibleCount: Int,
    val topRepeatedGroupCount: Int,
    val selectedTopRepeatedGroupCount: Int,
    val topRepeatedSuggestedCategory: Category?,
    val topRepeatedSuggestedCategoryUseCount: Int,
    val safeRepeatedSuggestedCategory: Category?,
    val safeRepeatedSuggestedCategoryTransactionCount: Int,
    val safeRepeatedSuggestedGroupCount: Int,
    val safeRepeatedSuggestedTransactionCount: Int,
    val selectedMerchantName: String?,
    val suggestedCategoryId: String?,
    val suggestedCategoryUseCount: Int,
    val matchingMerchantCount: Int,
    val eligibleCount: Int,
    val skippedCount: Int,
    val hasMixedCategoryKinds: Boolean,
    val categories: List<Category>,
) {
    val canApply: Boolean = selectionMode && selectedCount > 0 && eligibleCount > 0 && !hasMixedCategoryKinds
    val canApplySuggestedCategory: Boolean =
        canApply && suggestedCategoryId != null && categories.any { it.id == suggestedCategoryId }
    val canSelectTopRepeatedGroup: Boolean =
        topRepeatedGroupCount > 0 &&
            (selectedCount != topRepeatedGroupCount || selectedTopRepeatedGroupCount != topRepeatedGroupCount)
    val canApplyTopRepeatedSuggestedCategory: Boolean =
        selectionMode && topRepeatedGroupCount > 0 && topRepeatedSuggestedCategory != null
    val canApplySingleSafeRepeatedSuggestedCategory: Boolean =
        selectionMode &&
            safeRepeatedSuggestedGroupCount == 1 &&
            safeRepeatedSuggestedCategory != null &&
            safeRepeatedSuggestedCategoryTransactionCount > 0 &&
            !canApplyTopRepeatedSuggestedCategory
    val canApplySafeRepeatedSuggestedCategories: Boolean =
        selectionMode && safeRepeatedSuggestedGroupCount > 1
    val canShowSafeRepeatedSuggestionsSummary: Boolean =
        canApplySafeRepeatedSuggestedCategories && safeRepeatedSuggestedTransactionCount > 0

    companion object {
        val Empty = HistoryBulkCategoryState(
            selectionMode = false,
            selectedIds = emptySet(),
            selectedCount = 0,
            visibleCount = 0,
            topRepeatedGroupCount = 0,
            selectedTopRepeatedGroupCount = 0,
            topRepeatedSuggestedCategory = null,
            topRepeatedSuggestedCategoryUseCount = 0,
            safeRepeatedSuggestedCategory = null,
            safeRepeatedSuggestedCategoryTransactionCount = 0,
            safeRepeatedSuggestedGroupCount = 0,
            safeRepeatedSuggestedTransactionCount = 0,
            selectedMerchantName = null,
            suggestedCategoryId = null,
            suggestedCategoryUseCount = 0,
            matchingMerchantCount = 0,
            eligibleCount = 0,
            skippedCount = 0,
            hasMixedCategoryKinds = false,
            categories = emptyList(),
        )
    }
}

internal fun buildHistoryBulkCategoryState(
    visibleRows: List<Transaction>,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    activeCategories: List<Category>,
    category: HistoryCategoryFilter = HistoryCategoryFilter.ALL,
    allRows: List<Transaction> = visibleRows,
): HistoryBulkCategoryState {
    val selectedRows = visibleRows.filter { it.id in selectedIds }
    val selectedVisibleIds = selectedRows.mapTo(mutableSetOf()) { it.id }
    val selectedKinds = selectedRows.mapNotNull { it.categoryKind() }.distinct()
    val categoryKind = selectedKinds.singleOrNull()
    val selectedMerchantKeys = selectedRows.mapNotNull { it.merchantSelectionKey() }.toSet()
    val selectedMerchantName = selectedRows.selectedMerchantName()
    val categoriesForSelection = categoryKind
        ?.let { kind ->
            activeCategories
                .filter { it.kind == kind && !it.archived }
                .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name })
        }
        .orEmpty()
    val suggestedCategory = allRows.suggestedCategoryForSelectedMerchant(
        selectedRows = selectedRows,
        categoryKind = categoryKind,
        activeCategoryIds = categoriesForSelection.mapTo(mutableSetOf()) { it.id },
    )
    val topRepeatedGroupIds = topRepeatedBacklogGroupIds(
        visibleRows = visibleRows,
        category = category,
    )
    val topRepeatedGroupRows = visibleRows.filter { it.id in topRepeatedGroupIds }
    val topRepeatedCategoryKind = topRepeatedGroupRows.mapNotNull { it.categoryKind() }.distinct().singleOrNull()
    val topRepeatedCategories = topRepeatedCategoryKind
        ?.let { kind ->
            activeCategories
                .filter { it.kind == kind && !it.archived }
                .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name })
        }
        .orEmpty()
    val topRepeatedSuggestedCategory = allRows.suggestedCategoryForSelectedMerchant(
        selectedRows = topRepeatedGroupRows,
        categoryKind = topRepeatedCategoryKind,
        activeCategoryIds = topRepeatedCategories.mapTo(mutableSetOf()) { it.id },
    )
    val topRepeatedSuggestedCategoryRow = topRepeatedSuggestedCategory
        ?.let { suggestion ->
            topRepeatedCategories.firstOrNull { it.id == suggestion.categoryId }
                ?.let { categoryRow -> categoryRow to suggestion.useCount }
        }
    val safeRepeatedSuggestedGroups = repeatedBacklogSuggestedGroups(
        visibleRows = visibleRows,
        category = category,
        allRows = allRows,
        activeCategories = activeCategories,
    )
    val safeRepeatedSuggestedCategoryRow = safeRepeatedSuggestedGroups
        .firstOrNull()
        ?.let { group ->
            activeCategories.firstOrNull { it.id == group.categoryId && !it.archived }
                ?.let { categoryRow -> categoryRow to group.count }
        }
    return HistoryBulkCategoryState(
        selectionMode = selectionMode,
        selectedIds = selectedVisibleIds,
        selectedCount = selectedRows.size,
        visibleCount = visibleRows.size,
        topRepeatedGroupCount = topRepeatedGroupIds.size,
        selectedTopRepeatedGroupCount = topRepeatedGroupIds.count { it in selectedVisibleIds },
        topRepeatedSuggestedCategory = topRepeatedSuggestedCategoryRow?.first,
        topRepeatedSuggestedCategoryUseCount = topRepeatedSuggestedCategoryRow?.second ?: 0,
        safeRepeatedSuggestedCategory = safeRepeatedSuggestedCategoryRow?.first,
        safeRepeatedSuggestedCategoryTransactionCount = safeRepeatedSuggestedCategoryRow?.second ?: 0,
        safeRepeatedSuggestedGroupCount = safeRepeatedSuggestedGroups.size,
        safeRepeatedSuggestedTransactionCount = safeRepeatedSuggestedGroups.sumOf { it.ids.size },
        selectedMerchantName = selectedMerchantName,
        suggestedCategoryId = suggestedCategory?.categoryId,
        suggestedCategoryUseCount = suggestedCategory?.useCount ?: 0,
        matchingMerchantCount = if (selectedMerchantKeys.isEmpty()) {
            0
        } else {
            visibleRows.count { it.merchantSelectionKey() in selectedMerchantKeys }
        },
        eligibleCount = selectedRows.count { it.categoryKind() != null },
        skippedCount = selectedRows.count { it.categoryKind() == null },
        hasMixedCategoryKinds = selectedKinds.size > 1,
        categories = categoriesForSelection,
    )
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

private fun Transaction.categoryKind(): CategoryKind? = when (type) {
    TxType.EXPENSE -> CategoryKind.EXPENSE
    TxType.INCOME -> CategoryKind.INCOME
    TxType.TRANSFER -> null
}

private data class UncategorizedMerchantGroupKey(
    val merchantKey: String,
    val categoryKind: CategoryKind,
)

private data class RepeatedBacklogGroupStats(
    val count: Int,
    val firstIndex: Int,
)

private data class RepeatedBacklogRow(
    val transaction: Transaction,
    val stats: RepeatedBacklogGroupStats,
    val originalIndex: Int,
)

private data class RepeatedBacklogCandidate(
    val tx: Transaction,
    val key: UncategorizedMerchantGroupKey,
    val originalIndex: Int,
)

private data class RepeatedBacklogCandidateGroup(
    val rows: List<RepeatedBacklogCandidate>,
    val count: Int,
    val firstIndex: Int,
)

private data class RepeatedBacklogSuggestedGroup(
    val ids: Set<String>,
    val categoryId: String,
    val count: Int,
    val firstIndex: Int,
)

private fun repeatedBacklogSuggestedGroups(
    visibleRows: List<Transaction>,
    category: HistoryCategoryFilter,
    allRows: List<Transaction>,
    activeCategories: List<Category>,
): List<RepeatedBacklogSuggestedGroup> {
    if (category != HistoryCategoryFilter.REPEATED_UNCATEGORIZED) return emptyList()
    val categoriesByKind = activeCategories
        .filterNot { it.archived }
        .groupBy { it.kind }
        .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.id } }
    return visibleRows
        .mapIndexedNotNull { index, tx ->
            tx.uncategorizedMerchantGroupKey()?.let { key ->
                RepeatedBacklogCandidate(tx = tx, key = key, originalIndex = index)
            }
        }
        .groupBy { it.key }
        .mapNotNull { (key, rows) ->
            if (rows.size <= 1) return@mapNotNull null
            val activeCategoryIds = categoriesByKind[key.categoryKind].orEmpty()
            val selectedRows = rows
                .sortedBy { it.originalIndex }
                .map { it.tx }
            val suggestion = allRows.suggestedCategoryForSelectedMerchant(
                selectedRows = selectedRows,
                categoryKind = key.categoryKind,
                activeCategoryIds = activeCategoryIds,
            ) ?: return@mapNotNull null
            RepeatedBacklogSuggestedGroup(
                ids = selectedRows.mapTo(mutableSetOf()) { it.id },
                categoryId = suggestion.categoryId,
                count = selectedRows.size,
                firstIndex = rows.minOf { it.originalIndex },
            )
        }
        .sortedWith(
            compareByDescending<RepeatedBacklogSuggestedGroup> { it.count }
                .thenBy { it.firstIndex },
        )
}

private fun Transaction.uncategorizedMerchantGroupKey(): UncategorizedMerchantGroupKey? {
    if (!categoryId.isNullOrBlank()) return null
    val kind = categoryKind() ?: return null
    val merchantKey = merchantSelectionKey()?.takeIf { it.isSpecificMerchantKey() } ?: return null
    return UncategorizedMerchantGroupKey(merchantKey = merchantKey, categoryKind = kind)
}

private fun Transaction.merchantSelectionKey(): String? =
    merchantNormalized
        .ifBlank { merchant }
        .trim()
        .lowercase()
        .takeIf { it.isNotBlank() }

private fun List<Transaction>.selectedMerchantName(): String? {
    if (isEmpty()) return null
    val merchantKey = mapNotNull { it.merchantSelectionKey() }
        .distinct()
        .singleOrNull()
        ?.takeIf { it.isSpecificMerchantKey() }
        ?: return null
    return firstOrNull { it.merchantSelectionKey() == merchantKey }
        ?.merchantDisplayName()
}

private fun Transaction.merchantDisplayName(): String? =
    merchant
        .ifBlank { merchantNormalized }
        .trim()
        .takeIf { it.isNotBlank() }

private data class SuggestedCategory(
    val categoryId: String,
    val useCount: Int,
)

private fun List<Transaction>.suggestedCategoryForSelectedMerchant(
    selectedRows: List<Transaction>,
    categoryKind: CategoryKind?,
    activeCategoryIds: Set<String>,
): SuggestedCategory? {
    if (selectedRows.isEmpty() || categoryKind == null || activeCategoryIds.isEmpty()) return null
    val merchantKey = selectedRows
        .mapNotNull { it.merchantSelectionKey() }
        .distinct()
        .singleOrNull()
        ?.takeIf { it.isSpecificMerchantKey() }
        ?: return null
    val categoryCounts = asSequence()
        .filter { it.merchantSelectionKey() == merchantKey }
        .filter { it.categoryKind() == categoryKind }
        .mapNotNull { it.categoryId?.trim()?.takeIf(String::isNotEmpty) }
        .filter { it in activeCategoryIds }
        .groupingBy { it }
        .eachCount()
    val category = categoryCounts.entries.singleOrNull() ?: return null
    return SuggestedCategory(categoryId = category.key, useCount = category.value)
}
