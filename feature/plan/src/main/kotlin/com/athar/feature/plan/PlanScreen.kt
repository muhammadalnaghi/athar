package com.athar.feature.plan

import com.athar.core.designsystem.display.AtharNumerals
import com.athar.core.designsystem.display.currentUiLocale

import androidx.activity.compose.BackHandler
import com.athar.feature.plan.scenarios.DebtScenarioContent
import com.athar.feature.plan.scenarios.CashScenarioContent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import com.athar.core.designsystem.theme.MinTouchTarget
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharAmountField
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.domain.model.Transaction
import com.athar.feature.plan.budget.BudgetBucket
import com.athar.feature.plan.budget.BudgetEvidence
import com.athar.feature.plan.budget.BudgetEvidenceRequest
import com.athar.feature.plan.budget.BudgetTargetInput
import com.athar.feature.plan.budget.ComparePanel
import com.athar.feature.plan.budget.TargetInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharEmptyState
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharSegment
import com.athar.core.designsystem.component.AtharSegmentedControl
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

enum class PlanTab { BUDGET, BILLS, WISHLIST, GOALS, INVESTMENTS }
private enum class PlanScenario { DEBT, CASH }

@Composable
fun PlanScreen(
    modifier: Modifier = Modifier,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BudgetRow?>(null) }
    var tab by rememberSaveable { mutableStateOf(PlanTab.BUDGET) }
    var scenario by rememberSaveable { mutableStateOf<PlanScenario?>(null) }
    BackHandler(enabled = scenario != null) { scenario = null }
    // WM032: system back closes the evidence sheet and preserves both periods.
    BackHandler(enabled = state.evidence != null) { viewModel.onEvent(PlanEvent.DismissEvidence) }
    val theme = AtharTheme
    LaunchedEffect(state.targetSaveStatus) {
        if (state.targetSaveStatus == TargetSaveStatus.SAVED) {
            editing = null
            viewModel.onEvent(PlanEvent.DismissTargetEditor)
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
            verticalArrangement = Arrangement.spacedBy(theme.spacing.l),
        ) {
            AtharText(text = stringResource(R.string.plan_header_title), style = theme.typography.overline, color = theme.colors.muted)
            AtharSegmentedControl(
                segments = listOf(
                    AtharSegment(PlanTab.BUDGET, stringResource(R.string.plan_tab_budget)),
                    AtharSegment(PlanTab.BILLS, stringResource(R.string.plan_tab_bills)),
                    AtharSegment(PlanTab.WISHLIST, stringResource(R.string.plan_tab_wishlist)),
                    AtharSegment(PlanTab.GOALS, stringResource(R.string.plan_tab_goals)),
                    AtharSegment(PlanTab.INVESTMENTS, stringResource(R.string.plan_tab_investments)),
                ),
                selected = tab,
                onSelect = { tab = it; scenario = null },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            // Navigation stays reachable while long budget/goal/investment bodies scroll.
            key(tab, scenario) {
                val bodyScroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(bodyScroll),
                ) {
                    when (tab) {
                        PlanTab.BUDGET -> BudgetContent(
                            state = state,
                            onClickRow = { viewModel.onEvent(PlanEvent.OpenTargetEditor(it.category.id)); editing = it },
                            onEvent = viewModel::onEvent,
                        )
                        PlanTab.BILLS -> when (scenario) {
                            PlanScenario.DEBT -> DebtScenarioContent(onClose = { scenario = null },
                                onPositionChanged = { scope.launch { bodyScroll.scrollTo(0) } })
                            PlanScenario.CASH -> CashScenarioContent(onClose = { scenario = null },
                                onPositionChanged = { scope.launch { bodyScroll.scrollTo(0) } })
                            null -> BillsContent(onOpenDebtScenario = { scenario = PlanScenario.DEBT },
                                onOpenCashScenario = { scenario = PlanScenario.CASH })
                        }
                        PlanTab.WISHLIST -> WishlistContent()
                        PlanTab.GOALS -> GoalsContent()
                        PlanTab.INVESTMENTS -> InvestmentsContent(onPositionChanged = { scope.launch { bodyScroll.scrollTo(0) } })
                    }
                }
            }
        }
    }

    editing?.let { row ->
        TargetEditor(
            row = row,
            saveStatus = state.targetSaveStatus,
            onDismiss = {
                if (state.targetSaveStatus != TargetSaveStatus.SAVING) {
                    editing = null
                    viewModel.onEvent(PlanEvent.DismissTargetEditor)
                }
            },
            onSave = { newTargetMinor ->
                viewModel.onEvent(PlanEvent.SaveTarget(row.category.id, newTargetMinor,
                    row.category.monthlyTarget?.currency ?: row.actual.currency))
            },
        )
    }

    state.evidence?.let { evidence ->
        EvidenceSheet(evidence = evidence, onDismiss = { viewModel.onEvent(PlanEvent.DismissEvidence) })
    }
}

