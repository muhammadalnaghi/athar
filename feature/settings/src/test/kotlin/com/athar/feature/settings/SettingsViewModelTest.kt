package com.athar.feature.settings

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.CatalogRefreshPhase
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
import com.athar.core.domain.repo.CsvImportDetectedColumns
import com.athar.core.domain.repo.CsvImportPreview
import com.athar.core.domain.repo.CsvImportPreviewResult
import com.athar.core.domain.repo.CsvImportPreviewRow
import com.athar.core.domain.repo.CsvImportResult
import com.athar.core.domain.repo.CsvImportRowDecision
import com.athar.core.domain.repo.CsvImportRowEdit
import com.athar.core.domain.repo.CsvImportTrigger
import com.athar.core.domain.repo.FinancialRecordingPreferences
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
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.WishlistImportPreview
import com.athar.core.domain.repo.WishlistImportPreviewResult
import com.athar.core.domain.repo.WishlistImportResult
import com.athar.core.domain.repo.WishlistImportTrigger
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var mainDispatcher: TestDispatcher

    @BeforeEach
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `recording control defaults to off`() = runTest(mainDispatcher) {
        val viewModel = settingsViewModel()

        advanceUntilIdle()

        assertThat(viewModel.recordingEnabled.value).isFalse()
    }

    @Test
    fun `recording enable writes the rollout flag on`() = runTest(mainDispatcher) {
        val recording = FakeFinancialRecordingPreferences()
        val viewModel = settingsViewModel(recording = recording)

        viewModel.setRecordingEnabled(true)
        advanceUntilIdle()

        assertThat(recording.writes).containsExactly(true)
        assertThat(recording.current).isTrue()
        assertThat(viewModel.recordingEnabled.value).isTrue()
    }

    @Test
    fun `recording explicit off wins over previous on`() = runTest(mainDispatcher) {
        val recording = FakeFinancialRecordingPreferences(initial = true)
        val viewModel = settingsViewModel(recording = recording)

        advanceUntilIdle()
        assertThat(viewModel.recordingEnabled.value).isTrue()

        viewModel.setRecordingEnabled(false)
        advanceUntilIdle()

        assertThat(recording.writes).containsExactly(false)
        assertThat(recording.current).isFalse()
        assertThat(viewModel.recordingEnabled.value).isFalse()
    }

    @Test
    fun `recording off preference survives view model recreation`() = runTest(mainDispatcher) {
        val recording = FakeFinancialRecordingPreferences(initial = true)
        settingsViewModel(recording = recording).setRecordingEnabled(false)
        advanceUntilIdle()

        val relaunched = settingsViewModel(recording = recording)
        advanceUntilIdle()

        assertThat(relaunched.recordingEnabled.value).isFalse()
    }

    @Test
    fun `pending count follows pending repository flow`() = runTest(mainDispatcher) {
        val transactions = FakeTransactionRepository()
        val viewModel = settingsViewModel(transactions = transactions)

        transactions.pending.value = listOf(
            Fixtures.transaction(id = "pending-1"),
            Fixtures.transaction(id = "pending-2"),
        )
        advanceUntilIdle()

        assertThat(viewModel.pendingCount.value).isEqualTo(2)
    }

    @Test
    fun `rescan and clean clears pending before backfill`() = runTest(mainDispatcher) {
        val calls = mutableListOf<String>()
        val transactions = FakeTransactionRepository(
            clearPendingResult = 7,
            onClearPending = { calls += "clear" },
        )
        val backfill = FakeSmsBackfillTrigger(onBackfill = { calls += "backfill" })
        val viewModel = settingsViewModel(
            backfill = backfill,
            transactions = transactions,
        )

        viewModel.rescanAndClean()
        advanceUntilIdle()

        assertThat(calls).containsExactly("clear", "backfill").inOrder()
        assertThat(viewModel.rescanStatus.value).isEqualTo(RescanStatus.Done(clearedPending = 7))
    }

    @Test
    fun `confirm csv import imports the previewed bytes`() = runTest(mainDispatcher) {
        val csv = RecordingCsvImportTrigger()
        val viewModel = settingsViewModel(csvImporter = csv)

        viewModel.previewCsvImportBytes("previewed csv".toByteArray())
        advanceUntilIdle()
        viewModel.confirmCsvImport()
        advanceUntilIdle()

        assertThat(csv.importedBytes.map { it.decodeToString() }).containsExactly("previewed csv")
        assertThat(viewModel.csvStatus.value).isEqualTo(CsvStatus.Done(imported = 3, skipped = 1))
    }

    @Test
    fun `confirm csv import uses selected statement account`() = runTest(mainDispatcher) {
        val csv = RecordingCsvImportTrigger()
        val accounts = FakeAccountRepository(
            active = listOf(
                Fixtures.account(id = MANUAL_ACCOUNT_ID, name = "Cash"),
                Fixtures.account(id = "acc-checking", name = "Checking"),
            ),
        )
        val viewModel = settingsViewModel(
            csvImporter = csv,
            accounts = accounts,
        )

        advanceUntilIdle()
        viewModel.setStatementImportAccount("acc-checking")
        viewModel.previewCsvImportBytes("selected account csv".toByteArray())
        advanceUntilIdle()
        viewModel.confirmCsvImport()
        advanceUntilIdle()

        assertThat(csv.previewedAccountIds).containsExactly("acc-checking")
        assertThat(csv.importedAccountIds).containsExactly("acc-checking")
        assertThat(csv.importedBytes.map { it.decodeToString() }).containsExactly("selected account csv")
    }

    @Test
    fun `csv column mapping is used for preview and confirm`() = runTest(mainDispatcher) {
        val csv = RecordingCsvImportTrigger(
            previewResult = CsvImportPreviewResult.MappingRequired(
                columns = listOf("Booked", "Details", "Out"),
                reason = "Map columns",
            ),
        )
        val viewModel = settingsViewModel(csvImporter = csv)

        viewModel.previewCsvImportBytes("needs mapping".toByteArray())
        advanceUntilIdle()
        viewModel.setCsvColumnMapping(CsvImportColumnRole.DATE, "Booked")
        viewModel.setCsvColumnMapping(CsvImportColumnRole.MERCHANT, "Details")
        viewModel.setCsvColumnMapping(CsvImportColumnRole.DEBIT, "Out")
        csv.previewResult = CsvImportPreviewResult.Done(emptyPreview())
        viewModel.previewCsvImportWithMapping()
        advanceUntilIdle()
        viewModel.confirmCsvImport()
        advanceUntilIdle()

        assertThat(csv.previewedMappings.last()).isEqualTo(
            CsvImportColumnMapping(date = "Booked", merchant = "Details", debit = "Out"),
        )
        assertThat(csv.importedMappings).containsExactly(
            CsvImportColumnMapping(date = "Booked", merchant = "Details", debit = "Out"),
        )
        assertThat(viewModel.csvStatus.value).isEqualTo(CsvStatus.Done(imported = 3, skipped = 1))
    }

    @Test
    fun `csv row exclusions are used for preview and confirm`() = runTest(mainDispatcher) {
        val csv = RecordingCsvImportTrigger()
        val viewModel = settingsViewModel(csvImporter = csv)

        viewModel.previewCsvImportBytes("row decision csv".toByteArray())
        advanceUntilIdle()
        viewModel.setCsvImportRowIncluded(rowNumber = 2, included = false)
        advanceUntilIdle()
        viewModel.confirmCsvImport()
        advanceUntilIdle()

        assertThat(csv.previewedRowDecisions.last()).containsExactly(
            CsvImportRowDecision(rowNumber = 2, shouldImport = false),
        )
        assertThat(csv.importedRowDecisions).containsExactly(
            listOf(CsvImportRowDecision(rowNumber = 2, shouldImport = false)),
        )
    }

    @Test
    fun `csv row edits are used for preview and confirm`() = runTest(mainDispatcher) {
        val csv = RecordingCsvImportTrigger()
        val viewModel = settingsViewModel(csvImporter = csv)
        val edit = CsvImportRowEdit(
            rowNumber = 2,
            accountId = "acc-checking",
            merchant = "Corrected Merchant",
            amount = "20.00",
            currency = "USD",
            type = TxType.TRANSFER,
            notes = "preview fix",
        )

        viewModel.previewCsvImportBytes("row edit csv".toByteArray())
        advanceUntilIdle()
        viewModel.setCsvImportRowEdit(edit)
        advanceUntilIdle()
        viewModel.confirmCsvImport()
        advanceUntilIdle()

        assertThat(csv.previewedRowEdits.last()).containsExactly(edit)
        assertThat(csv.importedRowEdits).containsExactly(listOf(edit))
    }

    @Test
    fun `confirm budget target import applies the previewed bytes`() = runTest(mainDispatcher) {
        val budgetTargets = RecordingBudgetTargetImportTrigger()
        val viewModel = settingsViewModel(budgetTargetsImporter = budgetTargets)

        viewModel.previewBudgetTargetsImportBytes("previewed workbook".toByteArray())
        advanceUntilIdle()
        viewModel.confirmBudgetTargetsImport()
        advanceUntilIdle()

        assertThat(budgetTargets.previewedBytes.map { it.decodeToString() }).containsExactly("previewed workbook")
        assertThat(budgetTargets.importedBytes.map { it.decodeToString() }).containsExactly("previewed workbook")
        assertThat(viewModel.budgetTargetStatus.value)
            .isEqualTo(BudgetTargetStatus.Done(applied = 4, changed = 3, skipped = 1))
    }

    @Test
    fun `failed budget target preview clears pending import bytes`() = runTest(mainDispatcher) {
        val budgetTargets = RecordingBudgetTargetImportTrigger(
            previewResult = BudgetTargetImportPreviewResult.Failed("Not a TMOAP workbook."),
        )
        val viewModel = settingsViewModel(budgetTargetsImporter = budgetTargets)

        viewModel.previewBudgetTargetsImportBytes("bad workbook".toByteArray())
        advanceUntilIdle()
        viewModel.confirmBudgetTargetsImport()
        advanceUntilIdle()

        assertThat(viewModel.budgetTargetStatus.value)
            .isEqualTo(BudgetTargetStatus.Failed("No budget target preview is ready to import."))
        assertThat(budgetTargets.importedBytes).isEmpty()
    }

    @Test
    fun `confirm wishlist import applies the previewed bytes`() = runTest(mainDispatcher) {
        val wishlist = RecordingWishlistImportTrigger()
        val viewModel = settingsViewModel(wishlistImporter = wishlist)

        viewModel.previewWishlistImportBytes("previewed workbook".toByteArray())
        advanceUntilIdle()
        viewModel.confirmWishlistImport()
        advanceUntilIdle()

        assertThat(wishlist.previewedBytes.map { it.decodeToString() }).containsExactly("previewed workbook")
        assertThat(wishlist.importedBytes.map { it.decodeToString() }).containsExactly("previewed workbook")
        assertThat(viewModel.wishlistImportStatus.value)
            .isEqualTo(WishlistImportStatus.Done(imported = 5, newItems = 3, updatedItems = 2, skipped = 1))
    }

    @Test
    fun `failed wishlist preview clears pending import bytes`() = runTest(mainDispatcher) {
        val wishlist = RecordingWishlistImportTrigger(
            previewResult = WishlistImportPreviewResult.Failed("Not a TMOAP workbook."),
        )
        val viewModel = settingsViewModel(wishlistImporter = wishlist)

        viewModel.previewWishlistImportBytes("bad workbook".toByteArray())
        advanceUntilIdle()
        viewModel.confirmWishlistImport()
        advanceUntilIdle()

        assertThat(viewModel.wishlistImportStatus.value)
            .isEqualTo(WishlistImportStatus.Failed("No wishlist preview is ready to import."))
        assertThat(wishlist.importedBytes).isEmpty()
    }

    @Test
    fun `confirm investment import applies the previewed bytes`() = runTest(mainDispatcher) {
        val investments = RecordingInvestmentImportTrigger()
        val viewModel = settingsViewModel(investmentImporter = investments)

        viewModel.previewInvestmentImportBytes("previewed workbook".toByteArray())
        advanceUntilIdle()
        viewModel.confirmInvestmentImport()
        advanceUntilIdle()

        assertThat(investments.previewedBytes.map { it.decodeToString() }).containsExactly("previewed workbook")
        assertThat(investments.importedBytes.map { it.decodeToString() }).containsExactly("previewed workbook")
        assertThat(viewModel.investmentImportStatus.value)
            .isEqualTo(
                InvestmentImportStatus.Done(
                    importedContributions = 6,
                    replacedContributions = 2,
                    skipped = 1,
                    existingPool = true,
                ),
            )
    }

    @Test
    fun `failed investment preview clears pending import bytes`() = runTest(mainDispatcher) {
        val investments = RecordingInvestmentImportTrigger(
            previewResult = InvestmentImportPreviewResult.Failed("Not a TMOAP workbook."),
        )
        val viewModel = settingsViewModel(investmentImporter = investments)

        viewModel.previewInvestmentImportBytes("bad workbook".toByteArray())
        advanceUntilIdle()
        viewModel.confirmInvestmentImport()
        advanceUntilIdle()

        assertThat(viewModel.investmentImportStatus.value)
            .isEqualTo(InvestmentImportStatus.Failed("No investment preview is ready to import."))
        assertThat(investments.importedBytes).isEmpty()
    }

    @Test
    fun `confirm bulk categorization import applies the previewed bytes`() = runTest(mainDispatcher) {
        val bulkImporter = RecordingMerchantBulkImportTrigger()
        val viewModel = settingsViewModel(bulkImporter = bulkImporter)

        viewModel.previewCategorizationsBytes("previewed csv".toByteArray())
        advanceUntilIdle()
        viewModel.confirmBulkCategorizeImport()
        advanceUntilIdle()

        assertThat(bulkImporter.previewedBytes.map { it.decodeToString() }).containsExactly("previewed csv")
        assertThat(bulkImporter.importedBytes.map { it.decodeToString() }).containsExactly("previewed csv")
        assertThat(viewModel.bulkCategorizeStatus.value).isEqualTo(
            BulkCategorizeStatus.Imported(
                updated = 2,
                rulesAdded = 1,
                skipped = 3,
                skipSummary = MerchantBulkImportSkipSummary(unknownCategories = 1, missingTransactions = 2),
            ),
        )
    }

    @Test
    fun `bulk categorization preview exposes category impact`() = runTest(mainDispatcher) {
        val impact = listOf(
            MerchantBulkImportCategoryImpact(
                categoryId = "cat-coffee",
                categoryName = "Coffee",
                categoryNameAr = "قهوة",
                updated = 3,
            ),
        )
        val bulkImporter = RecordingMerchantBulkImportTrigger(
            previewResult = MerchantBulkImportResult.Done(
                updated = 3,
                rulesAdded = 1,
                skipped = 0,
                categoryImpact = impact,
            ),
        )
        val viewModel = settingsViewModel(bulkImporter = bulkImporter)

        viewModel.previewCategorizationsBytes("preview csv".toByteArray())
        advanceUntilIdle()

        assertThat(viewModel.bulkCategorizeStatus.value).isEqualTo(
            BulkCategorizeStatus.Preview(
                updated = 3,
                rulesAdded = 1,
                skipped = 0,
                skipSummary = MerchantBulkImportSkipSummary(),
                categoryImpact = impact,
            ),
        )
        assertThat(bulkImporter.importedBytes).isEmpty()
    }

    @Test
    fun `failed bulk categorization preview clears pending import bytes`() = runTest(mainDispatcher) {
        val bulkImporter = RecordingMerchantBulkImportTrigger(
            previewResult = MerchantBulkImportResult.Failed("Not a bulk CSV."),
        )
        val viewModel = settingsViewModel(bulkImporter = bulkImporter)

        viewModel.previewCategorizationsBytes("bad csv".toByteArray())
        advanceUntilIdle()
        viewModel.confirmBulkCategorizeImport()
        advanceUntilIdle()

        assertThat(viewModel.bulkCategorizeStatus.value)
            .isEqualTo(BulkCategorizeStatus.Failed("No bulk categorization preview is ready to import."))
        assertThat(bulkImporter.importedBytes).isEmpty()
    }

    @Test
    fun `merchant catalog card mirrors repository state`() = runTest(mainDispatcher) {
        val catalog = FakeMerchantCatalogRepository(
            initial = MerchantCatalogState(
                updatesEnabled = true,
                networkSupported = true,
                version = 1,
                entryCount = 58,
                checkedAt = null,
                phase = CatalogRefreshPhase.CURRENT,
            ),
        )
        val viewModel = settingsViewModel(catalog = catalog)

        assertThat(viewModel.merchantCatalog.value.version).isEqualTo(1)
        assertThat(viewModel.merchantCatalog.value.entryCount).isEqualTo(58)
    }

    @Test
    fun `enabling updates persists opt-in and triggers a manual refresh`() = runTest(mainDispatcher) {
        val catalog = FakeMerchantCatalogRepository()
        val viewModel = settingsViewModel(catalog = catalog)

        viewModel.setMerchantCatalogUpdates(true)
        advanceUntilIdle()

        assertThat(catalog.enabledCalls).containsExactly(true)
        assertThat(catalog.refreshAttempts).isEqualTo(1)
    }

    @Test
    fun `disable and reset reach the repository`() = runTest(mainDispatcher) {
        val catalog = FakeMerchantCatalogRepository()
        val viewModel = settingsViewModel(catalog = catalog)

        viewModel.setMerchantCatalogUpdates(false)
        viewModel.resetMerchantCatalog()
        advanceUntilIdle()

        assertThat(catalog.enabledCalls).contains(false)
        assertThat(catalog.resetCalls).isEqualTo(1)
    }

    @Test
    fun `failed catalog consent write does not crash or trigger refresh`() = runTest(mainDispatcher) {
        val catalog = FakeMerchantCatalogRepository().apply {
            toggleFailure = java.io.IOException("synthetic metadata failure")
        }
        val viewModel = settingsViewModel(catalog = catalog)
        viewModel.setMerchantCatalogUpdates(true)
        advanceUntilIdle()
        assertThat(catalog.refreshAttempts).isEqualTo(0)
        assertThat(viewModel.merchantCatalog.value.updatesEnabled).isFalse()
        assertThat(viewModel.merchantCatalog.value.phase).isEqualTo(CatalogRefreshPhase.FAILED)
    }

    @Test
    fun `failed disable remains visible and a second action retries saving`() = runTest(mainDispatcher) {
        val catalog = FakeMerchantCatalogRepository().apply {
            toggleFailure = java.io.IOException("synthetic metadata failure")
        }
        val viewModel = settingsViewModel(catalog = catalog)
        viewModel.setMerchantCatalogUpdates(false)
        advanceUntilIdle()
        assertThat(viewModel.merchantCatalog.value.disablePersistencePending).isTrue()
        catalog.toggleFailure = null
        viewModel.setMerchantCatalogUpdates(false)
        advanceUntilIdle()
        assertThat(catalog.enabledCalls).containsExactly(false, false).inOrder()
        assertThat(viewModel.merchantCatalog.value.disablePersistencePending).isFalse()
        assertThat(catalog.refreshAttempts).isEqualTo(0)
    }

    @Test
    fun `backup import failure hides diagnostic text and allows retry`() = runTest(mainDispatcher) {
        var fail = true
        var imports = 0
        val repository = object : BackupRepository by FakeBackupRepository {
            override suspend fun import(input: InputStream, passphrase: CharArray) {
                imports += 1
                if (fail) throw IllegalStateException("BAD_DECRYPT synthetic payload diagnostic")
            }
        }
        val viewModel = settingsViewModel(backup = repository)
        viewModel.importBackup("synthetic-passphrase") { java.io.ByteArrayInputStream(byteArrayOf(1)) }
        val failure = viewModel.status.first { it is BackupStatus.ImportFailure } as BackupStatus.ImportFailure
        assertThat(failure.detail).isNull()
        assertThat(imports).isEqualTo(1)
        fail = false
        viewModel.importBackup("synthetic-passphrase") { java.io.ByteArrayInputStream(byteArrayOf(1)) }
        assertThat(viewModel.status.first { it == BackupStatus.ImportSuccess }).isEqualTo(BackupStatus.ImportSuccess)
        assertThat(imports).isEqualTo(2)
    }

    @Test
    fun `backup export failure hides provider diagnostic text`() = runTest(mainDispatcher) {
        val repository = object : BackupRepository by FakeBackupRepository {
            override suspend fun export(output: OutputStream, passphrase: CharArray) {
                throw java.io.IOException("synthetic private provider path diagnostic")
            }
        }
        val viewModel = settingsViewModel(backup = repository)
        viewModel.exportBackup("synthetic-passphrase") { java.io.ByteArrayOutputStream() }
        val failure = viewModel.status.first { it is BackupStatus.ExportFailure } as BackupStatus.ExportFailure
        assertThat(failure.detail).isNull()
    }

    private fun settingsViewModel(
        backup: BackupRepository = FakeBackupRepository,
        backfill: SmsBackfillTrigger = FakeSmsBackfillTrigger(),
        csvImporter: CsvImportTrigger = FakeCsvImportTrigger,
        budgetTargetsImporter: BudgetTargetImportTrigger = FakeBudgetTargetImportTrigger,
        wishlistImporter: WishlistImportTrigger = FakeWishlistImportTrigger,
        investmentImporter: InvestmentImportTrigger = FakeInvestmentImportTrigger,
        bulkImporter: MerchantBulkImportTrigger = FakeMerchantBulkImportTrigger,
        transactions: TransactionRepository = FakeTransactionRepository(),
        accounts: AccountRepository = FakeAccountRepository(),
        catalog: MerchantCatalogRepository = FakeMerchantCatalogRepository(),
        recording: FinancialRecordingPreferences = FakeFinancialRecordingPreferences(),
    ) = SettingsViewModel(
        backup = backup,
        backfillTrigger = backfill,
        csvImporter = csvImporter,
        budgetTargetsImporter = budgetTargetsImporter,
        wishlistImporter = wishlistImporter,
        investmentImporter = investmentImporter,
        csvExporter = FakeCsvExportTrigger,
        bulkExporter = FakeMerchantBulkExportTrigger,
        bulkImporter = bulkImporter,
        communityShare = FakeCommunityRulesShareTrigger,
        supportDiagnostics = FakeSupportDiagnosticsExportTrigger,
        taxExport = FakeTaxExportTrigger,
        prefs = FakeUserPreferencesRepository(),
        transactions = transactions,
        accounts = accounts,
        merchantCatalogRepository = catalog,
        recording = recording,
    )
}

