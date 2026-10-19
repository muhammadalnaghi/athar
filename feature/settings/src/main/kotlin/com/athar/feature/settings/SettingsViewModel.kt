package com.athar.feature.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.MerchantCatalogState
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.BackfillProgress
import com.athar.core.domain.repo.BackupRepository
import com.athar.core.domain.repo.BudgetTargetImportPreview
import com.athar.core.domain.repo.BudgetTargetImportPreviewResult
import com.athar.core.domain.repo.BudgetTargetImportResult
import com.athar.core.domain.repo.BudgetTargetImportTrigger
import com.athar.core.domain.repo.CommunityRulesShareResult
import com.athar.core.domain.repo.CommunityRulesShareTrigger
import com.athar.core.domain.repo.CsvExportResult
import com.athar.core.domain.repo.CsvExportTrigger
import com.athar.core.domain.repo.CsvImportColumnMapping
import com.athar.core.domain.repo.CsvImportColumnRole
import com.athar.core.domain.repo.CsvImportPreview
import com.athar.core.domain.repo.CsvImportPreviewResult
import com.athar.core.domain.repo.CsvImportResult
import com.athar.core.domain.repo.CsvImportRowDecision
import com.athar.core.domain.repo.CsvImportRowEdit
import com.athar.core.domain.repo.CsvImportTrigger
import com.athar.core.domain.repo.InvestmentImportPreview
import com.athar.core.domain.repo.InvestmentImportPreviewResult
import com.athar.core.domain.repo.InvestmentImportResult
import com.athar.core.domain.repo.InvestmentImportTrigger
import com.athar.core.domain.repo.MerchantBulkExportResult
import com.athar.core.domain.repo.MerchantBulkExportMode
import com.athar.core.domain.repo.MerchantBulkExportTrigger
import com.athar.core.domain.repo.MerchantBulkImportCategoryImpact
import com.athar.core.domain.repo.MerchantBulkImportResult
import com.athar.core.domain.repo.MerchantBulkImportSkipSummary
import com.athar.core.domain.repo.MerchantBulkImportTrigger
import com.athar.core.domain.repo.MerchantCatalogRepository
import com.athar.core.domain.repo.SmsBackfillTrigger
import com.athar.core.domain.repo.SupportDiagnosticsExportResult
import com.athar.core.domain.repo.SupportDiagnosticsExportTrigger
import com.athar.core.domain.repo.TaxExportResult
import com.athar.core.domain.repo.TaxExportTrigger
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.core.domain.repo.WishlistImportPreview
import com.athar.core.domain.repo.WishlistImportPreviewResult
import com.athar.core.domain.repo.WishlistImportResult
import com.athar.core.domain.repo.WishlistImportTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

sealed interface BackupStatus {
    data object Idle : BackupStatus
    data object Working : BackupStatus
    data object ExportSuccess : BackupStatus
    data object ImportSuccess : BackupStatus
    data class ExportFailure(val detail: String?) : BackupStatus
    data class ImportFailure(val detail: String?) : BackupStatus
}

sealed interface CsvStatus {
    data object Idle : CsvStatus
    data object Working : CsvStatus
    data class MappingRequired(
        val columns: List<String>,
        val reason: String,
        val mapping: CsvImportColumnMapping,
    ) : CsvStatus
    data class Preview(val preview: CsvImportPreview) : CsvStatus
    data class Done(val imported: Int, val skipped: Int) : CsvStatus
    data class Exported(val count: Int) : CsvStatus
    data class Failed(val reason: String) : CsvStatus
}

sealed interface BudgetTargetStatus {
    data object Idle : BudgetTargetStatus
    data object Working : BudgetTargetStatus
    data class Preview(val preview: BudgetTargetImportPreview) : BudgetTargetStatus
    data class Done(val applied: Int, val changed: Int, val skipped: Int) : BudgetTargetStatus
    data class Failed(val reason: String) : BudgetTargetStatus
}

sealed interface WishlistImportStatus {
    data object Idle : WishlistImportStatus
    data object Working : WishlistImportStatus
    data class Preview(val preview: WishlistImportPreview) : WishlistImportStatus
    data class Done(val imported: Int, val newItems: Int, val updatedItems: Int, val skipped: Int) : WishlistImportStatus
    data class Failed(val reason: String) : WishlistImportStatus
}