@Composable
private fun BudgetContent(
    state: PlanState,
    onClickRow: (BudgetRow) -> Unit,
    onEvent: (PlanEvent) -> Unit,
) {
    val theme = AtharTheme
    var showCustomRange by remember { mutableStateOf(false) }
    var showCompareRange by remember { mutableStateOf(false) }
    var showAllCategories by remember { mutableStateOf(false) }
    val activeRows = state.rows.filter { !it.actual.isZero() || it.target != null }
    val visibleRows = if (showAllCategories) state.rows else activeRows
    if (state.isLoading) {
        AtharText(stringResource(R.string.plan_budget_loading), color = theme.colors.muted)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
        Header(
            state = state,
            onOpenTotal = {
                onEvent(PlanEvent.OpenEvidence(BudgetEvidenceRequest(ComparePanel.A, BudgetBucket.Total)))
            },
        )
        AtharSegmentedControl(
            segments = listOf(
                AtharSegment(PlanPeriodKey.MONTH, stringResource(R.string.plan_period_month)),
                AtharSegment(PlanPeriodKey.MONTHS_3, stringResource(R.string.plan_period_months_3)),
                AtharSegment(PlanPeriodKey.YEAR, stringResource(R.string.plan_period_year)),
                AtharSegment(PlanPeriodKey.CUSTOM, stringResource(R.string.plan_period_custom)),
            ),
            selected = state.periodKey,
            onSelect = {
                if (it == PlanPeriodKey.CUSTOM) showCustomRange = true
                else onEvent(PlanEvent.SelectPeriod(it))
            },
        )
        if (state.periodKey == PlanPeriodKey.CUSTOM && state.customStart != null && state.customEnd != null) {
            CustomRangeBanner(
                start = state.customStart,
                end = state.customEnd,
                onEdit = { showCustomRange = true },
            )
        }
        if (state.periodKey != PlanPeriodKey.MONTH) {
            AtharText(
                text = stringResource(
                    R.string.plan_target_caption_scaled,
                    "%.1f".format(state.targetMultiplier),
                ),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        LimitWarningStrip(state = state)
        if (state.rows.isEmpty() && !state.isLoading) {
            AtharEmptyState(
                text = stringResource(R.string.plan_empty_text),
                subtle = stringResource(R.string.plan_empty_subtle),
            )
        } else {
            visibleRows.forEach { row ->
                BudgetRowView(row = row,
                    onClick = { onEvent(PlanEvent.OpenEvidence(BudgetEvidenceRequest(ComparePanel.A, BudgetBucket.Category(row.category.id)))) },
                    onEditTarget = { onClickRow(row) })
            }
        }
        if (!state.remainder.isZero() || state.remainderCount > 0) {
            RemainderRow(
                remainder = state.remainder,
                count = state.remainderCount,
                onClick = {
                    onEvent(PlanEvent.OpenEvidence(BudgetEvidenceRequest(ComparePanel.A, BudgetBucket.Remainder)))
                },
            )
        }
        ComparisonSection(state = state, showAllCategories = showAllCategories, onEvent = onEvent, onEditB = { showCompareRange = true })
        if (activeRows.size < state.rows.size) {
            TextButton(onClick = { showAllCategories = !showAllCategories }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                AtharText(stringResource(if (showAllCategories) R.string.plan_budget_hide_other_categories
                    else R.string.plan_budget_show_other_categories, state.rows.size - activeRows.size),
                    style = theme.typography.caption, color = theme.colors.ember)
            }
        }
    }

    if (showCustomRange) {
        CustomRangePickerSheet(
            initialStart = state.customStart,
            initialEnd = state.customEnd,
            onDismiss = { showCustomRange = false },
            onConfirm = { s, e ->
                onEvent(PlanEvent.SelectCustomRange(s, e))
                showCustomRange = false
            },
        )
    }

    if (showCompareRange) {
        CustomRangePickerSheet(
            initialStart = state.compareBStart,
            initialEnd = state.compareBEnd,
            onDismiss = { showCompareRange = false },
            onConfirm = { s, e ->
                onEvent(PlanEvent.SetCompareRange(s, e))
                showCompareRange = false
            },
        )
    }
}

@Composable
private fun CustomRangeBanner(start: LocalDate, end: LocalDate, onEdit: () -> Unit) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.surface)
            .clickable(onClick = onEdit)
            .padding(theme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtharText(
            text = stringResource(
                R.string.plan_custom_range_banner,
                start.toString(),
                end.toString(),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
            modifier = Modifier.weight(1f),
        )
        AtharText(text = stringResource(R.string.plan_custom_range_banner_edit), style = theme.typography.caption, color = theme.colors.ember)
    }
}

private enum class CustomRangeError { INVALID, ORDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangePickerSheet(
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var startText by remember { mutableStateOf(initialStart?.toString().orEmpty()) }
    var endText by remember { mutableStateOf(initialEnd?.toString().orEmpty()) }
    var error by remember { mutableStateOf<CustomRangeError?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = stringResource(R.string.plan_custom_range_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.plan_custom_range_hint),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharTextField(
                value = startText,
                onValueChange = { startText = it; error = null },
                label = stringResource(R.string.plan_custom_range_from),
                modifier = Modifier.fillMaxWidth(),
            )
            AtharTextField(
                value = endText,
                onValueChange = { endText = it; error = null },
                label = stringResource(R.string.plan_custom_range_to),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                val message = when (it) {
                    CustomRangeError.INVALID -> stringResource(R.string.plan_custom_range_error_invalid)
                    CustomRangeError.ORDER -> stringResource(R.string.plan_custom_range_error_order)
                }
                AtharText(text = message, style = theme.typography.caption, color = theme.colors.ember)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.ember)
                    .clickable {
                        val s = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
                        val e = runCatching { LocalDate.parse(endText.trim()) }.getOrNull()
                        when {
                            s == null || e == null -> error = CustomRangeError.INVALID
                            !(s <= e) -> error = CustomRangeError.ORDER
                            else -> onConfirm(s, e)
                        }
                    }
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(text = stringResource(R.string.plan_custom_range_apply), style = theme.typography.headline, color = theme.colors.parchment)
            }
        }
    }
}

