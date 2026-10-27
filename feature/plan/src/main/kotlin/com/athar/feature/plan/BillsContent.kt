package com.athar.feature.plan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharEmptyState
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.display.AtharNumerals
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.feature.plan.bills.ManualRuleEditorSheet
import com.athar.feature.plan.scenarios.ScenarioTextAction
import com.athar.core.domain.model.TxType
import kotlinx.datetime.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BillsContent(viewModel: BillsViewModel = hiltViewModel(), onOpenDebtScenario: (() -> Unit)? = null,
    onOpenCashScenario: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = AtharTheme
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsGranted by remember { mutableStateOf(hasPostNotificationsPermission(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        notificationsGranted = granted
        viewModel.onEvent(BillsEvent.SetRemindersEnabled(granted))
    }
    var detailItem by remember { mutableStateOf<BillItem?>(null) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = hasPostNotificationsPermission(context)
                viewModel.onEvent(BillsEvent.RefreshDate)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
        BillsSummaryCard(state = state)
        // Explicit manual-commitment entry point — always reachable from the Bills list.
        ReminderButton(
            text = stringResource(R.string.plan_bills033_add_commitment),
            onClick = { viewModel.onEvent(BillsEvent.OpenManualRuleEditor) },
            primary = false,
        )
        BillsCalendarStrip(days = state.calendarDays)
        // Honest bounded-view disclosure: the list covers a window around today. This
        // never claims anything about payment — older occurrences simply aren't listed.
        if (state.coverage?.uiHistoryTruncated == true) {
            AtharText(
                text = stringResource(R.string.plan_bills_coverage_bounded),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
        if (state.suggestedCommitments.isNotEmpty()) {
            SuggestedCommitmentsCard(
                commitments = state.suggestedCommitments,
                onCreateRule = { viewModel.onEvent(BillsEvent.CreateRuleFromSuggestion(it)) },
            )
        }
        if (state.items.isEmpty() && !state.isLoading) {
            AtharEmptyState(
                text = stringResource(R.string.plan_bills_empty_text),
                subtle = stringResource(R.string.plan_bills_empty_subtle),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
                state.items.forEach { item ->
                    BillRow(item = item, today = state.today, onOpenDetail = { detailItem = it })
                }
            }
        }
        onOpenDebtScenario?.let { open ->
            ScenarioTextAction(stringResource(R.string.debt_scenario_entry), onClick = open)
        }
        onOpenCashScenario?.let { open ->
            ScenarioTextAction(stringResource(R.string.cash_scenario_entry), onClick = open)
        }
        BillReminderCard(
            enabled = state.remindersEnabled,
            notificationsGranted = notificationsGranted,
            onEnable = {
                if (hasPostNotificationsPermission(context)) {
                    notificationsGranted = true
                    viewModel.onEvent(BillsEvent.SetRemindersEnabled(true))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onDisable = { viewModel.onEvent(BillsEvent.SetRemindersEnabled(false)) },
        )
    }

    detailItem?.let { item ->
        BillDetailSheet(
            item = item,
            today = state.today,
            onDismiss = { detailItem = null },
            onPause = {
                viewModel.onEvent(BillsEvent.SetRuleActive(item.ruleId, active = false))
                detailItem = null
            },
            onDelete = {
                viewModel.onEvent(BillsEvent.CancelRule(item.ruleId))
                detailItem = null
            },
        )
    }

    state.manualRuleEditor?.let { editor ->
        ManualRuleEditorSheet(
            editor = editor,
            onUpdateDraft = { viewModel.onEvent(BillsEvent.UpdateManualRuleDraft(it)) },
            onSubmit = { viewModel.onEvent(BillsEvent.SubmitManualRule) },
            onDismiss = { viewModel.onEvent(BillsEvent.DismissManualRuleEditor) },
        )
    }
}

@Composable
private fun BillReminderCard(
    enabled: Boolean,
    notificationsGranted: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    val theme = AtharTheme
    val active = enabled && notificationsGranted
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(
                    when {
                        active -> R.string.plan_bills_reminders_on_title
                        enabled && !notificationsGranted -> R.string.plan_bills_reminders_permission_title
                        else -> R.string.plan_bills_reminders_title
                    },
                ),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(
                    when {
                        active -> R.string.plan_bills035_reminders_body
                        enabled && !notificationsGranted -> R.string.plan_bills_reminders_permission_body
                        else -> R.string.plan_bills035_reminders_body
                    },
                ),
                style = theme.typography.body,
                color = theme.colors.ink,
            )
            ReminderButton(
                text = stringResource(
                    if (active) R.string.plan_bills_reminders_disable else R.string.plan_bills_reminders_enable,
                ),
                onClick = if (active) onDisable else onEnable,
                primary = !active,
            )
        }
    }
}

@Composable
private fun ReminderButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (primary) theme.colors.ember else theme.colors.divider)
            .clickable(onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = text,
            style = theme.typography.body,
            color = if (primary) theme.colors.parchment else theme.colors.ink,
        )
    }
}

