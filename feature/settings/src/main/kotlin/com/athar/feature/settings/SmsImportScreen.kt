package com.athar.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.repo.BackfillProgress
import com.athar.core.domain.smsimport.SmsSenderIds
import kotlinx.datetime.LocalDate

@Composable
fun SmsImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SmsImportViewModel = hiltViewModel(),
) {
    val theme = AtharTheme
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val draft by viewModel.manualDraft.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshDiscovered() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.colors.parchment)
            .verticalScroll(rememberScrollState())
            .padding(theme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtharText(
                text = stringResource(R.string.settings_sms_import_overline),
                style = theme.typography.overline,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(R.string.settings_action_back),
                style = theme.typography.body,
                color = theme.colors.muted,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_sms_import_title),
                    style = theme.typography.headline,
                )
                AtharText(
                    text = stringResource(R.string.settings_sms_import_body),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AtharText(
                        text = stringResource(R.string.settings_sms_import_live),
                        style = theme.typography.headline,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.liveTrackingEnabled,
                        onCheckedChange = viewModel::setLiveTracking,
                    )
                }
                AtharText(
                    text = stringResource(R.string.settings_sms_import_live_body),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_sms_import_senders),
                    style = theme.typography.headline,
                )
                if (settings.selectedSenders.isEmpty()) {
                    AtharText(
                        text = stringResource(R.string.settings_sms_import_no_senders),
                        style = theme.typography.body,
                        color = theme.colors.muted,
                    )
                }
                discovered.forEach { item ->
                    val selected = settings.selectedSenders.any {
                        SmsSenderIds.normalize(it) == SmsSenderIds.normalize(item.address)
                    }
                    SenderToggleRow(
                        label = "${item.address} (${item.messageCount})",
                        checked = selected,
                        onCheckedChange = { viewModel.toggleSender(item.address, it) },
                    )
                }
                settings.selectedSenders
                    .filter { sel ->
                        discovered.none {
                            SmsSenderIds.normalize(it.address) == SmsSenderIds.normalize(sel)
                        }
                    }
                    .forEach { sender ->
                        SenderToggleRow(
                            label = sender,
                            checked = true,
                            onCheckedChange = { viewModel.toggleSender(sender, false) },
                        )
                    }
                AtharTextField(
                    value = draft,
                    onValueChange = viewModel::setManualDraft,
                    label = stringResource(R.string.settings_sms_import_manual_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                AtharText(
                    text = stringResource(R.string.settings_sms_import_manual_add),
                    style = theme.typography.body,
                    color = theme.colors.ember,
                    modifier = Modifier.clickable(onClick = viewModel::addManualSender),
                )
            }
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_sms_import_dates),
                    style = theme.typography.headline,
                )
                AtharTextField(
                    value = settings.startDate?.toString().orEmpty(),
                    onValueChange = { raw ->
                        viewModel.setStartDate(raw.trim().takeIf { it.isNotEmpty() }?.let {
                            runCatching { LocalDate.parse(it) }.getOrNull()
                        })
                    },
                    label = stringResource(R.string.settings_sms_import_start),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                ) {
                    Checkbox(
                        checked = settings.openEnded,
                        onCheckedChange = viewModel::setOpenEnded,
                    )
                    AtharText(
                        text = stringResource(R.string.settings_sms_import_open_ended),
                        style = theme.typography.body,
                    )
                }
                if (!settings.openEnded) {
                    AtharTextField(
                        value = settings.endDate?.toString().orEmpty(),
                        onValueChange = { raw ->
                            viewModel.setEndDate(raw.trim().takeIf { it.isNotEmpty() }?.let {
                                runCatching { LocalDate.parse(it) }.getOrNull()
                            })
                        },
                        label = stringResource(R.string.settings_sms_import_end),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_sms_import_preview_action),
                    style = theme.typography.body,
                    color = theme.colors.ember,
                    modifier = Modifier.clickable(onClick = viewModel::runPreview),
                )
                preview?.let { p ->
                    AtharText(
                        text = stringResource(
                            R.string.settings_sms_import_preview_summary,
                            p.matchingSmsCount,
                            p.parseableCount,
                            p.duplicateCount,
                            p.skippedCount,
                        ),
                        style = theme.typography.body,
                    )
                }
                AtharText(
                    text = stringResource(R.string.settings_sms_import_confirm),
                    style = theme.typography.body,
                    color = theme.colors.ember,
                    modifier = Modifier.clickable(onClick = viewModel::runImport),
                )
                when (val p = progress) {
                    is BackfillProgress.Running -> AtharText(
                        text = stringResource(R.string.settings_backfill_running, p.scanned),
                        style = theme.typography.body,
                        color = theme.colors.muted,
                    )
                    is BackfillProgress.Done -> AtharText(
                        text = stringResource(
                            R.string.settings_backfill_done,
                            p.scanned,
                            p.sentToPipeline,
                        ),
                        style = theme.typography.body,
                    )
                    is BackfillProgress.Failed -> AtharText(
                        text = stringResource(R.string.settings_backfill_failed, p.reason),
                        style = theme.typography.body,
                        color = theme.colors.crimson,
                    )
                    BackfillProgress.Idle -> Unit
                }
                lastResult?.takeIf { !it.failed }?.let { r ->
                    AtharText(
                        text = stringResource(
                            R.string.settings_sms_import_result,
                            r.scanned,
                            r.accepted,
                            r.parseable,
                            r.duplicates,
                            r.skipped,
                        ),
                        style = theme.typography.body,
                    )
                }
                error?.let {
                    AtharText(text = it, style = theme.typography.body, color = theme.colors.crimson)
                }
            }
        }
    }
}