private class FakeTransactionRepository(
    val pending: MutableStateFlow<List<Transaction>> = MutableStateFlow(emptyList()),
    private val clearPendingResult: Int = 0,
    private val onClearPending: () -> Unit = {},
) : TransactionRepository {
    override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> = flowOf(emptyList())
    override fun observePending(): Flow<List<Transaction>> = pending
    override fun observeAll(): Flow<List<Transaction>> = flowOf(emptyList())
    override suspend fun get(id: String): Transaction? = null
    override suspend fun upsertIngest(transaction: Transaction): String =
        error("Ingestion is outside this UI or import test fixture")

    override suspend fun upsert(transaction: Transaction) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun setStatus(id: String, status: TxStatus) = Unit
    override suspend fun clearPending(): Int {
        onClearPending()
        return clearPendingResult
    }
    override suspend fun confirmAllConfident(minConfidence: Float): Int = 0
    override suspend fun dismissAllLowConfidence(maxConfidence: Float): Int = 0
    override suspend fun dismissAllPending(): Int = 0
    override suspend fun recoverDismissedToPending(): Int = 0
    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int = 0
}

private class FakeAccountRepository(
    active: List<Account> = listOf(Fixtures.account(id = MANUAL_ACCOUNT_ID, name = "Cash")),
) : AccountRepository {
    private val active = MutableStateFlow(active)

    override fun observeActive(): Flow<List<Account>> = active
    override fun observeAll(includeArchived: Boolean): Flow<List<Account>> = active
    override suspend fun get(id: String): Account? = active.value.firstOrNull { it.id == id }
    override suspend fun upsert(account: Account) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
    override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> =
        flowOf(NetWorth(total = Money.zero(displayCurrency), byCurrency = emptyMap(), accounts = emptyList()))
    override fun observeBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())
    override suspend fun resolveForIngest(sender: String, body: String, counterparty: String?): Account? = null
    override suspend fun reconcile(accountId: String, target: Money, label: String, note: String?): ReconcileResult =
        ReconcileResult.Failed("not used")
}