@Composable
private fun BillsSummaryCard(state: BillsState) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.plan_bills035_summary_30_days),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            // One row per currency — amounts are never merged across currencies.
            state.outgoingNext30ByCurrency.forEach { currencyTotal ->
                AtharNumber(money = currencyTotal.total, color = theme.colors.ember)
            }
            if (state.outgoingNext30ByCurrency.isEmpty()) {
                AtharNumber(money = Money.zero(), color = theme.colors.ember)
            }
            AtharText(
                text = stringResource(
                    R.string.plan_bills_summary_subtitle,
                    state.items.size,
                    state.horizonEnd?.let { AtharNumerals.localize(it.toString(), currentUiLocale()) }.orEmpty(),
                ),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun SuggestedCommitmentsCard(
    commitments: List<SuggestedCommitment>,
    onCreateRule: (RecurringSuggestion) -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.plan_bills_suggestions_title),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(R.string.plan_bills_suggestions_subtitle),
                style = theme.typography.body,
                color = theme.colors.ink,
            )
            commitments.forEach { commitment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(theme.spacing.m),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
                    ) {
                        AtharText(
                            text = commitment.merchant,
                            style = theme.typography.headline,
                            color = theme.colors.ink,
                        )
                        AtharText(
                            text = cadenceLabel(commitment.cadence),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                    }
                    AtharNumber(
                        money = commitment.amount,
                        color = theme.colors.muted,
                    )
                }
                ReminderButton(
                    text = stringResource(R.string.plan_bills_suggestions_create),
                    onClick = { onCreateRule(commitment.source) },
                    primary = false,
                )
            }
        }
    }
}

@Composable
private fun BillsCalendarStrip(days: List<BillCalendarDay>) {
    if (days.isEmpty()) return
    val theme = AtharTheme
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(
            items = days,
            key = { it.date.toString() },
        ) { day ->
            CalendarDayChip(day = day)
        }
    }
}

@Composable
private fun CalendarDayChip(day: BillCalendarDay) {
    val theme = AtharTheme
    val hasBills = day.count > 0
    Column(
        modifier = Modifier
            .width(52.dp)
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (hasBills) theme.colors.surface else theme.colors.divider)
            .padding(vertical = theme.spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // WM081: calendar digits follow the UI locale (Arabic-Indic in Arabic).
        AtharText(
            text = AtharNumerals.localize(day.date.dayOfMonth.toString(), currentUiLocale()),
            style = theme.typography.body,
            color = if (hasBills) theme.colors.ink else theme.colors.muted,
        )
        AtharText(
            text = AtharNumerals.localize(day.date.monthNumber.toString(), currentUiLocale()),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (hasBills) theme.colors.ember else theme.colors.divider),
        )
    }
}

@Composable
private fun BillRow(item: BillItem, today: LocalDate?, onOpenDetail: (BillItem) -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier.clickable { onOpenDetail(item) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            DateBadge(item = item, today = today)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AtharText(
                    text = item.title.ifBlank { stringResource(R.string.plan_bills_unknown_title) },
                    style = theme.typography.headline,
                    color = theme.colors.ink,
                )
                AtharText(
                    text = itemSubtitle(item),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            AtharNumber(
                money = item.amount,
                color = if (item.type == TxType.INCOME) theme.colors.olive else theme.colors.ember,
            )
        }
    }
}

