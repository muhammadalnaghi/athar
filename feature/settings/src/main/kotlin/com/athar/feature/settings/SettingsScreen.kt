package com.athar.feature.settings

import com.athar.core.designsystem.display.currentUiLocale

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import com.athar.core.domain.repo.BackfillProgress
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.athar.core.common.money.Money
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.BudgetTargetImportPreview
import com.athar.core.domain.repo.CsvImportColumnMapping
import com.athar.core.domain.repo.CsvImportColumnRole
import com.athar.core.domain.repo.CsvImportCurrencySummary
import com.athar.core.domain.repo.CsvImportPreview
import com.athar.core.domain.repo.CsvImportPreviewRow
import com.athar.core.domain.repo.CsvImportRowEdit
import com.athar.core.domain.repo.InvestmentImportPreview
import com.athar.core.domain.repo.MerchantBulkExportMode
import com.athar.core.domain.repo.MerchantBulkImportCategoryImpact
import com.athar.core.domain.repo.MerchantBulkImportSkipSummary
import com.athar.core.domain.repo.WishlistImportPreview
import com.athar.core.domain.repo.merchantBulkAiPrompt
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.display.CurrencyCatalog
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import java.math.BigDecimal
import java.time.YearMonth
import java.util.Locale

@Composable
fun SettingsScreen(
    onOpenCategories: () -> Unit = {},
    onOpenSmsAudit: () -> Unit = {},
    onOpenActivityLog: () -> Unit = {},
    onOpenUserTemplates: () -> Unit = {},
    onOpenRecurringRules: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenIncomeSources: () -> Unit = {},
    onOpenSmsImport: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onApplyLocale: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme = AtharTheme
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var smsGranted by remember { mutableStateOf(hasSmsPermissions(context)) }
    val status by viewModel.status.collectAsStateWithLifecycle()
    val backfill by viewModel.backfillProgress.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    var showRescanConfirm by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                smsGranted = hasSmsPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smsLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { results ->
        smsGranted = results.values.all { it }
    }

    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val csvStatus by viewModel.csvStatus.collectAsStateWithLifecycle()
    val hijriEnabled by viewModel.hijriEnabled.collectAsStateWithLifecycle()
    val rescanStatus by viewModel.rescanStatus.collectAsStateWithLifecycle()
    val recoverStatus by viewModel.recoverStatus.collectAsStateWithLifecycle()
    val merchantCatalogState by viewModel.merchantCatalog.collectAsStateWithLifecycle()
    val ownAccounts by viewModel.ownAccountNumbers.collectAsStateWithLifecycle()
    val displayCurrency by viewModel.displayCurrency.collectAsStateWithLifecycle()
    val appLocale by viewModel.appLocale.collectAsStateWithLifecycle()
    val statementImportAccounts by viewModel.statementImportAccounts.collectAsStateWithLifecycle()
    val selectedStatementImportAccountId by viewModel.selectedStatementImportAccountId.collectAsStateWithLifecycle()
    val budgetTargetStatus by viewModel.budgetTargetStatus.collectAsStateWithLifecycle()
    val wishlistImportStatus by viewModel.wishlistImportStatus.collectAsStateWithLifecycle()
    val investmentImportStatus by viewModel.investmentImportStatus.collectAsStateWithLifecycle()
    val recordingEnabled by viewModel.recordingEnabled.collectAsStateWithLifecycle()
    val recordingWriteFailed by viewModel.recordingWriteFailed.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) pendingExportUri = uri
    }
    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) pendingImportUri = uri
    }
    val csvLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.previewCsvImport(context.contentResolver, uri)
    }
    val budgetTargetLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.previewBudgetTargetsImport(context.contentResolver, uri)
    }
    val wishlistImportLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.previewWishlistImport(context.contentResolver, uri)
    }
    val investmentImportLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.previewInvestmentImport(context.contentResolver, uri)
    }
    val csvExportLauncher = rememberLauncherForActivityResult(CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(context.contentResolver, uri)
    }
    val bulkExportLauncher = rememberLauncherForActivityResult(CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            viewModel.exportUncategorized(
                context.contentResolver,
                uri,
                MerchantBulkExportMode.FULL_CONTEXT,
            )
        }
    }
    val bulkPrivateExportLauncher = rememberLauncherForActivityResult(CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            viewModel.exportUncategorized(
                context.contentResolver,
                uri,
                MerchantBulkExportMode.NO_RAW_BODY,
            )
        }
    }
    val bulkImportLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) viewModel.previewCategorizations(context.contentResolver, uri)
    }
    val bulkStatus by viewModel.bulkCategorizeStatus.collectAsStateWithLifecycle()
    var bulkPromptCopied by remember { mutableStateOf(false) }
    val bulkPromptClipboardLabel = stringResource(R.string.settings_bulk_cat_prompt_clip_label)
    val communityShareLauncher = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportLearnedRules(context.contentResolver, uri)
    }
    val communityShareStatus by viewModel.communityShareStatus.collectAsStateWithLifecycle()
    var taxYear by remember { mutableStateOf(java.time.Year.now().value) }
    val taxStatus by viewModel.taxExportStatus.collectAsStateWithLifecycle()
    val taxExportLauncher = rememberLauncherForActivityResult(CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.exportTaxReport(context.contentResolver, uri, taxYear)
    }
    val supportDiagnosticsLauncher = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportSupportDiagnostics(context.contentResolver, uri)
    }
    val supportDiagnosticsStatus by viewModel.supportDiagnosticsStatus.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.colors.parchment),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.l),
        ) {
            AtharText(text = stringResource(R.string.settings_screen_overline), style = theme.typography.overline, color = theme.colors.muted)

            LanguageCard(
                currentTag = appLocale,
                onSelect = { tag ->
                    viewModel.setAppLocale(tag)
                    onApplyLocale(tag)
                },
            )

            SmsPermissionCard(
                granted = smsGranted,
                onGrant = { smsLauncher.launch(SMS_PERMISSIONS) },
            )

            if (smsGranted) {
                BackfillCard(
                    progress = backfill,
                    onRescan = viewModel::runBackfill,
                )
            }

            RescanAndCleanCard(
                status = rescanStatus,
                pendingCount = pendingCount,
                onRequestRescan = { showRescanConfirm = true },
                onClearStatus = viewModel::clearRescanStatus,
            )

            RecoverDismissedCard(
                status = recoverStatus,
                onRecover = viewModel::recoverDismissed,
                onClearStatus = viewModel::clearRecoverStatus,
            )

            RecordingToggleCard(
                enabled = recordingEnabled,
                failed = recordingWriteFailed,
                onToggle = viewModel::setRecordingEnabled,
            )

            BackupCard(
                status = status,
                onExport = { exportLauncher.launch(DEFAULT_BACKUP_NAME) },
                onImport = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                onClearStatus = viewModel::clearStatus,
            )

            CsvImportCard(
                status = csvStatus,
                accounts = statementImportAccounts,
                selectedAccountId = selectedStatementImportAccountId,
                onSelectAccount = viewModel::setStatementImportAccount,
                onImport = {
                    csvLauncher.launch(
                        arrayOf(
                            "text/csv",
                            "text/comma-separated-values",
                            "text/tab-separated-values",
                            "text/plain",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/x-ofx",
                            "application/vnd.intu.qfx",
                            "application/x-mt940",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                onColumnMappingChange = viewModel::setCsvColumnMapping,
                onPreviewMappedImport = viewModel::previewCsvImportWithMapping,
                onRowIncludedChange = viewModel::setCsvImportRowIncluded,
                onRowEditChange = viewModel::setCsvImportRowEdit,
                onConfirmImport = viewModel::confirmCsvImport,
                onCancelPreview = viewModel::cancelCsvImportPreview,
                onExport = { csvExportLauncher.launch("athar-transactions.csv") },
                onClear = viewModel::clearCsvStatus,
            )

            BudgetTargetImportCard(
                status = budgetTargetStatus,
                onImport = {
                    budgetTargetLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                onConfirmImport = viewModel::confirmBudgetTargetsImport,
                onCancelPreview = viewModel::cancelBudgetTargetsImportPreview,
                onClear = viewModel::clearBudgetTargetStatus,
            )

            WishlistImportCard(
                status = wishlistImportStatus,
                onImport = {
                    wishlistImportLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                onConfirmImport = viewModel::confirmWishlistImport,
                onCancelPreview = viewModel::cancelWishlistImportPreview,
                onClear = viewModel::clearWishlistImportStatus,
            )

            InvestmentImportCard(
                status = investmentImportStatus,
                onImport = {
                    investmentImportLauncher.launch(
                        arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                onConfirmImport = viewModel::confirmInvestmentImport,
                onCancelPreview = viewModel::cancelInvestmentImportPreview,
                onClear = viewModel::clearInvestmentImportStatus,
            )

            TaxExportCard(
                year = taxYear,
                status = taxStatus,
                onYearChange = { year -> taxYear = year.coerceIn(2000, 2100) },
                onExport = { taxExportLauncher.launch("athar-tax-$taxYear.pdf") },
                onClear = viewModel::clearTaxExportStatus,
            )

            BulkCategorizeCard(
                status = bulkStatus,
                promptCopied = bulkPromptCopied,
                onCopyPrompt = {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    if (clip != null) {
                        clip.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                bulkPromptClipboardLabel,
                                merchantBulkAiPrompt,
                            ),
                        )
                        bulkPromptCopied = true
                    }
                },
                onExport = { bulkExportLauncher.launch("athar-uncategorized.csv") },
                onExportPrivate = { bulkPrivateExportLauncher.launch("athar-uncategorized-private.csv") },
                onImport = { bulkImportLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                onConfirmImport = viewModel::confirmBulkCategorizeImport,
                onCancelPreview = viewModel::cancelBulkCategorizePreview,
                onClear = viewModel::clearBulkCategorizeStatus,
            )

            CommunityRulesShareCard(
                status = communityShareStatus,
                onExport = { communityShareLauncher.launch("athar-shared-rules.json") },
                onOpenIssue = {
                    val rows = (communityShareStatus as? CommunityShareStatus.Exported)?.rows ?: 0
                    val url = buildGitHubIssueUrl(rows)
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        // Fall back to clipboard
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip?.setPrimaryClip(android.content.ClipData.newPlainText("Athar GitHub URL", url))
                    }
                },
                onClear = viewModel::clearCommunityShareStatus,
            )

            MerchantCatalogCard(
                state = merchantCatalogState,
                onToggleUpdates = viewModel::setMerchantCatalogUpdates,
                onRefresh = viewModel::refreshMerchantCatalog,
                onReset = viewModel::resetMerchantCatalog,
            )

            SupportDiagnosticsCard(
                status = supportDiagnosticsStatus,
                onExport = { supportDiagnosticsLauncher.launch("athar-support-diagnostics.json") },
                onClear = viewModel::clearSupportDiagnosticsStatus,
            )

            DisplayCurrencyCard(
                currentCode = displayCurrency,
                onSelect = viewModel::setDisplayCurrency,
            )

            HijriToggleCard(
                enabled = hijriEnabled,
                onToggle = viewModel::setHijriEnabled,
            )

            OwnAccountsCard(
                accounts = ownAccounts,
                onSave = viewModel::setOwnAccountNumbers,
            )

            UpdatesCard()

            HistoryEntryCard(onOpen = onOpenHistory)

            AccountsEntryCard(onOpen = onOpenAccounts)

            IncomeSourcesEntryCard(onOpen = onOpenIncomeSources)

            SmsImportEntryCard(onOpen = onOpenSmsImport)

            RecurringRulesEntryCard(onOpen = onOpenRecurringRules)

            SmsAuditEntryCard(onOpen = onOpenSmsAudit)

            UserTemplatesEntryCard(onOpen = onOpenUserTemplates)

            ActivityLogEntryCard(onOpen = onOpenActivityLog)

            CategoriesEntryCard(onOpen = onOpenCategories)

            AboutCard()
        }
    }

    pendingExportUri?.let { uri ->
        PassphrasePrompt(
            title = stringResource(R.string.settings_backup_passphrase_export_title),
            description = stringResource(R.string.settings_backup_passphrase_export_body),
            confirmLabel = stringResource(R.string.settings_backup_passphrase_export_confirm),
            onConfirm = { pass ->
                viewModel.export(context.contentResolver, uri, pass)
                pendingExportUri = null
            },
            onDismiss = { pendingExportUri = null },
        )
    }

    pendingImportUri?.let { uri ->
        PassphrasePrompt(
            title = stringResource(R.string.settings_backup_passphrase_import_title),
            description = stringResource(R.string.settings_backup_passphrase_import_body),
            confirmLabel = stringResource(R.string.settings_backup_passphrase_import_confirm),
            onConfirm = { pass ->
                viewModel.import(context.contentResolver, uri, pass)
                pendingImportUri = null
            },
            onDismiss = { pendingImportUri = null },
        )
    }

    if (showRescanConfirm) {
        RescanConfirmDialog(
            pendingCount = pendingCount,
            onConfirm = {
                showRescanConfirm = false
                viewModel.rescanAndClean()
            },
            onDismiss = { showRescanConfirm = false },
        )
    }
}

@Composable
private fun SmsPermissionCard(
    granted: Boolean,
    onGrant: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(
                text = if (granted) {
                    stringResource(R.string.settings_sms_card_title_granted)
                } else {
                    stringResource(R.string.settings_sms_card_title_denied)
                },
                style = theme.typography.headline,
                color = if (granted) theme.colors.olive else theme.colors.ink,
            )
            AtharText(
                text = if (granted) {
                    stringResource(R.string.settings_sms_card_body_granted)
                } else {
                    stringResource(R.string.settings_sms_card_body_denied)
                },
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            if (!granted) PrimaryButton(text = stringResource(R.string.settings_sms_card_grant_action), onClick = onGrant)
        }
    }
}

@Composable
private fun HijriToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable { onToggle(!enabled) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AtharText(text = stringResource(R.string.settings_hijri_title), style = theme.typography.headline)
                AtharText(
                    text = if (enabled) {
                        stringResource(R.string.settings_hijri_body_on)
                    } else {
                        stringResource(R.string.settings_hijri_body_off)
                    },
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(if (enabled) theme.colors.ember else theme.colors.divider)
                    .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
            ) {
                AtharText(
                    text = if (enabled) {
                        stringResource(R.string.settings_hijri_pill_on)
                    } else {
                        stringResource(R.string.settings_hijri_pill_off)
                    },
                    style = theme.typography.caption,
                    color = if (enabled) theme.colors.parchment else theme.colors.ink,
                )
            }
        }
    }
}