private class FakeSmsBackfillTrigger(
    private val onBackfill: () -> Unit = {},
) : SmsBackfillTrigger {
    private val _progress = MutableStateFlow<BackfillProgress>(BackfillProgress.Idle)
    override val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()
    override suspend fun backfill(daysBack: Int?) {
        onBackfill()
        _progress.value = BackfillProgress.Done(scanned = 0, sentToPipeline = 0)
    }
}

private object FakeBackupRepository : BackupRepository {
    override suspend fun export(output: OutputStream, passphrase: CharArray) = Unit
    override suspend fun import(bytes: ByteArray, passphrase: CharArray) = Unit
    override suspend fun import(input: InputStream, passphrase: CharArray) = Unit
}

private object FakeCsvImportTrigger : CsvImportTrigger {
    override suspend fun preview(
        input: InputStream,
        accountId: String,
        mapping: CsvImportColumnMapping?,
        rowDecisions: List<CsvImportRowDecision>,
        rowEdits: List<CsvImportRowEdit>,
    ): CsvImportPreviewResult =
        CsvImportPreviewResult.Done(emptyPreview())

    override suspend fun import(
        input: InputStream,
        accountId: String,
        mapping: CsvImportColumnMapping?,
        rowDecisions: List<CsvImportRowDecision>,
        rowEdits: List<CsvImportRowEdit>,
    ): CsvImportResult =
        CsvImportResult.Done(imported = 0, skipped = 0)
}