sealed interface InvestmentImportStatus {
    data object Idle : InvestmentImportStatus
    data object Working : InvestmentImportStatus
    data class Preview(val preview: InvestmentImportPreview) : InvestmentImportStatus
    data class Done(
        val importedContributions: Int,
        val replacedContributions: Int,
        val skipped: Int,
        val existingPool: Boolean,
    ) : InvestmentImportStatus
    data class Failed(val reason: String) : InvestmentImportStatus
}

sealed interface RescanStatus {
    data object Idle : RescanStatus
    data object Working : RescanStatus
    data class Done(val clearedPending: Int) : RescanStatus
}

sealed interface RecoverStatus {
    data object Idle : RecoverStatus
    data object Working : RecoverStatus
    data class Done(val recovered: Int) : RecoverStatus
}

/**
 * Status of the bulk-categorize-via-AI flow (Issue #5a). Export writes a CSV of every
 * uncategorized transaction; the user feeds it to ChatGPT/Claude with the AI triage
 * prompt and imports the filled CSV. Each row's `category_id` becomes the transaction's
 * new category AND seeds a `CategoryRule` so future ingests benefit too.
 */
sealed interface BulkCategorizeStatus {
    data object Idle : BulkCategorizeStatus
    data object Working : BulkCategorizeStatus
    data class Exported(val rows: Int) : BulkCategorizeStatus
    data class Preview(
        val updated: Int,
        val rulesAdded: Int,
        val skipped: Int,
        val skipSummary: MerchantBulkImportSkipSummary,
        val categoryImpact: List<MerchantBulkImportCategoryImpact> = emptyList(),
    ) : BulkCategorizeStatus
    data class Imported(
        val updated: Int,
        val rulesAdded: Int,
        val skipped: Int,
        val skipSummary: MerchantBulkImportSkipSummary,
    ) : BulkCategorizeStatus
    data class Failed(val reason: String) : BulkCategorizeStatus
}

/**
 * Status of the community-rule sharing flow (beta.20). User exports their
 * `learnedFromUser=true` rules to a JSON file, then submits it as a GitHub issue.
 * Pure local-only — no upload, no backend. See `docs/COMMUNITY_RULES_WORKFLOW.md`.
 */
sealed interface CommunityShareStatus {
    data object Idle : CommunityShareStatus
    data object Working : CommunityShareStatus
    data class Exported(val rows: Int, val fileUri: Uri) : CommunityShareStatus
    data object Empty : CommunityShareStatus
    data class Failed(val reason: String) : CommunityShareStatus
}

sealed interface TaxExportStatus {
    data object Idle : TaxExportStatus
    data object Working : TaxExportStatus
    data class Exported(
        val year: Int,
        val transactions: Int,
        val categoryTotals: Int,
        val excludedReconciliations: Int,
    ) : TaxExportStatus
    data class Failed(val reason: String) : TaxExportStatus
}

