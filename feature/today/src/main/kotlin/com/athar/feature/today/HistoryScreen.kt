package com.athar.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharCategoryPicker
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharListRow
import com.athar.core.designsystem.component.AtharPickerItem
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialCategoryFilter: HistoryCategoryFilter? = null,
    initialSelectionMode: Boolean = false,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val theme = AtharTheme
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val categoryLabels by viewModel.categoryLabels.collectAsStateWithLifecycle()
    val repeatedBacklogCounts by viewModel.repeatedBacklogCounts.collectAsStateWithLifecycle()
    val backfill by viewModel.lastBackfill.collectAsStateWithLifecycle()
    val bulkCategory by viewModel.bulkCategoryState.collectAsStateWithLifecycle()
    val bulkCategoryResult by viewModel.lastBulkCategory.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var choosingBulkCategory by remember { mutableStateOf(false) }

    LaunchedEffect(backfill) {
        if (backfill != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.clearBackfill()
        }
    }
    LaunchedEffect(bulkCategoryResult) {
        if (bulkCategoryResult != null) {
            kotlinx.coroutines.delay(4_000)
            viewModel.clearBulkCategory()
        }
    }
    LaunchedEffect(initialCategoryFilter, initialSelectionMode) {
        initialCategoryFilter?.let(viewModel::setCategory)
        if (initialSelectionMode) {
            viewModel.enterSelectionMode()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.colors.parchment),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                BackChip(onBack)
                AtharText(
                    text = stringResource(R.string.history_overline),
                    style = theme.typography.overline,
                    color = theme.colors.muted,
                )
            }

            backfill?.let {
                CategoryBackfillToast(
                    pattern = it.pattern,
                    count = it.count,
                    onDismiss = viewModel::clearBackfill,
                )
            }
            bulkCategoryResult?.let {
                BulkCategoryToast(
                    applied = it.applied,
                    skipped = it.skipped,
                    exactRuleLearned = it.exactRuleLearned,
                    onDismiss = viewModel::clearBulkCategory,
                )
            }

            AtharTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = stringResource(R.string.history_search_label),
                placeholder = stringResource(R.string.history_search_placeholder),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Text,
            )

            FilterChipRow(
                labels = listOf(
                    HistoryStatusFilter.ALL to stringResource(R.string.history_filter_all),
                    HistoryStatusFilter.CONFIRMED to stringResource(R.string.history_filter_confirmed),
                    HistoryStatusFilter.PENDING to stringResource(R.string.history_filter_pending),
                    HistoryStatusFilter.DISMISSED to stringResource(R.string.history_filter_dismissed),
                ),
                selected = status,
                onSelect = viewModel::setStatus,
            )

            FilterChipRow(
                labels = listOf(
                    HistoryTypeFilter.ALL to stringResource(R.string.history_type_all),
                    HistoryTypeFilter.EXPENSE to stringResource(R.string.history_type_expense),
                    HistoryTypeFilter.INCOME to stringResource(R.string.history_type_income),
                    HistoryTypeFilter.TRANSFER to stringResource(R.string.history_type_transfer),
                ),
                selected = type,
                onSelect = viewModel::setType,
            )

            FilterChipRow(
                labels = listOf(
                    HistorySourceFilter.ALL to stringResource(R.string.history_source_all),
                    HistorySourceFilter.SMS to stringResource(R.string.history_source_sms),
                    HistorySourceFilter.NOTIFICATION to stringResource(R.string.history_source_notification),
                    HistorySourceFilter.MANUAL to stringResource(R.string.history_source_manual),
                    HistorySourceFilter.IMPORT to stringResource(R.string.history_source_import),
                    HistorySourceFilter.RECURRING to stringResource(R.string.history_source_recurring),
                    HistorySourceFilter.SHARE to stringResource(R.string.history_source_share),
                ),
                selected = source,
                onSelect = viewModel::setSource,
            )

            FilterChipRow(
                labels = listOf(
                    HistoryCategoryFilter.ALL to stringResource(R.string.history_category_all),
                    HistoryCategoryFilter.UNCATEGORIZED to stringResource(R.string.history_category_uncategorized),
                    HistoryCategoryFilter.REPEATED_UNCATEGORIZED to stringResource(
                        R.string.history_category_repeated_uncategorized,
                    ),
                    HistoryCategoryFilter.CATEGORIZED to stringResource(R.string.history_category_categorized),
                ),
                selected = category,
                onSelect = viewModel::setCategory,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                AtharText(
                    text = stringResource(R.string.history_count, items.size),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                    modifier = Modifier.weight(1f),
                )
                HistoryActionChip(
                    text = stringResource(
                        if (bulkCategory.selectionMode) {
                            R.string.history_bulk_done
                        } else {
                            R.string.history_bulk_select
                        },
                    ),
                    onClick = viewModel::toggleSelectionMode,
                    selected = bulkCategory.selectionMode,
                )
            }

            if (bulkCategory.selectionMode) {
                BulkSelectionCard(
                    state = bulkCategory,
                    onSelectTopRepeatedGroup = viewModel::selectTopRepeatedBacklogGroup,
                    onSelectVisible = viewModel::selectVisibleRows,
                    onSelectMatchingMerchant = viewModel::selectMatchingSelectedMerchants,
                    onApplyTopRepeatedSuggestion = viewModel::applyTopRepeatedBacklogSuggestedCategory,
                    onApplySafeRepeatedSuggestions = viewModel::applySafeRepeatedBacklogSuggestedCategories,
                    onApplySuggestedCategory = viewModel::applyBulkCategory,
                    onApplyCategory = { choosingBulkCategory = true },
                    onClear = viewModel::clearSelection,
                )
            }

            if (items.isEmpty()) {
                AtharCard {
                    AtharText(
                        text = stringResource(R.string.history_empty),
                        style = theme.typography.body,
                        color = theme.colors.muted,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
                    items(items, key = { it.id }) { tx ->
                        TxRow(
                            tx = tx,
                            categoryLabel = transactionCategoryLabel(tx, categoryLabels),
                            repeatedBacklogCount = repeatedBacklogCounts[tx.id] ?: 0,
                            selected = tx.id in bulkCategory.selectedIds,
                            selectionMode = bulkCategory.selectionMode,
                            onClick = {
                                if (bulkCategory.selectionMode) {
                                    viewModel.toggleSelected(tx.id)
                                } else {
                                    editing = tx
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { tx ->
        key(tx.id) {
            EditTransactionSheet(
                transaction = tx,
                onDismiss = { editing = null },
                onSave = { updated, learnRule ->
                    viewModel.updateTransaction(updated, learnRule)
                    editing = null
                },
                onDelete = { id ->
                    viewModel.deleteTransaction(id)
                    editing = null
                },
            )
        }
    }

    if (choosingBulkCategory && bulkCategory.canApply) {
        BulkCategorySheet(
            state = bulkCategory,
            onDismiss = { choosingBulkCategory = false },
            onApply = { categoryId ->
                viewModel.applyBulkCategory(categoryId)
                choosingBulkCategory = false
            },
        )
    }
}

@Composable
private fun TxRow(
    tx: Transaction,
    categoryLabel: String,
    repeatedBacklogCount: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    val typeLabel = when (tx.type) {
        TxType.INCOME -> stringResource(R.string.history_row_type_income)
        TxType.EXPENSE -> stringResource(R.string.history_row_type_expense)
        TxType.TRANSFER -> stringResource(R.string.history_row_type_transfer)
    }
    val title = tx.merchant.ifBlank { typeLabel }
    AtharListRow(
        title = if (selectionMode) {
            "${if (selected) "✓" else "○"} $title"
        } else {
            title
        },
        subtitle = historyRowSubtitle(
            tx = tx,
            typeLabel = typeLabel,
            categoryLabel = categoryLabel,
            repeatedBacklogCount = repeatedBacklogCount,
        ),
        trailing = tx.amount,
        onClick = onClick,
    )
}

@Composable
private fun historyRowSubtitle(
    tx: Transaction,
    typeLabel: String,
    categoryLabel: String,
    repeatedBacklogCount: Int,
): String {
    val base = listOf(
        tx.date.toString(),
        typeLabel,
        statusLabel(tx.status),
        sourceLabel(tx.source),
        categoryLabel,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    return if (repeatedBacklogCount > 1) {
        stringResource(
            R.string.history_row_subtitle_with_repeated_count,
            base,
            repeatedBacklogCount,
        )
    } else {
        base
    }
}

@Composable
private fun BulkCategoryToast(
    applied: Int,
    skipped: Int,
    exactRuleLearned: Boolean,
    onDismiss: () -> Unit,
) {
    AtharCard(modifier = Modifier.clickable(onClick = onDismiss)) {
        AtharText(
            text = when {
                exactRuleLearned && skipped > 0 -> stringResource(
                    R.string.history_bulk_applied_with_skips_and_rule,
                    applied,
                    skipped,
                )
                exactRuleLearned -> stringResource(R.string.history_bulk_applied_with_rule, applied)
                skipped > 0 -> stringResource(R.string.history_bulk_applied_with_skips, applied, skipped)
                else -> stringResource(R.string.history_bulk_applied, applied)
            },
            style = AtharTheme.typography.body,
            color = AtharTheme.colors.ink,
        )
    }
}

@Composable
private fun BulkSelectionCard(
    state: HistoryBulkCategoryState,
    onSelectTopRepeatedGroup: () -> Unit,
    onSelectVisible: () -> Unit,
    onSelectMatchingMerchant: () -> Unit,
    onApplyTopRepeatedSuggestion: () -> Unit,
    onApplySafeRepeatedSuggestions: () -> Unit,
    onApplySuggestedCategory: (String) -> Unit,
    onApplyCategory: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    val suggestedCategory = state.suggestedCategoryId
        ?.let { id -> state.categories.firstOrNull { it.id == id } }
    val topRepeatedSuggestedCategory = state.topRepeatedSuggestedCategory
    val safeRepeatedSuggestedCategory = state.safeRepeatedSuggestedCategory
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.history_bulk_selected, state.selectedCount),
                style = theme.typography.body,
                color = theme.colors.ink,
            )
            state.selectedMerchantName?.let { merchantName ->
                AtharText(
                    text = stringResource(
                        R.string.history_bulk_selected_merchant,
                        merchantName,
                        state.selectedCount,
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            AtharText(
                text = when {
                    state.selectedCount == 0 -> stringResource(R.string.history_bulk_select_rows)
                    state.hasMixedCategoryKinds -> stringResource(R.string.history_bulk_mixed_types)
                    state.eligibleCount == 0 -> stringResource(R.string.history_bulk_no_eligible)
                    state.skippedCount > 0 -> stringResource(
                        R.string.history_bulk_ready_with_skips,
                        state.eligibleCount,
                        state.skippedCount,
                    )
                    else -> stringResource(R.string.history_bulk_ready, state.eligibleCount)
                },
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            if (state.canShowSafeRepeatedSuggestionsSummary) {
                AtharText(
                    text = stringResource(
                        R.string.history_bulk_safe_repeated_suggestions_summary,
                        state.safeRepeatedSuggestedGroupCount,
                        state.safeRepeatedSuggestedTransactionCount,
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                if (state.topRepeatedGroupCount > 0) {
                    HistoryActionChip(
                        text = stringResource(
                            R.string.history_bulk_select_top_repeated_group,
                            state.topRepeatedGroupCount,
                        ),
                        onClick = onSelectTopRepeatedGroup,
                        enabled = state.canSelectTopRepeatedGroup,
                    )
                    if (state.canApplyTopRepeatedSuggestedCategory && topRepeatedSuggestedCategory != null) {
                        HistoryActionChip(
                            text = stringResource(
                                R.string.history_bulk_apply_top_suggestion,
                                topRepeatedSuggestedCategory.localizedName(),
                                state.topRepeatedGroupCount,
                            ),
                            onClick = onApplyTopRepeatedSuggestion,
                            selected = true,
                        )
                    }
                    if (state.canApplySingleSafeRepeatedSuggestedCategory && safeRepeatedSuggestedCategory != null) {
                        HistoryActionChip(
                            text = stringResource(
                                R.string.history_bulk_apply_single_safe_repeated_suggestion,
                                safeRepeatedSuggestedCategory.localizedName(),
                                state.safeRepeatedSuggestedCategoryTransactionCount,
                            ),
                            onClick = onApplySafeRepeatedSuggestions,
                            selected = true,
                        )
                    }
                    if (state.canApplySafeRepeatedSuggestedCategories) {
                        HistoryActionChip(
                            text = stringResource(
                                R.string.history_bulk_apply_safe_repeated_suggestions,
                                state.safeRepeatedSuggestedGroupCount,
                            ),
                            onClick = onApplySafeRepeatedSuggestions,
                            selected = true,
                        )
                    }
                }
                HistoryActionChip(
                    text = stringResource(R.string.history_bulk_select_visible),
                    onClick = onSelectVisible,
                    enabled = state.visibleCount > 0 && state.selectedCount < state.visibleCount,
                )
                HistoryActionChip(
                    text = stringResource(R.string.history_bulk_select_same_merchant),
                    onClick = onSelectMatchingMerchant,
                    enabled = state.matchingMerchantCount > state.selectedCount,
                )
                HistoryActionChip(
                    text = stringResource(R.string.history_bulk_clear),
                    onClick = onClear,
                )
                if (state.canApplySuggestedCategory && suggestedCategory != null) {
                    HistoryActionChip(
                        text = stringResource(
                            R.string.history_bulk_apply_suggestion,
                            suggestedCategory.localizedName(),
                        ),
                        onClick = { onApplySuggestedCategory(suggestedCategory.id) },
                        selected = true,
                    )
                }
                HistoryActionChip(
                    text = stringResource(R.string.history_bulk_category),
                    onClick = onApplyCategory,
                    selected = !state.canApplySuggestedCategory,
                    enabled = state.canApply,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkCategorySheet(
    state: HistoryBulkCategoryState,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val suggestedCategory = state.suggestedCategoryId
        ?.let { id -> state.categories.firstOrNull { it.id == id } }
    val initialCategoryId = suggestedCategory?.id ?: state.categories.firstOrNull()?.id
    var selectedCategoryId by remember(state.categories, state.suggestedCategoryId) {
        mutableStateOf(initialCategoryId)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(
                text = stringResource(R.string.history_bulk_category_title),
                style = theme.typography.headline,
                color = theme.colors.ink,
            )
            state.selectedMerchantName?.let { merchantName ->
                AtharText(
                    text = stringResource(
                        R.string.history_bulk_category_context,
                        merchantName,
                        state.selectedCount,
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            suggestedCategory?.let { category ->
                AtharText(
                    text = stringResource(
                        R.string.history_bulk_category_suggestion,
                        category.localizedName(),
                        state.suggestedCategoryUseCount,
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            AtharCategoryPicker(
                items = state.categories.map {
                    AtharPickerItem(key = it.id, labelEn = it.name, labelAr = it.nameAr)
                },
                selectedKey = selectedCategoryId,
                onSelect = { selectedCategoryId = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                HistoryActionChip(
                    text = stringResource(R.string.history_bulk_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                HistoryActionChip(
                    text = stringResource(R.string.history_bulk_apply),
                    onClick = { selectedCategoryId?.let(onApply) },
                    selected = true,
                    enabled = selectedCategoryId != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun <T> FilterChipRow(
    labels: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        labels.forEach { (key, label) ->
            val isSel = key == selected
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(if (isSel) theme.colors.ember else theme.colors.surface)
                    .clickable { onSelect(key) }
                    .padding(horizontal = theme.spacing.s, vertical = theme.spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(
                    text = label,
                    style = theme.typography.caption,
                    color = if (isSel) theme.colors.parchment else theme.colors.ink,
                )
            }
        }
    }
}

@Composable
private fun HistoryActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val theme = AtharTheme
    val background = when {
        !enabled -> theme.colors.divider
        selected -> theme.colors.ember
        else -> theme.colors.surface
    }
    val textColor = when {
        !enabled -> theme.colors.muted
        selected -> theme.colors.parchment
        else -> theme.colors.ink
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = theme.spacing.s, vertical = theme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = text,
            style = theme.typography.caption,
            color = textColor,
        )
    }
}

@Composable
private fun statusLabel(status: TxStatus): String = when (status) {
    TxStatus.CONFIRMED -> stringResource(R.string.history_filter_confirmed)
    TxStatus.PENDING -> stringResource(R.string.history_filter_pending)
    TxStatus.DISMISSED -> stringResource(R.string.history_filter_dismissed)
}

@Composable
private fun sourceLabel(source: IngestSource): String = when (source) {
    IngestSource.SMS -> stringResource(R.string.history_source_sms)
    IngestSource.NOTIFICATION -> stringResource(R.string.history_source_notification)
    IngestSource.MANUAL -> stringResource(R.string.history_source_manual)
    IngestSource.IMPORT -> stringResource(R.string.history_source_import)
    IngestSource.RECURRING -> stringResource(R.string.history_source_recurring)
    IngestSource.SHARE -> stringResource(R.string.history_source_share)
}

@Composable
private fun BackChip(onBack: () -> Unit) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.surface)
            .clickable(onClick = onBack)
            .padding(horizontal = theme.spacing.s, vertical = theme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = stringResource(R.string.history_back),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}