@Composable
private fun LimitWarningStrip(state: PlanState) {
    val theme = AtharTheme
    val tight = state.rows.filter { it.limit == LimitState.Tight }
    val over = state.rows.filter { it.limit == LimitState.Over }
    if (tight.isEmpty() && over.isEmpty()) return
    val isArabic = currentUiLocale().language == "ar"
    val separator = if (isArabic) "، " else ", "
    fun categoryLabel(row: BudgetRow): String = if (isArabic) row.category.nameAr else row.category.name
    val (message, color) = when {
        over.isNotEmpty() -> {
            val names = over.take(3).joinToString(separator) { categoryLabel(it) }
            val extra = if (over.size > 3) " (+${over.size - 3})" else ""
            "$names$extra${stringResource(R.string.plan_limit_warning_over_suffix)}" to theme.colors.ember
        }
        else -> {
            val names = tight.take(3).joinToString(separator) { categoryLabel(it) }
            val extra = if (tight.size > 3) " (+${tight.size - 3})" else ""
            "$names$extra${stringResource(R.string.plan_limit_warning_tight_suffix)}" to theme.colors.dust
        }
    }
    AtharCard {
        AtharText(text = message, style = theme.typography.body, color = color)
    }
}

@Composable
private fun Header(state: PlanState, onOpenTotal: () -> Unit) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(stringResource(R.string.plan_budget_actual), style = theme.typography.caption, color = theme.colors.muted)
        Box(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(onClick = onOpenTotal)) {
            AtharNumber(money = state.totalActual)
        }
        AtharText(stringResource(R.string.plan_budget_target_total), style = theme.typography.caption, color = theme.colors.muted)
        if (state.totalTarget.isZero()) {
            AtharText(stringResource(R.string.plan_budget_limit_none), style = theme.typography.caption, color = theme.colors.muted)
        } else {
            AtharNumber(money = state.totalTarget, color = theme.colors.muted)
        }
        TextButton(onClick = onOpenTotal, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            AtharText(stringResource(R.string.plan_budget_show_records), style = theme.typography.caption, color = theme.colors.ember)
        }
        AtharText(periodLabel(state), style = theme.typography.caption, color = theme.colors.muted)
        AtharText(stringResource(R.string.plan_budget_currency_scope, state.totalActual.currency),
            style = theme.typography.caption, color = theme.colors.muted)
    }
}