private object FakeBudgetTargetImportTrigger : BudgetTargetImportTrigger {
    override suspend fun preview(input: InputStream): BudgetTargetImportPreviewResult =
        BudgetTargetImportPreviewResult.Done(emptyBudgetPreview())

    override suspend fun import(input: InputStream): BudgetTargetImportResult =
        BudgetTargetImportResult.Done(applied = 0, changed = 0, skipped = 0)
}

private object FakeWishlistImportTrigger : WishlistImportTrigger {
    override suspend fun preview(input: InputStream): WishlistImportPreviewResult =
        WishlistImportPreviewResult.Done(emptyWishlistPreview())

    override suspend fun import(input: InputStream): WishlistImportResult =
        WishlistImportResult.Done(imported = 0, newItems = 0, updatedItems = 0, skipped = 0)
}

private object FakeInvestmentImportTrigger : InvestmentImportTrigger {
    override suspend fun preview(input: InputStream): InvestmentImportPreviewResult =
        InvestmentImportPreviewResult.Done(emptyInvestmentPreview())

    override suspend fun import(input: InputStream): InvestmentImportResult =
        InvestmentImportResult.Done(
            importedContributions = 0,
            replacedContributions = 0,
            skipped = 0,
            existingPool = false,
        )
}

private class RecordingBudgetTargetImportTrigger(
    var previewResult: BudgetTargetImportPreviewResult =
        BudgetTargetImportPreviewResult.Done(emptyBudgetPreview()),
) : BudgetTargetImportTrigger {
    val previewedBytes = mutableListOf<ByteArray>()
    val importedBytes = mutableListOf<ByteArray>()

    override suspend fun preview(input: InputStream): BudgetTargetImportPreviewResult {
        previewedBytes += input.readBytes()
        return previewResult
    }

    override suspend fun import(input: InputStream): BudgetTargetImportResult {
        importedBytes += input.readBytes()
        return BudgetTargetImportResult.Done(applied = 4, changed = 3, skipped = 1)
    }
}