/**
 * One-tap detail for an expected occurrence: status truth, and for paid items the
 * real matching evidence (charge date and transaction id) — a genuine trace source
 * two taps deep from the Bills list. Offers pause (reversible) and a confirmed
 * permanent cancel of the underlying rule.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun BillDetailSheet(
    item: BillItem,
    today: LocalDate?,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = AtharTheme
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(
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
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
                AtharText(
                    text = item.title.ifBlank { stringResource(R.string.plan_bills_unknown_title) },
                    style = theme.typography.title,
                )
                item.detail.takeIf { it.isNotBlank() }?.let {
                    AtharText(text = it, style = theme.typography.caption, color = theme.colors.muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    AtharNumber(
                        money = item.amount,
                        color = if (item.type == TxType.INCOME) theme.colors.olive else theme.colors.ember,
                    )
                    item.cadence?.let {
                        AtharText(text = cadenceLabel(it), style = theme.typography.caption, color = theme.colors.muted)
                    }
                }
                AtharText(
                    text = stringResource(R.string.plan_bills_detail_due, item.dueDate.toString()),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                AtharText(
                    text = statusLabel(item, today),
                    style = theme.typography.body,
                    color = when (item.status) {
                        BillStatus.PAID -> theme.colors.olive
                        BillStatus.UNCONFIRMED -> theme.colors.dust
                        BillStatus.DUE_TODAY -> theme.colors.ember
                        BillStatus.UPCOMING -> theme.colors.muted
                    },
                )
            }

            // Real evidence trace: the exact confirmed charge that settled this
            // occurrence — the matcher's own bound transaction record (date, amount,
            // currency, account, merchant). Never a synthesized or capped row.
            val evidence = item.evidence
            if (item.status == BillStatus.PAID && evidence != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(theme.spacing.s))
                        .background(theme.colors.surface)
                        .padding(theme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
                ) {
                    AtharText(
                        text = stringResource(R.string.plan_bills_detail_evidence_title),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        AtharText(
                            text = stringResource(R.string.plan_bills033_evidence_charged),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                        AtharNumber(money = evidence.amount, color = theme.colors.ink)
                    }
                    AtharText(
                        text = stringResource(R.string.plan_bills_detail_evidence_date, evidence.date.toString()),
                        style = theme.typography.body,
                        color = theme.colors.ink,
                    )
                    AtharText(
                        text = stringResource(R.string.plan_bills033_evidence_account, evidence.accountId),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                    AtharText(
                        text = stringResource(R.string.plan_bills033_evidence_merchant, evidence.merchant),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                    AtharText(
                        text = stringResource(R.string.plan_bills_detail_evidence_ref, evidence.transactionId),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
            } else {
                AtharText(
                    text = stringResource(R.string.plan_bills_detail_evidence_missing),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }

            ReminderButton(
                text = stringResource(R.string.plan_bills_detail_pause),
                onClick = onPause,
                primary = false,
            )
            ReminderButton(
                text = stringResource(
                    if (confirmDelete) {
                        R.string.plan_bills_detail_confirm_delete
                    } else {
                        R.string.plan_bills_detail_delete
                    },
                ),
                onClick = {
                    if (confirmDelete) {
                        onDelete()
                    } else {
                        confirmDelete = true
                    }
                },
                primary = false,
            )
        }
    }
}

@Composable
private fun DateBadge(item: BillItem, today: LocalDate?) {
    val theme = AtharTheme
    Column(
        modifier = Modifier
            .width(58.dp)
            .height(MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.divider),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AtharText(
            text = AtharNumerals.localize(item.dueDate.dayOfMonth.toString(), currentUiLocale()),
            style = theme.typography.body,
            color = theme.colors.ink,
        )
        AtharText(
            text = statusLabel(item, today),
            style = theme.typography.caption,
            color = when (item.status) {
                BillStatus.PAID -> theme.colors.olive
                BillStatus.UNCONFIRMED -> theme.colors.dust
                BillStatus.DUE_TODAY -> theme.colors.ember
                BillStatus.UPCOMING -> theme.colors.muted
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun itemSubtitle(item: BillItem): String =
    listOfNotNull(
        item.cadence?.let { cadenceLabel(it) }?.takeIf { it.isNotBlank() },
        item.detail.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

@Composable
private fun cadenceLabel(cadence: BillCadenceLabel): String = when (cadence) {
    BillCadenceLabel.MONTHLY -> stringResource(R.string.plan_bills_cadence_monthly)
    BillCadenceLabel.WEEKLY -> stringResource(R.string.plan_bills_cadence_weekly)
    BillCadenceLabel.QUARTERLY -> stringResource(R.string.plan_bills_cadence_quarterly)
    BillCadenceLabel.YEARLY -> stringResource(R.string.plan_bills_cadence_yearly)
}

@Composable
private fun statusLabel(item: BillItem, today: LocalDate?): String = when (item.status) {
    BillStatus.PAID -> stringResource(R.string.plan_bills_status_paid)
    BillStatus.UNCONFIRMED -> stringResource(R.string.plan_bills_status_unconfirmed)
    BillStatus.DUE_TODAY -> stringResource(R.string.plan_bills_today)
    BillStatus.UPCOMING -> {
        if (today == null) {
            item.dueDate.monthNumber.toString()
        } else {
            val days = ChronoUnit.DAYS.between(today.toJava(), item.dueDate.toJava()).toInt()
            when {
                days in 1..6 -> stringResource(R.string.plan_bills_in_days_short, days)
                else -> item.dueDate.monthNumber.toString()
            }
        }
    }
}

private fun LocalDate.toJava(): java.time.LocalDate =
    java.time.LocalDate.of(year, monthNumber, dayOfMonth)

private fun hasPostNotificationsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