@Composable
private fun periodLabel(state: PlanState): String {
    // WM077-class fix (WM079): period labels are built by string templates —
    // transliterate digits for Arabic locales so no Latin digits leak into RTL copy.
    val locale = currentUiLocale()
    return when (state.periodKey) {
        PlanPeriodKey.MONTH -> AtharNumerals.localize("${state.month.year}/${state.month.monthValue}", locale)
        PlanPeriodKey.MONTHS_3 -> stringResource(R.string.plan_period_label_last_3_months)
        PlanPeriodKey.YEAR -> AtharNumerals.localize("${state.month.year}", locale)
        PlanPeriodKey.CUSTOM -> if (state.customStart != null && state.customEnd != null)
            AtharNumerals.localize("${state.customStart} → ${state.customEnd}", locale)
        else stringResource(R.string.plan_period_label_custom_default)
    }
}

@Composable
private fun BudgetRowView(row: BudgetRow, onClick: () -> Unit, onEditTarget: () -> Unit) {
    val theme = AtharTheme
    AtharCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AtharText(text = row.category.nameAr, style = theme.typography.headline)
                TextButton(onClick = onEditTarget, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    AtharText(stringResource(R.string.plan_budget_edit_target), style = theme.typography.caption, color = theme.colors.ember)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                AtharNumber(money = row.actual)
                if (row.target != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AtharText(
                            text = stringResource(R.string.plan_budget_target_label),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                        AtharNumber(money = row.target, color = theme.colors.muted)
                    }
                    VariancePill(row = row)
                } else {
                    AtharText(text = stringResource(R.string.plan_budget_limit_none), style = theme.typography.caption, color = theme.colors.muted)
                }
            }
        }
    }
}