private class RecordingWishlistImportTrigger(
    var previewResult: WishlistImportPreviewResult =
        WishlistImportPreviewResult.Done(emptyWishlistPreview()),
) : WishlistImportTrigger {
    val previewedBytes = mutableListOf<ByteArray>()
    val importedBytes = mutableListOf<ByteArray>()

    override suspend fun preview(input: InputStream): WishlistImportPreviewResult {
        previewedBytes += input.readBytes()
        return previewResult
    }

    override suspend fun import(input: InputStream): WishlistImportResult {
        importedBytes += input.readBytes()
        return WishlistImportResult.Done(imported = 5, newItems = 3, updatedItems = 2, skipped = 1)
    }
}

private class RecordingInvestmentImportTrigger(
    var previewResult: InvestmentImportPreviewResult =
        InvestmentImportPreviewResult.Done(emptyInvestmentPreview()),
) : InvestmentImportTrigger {
    val previewedBytes = mutableListOf<ByteArray>()
    val importedBytes = mutableListOf<ByteArray>()

    override suspend fun preview(input: InputStream): InvestmentImportPreviewResult {
        previewedBytes += input.readBytes()
        return previewResult
    }

    override suspend fun import(input: InputStream): InvestmentImportResult {
        importedBytes += input.readBytes()
        return InvestmentImportResult.Done(
            importedContributions = 6,
            replacedContributions = 2,
            skipped = 1,
            existingPool = true,
        )
    }
}