sealed interface SupportDiagnosticsStatus {
    data object Idle : SupportDiagnosticsStatus
    data object Working : SupportDiagnosticsStatus
    data class Exported(
        val auditRows: Int,
        val parsed: Int,
        val failed: Int,
        val ignored: Int,
    ) : SupportDiagnosticsStatus
    data class Failed(val reason: String) : SupportDiagnosticsStatus
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backup: BackupRepository,
    private val backfillTrigger: SmsBackfillTrigger,
    private val csvImporter: CsvImportTrigger,
    private val budgetTargetsImporter: BudgetTargetImportTrigger,
    private val wishlistImporter: WishlistImportTrigger,
    private val investmentImporter: InvestmentImportTrigger,
    private val csvExporter: CsvExportTrigger,
    private val bulkExporter: MerchantBulkExportTrigger,
    private val bulkImporter: MerchantBulkImportTrigger,
    private val communityShare: CommunityRulesShareTrigger,
    private val supportDiagnostics: SupportDiagnosticsExportTrigger,
    private val taxExport: TaxExportTrigger,
    private val prefs: UserPreferencesRepository,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    private val merchantCatalogRepository: MerchantCatalogRepository,
) : ViewModel() {

    private val _rescan = MutableStateFlow<RescanStatus>(RescanStatus.Idle)
    val rescanStatus: StateFlow<RescanStatus> = _rescan.asStateFlow()

    private val _recover = MutableStateFlow<RecoverStatus>(RecoverStatus.Idle)
    val recoverStatus: StateFlow<RecoverStatus> = _recover.asStateFlow()

    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val _csv = MutableStateFlow<CsvStatus>(CsvStatus.Idle)
    val csvStatus: StateFlow<CsvStatus> = _csv.asStateFlow()
    private var pendingCsvImportBytes: ByteArray? = null
    private var pendingCsvImportMapping: CsvImportColumnMapping? = null
    private var pendingCsvRowDecisions: Map<Int, CsvImportRowDecision> = emptyMap()
    private var pendingCsvRowEdits: Map<Int, CsvImportRowEdit> = emptyMap()
    private val _selectedStatementImportAccountId = MutableStateFlow(MANUAL_ACCOUNT_ID)
    val selectedStatementImportAccountId: StateFlow<String> = _selectedStatementImportAccountId.asStateFlow()

    private val _budgetTargets = MutableStateFlow<BudgetTargetStatus>(BudgetTargetStatus.Idle)
    val budgetTargetStatus: StateFlow<BudgetTargetStatus> = _budgetTargets.asStateFlow()
    private var pendingBudgetTargetImportBytes: ByteArray? = null

    private val _wishlistImport = MutableStateFlow<WishlistImportStatus>(WishlistImportStatus.Idle)
    val wishlistImportStatus: StateFlow<WishlistImportStatus> = _wishlistImport.asStateFlow()
    private var pendingWishlistImportBytes: ByteArray? = null

    private val _investmentImport = MutableStateFlow<InvestmentImportStatus>(InvestmentImportStatus.Idle)
    val investmentImportStatus: StateFlow<InvestmentImportStatus> = _investmentImport.asStateFlow()
    private var pendingInvestmentImportBytes: ByteArray? = null

    val statementImportAccounts: StateFlow<List<Account>> = accounts.observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _bulkCategorize = MutableStateFlow<BulkCategorizeStatus>(BulkCategorizeStatus.Idle)
    val bulkCategorizeStatus: StateFlow<BulkCategorizeStatus> = _bulkCategorize.asStateFlow()
    private var pendingBulkCategorizeImportBytes: ByteArray? = null

    private val _communityShare = MutableStateFlow<CommunityShareStatus>(CommunityShareStatus.Idle)
    val communityShareStatus: StateFlow<CommunityShareStatus> = _communityShare.asStateFlow()

    private val _supportDiagnostics = MutableStateFlow<SupportDiagnosticsStatus>(SupportDiagnosticsStatus.Idle)
    val supportDiagnosticsStatus: StateFlow<SupportDiagnosticsStatus> = _supportDiagnostics.asStateFlow()

    private val _taxExport = MutableStateFlow<TaxExportStatus>(TaxExportStatus.Idle)
    val taxExportStatus: StateFlow<TaxExportStatus> = _taxExport.asStateFlow()

    val backfillProgress: StateFlow<BackfillProgress> = backfillTrigger.progress

    val pendingCount: StateFlow<Int> = transactions.observePending()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val hijriEnabled: StateFlow<Boolean> = prefs.hijriEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val ownAccountNumbers: StateFlow<List<String>> = prefs.ownAccountNumbers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val displayCurrency: StateFlow<String> = prefs.displayCurrency()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SAR")

    val appLocale: StateFlow<String> = prefs.appLocale()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Shared merchant catalog (ENH-009): public, nonfinancial state only. */
    val merchantCatalog: StateFlow<MerchantCatalogState> = merchantCatalogRepository.state

    init {
        viewModelScope.launch {
            statementImportAccounts.collectLatest { rows ->
                val selected = _selectedStatementImportAccountId.value
                if (rows.isNotEmpty() && rows.none { it.id == selected }) {
                    _selectedStatementImportAccountId.value = rows.first().id
                }
            }
        }
    }

    fun setHijriEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setHijriEnabled(enabled) }
    }

    fun setMerchantCatalogUpdates(enabled: Boolean) {
        viewModelScope.launch {
            try {
                merchantCatalogRepository.setUpdatesEnabled(enabled)
                if (enabled) {
                    // A failed consent change must never trigger a forced download.
                    merchantCatalogRepository.refresh(force = true)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Repository state retains the failure and any unsaved-disable retry.
            }
        }
    }

    fun refreshMerchantCatalog() {
        viewModelScope.launch { merchantCatalogRepository.refresh(force = true) }
    }

    fun resetMerchantCatalog() {
        viewModelScope.launch { merchantCatalogRepository.resetToBundled() }
    }

    fun setOwnAccountNumbers(csv: String) {
        viewModelScope.launch {
            val list = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            prefs.setOwnAccountNumbers(list)
        }
    }

    fun setDisplayCurrency(code: String) {
        viewModelScope.launch { prefs.setDisplayCurrency(code) }
    }

    fun setAppLocale(tag: String) {
        viewModelScope.launch { prefs.setAppLocale(tag) }
    }

    fun setStatementImportAccount(accountId: String) {
        _selectedStatementImportAccountId.value = accountId
        val bytes = pendingCsvImportBytes
        if (bytes != null && _csv.value is CsvStatus.Preview) {
            previewCsvImportBytes(bytes, pendingCsvImportMapping)
        }
    }

    fun export(resolver: ContentResolver, uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _status.value = BackupStatus.Working
            runCatching {
                val output = resolver.openOutputStream(uri)
                    ?: error("Couldn't open output stream for $uri")
                backup.export(output, passphrase.toCharArray())
            }
                .onSuccess { _status.value = BackupStatus.ExportSuccess }
                .onFailure { _status.value = BackupStatus.ExportFailure(it.message) }
        }
    }

    fun import(resolver: ContentResolver, uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _status.value = BackupStatus.Working
            runCatching {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Couldn't open input stream for $uri")
                backup.import(bytes, passphrase.toCharArray())
            }
                .onSuccess { _status.value = BackupStatus.ImportSuccess }
                .onFailure { _status.value = BackupStatus.ImportFailure(it.message) }
        }
    }

    fun previewCsvImport(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _csv.value = CsvStatus.Working
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                pendingCsvImportBytes = null
                pendingCsvImportMapping = null
                pendingCsvRowDecisions = emptyMap()
                pendingCsvRowEdits = emptyMap()
                _csv.value = CsvStatus.Failed("Couldn't open import file.")
                return@launch
            }
            pendingCsvImportMapping = null
            pendingCsvRowDecisions = emptyMap()
            pendingCsvRowEdits = emptyMap()
            previewCsvImportBytes(bytes, mapping = null)
        }
    }

    internal fun previewCsvImportBytes(
        bytes: ByteArray,
        mapping: CsvImportColumnMapping? = pendingCsvImportMapping,
    ) {
        viewModelScope.launch {
            _csv.value = CsvStatus.Working
            _csv.value = when (
                val result = csvImporter.preview(
                    input = bytes.inputStream(),
                    accountId = _selectedStatementImportAccountId.value,
                    mapping = mapping,
                    rowDecisions = pendingCsvRowDecisions.values.toList(),
                    rowEdits = pendingCsvRowEdits.values.toList(),
                )
            ) {
                is CsvImportPreviewResult.Done -> {
                    pendingCsvImportBytes = bytes
                    pendingCsvImportMapping = mapping
                    CsvStatus.Preview(result.preview)
                }
                is CsvImportPreviewResult.MappingRequired -> {
                    val nextMapping = mapping ?: CsvImportColumnMapping()
                    pendingCsvImportBytes = bytes
                    pendingCsvImportMapping = nextMapping
                    CsvStatus.MappingRequired(
                        columns = result.columns,
                        reason = result.reason,
                        mapping = nextMapping,
                    )
                }
                is CsvImportPreviewResult.Failed -> {
                    pendingCsvImportBytes = null
                    pendingCsvImportMapping = null
                    pendingCsvRowDecisions = emptyMap()
                    pendingCsvRowEdits = emptyMap()
                    CsvStatus.Failed(result.reason)
                }
            }
        }
    }

    fun setCsvColumnMapping(role: CsvImportColumnRole, column: String?) {
        val current = pendingCsvImportMapping ?: CsvImportColumnMapping()
        val next = current.with(role, column?.takeIf { it.isNotBlank() })
        pendingCsvImportMapping = next
        val status = _csv.value
        if (status is CsvStatus.MappingRequired) {
            _csv.value = status.copy(mapping = next)
        }
    }

    fun previewCsvImportWithMapping() {
        val bytes = pendingCsvImportBytes
        if (bytes == null) {
            _csv.value = CsvStatus.Failed("No import file is ready to map.")
            return
        }
        previewCsvImportBytes(bytes, pendingCsvImportMapping ?: CsvImportColumnMapping())
    }

    fun setCsvImportRowIncluded(rowNumber: Int, included: Boolean) {
        pendingCsvRowDecisions = if (included) {
            pendingCsvRowDecisions - rowNumber
        } else {
            pendingCsvRowDecisions + (rowNumber to CsvImportRowDecision(rowNumber, shouldImport = false))
        }
        val bytes = pendingCsvImportBytes
        if (bytes == null) {
            _csv.value = CsvStatus.Failed("No import preview is ready to edit.")
            return
        }
        previewCsvImportBytes(bytes, pendingCsvImportMapping)
    }

    fun setCsvImportRowEdit(edit: CsvImportRowEdit) {
        pendingCsvRowEdits = pendingCsvRowEdits + (edit.rowNumber to edit)
        val bytes = pendingCsvImportBytes
        if (bytes == null) {
            _csv.value = CsvStatus.Failed("No import preview is ready to edit.")
            return
        }
        previewCsvImportBytes(bytes, pendingCsvImportMapping)
    }

    fun confirmCsvImport() {
        viewModelScope.launch {
            val bytes = pendingCsvImportBytes
            if (bytes == null) {
                _csv.value = CsvStatus.Failed("No import preview is ready to import.")
                return@launch
            }
            _csv.value = CsvStatus.Working
            _csv.value = when (
                val result = csvImporter.import(
                    input = bytes.inputStream(),
                    accountId = _selectedStatementImportAccountId.value,
                    mapping = pendingCsvImportMapping,
                    rowDecisions = pendingCsvRowDecisions.values.toList(),
                    rowEdits = pendingCsvRowEdits.values.toList(),
                )
            ) {
                is CsvImportResult.Done -> CsvStatus.Done(result.imported, result.skipped)
                is CsvImportResult.Failed -> CsvStatus.Failed(result.reason)
            }
            pendingCsvImportBytes = null
            pendingCsvImportMapping = null
            pendingCsvRowDecisions = emptyMap()
            pendingCsvRowEdits = emptyMap()
        }
    }

    fun previewBudgetTargetsImport(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _budgetTargets.value = BudgetTargetStatus.Working
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                pendingBudgetTargetImportBytes = null
                _budgetTargets.value = BudgetTargetStatus.Failed("Couldn't open workbook.")
                return@launch
            }
            previewBudgetTargetsImportBytes(bytes)
        }
    }

    internal fun previewBudgetTargetsImportBytes(bytes: ByteArray) {
        viewModelScope.launch {
            _budgetTargets.value = BudgetTargetStatus.Working
            _budgetTargets.value = when (val result = budgetTargetsImporter.preview(bytes.inputStream())) {
                is BudgetTargetImportPreviewResult.Done -> {
                    pendingBudgetTargetImportBytes = bytes
                    BudgetTargetStatus.Preview(result.preview)
                }
                is BudgetTargetImportPreviewResult.Failed -> {
                    pendingBudgetTargetImportBytes = null
                    BudgetTargetStatus.Failed(result.reason)
                }
            }
        }
    }

    fun confirmBudgetTargetsImport() {
        viewModelScope.launch {
            val bytes = pendingBudgetTargetImportBytes
            if (bytes == null) {
                _budgetTargets.value = BudgetTargetStatus.Failed("No budget target preview is ready to import.")
                return@launch
            }
            _budgetTargets.value = BudgetTargetStatus.Working
            _budgetTargets.value = when (val result = budgetTargetsImporter.import(bytes.inputStream())) {
                is BudgetTargetImportResult.Done -> {
                    pendingBudgetTargetImportBytes = null
                    BudgetTargetStatus.Done(
                        applied = result.applied,
                        changed = result.changed,
                        skipped = result.skipped,
                    )
                }
                is BudgetTargetImportResult.Failed -> BudgetTargetStatus.Failed(result.reason)
            }
        }
    }

    fun cancelBudgetTargetsImportPreview() {
        pendingBudgetTargetImportBytes = null
        _budgetTargets.value = BudgetTargetStatus.Idle
    }

    fun previewWishlistImport(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _wishlistImport.value = WishlistImportStatus.Working
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                pendingWishlistImportBytes = null
                _wishlistImport.value = WishlistImportStatus.Failed("Couldn't open workbook.")
                return@launch
            }
            previewWishlistImportBytes(bytes)
        }
    }

    internal fun previewWishlistImportBytes(bytes: ByteArray) {
        viewModelScope.launch {
            _wishlistImport.value = WishlistImportStatus.Working
            _wishlistImport.value = when (val result = wishlistImporter.preview(bytes.inputStream())) {
                is WishlistImportPreviewResult.Done -> {
                    pendingWishlistImportBytes = bytes
                    WishlistImportStatus.Preview(result.preview)
                }
                is WishlistImportPreviewResult.Failed -> {
                    pendingWishlistImportBytes = null
                    WishlistImportStatus.Failed(result.reason)
                }
            }
        }
    }

    fun confirmWishlistImport() {
        viewModelScope.launch {
            val bytes = pendingWishlistImportBytes
            if (bytes == null) {
                _wishlistImport.value = WishlistImportStatus.Failed("No wishlist preview is ready to import.")
                return@launch
            }
            _wishlistImport.value = WishlistImportStatus.Working
            _wishlistImport.value = when (val result = wishlistImporter.import(bytes.inputStream())) {
                is WishlistImportResult.Done -> {
                    pendingWishlistImportBytes = null
                    WishlistImportStatus.Done(
                        imported = result.imported,
                        newItems = result.newItems,
                        updatedItems = result.updatedItems,
                        skipped = result.skipped,
                    )
                }
                is WishlistImportResult.Failed -> WishlistImportStatus.Failed(result.reason)
            }
        }
    }

    fun cancelWishlistImportPreview() {
        pendingWishlistImportBytes = null
        _wishlistImport.value = WishlistImportStatus.Idle
    }

    fun previewInvestmentImport(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _investmentImport.value = InvestmentImportStatus.Working
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                pendingInvestmentImportBytes = null
                _investmentImport.value = InvestmentImportStatus.Failed("Couldn't open workbook.")
                return@launch
            }
            previewInvestmentImportBytes(bytes)
        }
    }

    internal fun previewInvestmentImportBytes(bytes: ByteArray) {
        viewModelScope.launch {
            _investmentImport.value = InvestmentImportStatus.Working
            _investmentImport.value = when (val result = investmentImporter.preview(bytes.inputStream())) {
                is InvestmentImportPreviewResult.Done -> {
                    pendingInvestmentImportBytes = bytes
                    InvestmentImportStatus.Preview(result.preview)
                }
                is InvestmentImportPreviewResult.Failed -> {
                    pendingInvestmentImportBytes = null
                    InvestmentImportStatus.Failed(result.reason)
                }
            }
        }
    }

    fun confirmInvestmentImport() {
        viewModelScope.launch {
            val bytes = pendingInvestmentImportBytes
            if (bytes == null) {
                _investmentImport.value = InvestmentImportStatus.Failed("No investment preview is ready to import.")
                return@launch
            }
            _investmentImport.value = InvestmentImportStatus.Working
            _investmentImport.value = when (val result = investmentImporter.import(bytes.inputStream())) {
                is InvestmentImportResult.Done -> {
                    pendingInvestmentImportBytes = null
                    InvestmentImportStatus.Done(
                        importedContributions = result.importedContributions,
                        replacedContributions = result.replacedContributions,
                        skipped = result.skipped,
                        existingPool = result.existingPool,
                    )
                }
                is InvestmentImportResult.Failed -> InvestmentImportStatus.Failed(result.reason)
            }
        }
    }

    fun cancelInvestmentImportPreview() {
        pendingInvestmentImportBytes = null
        _investmentImport.value = InvestmentImportStatus.Idle
    }

    fun cancelCsvImportPreview() {
        pendingCsvImportBytes = null
        pendingCsvImportMapping = null
        pendingCsvRowDecisions = emptyMap()
        pendingCsvRowEdits = emptyMap()
        _csv.value = CsvStatus.Idle
    }

    fun exportCsv(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _csv.value = CsvStatus.Working
            val output = resolver.openOutputStream(uri)
            if (output == null) {
                _csv.value = CsvStatus.Failed("Couldn't open CSV destination.")
                return@launch
            }
            _csv.value = when (val result = csvExporter.exportAll(output)) {
                is CsvExportResult.Done -> CsvStatus.Exported(result.exported)
                is CsvExportResult.Failed -> CsvStatus.Failed(result.reason)
            }
        }
    }

    fun exportTaxReport(resolver: ContentResolver, uri: Uri, year: Int) {
        viewModelScope.launch {
            _taxExport.value = TaxExportStatus.Working
            val output = resolver.openOutputStream(uri)
            if (output == null) {
                _taxExport.value = TaxExportStatus.Failed("Couldn't open PDF destination.")
                return@launch
            }
            val localeTag = appLocale.value.ifBlank { Locale.getDefault().toLanguageTag() }
            _taxExport.value = when (val result = taxExport.exportAnnual(output, year, localeTag)) {
                is TaxExportResult.Done -> TaxExportStatus.Exported(
                    year = result.year,
                    transactions = result.transactions,
                    categoryTotals = result.categoryTotals,
                    excludedReconciliations = result.excludedReconciliations,
                )
                is TaxExportResult.Failed -> TaxExportStatus.Failed(result.reason)
            }
        }
    }

    fun runBackfill() {
        viewModelScope.launch { backfillTrigger.backfill() }
    }

    /**
     * Wipes the pending tray (transactions never confirmed by the user) and triggers
     * a full SMS backfill so the audit log gets re-parsed with the latest templates.
     * Used to recover from earlier beta builds that ingested ads as transactions.
     * Confirmed transactions are untouched.
     */
    fun rescanAndClean() {
        viewModelScope.launch {
            _rescan.value = RescanStatus.Working
            val cleared = transactions.clearPending()
            backfillTrigger.backfill()
            _rescan.value = RescanStatus.Done(cleared)
        }
    }

    fun clearRescanStatus() {
        _rescan.value = RescanStatus.Idle
    }

    /**
     * Recovers transactions that older builds auto-dismissed for low confidence.
     * Older policy auto-DISMISSED any parse with confidence < 0.50; the new policy
     * keeps them all in PENDING so the user decides. This action moves the legacy
     * DISMISSED rows back to PENDING so the user can review what was hidden.
     */
    fun recoverDismissed() {
        viewModelScope.launch {
            _recover.value = RecoverStatus.Working
            val count = transactions.recoverDismissedToPending()
            _recover.value = RecoverStatus.Done(count)
        }
    }

    fun clearRecoverStatus() {
        _recover.value = RecoverStatus.Idle
    }

    fun clearStatus() {
        _status.value = BackupStatus.Idle
    }

    fun clearCsvStatus() {
        pendingCsvImportBytes = null
        pendingCsvImportMapping = null
        pendingCsvRowDecisions = emptyMap()
        pendingCsvRowEdits = emptyMap()
        _csv.value = CsvStatus.Idle
    }

    fun clearBudgetTargetStatus() {
        pendingBudgetTargetImportBytes = null
        _budgetTargets.value = BudgetTargetStatus.Idle
    }

    fun clearWishlistImportStatus() {
        pendingWishlistImportBytes = null
        _wishlistImport.value = WishlistImportStatus.Idle
    }

    fun clearInvestmentImportStatus() {
        pendingInvestmentImportBytes = null
        _investmentImport.value = InvestmentImportStatus.Idle
    }

    fun clearTaxExportStatus() {
        _taxExport.value = TaxExportStatus.Idle
    }

    /**
     * Bulk-categorize export: writes a CSV of every PENDING/DISMISSED/uncategorized
     * transaction so the user can run them through an AI and import the filled file back.
     */
    fun exportUncategorized(
        resolver: ContentResolver,
        uri: Uri,
        mode: MerchantBulkExportMode = MerchantBulkExportMode.FULL_CONTEXT,
    ) {
        viewModelScope.launch {
            pendingBulkCategorizeImportBytes = null
            _bulkCategorize.value = BulkCategorizeStatus.Working
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                _bulkCategorize.value = BulkCategorizeStatus.Failed("Couldn't open CSV destination.")
                return@launch
            }
            _bulkCategorize.value = when (val r = bulkExporter.exportUncategorized(out, mode)) {
                is MerchantBulkExportResult.Done -> BulkCategorizeStatus.Exported(r.rows)
                is MerchantBulkExportResult.Failed -> BulkCategorizeStatus.Failed(r.reason)
            }
        }
    }

    /**
     * Bulk-categorize preview: reads the filled CSV, reports the updates/rules/skips
     * that confirmation would apply, and keeps the bytes only in memory until confirm.
     */
    fun previewCategorizations(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _bulkCategorize.value = BulkCategorizeStatus.Working
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                pendingBulkCategorizeImportBytes = null
                _bulkCategorize.value = BulkCategorizeStatus.Failed("Couldn't open CSV file.")
                return@launch
            }
            previewCategorizationsBytes(bytes)
        }
    }

    internal fun previewCategorizationsBytes(bytes: ByteArray) {
        viewModelScope.launch {
            _bulkCategorize.value = BulkCategorizeStatus.Working
            _bulkCategorize.value = when (val r = bulkImporter.previewCategorizations(bytes.inputStream())) {
                is MerchantBulkImportResult.Done -> {
                    pendingBulkCategorizeImportBytes = bytes
                    BulkCategorizeStatus.Preview(
                        updated = r.updated,
                        rulesAdded = r.rulesAdded,
                        skipped = r.skipped,
                        skipSummary = r.skipSummary,
                        categoryImpact = r.categoryImpact,
                    )
                }
                is MerchantBulkImportResult.Failed -> {
                    pendingBulkCategorizeImportBytes = null
                    BulkCategorizeStatus.Failed(r.reason)
                }
            }
        }
    }

    /**
     * Bulk-categorize import: each row whose `category_id` is set updates the matching
     * transaction (→ CONFIRMED) AND records a learned `CategoryRule` so future ingests
     * of the same merchant auto-categorize.
     */
    fun confirmBulkCategorizeImport() {
        viewModelScope.launch {
            val bytes = pendingBulkCategorizeImportBytes
            if (bytes == null) {
                _bulkCategorize.value = BulkCategorizeStatus.Failed("No bulk categorization preview is ready to import.")
                return@launch
            }
            _bulkCategorize.value = BulkCategorizeStatus.Working
            _bulkCategorize.value = when (val r = bulkImporter.importCategorizations(bytes.inputStream())) {
                is MerchantBulkImportResult.Done ->
                    BulkCategorizeStatus.Imported(r.updated, r.rulesAdded, r.skipped, r.skipSummary).also {
                        pendingBulkCategorizeImportBytes = null
                    }
                is MerchantBulkImportResult.Failed -> {
                    pendingBulkCategorizeImportBytes = null
                    BulkCategorizeStatus.Failed(r.reason)
                }
            }
        }
    }

    fun cancelBulkCategorizePreview() {
        pendingBulkCategorizeImportBytes = null
        _bulkCategorize.value = BulkCategorizeStatus.Idle
    }

    fun clearBulkCategorizeStatus() {
        pendingBulkCategorizeImportBytes = null
        _bulkCategorize.value = BulkCategorizeStatus.Idle
    }

    /**
     * Export the user's `learnedFromUser=true` rules as JSON for submission to the
     * public Athar GitHub repository (beta.20). Privacy: ONLY (pattern, categoryId)
     * tuples — no transaction data of any kind. Caller passes the destination URI
     * picked via `CreateDocument("application/json")`.
     */
    fun exportLearnedRules(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _communityShare.value = CommunityShareStatus.Working
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                _communityShare.value = CommunityShareStatus.Failed("Couldn't open JSON destination.")
                return@launch
            }
            _communityShare.value = when (val r = communityShare.exportLearnedRules(out)) {
                is CommunityRulesShareResult.Done -> CommunityShareStatus.Exported(r.rows, uri)
                CommunityRulesShareResult.Empty -> CommunityShareStatus.Empty
                is CommunityRulesShareResult.Failed -> CommunityShareStatus.Failed(r.reason)
            }
        }
    }

    fun clearCommunityShareStatus() {
        _communityShare.value = CommunityShareStatus.Idle
    }

    /**
     * Exports a redacted parser-support report. The JSON contains counts, hashes,
     * body-shape flags, and redacted parser errors only — no raw SMS body or sender.
     */
    fun exportSupportDiagnostics(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _supportDiagnostics.value = SupportDiagnosticsStatus.Working
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                _supportDiagnostics.value =
                    SupportDiagnosticsStatus.Failed("Couldn't open JSON destination.")
                return@launch
            }
            _supportDiagnostics.value = when (val r = supportDiagnostics.exportDiagnostics(out)) {
                is SupportDiagnosticsExportResult.Done -> SupportDiagnosticsStatus.Exported(
                    auditRows = r.auditRows,
                    parsed = r.parsed,
                    failed = r.failed,
                    ignored = r.ignored,
                )
                is SupportDiagnosticsExportResult.Failed -> SupportDiagnosticsStatus.Failed(r.reason)
            }
        }
    }

    fun clearSupportDiagnosticsStatus() {
        _supportDiagnostics.value = SupportDiagnosticsStatus.Idle
    }
}