@Composable
private fun VariancePill(row: BudgetRow) {
    val theme = AtharTheme
    val variance = row.variance ?: return
    val (label, color) = when (row.limit) {
        LimitState.Over -> "${stringResource(R.string.plan_limit_over)} " to theme.colors.ember
        LimitState.Tight -> "${stringResource(R.string.plan_limit_tight)} · " to theme.colors.ember
        LimitState.Watch -> "${stringResource(R.string.plan_limit_watch)} · " to theme.colors.dust
        LimitState.Healthy -> "${stringResource(R.string.plan_limit_healthy)} " to theme.colors.olive
        LimitState.None -> "" to theme.colors.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) AtharText(text = label, style = theme.typography.caption, color = color)
        AtharNumber(money = if (variance.isNegative()) -variance else variance, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetEditor(
    row: BudgetRow,
    saveStatus: TargetSaveStatus,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit,
) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // WM032: editing always starts from the category's ORIGINAL monthly target (literal
    // currency), never from the scaled period projection shown on the row.
    val targetCurrency = row.category.monthlyTarget?.currency ?: row.actual.currency
    var amount by remember(row.category.id) {
        mutableStateOf(row.category.monthlyTarget?.amount?.toPlainString() ?: "")
    }
    var error by remember(row.category.id) { mutableStateOf<TargetInput.Reason?>(null) }
    val busy = saveStatus == TargetSaveStatus.SAVING

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
                text = "${row.category.nameAr}${stringResource(R.string.plan_target_editor_title_suffix)}",
                style = theme.typography.headline,
            )
            AtharAmountField(
                value = amount,
                onValueChange = { amount = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.plan_target_editor_amount_label),
            )
            AtharText(
                text = stringResource(R.string.plan_target_editor_currency_label, targetCurrency),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            if (saveStatus == TargetSaveStatus.FAILED || saveStatus == TargetSaveStatus.UNSUPPORTED_PRECISION) {
                AtharText(stringResource(if (saveStatus == TargetSaveStatus.UNSUPPORTED_PRECISION)
                    R.string.plan_budget_target_save_precision else R.string.plan_budget_target_save_error),
                    style = theme.typography.caption, color = theme.colors.ember)
            }
            if (busy) AtharText(stringResource(R.string.plan_budget_saving), style = theme.typography.caption)
            error?.let { reason ->
                AtharText(
                    text = when (reason) {
                        TargetInput.Reason.FRACTIONAL -> stringResource(R.string.plan_target_error_fractional)
                        TargetInput.Reason.OVERFLOW -> stringResource(R.string.plan_target_error_overflow)
                        else -> stringResource(R.string.plan_target_error_invalid)
                    },
                    style = theme.typography.caption,
                    color = theme.colors.ember,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                SheetButton(
                    text = stringResource(R.string.plan_action_clear_target),
                    background = theme.colors.divider,
                    textColor = theme.colors.ink,
                    onClick = { if (!busy) onSave(null) },
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    text = stringResource(R.string.plan_action_save),
                    background = theme.colors.ember,
                    textColor = theme.colors.parchment,
                    onClick = {
                        // WM032: malformed/overflow/fractional input shows an error and
                        // leaves the stored target untouched — a failed Save never
                        // degrades into a clear; Clear is the separate explicit action.
                        when (val parsed = BudgetTargetInput.parse(amount, targetCurrency)) {
                            is TargetInput.Valid -> if (!busy) onSave(parsed.minor)
                            is TargetInput.Invalid -> error = parsed.reason
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ComparisonSection(state: PlanState, showAllCategories: Boolean, onEvent: (PlanEvent) -> Unit, onEditB: () -> Unit) {
    val theme = AtharTheme
    val comparison = state.comparison
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        CompareToggleRow(
            enabled = state.compareEnabled,
            onToggle = { onEvent(PlanEvent.SetCompareEnabled(!state.compareEnabled)) },
        )
        if (!state.compareEnabled || comparison == null) return@Column
        AtharText(
            text = stringResource(
                R.string.plan_compare_panel_a,
                comparison.panelA.start.toString(),
                comparison.panelA.endInclusive.toString(),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AtharText(
                text = stringResource(
                    R.string.plan_compare_panel_b,
                    comparison.panelB.start.toString(),
                    comparison.panelB.endInclusive.toString(),
                ),
                style = theme.typography.caption,
                color = theme.colors.muted,
                modifier = Modifier.weight(1f),
            )
            AtharText(
                text = stringResource(R.string.plan_compare_edit_b),
                style = theme.typography.caption,
                color = theme.colors.ember,
                modifier = Modifier.clickable(onClick = onEditB),
            )
        }
        fun open(panel: ComparePanel, bucket: BudgetBucket) =
            onEvent(PlanEvent.OpenEvidence(BudgetEvidenceRequest(panel, bucket)))
        CompareLine(
            label = stringResource(R.string.plan_compare_total),
            amountA = comparison.panelA.totalActual,
            amountB = comparison.panelB.totalActual,
            targetA = comparison.panelA.totalTarget,
            targetB = comparison.panelB.totalTarget,
            onOpenA = { open(ComparePanel.A, BudgetBucket.Total) },
            onOpenB = { open(ComparePanel.B, BudgetBucket.Total) },
        )
        val idsA = comparison.panelA.rows.map { it.category.id }
        val idsB = comparison.panelB.rows.map { it.category.id }
        (idsA + (idsB - idsA.toSet())).forEach { id ->
            val rowA = comparison.panelA.rows.firstOrNull { it.category.id == id }
            val rowB = comparison.panelB.rows.firstOrNull { it.category.id == id }
            if (!showAllCategories && rowA?.actual?.isZero() != false && rowB?.actual?.isZero() != false &&
                rowA?.target == null && rowB?.target == null) return@forEach
            val label = rowA?.category?.nameAr ?: rowB?.category?.nameAr ?: return@forEach
            CompareLine(
                label = label,
                amountA = rowA?.actual ?: Money.zero(comparison.panelA.currency),
                amountB = rowB?.actual ?: Money.zero(comparison.panelB.currency),
                targetA = rowA?.target,
                targetB = rowB?.target,
                onOpenA = { open(ComparePanel.A, BudgetBucket.Category(id)) },
                onOpenB = { open(ComparePanel.B, BudgetBucket.Category(id)) },
            )
        }
        CompareLine(
            label = stringResource(R.string.plan_compare_remainder),
            amountA = comparison.panelA.remainder,
            amountB = comparison.panelB.remainder,
            onOpenA = { open(ComparePanel.A, BudgetBucket.Remainder) },
            onOpenB = { open(ComparePanel.B, BudgetBucket.Remainder) },
        )
    }
}

@Composable
private fun CompareToggleRow(enabled: Boolean, onToggle: () -> Unit) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.surface)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .padding(theme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtharText(
            text = stringResource(if (enabled) R.string.plan_compare_toggle_on else R.string.plan_compare_toggle_off),
            style = theme.typography.caption,
            color = if (enabled) theme.colors.ember else theme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun CompareLine(
    label: String,
    amountA: Money,
    amountB: Money,
    targetA: Money? = null,
    targetB: Money? = null,
    onOpenA: () -> Unit,
    onOpenB: () -> Unit,
) {
    val theme = AtharTheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(label, style = theme.typography.body)
        CompareAmount(stringResource(R.string.plan_compare_label_a), amountA, targetA, onOpenA)
        CompareAmount(stringResource(R.string.plan_compare_label_b), amountB, targetB, onOpenB)
    }
}

@Composable
private fun CompareAmount(label: String, actual: Money, target: Money?, onOpen: () -> Unit) {
    val theme = AtharTheme
    Column(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(onClick = onOpen)
        .padding(theme.spacing.s), verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(label, style = theme.typography.caption, color = theme.colors.muted)
        AtharNumber(money = actual)
        if (target != null) {
            AtharText(stringResource(R.string.plan_budget_target_label), style = theme.typography.caption, color = theme.colors.muted)
            AtharNumber(money = target, color = theme.colors.muted)
        }
    }
}

@Composable
private fun RemainderRow(remainder: Money, count: Int, onClick: () -> Unit) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.surface)
            .clickable(onClick = onClick)
            .padding(theme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AtharText(
            text = stringResource(R.string.plan_compare_uncategorized_count, count),
            style = theme.typography.caption,
            color = theme.colors.muted,
            modifier = Modifier.weight(1f),
        )
        AtharNumber(money = remainder)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceSheet(evidence: BudgetEvidence, onDismiss: () -> Unit) {
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(text = stringResource(R.string.plan_evidence_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(
                    R.string.plan_evidence_scope,
                    evidence.start.toString(),
                    evidence.endInclusive.toString(),
                    evidence.currency,
                ),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharNumber(money = evidence.total)
            if (evidence.records.isEmpty()) {
                AtharEmptyState(
                    text = stringResource(R.string.plan_evidence_empty),
                    subtle = "",
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    items(evidence.records, key = { it.id }) { tx -> EvidenceRecordRow(tx = tx) }
                }
            }
            SheetButton(
                text = stringResource(R.string.plan_evidence_close),
                background = theme.colors.divider,
                textColor = theme.colors.ink,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EvidenceRecordRow(tx: Transaction) {
    val theme = AtharTheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AtharText(text = tx.merchant.ifBlank { stringResource(R.string.plan_budget_transaction) }, style = theme.typography.body)
            AtharText(text = tx.date.toString(), style = theme.typography.caption, color = theme.colors.muted)
        }
        AtharNumber(money = tx.amount)
    }
}

@Composable
private fun SheetButton(
    text: String,
    background: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(background)
            .clickable(onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = text, style = theme.typography.headline, color = textColor)
    }
}