private class RecordingMerchantBulkImportTrigger(
    var previewResult: MerchantBulkImportResult = bulkImportResult(),
    var importResult: MerchantBulkImportResult = bulkImportResult(),
) : MerchantBulkImportTrigger {
    val previewedBytes = mutableListOf<ByteArray>()
    val importedBytes = mutableListOf<ByteArray>()

    override suspend fun previewCategorizations(input: InputStream): MerchantBulkImportResult {
        previewedBytes += input.readBytes()
        return previewResult
    }

    override suspend fun importCategorizations(input: InputStream): MerchantBulkImportResult {
        importedBytes += input.readBytes()
        return importResult
    }
}

private fun bulkImportResult(): MerchantBulkImportResult = MerchantBulkImportResult.Done(
    updated = 2,
    rulesAdded = 1,
    skipped = 3,
    skipSummary = MerchantBulkImportSkipSummary(unknownCategories = 1, missingTransactions = 2),
)

private class RecordingCsvImportTrigger(
    var previewResult: CsvImportPreviewResult = CsvImportPreviewResult.Done(emptyPreview()),
) : CsvImportTrigger {
    val previewedBytes = mutableListOf<ByteArray>()
    val previewedAccountIds = mutableListOf<String>()
    val previewedMappings = mutableListOf<CsvImportColumnMapping?>()
    val previewedRowDecisions = mutableListOf<List<CsvImportRowDecision>>()
    val previewedRowEdits = mutableListOf<List<CsvImportRowEdit>>()
    val importedBytes = mutableListOf<ByteArray>()
    val importedAccountIds = mutableListOf<String>()
    val importedMappings = mutableListOf<CsvImportColumnMapping?>()
    val importedRowDecisions = mutableListOf<List<CsvImportRowDecision>>()
    val importedRowEdits = mutableListOf<List<CsvImportRowEdit>>()

    override suspend fun preview(
        input: InputStream,
        accountId: String,
        mapping: CsvImportColumnMapping?,
        rowDecisions: List<CsvImportRowDecision>,
        rowEdits: List<CsvImportRowEdit>,
    ): CsvImportPreviewResult {
        previewedBytes += input.readBytes()
        previewedAccountIds += accountId
        previewedMappings += mapping
        previewedRowDecisions += rowDecisions
        previewedRowEdits += rowEdits
        return previewResult
    }

    override suspend fun import(
        input: InputStream,
        accountId: String,
        mapping: CsvImportColumnMapping?,
        rowDecisions: List<CsvImportRowDecision>,
        rowEdits: List<CsvImportRowEdit>,
    ): CsvImportResult {
        importedBytes += input.readBytes()
        importedAccountIds += accountId
        importedMappings += mapping
        importedRowDecisions += rowDecisions
        importedRowEdits += rowEdits
        return CsvImportResult.Done(imported = 3, skipped = 1)
    }
}