/**
 * WM033: prospective automatic recording of clearly evidenced completed
 * purchases. Same toggle pattern as [HijriToggleCard]; the whole card is the
 * touch target (≥48dp), ember pill marks ON, one accent.
 */
@Composable
private fun RecordingToggleCard(
    enabled: Boolean,
    failed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val theme = AtharTheme
    AtharCard(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onToggle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AtharText(
                    text = stringResource(R.string.settings_recording_title),
                    style = theme.typography.headline,
                )
                AtharText(
                    text = if (enabled) {
                        stringResource(R.string.settings_recording_body_on)
                    } else {
                        stringResource(R.string.settings_recording_body_off)
                    },
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
                if (failed) AtharText(
                    text = stringResource(R.string.settings_recording_write_failed),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(if (enabled) theme.colors.ember else theme.colors.divider)
                    .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
            ) {
                AtharText(
                    text = if (enabled) {
                        stringResource(R.string.settings_recording_pill_on)
                    } else {
                        stringResource(R.string.settings_recording_pill_off)
                    },
                    style = theme.typography.caption,
                    color = if (enabled) theme.colors.parchment else theme.colors.ink,
                )
            }
        }
    }
}

@Composable
private fun CommunityRulesShareCard(
    status: CommunityShareStatus,
    onExport: () -> Unit,
    onOpenIssue: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_community_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_community_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            when (val s = status) {
                CommunityShareStatus.Idle -> Unit
                CommunityShareStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is CommunityShareStatus.Exported -> {
                    AtharText(
                        text = stringResource(R.string.settings_community_exported, s.rows),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PrimaryButton(
                                text = stringResource(R.string.settings_community_action_open_issue),
                                onClick = onOpenIssue,
                            )
                        }
                        TextButton(onClick = onClear) {
                            AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                        }
                    }
                }
                CommunityShareStatus.Empty -> {
                    AtharText(
                        text = stringResource(R.string.settings_community_empty),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is CommunityShareStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is CommunityShareStatus.Working
            val isExported = status is CommunityShareStatus.Exported
            if (!isExported) {
                PrimaryButton(
                    text = if (isWorking) {
                        stringResource(R.string.settings_status_in_progress)
                    } else {
                        stringResource(R.string.settings_community_action_export)
                    },
                    onClick = { if (!isWorking) onExport() },
                )
            }
        }
    }
}