private fun emptyPreview(): CsvImportPreview = CsvImportPreview(
    importable = 0,
    skipped = 0,
    columns = CsvImportDetectedColumns(
        date = "date",
        merchant = "merchant",
        amount = "amount",
        debit = null,
        credit = null,
        currency = null,
        category = null,
        type = null,
        notes = null,
    ),
    sampleRows = listOf(
        CsvImportPreviewRow(
            rowNumber = 2,
            accountId = MANUAL_ACCOUNT_ID,
            date = "2026-06-13",
            merchant = "Sample",
            amount = "0",
            currency = "SAR",
            type = com.athar.core.domain.model.TxType.EXPENSE,
            category = null,
            notes = null,
            included = true,
            edited = false,
        ),
    ),
    skippedRows = emptyList(),
)

private fun emptyBudgetPreview(): BudgetTargetImportPreview = BudgetTargetImportPreview(
    targetRows = 0,
    changed = 0,
    skipped = 0,
    expenseTargets = 0,
    incomeTargets = 0,
    monthlyExpenseTotal = Money.zero(),
    monthlyIncomeTotal = Money.zero(),
    sampleRows = emptyList(),
    skippedRows = emptyList(),
)

private fun emptyWishlistPreview(): WishlistImportPreview = WishlistImportPreview(
    itemRows = 0,
    newItems = 0,
    updatedItems = 0,
    skipped = 0,
    totalCost = Money.zero(),
    totalSaved = Money.zero(),
    sampleRows = emptyList(),
    skippedRows = emptyList(),
)

private fun emptyInvestmentPreview(): InvestmentImportPreview = InvestmentImportPreview(
    poolName = "Family Investments",
    period = "Imported",
    existingPool = false,
    contributionRows = 0,
    replacedContributions = 0,
    skipped = 0,
    totalCorpus = Money.zero(),
    totalReturn = Money.zero(),
    sampleRows = emptyList(),
    skippedRows = emptyList(),
)

private object FakeCsvExportTrigger : CsvExportTrigger {
    override suspend fun exportAll(output: OutputStream): CsvExportResult = CsvExportResult.Done(exported = 0)
}

private object FakeMerchantBulkExportTrigger : MerchantBulkExportTrigger {
    override suspend fun exportUncategorized(
        out: OutputStream,
        mode: MerchantBulkExportMode,
    ): MerchantBulkExportResult =
        MerchantBulkExportResult.Done(rows = 0)
}

private object FakeMerchantBulkImportTrigger : MerchantBulkImportTrigger {
    override suspend fun previewCategorizations(input: InputStream): MerchantBulkImportResult =
        MerchantBulkImportResult.Done(updated = 0, rulesAdded = 0, skipped = 0)

    override suspend fun importCategorizations(input: InputStream): MerchantBulkImportResult =
        MerchantBulkImportResult.Done(updated = 0, rulesAdded = 0, skipped = 0)
}

private object FakeCommunityRulesShareTrigger : CommunityRulesShareTrigger {
    override suspend fun exportLearnedRules(out: OutputStream): CommunityRulesShareResult =
        CommunityRulesShareResult.Empty
}

private object FakeSupportDiagnosticsExportTrigger : SupportDiagnosticsExportTrigger {
    override suspend fun exportDiagnostics(out: OutputStream): SupportDiagnosticsExportResult =
        SupportDiagnosticsExportResult.Done(auditRows = 0, parsed = 0, failed = 0, ignored = 0)
}

private object FakeTaxExportTrigger : TaxExportTrigger {
    override suspend fun exportAnnual(output: OutputStream, year: Int, localeTag: String): TaxExportResult =
        TaxExportResult.Done(year = year, transactions = 0, categoryTotals = 0, excludedReconciliations = 0)
}

private class FakeUserPreferencesRepository : UserPreferencesRepository {
    override fun onboardingComplete(): Flow<Boolean> = flowOf(false)
    override suspend fun setOnboardingComplete(complete: Boolean) = Unit
    override fun lastSmsBackfillEpochSeconds(): Flow<Long> = flowOf(0L)
    override suspend fun setLastSmsBackfillEpochSeconds(epoch: Long) = Unit
    override fun hijriEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setHijriEnabled(enabled: Boolean) = Unit
    override fun ownAccountNumbers(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setOwnAccountNumbers(numbers: List<String>) = Unit
    override fun displayCurrency(): Flow<String> = flowOf("SAR")
    override suspend fun setDisplayCurrency(currency: String) = Unit
    override fun appLocale(): Flow<String> = flowOf("")
    override suspend fun setAppLocale(languageTag: String) = Unit
    override fun savingsRateTargetPercent(): Flow<Int> = flowOf(20)
    override suspend fun setSavingsRateTargetPercent(percent: Int) = Unit
    override fun emergencyFundTargetMonths(): Flow<Int> = flowOf(6)
    override suspend fun setEmergencyFundTargetMonths(months: Int) = Unit
    override fun billRemindersEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setBillRemindersEnabled(enabled: Boolean) = Unit
    override fun billReminderSentKeys(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun setBillReminderSentKeys(keys: Set<String>) = Unit
}

private class FakeFinancialRecordingPreferences(
    initial: Boolean = false,
) : FinancialRecordingPreferences {
    val writes = mutableListOf<Boolean>()
    private val state = MutableStateFlow(initial)

    /** Latest persisted value, as a test-only read. */
    val current: Boolean
        get() = state.value

    override fun enabled(): Flow<Boolean> = state

    override suspend fun setEnabled(enabled: Boolean) {
        writes += enabled
        state.value = enabled
    }
}

/** Honest in-memory fake of the domain catalog repository for Settings tests. */
private class FakeMerchantCatalogRepository(
    initial: MerchantCatalogState = MerchantCatalogState(),
) : MerchantCatalogRepository {

    val state_ = MutableStateFlow(initial)
    val enabledCalls = mutableListOf<Boolean>()
    var toggleFailure: Exception? = null
    var refreshAttempts = 0
        private set
    var resetCalls = 0
        private set

    override val state = state_
    override suspend fun initialize() = Unit
    override suspend fun setUpdatesEnabled(enabled: Boolean) {
        enabledCalls += enabled
        toggleFailure?.let { failure ->
            state_.value = state_.value.copy(
                updatesEnabled = false,
                disablePersistencePending = !enabled,
                phase = CatalogRefreshPhase.FAILED,
            )
            throw failure
        }
        state_.value = state_.value.copy(updatesEnabled = enabled, disablePersistencePending = false)
    }

    override suspend fun refresh(force: Boolean) {
        refreshAttempts += 1
    }

    override suspend fun resetToBundled() {
        resetCalls += 1
        state_.value = MerchantCatalogState()
    }

    override suspend fun snapshot(): com.athar.core.domain.model.MerchantCatalogSnapshot? = null
}