@Composable
private fun SupportDiagnosticsCard(
    status: SupportDiagnosticsStatus,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_support_diagnostics_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_support_diagnostics_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            when (val s = status) {
                SupportDiagnosticsStatus.Idle -> Unit
                SupportDiagnosticsStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is SupportDiagnosticsStatus.Exported -> {
                    AtharText(
                        text = stringResource(
                            R.string.settings_support_diagnostics_exported,
                            s.auditRows,
                            s.parsed,
                            s.failed,
                            s.ignored,
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is SupportDiagnosticsStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is SupportDiagnosticsStatus.Working
            PrimaryButton(
                text = if (isWorking) {
                    stringResource(R.string.settings_status_in_progress)
                } else {
                    stringResource(R.string.settings_support_diagnostics_action_export)
                },
                onClick = { if (!isWorking) onExport() },
            )
        }
    }
}

/**
 * Returns a GitHub "new issue" URL pre-filled with the community-rules issue
 * template name and a body describing the submission. The user attaches the JSON
 * file manually since GitHub doesn't accept file attachments via URL params.
 */
private fun buildGitHubIssueUrl(ruleCount: Int): String {
    val title = java.net.URLEncoder.encode(
        "Community rules · $ruleCount rules from Athar export",
        "UTF-8",
    )
    val body = java.net.URLEncoder.encode(
        """
        I'm submitting $ruleCount learned rules from my local Athar database for review.

        Per `docs/COMMUNITY_RULES_WORKFLOW.md`:
        - All entries are merchant→category mappings I explicitly created via "Always categorize X as Y".
        - The JSON file (`athar-shared-rules.json`) contains only (pattern, categoryId, confidence) — no transaction data, no PII.

        I will attach the JSON file in a comment after creating this issue (GitHub doesn't accept file attachments in URL parameters).
        """.trimIndent(),
        "UTF-8",
    )
    return "https://github.com/wa1939/athar/issues/new?title=$title&body=$body&labels=community-rules"
}

@Composable
private fun BulkCategorizeCard(
    status: BulkCategorizeStatus,
    promptCopied: Boolean,
    onCopyPrompt: () -> Unit,
    onExport: () -> Unit,
    onExportPrivate: () -> Unit,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelPreview: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_bulk_cat_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_bulk_cat_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            SecondaryButton(
                text = stringResource(R.string.settings_bulk_cat_action_copy_prompt),
                onClick = onCopyPrompt,
            )
            if (promptCopied) {
                AtharText(
                    text = stringResource(R.string.settings_bulk_cat_prompt_copied),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
            }
            when (val s = status) {
                BulkCategorizeStatus.Idle -> Unit
                BulkCategorizeStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is BulkCategorizeStatus.Exported -> {
                    AtharText(
                        text = stringResource(R.string.settings_bulk_cat_exported, s.rows),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is BulkCategorizeStatus.Preview -> {
                    AtharText(
                        text = stringResource(
                            R.string.settings_bulk_cat_previewed,
                            s.updated,
                            s.rulesAdded,
                            s.skipped,
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    BulkCategorizeCategoryImpactSummary(impact = s.categoryImpact)
                    BulkCategorizeSkipDetails(summary = s.skipSummary)
                }
                is BulkCategorizeStatus.Imported -> {
                    AtharText(
                        text = stringResource(
                            R.string.settings_bulk_cat_imported,
                            s.updated,
                            s.rulesAdded,
                            s.skipped,
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    BulkCategorizeSkipDetails(summary = s.skipSummary)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is BulkCategorizeStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is BulkCategorizeStatus.Working
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                if (status is BulkCategorizeStatus.Preview) {
                    PrimaryButton(
                        text = stringResource(R.string.settings_bulk_cat_action_confirm_import),
                        onClick = onConfirmImport,
                    )
                    SecondaryButton(
                        text = stringResource(R.string.settings_bulk_cat_action_cancel_import),
                        onClick = onCancelPreview,
                    )
                } else {
                    PrimaryButton(
                        text = if (isWorking) {
                            stringResource(R.string.settings_status_in_progress)
                        } else {
                            stringResource(R.string.settings_bulk_cat_action_export)
                        },
                        onClick = { if (!isWorking) onExport() },
                    )
                    SecondaryButton(
                        text = if (isWorking) {
                            stringResource(R.string.settings_status_in_progress)
                        } else {
                            stringResource(R.string.settings_bulk_cat_action_export_private)
                        },
                        onClick = { if (!isWorking) onExportPrivate() },
                    )
                    SecondaryButton(
                        text = if (isWorking) {
                            stringResource(R.string.settings_status_in_progress)
                        } else {
                            stringResource(R.string.settings_bulk_cat_action_import)
                        },
                        onClick = { if (!isWorking) onImport() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BulkCategorizeCategoryImpactSummary(impact: List<MerchantBulkImportCategoryImpact>) {
    if (impact.isEmpty()) return

    val theme = AtharTheme
    val isArabic = currentUiLocale().language.equals("ar", ignoreCase = true)
    val visible = impact.take(5).map { row ->
        val categoryName = if (isArabic) {
            row.categoryNameAr.ifBlank { row.categoryName }
        } else {
            row.categoryName
        }
        stringResource(R.string.settings_bulk_cat_impact_item, categoryName, row.updated)
    }
    val remaining = impact.size - visible.size
    val parts = if (remaining > 0) {
        visible + stringResource(R.string.settings_bulk_cat_impact_more, remaining)
    } else {
        visible
    }
    AtharText(
        text = stringResource(R.string.settings_bulk_cat_impact, parts.joinToString(" · ")),
        style = theme.typography.caption,
        color = theme.colors.muted,
    )
}

@Composable
private fun BulkCategorizeSkipDetails(summary: MerchantBulkImportSkipSummary) {
    val theme = AtharTheme
    val parts = buildList {
        if (summary.unknownCategories > 0) {
            add(stringResource(R.string.settings_bulk_cat_skip_unknown, summary.unknownCategories))
        }
        if (summary.incompatibleCategories > 0) {
            add(stringResource(R.string.settings_bulk_cat_skip_incompatible, summary.incompatibleCategories))
        }
        if (summary.missingTransactions > 0) {
            add(stringResource(R.string.settings_bulk_cat_skip_missing, summary.missingTransactions))
        }
        if (summary.conflictingGroups > 0) {
            add(stringResource(R.string.settings_bulk_cat_skip_conflict, summary.conflictingGroups))
        }
        if (summary.blankRowsWithoutGroupChoice > 0) {
            add(stringResource(R.string.settings_bulk_cat_skip_blank, summary.blankRowsWithoutGroupChoice))
        }
        if (summary.malformedRows > 0) {
            add(stringResource(R.string.settings_bulk_cat_skip_malformed, summary.malformedRows))
        }
    }
    if (parts.isNotEmpty()) {
        AtharText(
            text = stringResource(R.string.settings_bulk_cat_skip_details, parts.joinToString(" · ")),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}

@Composable
private fun CsvImportCard(
    status: CsvStatus,
    accounts: List<Account>,
    selectedAccountId: String,
    onSelectAccount: (String) -> Unit,
    onImport: () -> Unit,
    onColumnMappingChange: (CsvImportColumnRole, String?) -> Unit,
    onPreviewMappedImport: () -> Unit,
    onRowIncludedChange: (Int, Boolean) -> Unit,
    onRowEditChange: (CsvImportRowEdit) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelPreview: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_csv_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_csv_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            StatementImportAccountPicker(
                accounts = accounts,
                selectedAccountId = selectedAccountId,
                onSelectAccount = onSelectAccount,
            )
            when (val s = status) {
                CsvStatus.Idle -> Unit
                CsvStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is CsvStatus.MappingRequired -> {
                    CsvMappingEditor(
                        columns = s.columns,
                        reason = s.reason,
                        mapping = s.mapping,
                        onChange = onColumnMappingChange,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.settings_csv_mapping_preview),
                            onClick = onPreviewMappedImport,
                            modifier = Modifier.weight(1f),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(
                                text = stringResource(R.string.settings_csv_preview_cancel),
                                onClick = onCancelPreview,
                            )
                        }
                    }
                }
                is CsvStatus.Preview -> {
                    CsvPreviewSummary(
                        preview = s.preview,
                        accounts = accounts,
                        selectedAccountId = selectedAccountId,
                        onRowIncludedChange = onRowIncludedChange,
                        onRowEditChange = onRowEditChange,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.settings_csv_preview_confirm),
                            onClick = onConfirmImport,
                            modifier = Modifier.weight(1f),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(
                                text = stringResource(R.string.settings_csv_preview_cancel),
                                onClick = onCancelPreview,
                            )
                        }
                    }
                }
                is CsvStatus.Done -> {
                    AtharText(
                        text = stringResource(R.string.settings_csv_done, s.imported, s.skipped),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is CsvStatus.Exported -> {
                    AtharText(
                        text = stringResource(R.string.settings_csv_exported, s.count),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is CsvStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is CsvStatus.Working
            if (status !is CsvStatus.Preview && status !is CsvStatus.MappingRequired) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryButton(
                            text = if (isWorking) {
                                stringResource(R.string.settings_status_in_progress)
                            } else {
                                stringResource(R.string.settings_csv_action_import)
                            },
                            onClick = { if (!isWorking) onImport() },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(theme.spacing.s))
                                .background(theme.colors.divider)
                                .clickable(enabled = !isWorking) { onExport() }
                                .padding(theme.spacing.m),
                            contentAlignment = Alignment.Center,
                        ) {
                            AtharText(
                                text = if (isWorking) {
                                    stringResource(R.string.settings_status_in_progress)
                                } else {
                                    stringResource(R.string.settings_csv_action_export)
                                },
                                style = theme.typography.headline,
                                color = theme.colors.ink,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetTargetImportCard(
    status: BudgetTargetStatus,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelPreview: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_budget_targets_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_budget_targets_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            when (val s = status) {
                BudgetTargetStatus.Idle -> Unit
                BudgetTargetStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is BudgetTargetStatus.Preview -> {
                    BudgetTargetPreviewSummary(preview = s.preview)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.settings_budget_targets_preview_confirm),
                            onClick = onConfirmImport,
                            modifier = Modifier.weight(1f),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(
                                text = stringResource(R.string.settings_csv_preview_cancel),
                                onClick = onCancelPreview,
                            )
                        }
                    }
                }
                is BudgetTargetStatus.Done -> {
                    AtharText(
                        text = stringResource(
                            R.string.settings_budget_targets_done,
                            s.applied,
                            s.changed,
                            s.skipped,
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is BudgetTargetStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is BudgetTargetStatus.Working
            if (status !is BudgetTargetStatus.Preview) {
                PrimaryButton(
                    text = if (isWorking) {
                        stringResource(R.string.settings_status_in_progress)
                    } else {
                        stringResource(R.string.settings_budget_targets_action_import)
                    },
                    onClick = { if (!isWorking) onImport() },
                )
            }
        }
    }
}

@Composable
private fun BudgetTargetPreviewSummary(preview: BudgetTargetImportPreview) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = stringResource(
                R.string.settings_budget_targets_preview_summary,
                preview.targetRows,
                preview.changed,
                preview.skipped,
            ),
            style = theme.typography.caption,
            color = theme.colors.olive,
        )
        AtharText(
            text = stringResource(
                R.string.settings_budget_targets_preview_totals,
                preview.expenseTargets,
                preview.monthlyExpenseTotal.compactMoney(),
                preview.incomeTargets,
                preview.monthlyIncomeTotal.compactMoney(),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        preview.sampleRows.forEach { row ->
            AtharText(
                text = stringResource(
                    R.string.settings_budget_targets_preview_row,
                    row.categoryName,
                    row.kind.budgetTargetLabel(),
                    row.target.compactMoney(),
                ),
                style = theme.typography.caption,
                color = if (row.changed) theme.colors.ink else theme.colors.muted,
            )
        }
        preview.skippedRows.firstOrNull()?.let { skipped ->
            AtharText(
                text = stringResource(
                    R.string.settings_budget_targets_preview_first_skip,
                    skipped.rowNumber,
                    skipped.label,
                    skipped.reason,
                ),
                style = theme.typography.caption,
                color = theme.colors.crimson,
            )
        }
    }
}

@Composable
private fun CategoryKind.budgetTargetLabel(): String = when (this) {
    CategoryKind.EXPENSE -> stringResource(R.string.settings_csv_preview_type_expense)
    CategoryKind.INCOME -> stringResource(R.string.settings_csv_preview_type_income)
}

@Composable
private fun WishlistImportCard(
    status: WishlistImportStatus,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelPreview: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_wishlist_import_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_wishlist_import_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            when (val s = status) {
                WishlistImportStatus.Idle -> Unit
                WishlistImportStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is WishlistImportStatus.Preview -> {
                    WishlistImportPreviewSummary(preview = s.preview)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.settings_wishlist_import_preview_confirm),
                            onClick = onConfirmImport,
                            modifier = Modifier.weight(1f),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(
                                text = stringResource(R.string.settings_csv_preview_cancel),
                                onClick = onCancelPreview,
                            )
                        }
                    }
                }
                is WishlistImportStatus.Done -> {
                    AtharText(
                        text = stringResource(
                            R.string.settings_wishlist_import_done,
                            s.imported,
                            s.newItems,
                            s.updatedItems,
                            s.skipped,
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is WishlistImportStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is WishlistImportStatus.Working
            if (status !is WishlistImportStatus.Preview) {
                PrimaryButton(
                    text = if (isWorking) {
                        stringResource(R.string.settings_status_in_progress)
                    } else {
                        stringResource(R.string.settings_wishlist_import_action_import)
                    },
                    onClick = { if (!isWorking) onImport() },
                )
            }
        }
    }
}

@Composable
private fun WishlistImportPreviewSummary(preview: WishlistImportPreview) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = stringResource(
                R.string.settings_wishlist_import_preview_summary,
                preview.itemRows,
                preview.newItems,
                preview.updatedItems,
                preview.skipped,
            ),
            style = theme.typography.caption,
            color = theme.colors.olive,
        )
        AtharText(
            text = stringResource(
                R.string.settings_wishlist_import_preview_totals,
                preview.totalCost.compactMoney(),
                preview.totalSaved.compactMoney(),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        preview.sampleRows.forEach { row ->
            val mode = if (row.existing) {
                stringResource(R.string.settings_wishlist_import_preview_existing)
            } else {
                stringResource(R.string.settings_wishlist_import_preview_new)
            }
            val desiredMonths = row.desiredMonths?.toString()
                ?: stringResource(R.string.settings_wishlist_import_preview_flexible_months)
            AtharText(
                text = stringResource(
                    R.string.settings_wishlist_import_preview_row,
                    row.name,
                    row.cost.compactMoney(),
                    row.currentSaved.compactMoney(),
                    desiredMonths,
                    row.startMonth.formatWishlistMonth(),
                    mode,
                ),
                style = theme.typography.caption,
                color = if (row.existing) theme.colors.muted else theme.colors.ink,
            )
        }
        preview.skippedRows.firstOrNull()?.let { skipped ->
            AtharText(
                text = stringResource(
                    R.string.settings_wishlist_import_preview_first_skip,
                    skipped.rowNumber,
                    skipped.label,
                    skipped.reason,
                ),
                style = theme.typography.caption,
                color = theme.colors.crimson,
            )
        }
    }
}

private fun YearMonth.formatWishlistMonth(): String =
    "$year/${monthValue.toString().padStart(2, '0')}"

@Composable
private fun InvestmentImportCard(
    status: InvestmentImportStatus,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelPreview: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_investment_import_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_investment_import_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            when (val s = status) {
                InvestmentImportStatus.Idle -> Unit
                InvestmentImportStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is InvestmentImportStatus.Preview -> {
                    InvestmentImportPreviewSummary(preview = s.preview)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.settings_investment_import_preview_confirm),
                            onClick = onConfirmImport,
                            modifier = Modifier.weight(1f),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SecondaryButton(
                                text = stringResource(R.string.settings_csv_preview_cancel),
                                onClick = onCancelPreview,
                            )
                        }
                    }
                }
                is InvestmentImportStatus.Done -> {
                    val doneText = if (s.existingPool) {
                        stringResource(
                            R.string.settings_investment_import_done_existing,
                            s.importedContributions,
                            s.replacedContributions,
                            s.skipped,
                        )
                    } else {
                        stringResource(
                            R.string.settings_investment_import_done_new,
                            s.importedContributions,
                            s.skipped,
                        )
                    }
                    AtharText(
                        text = doneText,
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is InvestmentImportStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
            val isWorking = status is InvestmentImportStatus.Working
            if (status !is InvestmentImportStatus.Preview) {
                PrimaryButton(
                    text = if (isWorking) {
                        stringResource(R.string.settings_status_in_progress)
                    } else {
                        stringResource(R.string.settings_investment_import_action_import)
                    },
                    onClick = { if (!isWorking) onImport() },
                )
            }
        }
    }
}

@Composable
private fun InvestmentImportPreviewSummary(preview: InvestmentImportPreview) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = stringResource(
                R.string.settings_investment_import_preview_summary,
                preview.poolName,
                preview.contributionRows,
                preview.skipped,
            ),
            style = theme.typography.caption,
            color = theme.colors.olive,
        )
        AtharText(
            text = stringResource(
                R.string.settings_investment_import_preview_totals,
                preview.period,
                preview.totalCorpus.compactMoney(),
                preview.totalReturn.compactMoney(),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        if (preview.existingPool) {
            AtharText(
                text = stringResource(
                    R.string.settings_investment_import_preview_replace,
                    preview.replacedContributions,
                ),
                style = theme.typography.caption,
                color = theme.colors.crimson,
            )
        }
        preview.sampleRows.forEach { row ->
            AtharText(
                text = stringResource(
                    R.string.settings_investment_import_preview_row,
                    row.ownerName,
                    row.amount.compactMoney(),
                ),
                style = theme.typography.caption,
                color = theme.colors.ink,
            )
        }
        preview.skippedRows.firstOrNull()?.let { skipped ->
            AtharText(
                text = stringResource(
                    R.string.settings_investment_import_preview_first_skip,
                    skipped.rowNumber,
                    skipped.label,
                    skipped.reason,
                ),
                style = theme.typography.caption,
                color = theme.colors.crimson,
            )
        }
    }
}

private fun Money.compactMoney(): String =
    "${rounded().amount.stripTrailingZeros().toPlainString()} $currency"

@Composable
private fun StatementImportAccountPicker(
    accounts: List<Account>,
    selectedAccountId: String,
    onSelectAccount: (String) -> Unit,
) {
    if (accounts.size <= 1) return
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.settings_csv_account_label),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        accounts.forEach { account ->
            val selected = account.id == selectedAccountId
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(if (selected) theme.colors.olive else theme.colors.divider)
                    .clickable { onSelectAccount(account.id) }
                    .padding(theme.spacing.s),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
                    AtharText(
                        text = account.name,
                        style = theme.typography.body,
                        color = if (selected) theme.colors.parchment else theme.colors.ink,
                    )
                    AtharText(
                        text = account.currency,
                        style = theme.typography.caption,
                        color = if (selected) theme.colors.parchment else theme.colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CsvMappingEditor(
    columns: List<String>,
    reason: String,
    mapping: CsvImportColumnMapping,
    onChange: (CsvImportColumnRole, String?) -> Unit,
) {
    val theme = AtharTheme
    val roles = listOf(
        CsvImportColumnRole.DATE,
        CsvImportColumnRole.MERCHANT,
        CsvImportColumnRole.AMOUNT,
        CsvImportColumnRole.DEBIT,
        CsvImportColumnRole.CREDIT,
        CsvImportColumnRole.CURRENCY,
        CsvImportColumnRole.CATEGORY,
        CsvImportColumnRole.TYPE,
        CsvImportColumnRole.NOTES,
    )
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = reason,
            style = theme.typography.caption,
            color = theme.colors.crimson,
        )
        AtharText(
            text = stringResource(R.string.settings_csv_mapping_body),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        roles.forEach { role ->
            CsvMappingRoleSelector(
                role = role,
                columns = columns,
                selected = mapping.valueFor(role),
                onSelect = { onChange(role, it) },
            )
        }
    }
}

@Composable
private fun CsvMappingRoleSelector(
    role: CsvImportColumnRole,
    columns: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = role.label(),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        CsvMappingChoice(
            label = stringResource(R.string.settings_csv_mapping_none),
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        columns.forEach { column ->
            CsvMappingChoice(
                label = column.ifBlank { stringResource(R.string.settings_csv_mapping_blank_column) },
                selected = selected == column,
                onClick = { onSelect(column) },
            )
        }
    }
}

@Composable
private fun CsvMappingChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (selected) theme.colors.olive else theme.colors.divider)
            .clickable(onClick = onClick)
            .padding(theme.spacing.s),
    ) {
        AtharText(
            text = label,
            style = theme.typography.caption,
            color = if (selected) theme.colors.parchment else theme.colors.ink,
        )
    }
}

@Composable
private fun CsvImportColumnRole.label(): String = when (this) {
    CsvImportColumnRole.DATE -> stringResource(R.string.settings_csv_mapping_date)
    CsvImportColumnRole.MERCHANT -> stringResource(R.string.settings_csv_mapping_merchant)
    CsvImportColumnRole.AMOUNT -> stringResource(R.string.settings_csv_mapping_amount)
    CsvImportColumnRole.DEBIT -> stringResource(R.string.settings_csv_mapping_debit)
    CsvImportColumnRole.CREDIT -> stringResource(R.string.settings_csv_mapping_credit)
    CsvImportColumnRole.CURRENCY -> stringResource(R.string.settings_csv_mapping_currency)
    CsvImportColumnRole.CATEGORY -> stringResource(R.string.settings_csv_mapping_category)
    CsvImportColumnRole.TYPE -> stringResource(R.string.settings_csv_mapping_type)
    CsvImportColumnRole.NOTES -> stringResource(R.string.settings_csv_mapping_notes)
}

@Composable
private fun CsvPreviewSummary(
    preview: CsvImportPreview,
    accounts: List<Account>,
    selectedAccountId: String,
    onRowIncludedChange: (Int, Boolean) -> Unit,
    onRowEditChange: (CsvImportRowEdit) -> Unit,
) {
    val theme = AtharTheme
    var editingRow by remember { mutableStateOf<CsvImportPreviewRow?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        AtharText(
            text = stringResource(R.string.settings_csv_preview_summary, preview.importable, preview.skipped),
            style = theme.typography.caption,
            color = theme.colors.olive,
        )
        AtharText(
            text = preview.columnSummary(
                dateLabel = stringResource(R.string.settings_csv_preview_column_date),
                merchantLabel = stringResource(R.string.settings_csv_preview_column_merchant),
                moneyLabel = stringResource(R.string.settings_csv_preview_column_money),
                currencyLabel = stringResource(R.string.settings_csv_preview_column_currency),
                categoryLabel = stringResource(R.string.settings_csv_preview_column_category),
            ),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        if (preview.currencySummaries.isNotEmpty()) {
            AtharText(
                text = stringResource(R.string.settings_csv_preview_currency_title),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            preview.currencySummaries.forEach { summary ->
                AtharText(
                    text = summary.previewLine(
                        rowLabel = stringResource(R.string.settings_csv_preview_currency_rows),
                        expenseLabel = stringResource(R.string.settings_csv_preview_type_expense),
                        incomeLabel = stringResource(R.string.settings_csv_preview_type_income),
                        transferLabel = stringResource(R.string.settings_csv_preview_type_transfer),
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.ink,
                )
            }
        }
        preview.sampleRows.forEach { row ->
            CsvPreviewRowToggle(
                row = row,
                accountLabel = accounts.firstOrNull { it.id == row.accountId }?.name ?: row.accountId,
                showAccount = accounts.size > 1,
                onIncludedChange = onRowIncludedChange,
                onEdit = { editingRow = row },
            )
        }
        preview.skippedRows.firstOrNull()?.let { skipped ->
            AtharText(
                text = stringResource(R.string.settings_csv_preview_first_skip, skipped.rowNumber, skipped.reason),
                style = theme.typography.caption,
                color = theme.colors.crimson,
            )
        }
    }
    editingRow?.let { row ->
        CsvPreviewRowEditDialog(
            row = row,
            accounts = accounts,
            defaultAccountId = selectedAccountId,
            onSave = { edit ->
                onRowEditChange(edit)
                editingRow = null
            },
            onDismiss = { editingRow = null },
        )
    }
}

@Composable
private fun CsvPreviewRowToggle(
    row: CsvImportPreviewRow,
    accountLabel: String,
    showAccount: Boolean,
    onIncludedChange: (Int, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val theme = AtharTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.divider)
            .clickable { onIncludedChange(row.rowNumber, !row.included) }
            .padding(theme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        Checkbox(
            checked = row.included,
            onCheckedChange = { checked -> onIncludedChange(row.rowNumber, checked) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
        ) {
            AtharText(
                text = row.previewLine(
                    expenseLabel = stringResource(R.string.settings_csv_preview_type_expense),
                    incomeLabel = stringResource(R.string.settings_csv_preview_type_income),
                    transferLabel = stringResource(R.string.settings_csv_preview_type_transfer),
                ),
                style = theme.typography.caption,
                color = if (row.included) theme.colors.ink else theme.colors.muted,
            )
            AtharText(
                text = if (row.included) {
                    stringResource(R.string.settings_csv_preview_row_included)
                } else {
                    stringResource(R.string.settings_csv_preview_row_excluded)
                },
                style = theme.typography.caption,
                color = if (row.included) theme.colors.olive else theme.colors.crimson,
            )
            if (showAccount) {
                AtharText(
                    text = stringResource(R.string.settings_csv_preview_row_account, accountLabel),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            if (row.edited) {
                AtharText(
                    text = stringResource(R.string.settings_csv_preview_row_edited),
                    style = theme.typography.caption,
                    color = theme.colors.ember,
                )
            }
            TextButton(onClick = onEdit) {
                AtharText(
                    text = stringResource(R.string.settings_csv_preview_row_edit),
                    color = theme.colors.ember,
                )
            }
        }
    }
}

@Composable
private fun CsvPreviewRowEditDialog(
    row: CsvImportPreviewRow,
    accounts: List<Account>,
    defaultAccountId: String,
    onSave: (CsvImportRowEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    var date by remember(row.rowNumber, row.date) { mutableStateOf(row.date) }
    var merchant by remember(row.rowNumber, row.merchant) { mutableStateOf(row.merchant) }
    var amount by remember(row.rowNumber, row.amount) { mutableStateOf(row.amount) }
    var currency by remember(row.rowNumber, row.currency) { mutableStateOf(row.currency) }
    var type by remember(row.rowNumber, row.type) { mutableStateOf(row.type) }
    var selectedAccountId by remember(row.rowNumber, row.accountId) { mutableStateOf(row.accountId) }
    var category by remember(row.rowNumber, row.category) { mutableStateOf(row.category.orEmpty()) }
    var notes by remember(row.rowNumber, row.notes) { mutableStateOf(row.notes.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            AtharText(
                text = stringResource(R.string.settings_csv_row_edit_title, row.rowNumber),
                style = theme.typography.headline,
                color = theme.colors.ink,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                AtharTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = stringResource(R.string.settings_csv_row_edit_date),
                    modifier = Modifier.fillMaxWidth(),
                )
                AtharTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = stringResource(R.string.settings_csv_row_edit_merchant),
                    modifier = Modifier.fillMaxWidth(),
                )
                AtharTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = stringResource(R.string.settings_csv_row_edit_amount),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Decimal,
                )
                AtharTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(3) },
                    label = stringResource(R.string.settings_csv_row_edit_currency),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Ascii,
                )
                if (accounts.size > 1) {
                    AtharText(
                        text = stringResource(R.string.settings_csv_row_edit_account),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                    accounts.forEach { account ->
                        CsvAccountChoice(
                            account = account,
                            selected = selectedAccountId == account.id,
                            onClick = { selectedAccountId = account.id },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    CsvTypeChoice(
                        label = stringResource(R.string.settings_csv_preview_type_expense),
                        selected = type == TxType.EXPENSE,
                        onClick = { type = TxType.EXPENSE },
                        modifier = Modifier.weight(1f),
                    )
                    CsvTypeChoice(
                        label = stringResource(R.string.settings_csv_preview_type_income),
                        selected = type == TxType.INCOME,
                        onClick = { type = TxType.INCOME },
                        modifier = Modifier.weight(1f),
                    )
                    CsvTypeChoice(
                        label = stringResource(R.string.settings_csv_preview_type_transfer),
                        selected = type == TxType.TRANSFER,
                        onClick = { type = TxType.TRANSFER },
                        modifier = Modifier.weight(1f),
                    )
                }
                AtharTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = stringResource(R.string.settings_csv_row_edit_category),
                    modifier = Modifier.fillMaxWidth(),
                )
                AtharTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = stringResource(R.string.settings_csv_row_edit_notes),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CsvImportRowEdit(
                            rowNumber = row.rowNumber,
                            accountId = selectedAccountId.takeIf { it != defaultAccountId },
                            date = date,
                            merchant = merchant,
                            amount = amount,
                            currency = currency,
                            type = type,
                            category = category,
                            notes = notes,
                        ),
                    )
                },
            ) {
                AtharText(text = stringResource(R.string.settings_csv_row_edit_save), color = theme.colors.ember)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                AtharText(text = stringResource(R.string.settings_action_cancel), color = theme.colors.muted)
            }
        },
        containerColor = theme.colors.parchment,
    )
}

@Composable
private fun CsvAccountChoice(
    account: Account,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (selected) theme.colors.olive else theme.colors.divider)
            .clickable(onClick = onClick)
            .padding(theme.spacing.s),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
            AtharText(
                text = account.name,
                style = theme.typography.body,
                color = if (selected) theme.colors.parchment else theme.colors.ink,
            )
            AtharText(
                text = account.currency,
                style = theme.typography.caption,
                color = if (selected) theme.colors.parchment else theme.colors.muted,
            )
        }
    }
}

@Composable
private fun CsvTypeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (selected) theme.colors.olive else theme.colors.divider)
            .clickable(onClick = onClick)
            .padding(theme.spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = label,
            style = theme.typography.caption,
            color = if (selected) theme.colors.parchment else theme.colors.ink,
        )
    }
}

private fun CsvImportPreview.columnSummary(
    dateLabel: String,
    merchantLabel: String,
    moneyLabel: String,
    currencyLabel: String,
    categoryLabel: String,
): String {
    val money = columns.amount ?: listOfNotNull(columns.debit, columns.credit).joinToString(" / ")
    return listOfNotNull(
        "$dateLabel=${columns.date}",
        "$merchantLabel=${columns.merchant}",
        money.takeIf { it.isNotBlank() }?.let { "$moneyLabel=$it" },
        columns.currency?.let { "$currencyLabel=$it" },
        columns.category?.let { "$categoryLabel=$it" },
    ).joinToString(" · ")
}

private fun CsvImportCurrencySummary.previewLine(
    rowLabel: String,
    expenseLabel: String,
    incomeLabel: String,
    transferLabel: String,
): String {
    val totals = listOfNotNull(
        expenseTotal.nonZero()?.let { "$expenseLabel ${it.toPlainString()}" },
        incomeTotal.nonZero()?.let { "$incomeLabel ${it.toPlainString()}" },
        transferTotal.nonZero()?.let { "$transferLabel ${it.toPlainString()}" },
    )
    return listOf("$currency · $rows $rowLabel", totals.joinToString(" · ").takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(" · ")
}

private fun BigDecimal.nonZero(): BigDecimal? =
    takeIf { compareTo(BigDecimal.ZERO) != 0 }

private fun CsvImportPreviewRow.previewLine(
    expenseLabel: String,
    incomeLabel: String,
    transferLabel: String,
): String =
    "#$rowNumber · $date · ${type.label(expenseLabel, incomeLabel, transferLabel)} · $merchant · $amount $currency" +
        category?.let { " · $it" }.orEmpty()

private fun TxType.label(expenseLabel: String, incomeLabel: String, transferLabel: String): String = when (this) {
    TxType.EXPENSE -> expenseLabel
    TxType.INCOME -> incomeLabel
    TxType.TRANSFER -> transferLabel
}

@Composable
private fun TaxExportCard(
    year: Int,
    status: TaxExportStatus,
    onYearChange: (Int) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_tax_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_tax_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(text = "-", onClick = { onYearChange(year - 1) }, modifier = Modifier.weight(0.7f))
                Column(
                    modifier = Modifier.weight(1.6f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AtharText(
                        text = stringResource(R.string.settings_tax_year_label),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                    AtharText(text = year.toString(), style = theme.typography.headline)
                }
                SecondaryButton(text = "+", onClick = { onYearChange(year + 1) }, modifier = Modifier.weight(0.7f))
            }

            when (val s = status) {
                TaxExportStatus.Idle -> Unit
                TaxExportStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is TaxExportStatus.Exported -> {
                    AtharText(
                        text = stringResource(
                            R.string.settings_tax_exported,
                            s.year,
                            s.transactions,
                            s.categoryTotals,
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    if (s.excludedReconciliations > 0) {
                        AtharText(
                            text = stringResource(
                                R.string.settings_tax_excluded_reconciliations,
                                s.excludedReconciliations,
                            ),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                    }
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is TaxExportStatus.Failed -> {
                    AtharText(text = s.reason, style = theme.typography.caption, color = theme.colors.crimson)
                    TextButton(onClick = onClear) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }

            val isWorking = status is TaxExportStatus.Working
            PrimaryButton(
                text = if (isWorking) {
                    stringResource(R.string.settings_status_in_progress)
                } else {
                    stringResource(R.string.settings_tax_action_export)
                },
                onClick = { if (!isWorking) onExport() },
            )
        }
    }
}

@Composable
private fun ActivityLogEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_activity_log_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_activity_log_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun SmsAuditEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_sms_audit_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_sms_audit_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

/**
 * Update-availability nudge (beta.22). Athar has no INTERNET permission, so it
 * cannot poll GitHub itself. Instead we delegate to Obtainium — the sideload
 * manager users already trust — via a deep link that pre-fills Athar's repo
 * URL in Obtainium's "Add app" flow. Obtainium then watches releases on the
 * user's behalf and notifies them when a new build ships.
 *
 * Fallback chain: if Obtainium isn't installed (deep link has no handler),
 * we catch the ActivityNotFound and open the Obtainium install page instead.
 */
@Composable
private fun UpdatesCard() {
    val theme = AtharTheme
    val context = LocalContext.current
    val obtainiumMissing = stringResource(R.string.settings_updates_obtainium_missing)
    AtharCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(
                text = stringResource(R.string.settings_updates_title),
                style = theme.typography.headline,
            )
            AtharText(
                text = stringResource(R.string.settings_updates_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            PrimaryButton(
                text = stringResource(R.string.settings_updates_action_obtainium),
                onClick = {
                    val ok = launchUrl(context, OBTAINIUM_DEEP_LINK)
                    if (!ok) {
                        android.widget.Toast
                            .makeText(context, obtainiumMissing, android.widget.Toast.LENGTH_LONG)
                            .show()
                        launchUrl(context, OBTAINIUM_INSTALL_URL)
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SecondaryButton(
                        text = stringResource(R.string.settings_updates_action_install_obtainium),
                        onClick = { launchUrl(context, OBTAINIUM_INSTALL_URL) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    SecondaryButton(
                        text = stringResource(R.string.settings_updates_action_releases),
                        onClick = { launchUrl(context, ATHAR_RELEASES_URL) },
                    )
                }
            }
        }
    }
}

/** Returns true if the URI was handed off; false if no activity could handle it. */
private fun launchUrl(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrElse { false }

private const val ATHAR_RELEASES_URL = "https://github.com/wa1939/athar/releases"
private const val OBTAINIUM_INSTALL_URL = "https://obtainium.imranr.dev"

/**
 * Obtainium accepts an `obtainium://app/{percent-encoded-json}` deep link that
 * pre-fills its "Add app" form. The JSON below points Obtainium at Athar's
 * GitHub releases (source: GitHub) so it can poll the tag list and notify on
 * new releases. preferredApkIndex 0 picks `app-personalFullSms-release.apk`.
 */
private val OBTAINIUM_DEEP_LINK: String by lazy {
    val json = """{"id":"com.athar.personal","url":"https://github.com/wa1939/athar","author":"wa1939","name":"Athar","preferredApkIndex":0,"additionalSettings":"{}"}"""
    "obtainium://app/" + java.net.URLEncoder.encode(json, "UTF-8")
}

@Composable
private fun AboutCard() {
    val theme = AtharTheme
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    AtharCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(text = stringResource(R.string.settings_about_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_about_version, versionName ?: "—"),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(R.string.settings_about_author),
                style = theme.typography.body,
                color = theme.colors.ink,
            )
            AtharText(
                text = stringResource(R.string.settings_about_inspiration),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(R.string.settings_about_local),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun DisplayCurrencyCard(
    currentCode: String,
    onSelect: (String) -> Unit,
) {
    val theme = AtharTheme
    var expanded by remember { mutableStateOf(false) }
    val current = remember(currentCode) { CurrencyCatalog.entryOf(currentCode) }
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_currency_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_currency_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.divider)
                    .clickable { expanded = !expanded }
                    .padding(theme.spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AtharText(
                    text = current?.labelAr ?: currentCode,
                    style = theme.typography.headline,
                    color = theme.colors.ink,
                )
                AtharText(
                    text = "${current?.symbol ?: currentCode} · $currentCode",
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
                    CurrencyCatalog.supported.forEach { entry ->
                        val isSelected = entry.code == currentCode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(theme.spacing.s))
                                .background(if (isSelected) theme.colors.ember else theme.colors.parchment)
                                .clickable {
                                    onSelect(entry.code)
                                    expanded = false
                                }
                                .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AtharText(
                                text = entry.labelAr,
                                style = theme.typography.body,
                                color = if (isSelected) theme.colors.parchment else theme.colors.ink,
                            )
                            AtharText(
                                text = "${entry.symbol} · ${entry.code}",
                                style = theme.typography.caption,
                                color = if (isSelected) theme.colors.parchment else theme.colors.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnAccountsCard(accounts: List<String>, onSave: (String) -> Unit) {
    val theme = AtharTheme
    var draft by remember(accounts) { mutableStateOf(accounts.joinToString(", ")) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    val savedCount = accounts.size
    LaunchedEffect(savedAt) {
        if (savedAt != null) {
            kotlinx.coroutines.delay(3000)
            savedAt = null
        }
    }
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_own_accounts_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_own_accounts_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            AtharTextField(
                value = draft,
                onValueChange = { draft = it },
                label = stringResource(R.string.settings_own_accounts_field_label),
                modifier = Modifier.fillMaxWidth(),
            )
            if (savedAt != null) {
                AtharText(
                    text = stringResource(R.string.settings_own_accounts_saved, savedCount),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
            } else if (accounts.isNotEmpty()) {
                AtharText(
                    text = stringResource(R.string.settings_own_accounts_current, savedCount),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.ember)
                    .clickable {
                        onSave(draft)
                        savedAt = System.currentTimeMillis()
                    }
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(text = stringResource(R.string.settings_own_accounts_save), style = theme.typography.headline, color = theme.colors.parchment)
            }
        }
    }
}

@Composable
private fun RescanAndCleanCard(
    status: RescanStatus,
    pendingCount: Int,
    onRequestRescan: () -> Unit,
    onClearStatus: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_rescan_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_rescan_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(R.string.settings_rescan_pending_count, pendingCount),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            val statusText = when (status) {
                RescanStatus.Idle -> null
                RescanStatus.Working -> stringResource(R.string.settings_rescan_working)
                is RescanStatus.Done -> stringResource(R.string.settings_rescan_done, status.clearedPending)
            }
            statusText?.let {
                AtharText(text = it, style = theme.typography.caption, color = theme.colors.muted)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.ember)
                    .clickable(enabled = status != RescanStatus.Working) {
                        if (status is RescanStatus.Done) onClearStatus() else onRequestRescan()
                    }
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                val buttonText = when (status) {
                    is RescanStatus.Done -> stringResource(R.string.settings_action_done)
                    else -> stringResource(R.string.settings_rescan_action)
                }
                AtharText(text = buttonText, style = theme.typography.headline, color = theme.colors.parchment)
            }
        }
    }
}

@Composable
private fun RescanConfirmDialog(
    pendingCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            AtharText(
                text = stringResource(R.string.settings_rescan_confirm_title),
                style = theme.typography.headline,
            )
        },
        text = {
            AtharText(
                text = stringResource(R.string.settings_rescan_confirm_body, pendingCount),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                AtharText(
                    text = stringResource(R.string.settings_rescan_confirm_action),
                    color = theme.colors.ember,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                AtharText(text = stringResource(R.string.settings_rescan_confirm_cancel), color = theme.colors.muted)
            }
        },
        containerColor = theme.colors.parchment,
    )
}

@Composable
private fun RecoverDismissedCard(
    status: RecoverStatus,
    onRecover: () -> Unit,
    onClearStatus: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_recover_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_recover_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            val statusText = when (status) {
                RecoverStatus.Idle -> null
                RecoverStatus.Working -> stringResource(R.string.settings_rescan_working)
                is RecoverStatus.Done -> stringResource(R.string.settings_recover_done, status.recovered)
            }
            statusText?.let {
                AtharText(text = it, style = theme.typography.caption, color = theme.colors.muted)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.dust)
                    .clickable(enabled = status != RecoverStatus.Working) {
                        if (status is RecoverStatus.Done) onClearStatus() else onRecover()
                    }
                    .padding(theme.spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                val buttonText = when (status) {
                    is RecoverStatus.Done -> stringResource(R.string.settings_action_done)
                    else -> stringResource(R.string.settings_recover_action)
                }
                AtharText(text = buttonText, style = theme.typography.headline, color = theme.colors.parchment)
            }
        }
    }
}

@Composable
private fun LanguageCard(
    currentTag: String,
    onSelect: (String) -> Unit,
) {
    val theme = AtharTheme
    val options = listOf(
        Triple("", stringResource(R.string.settings_language_option_system_ar), stringResource(R.string.settings_language_option_system_en)),
        Triple("ar", stringResource(R.string.settings_language_option_arabic_ar), stringResource(R.string.settings_language_option_arabic_en)),
        Triple("en", stringResource(R.string.settings_language_option_english_ar), stringResource(R.string.settings_language_option_english_en)),
    )
    AtharCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_language_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_language_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            options.forEach { (tag, labelAr, labelEn) ->
                val isSelected = tag == currentTag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clip(RoundedCornerShape(theme.spacing.s))
                        .background(if (isSelected) theme.colors.ember else theme.colors.parchment)
                        .clickable { onSelect(tag) }
                        .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AtharText(
                        text = labelAr,
                        style = theme.typography.body,
                        color = if (isSelected) theme.colors.parchment else theme.colors.ink,
                    )
                    AtharText(
                        text = labelEn,
                        style = theme.typography.caption,
                        color = if (isSelected) theme.colors.parchment else theme.colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_history_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_history_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun AccountsEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_accounts_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_accounts_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun IncomeSourcesEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_income_sources_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_income_sources_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun SmsImportEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_sms_import_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_sms_import_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun RecurringRulesEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_recurring_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_recurring_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun UserTemplatesEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_user_templates_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_user_templates_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun CategoriesEntryCard(onOpen: () -> Unit) {
    val theme = AtharTheme
    AtharCard(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(text = stringResource(R.string.settings_entry_categories_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_entry_categories_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
        }
    }
}

@Composable
private fun BackfillCard(
    progress: BackfillProgress,
    onRescan: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(text = stringResource(R.string.settings_backfill_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_backfill_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            when (val p = progress) {
                BackfillProgress.Idle -> Unit
                is BackfillProgress.Running -> AtharText(
                    text = stringResource(R.string.settings_backfill_running, p.scanned),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                is BackfillProgress.Done -> AtharText(
                    text = stringResource(R.string.settings_backfill_done, p.scanned, p.sentToPipeline),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
                is BackfillProgress.Failed -> AtharText(
                    text = stringResource(R.string.settings_backfill_failed, p.reason),
                    style = theme.typography.caption,
                    color = theme.colors.crimson,
                )
            }
            val isRunning = progress is BackfillProgress.Running
            PrimaryButton(
                text = if (isRunning) {
                    stringResource(R.string.settings_backfill_action_running)
                } else {
                    stringResource(R.string.settings_backfill_action_start)
                },
                onClick = { if (!isRunning) onRescan() },
            )
        }
    }
}

@Composable
private fun BackupCard(
    status: BackupStatus,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearStatus: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.s),
        ) {
            AtharText(text = stringResource(R.string.settings_backup_title), style = theme.typography.headline)
            AtharText(
                text = stringResource(R.string.settings_backup_body),
                style = theme.typography.body,
                color = theme.colors.muted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                PrimaryButton(text = stringResource(R.string.settings_backup_export), onClick = onExport, modifier = Modifier.weight(1f))
                SecondaryButton(text = stringResource(R.string.settings_backup_import), onClick = onImport, modifier = Modifier.weight(1f))
            }
            when (status) {
                BackupStatus.Idle -> Unit
                BackupStatus.Working -> AtharText(
                    text = stringResource(R.string.settings_status_working),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                BackupStatus.ExportSuccess -> {
                    AtharText(
                        text = stringResource(R.string.settings_backup_export_success),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClearStatus) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                BackupStatus.ImportSuccess -> {
                    AtharText(
                        text = stringResource(R.string.settings_backup_import_success),
                        style = theme.typography.caption,
                        color = theme.colors.olive,
                    )
                    TextButton(onClick = onClearStatus) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is BackupStatus.ExportFailure -> {
                    AtharText(
                        text = stringResource(R.string.settings_backup_export_failure),
                        style = theme.typography.caption,
                        color = theme.colors.dust,
                    )
                    TextButton(onClick = onClearStatus) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
                is BackupStatus.ImportFailure -> {
                    AtharText(
                        text = stringResource(R.string.settings_backup_import_failure),
                        style = theme.typography.caption,
                        color = theme.colors.dust,
                    )
                    TextButton(onClick = onClearStatus) {
                        AtharText(stringResource(R.string.settings_action_ok), color = theme.colors.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun PassphrasePrompt(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = AtharTheme
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { AtharText(text = title, style = theme.typography.headline) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(text = description, style = theme.typography.body, color = theme.colors.muted)
                AtharTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.settings_backup_passphrase_label),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Password,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (passphrase.isNotBlank()) onConfirm(passphrase) },
                enabled = passphrase.isNotBlank(),
            ) { AtharText(text = confirmLabel, color = theme.colors.ember) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                AtharText(text = stringResource(R.string.settings_action_cancel), color = theme.colors.muted)
            }
        },
        containerColor = theme.colors.parchment,
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.ember)
            .clickable(onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = text, style = theme.typography.headline, color = theme.colors.parchment)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.divider)
            .clickable(onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = text, style = theme.typography.headline, color = theme.colors.ink)
    }
}

private val SMS_PERMISSIONS = arrayOf(
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_SMS,
)

private const val DEFAULT_BACKUP_NAME = "athar-backup.athar"

private fun hasSmsPermissions(context: Context): Boolean = SMS_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
