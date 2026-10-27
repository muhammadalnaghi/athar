package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.ReceiptAttachment
import com.athar.core.domain.model.ReceiptAttachmentMeta
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.ReceiptAttachmentRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Regression tests for WM028-09: explicit manual financial recording must not be
 * gated on category assignment or merchant name. Amount > 0 remains the only
 * financial Save gate (ADR-010, Master Brief §2.2 amendment 2026-10-20).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModelTest {

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
    fun `manual save with blank merchant and no category records confirmed unassigned transaction`() = runTest(mainDispatcher) {
        val transactions = AddFakeTransactionRepository()
        val viewModel = viewModel(transactions)
        val saved = mutableListOf<AddTransactionResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { saved += it }
        }

        viewModel.onEvent(AddTransactionEvent.SetType(TxType.EXPENSE))
        viewModel.onEvent(AddTransactionEvent.SetAmount("200"))
        viewModel.onEvent(AddTransactionEvent.Save)
        advanceUntilIdle()

        assertThat(saved).containsExactly(AddTransactionResult.Saved)
        val row = transactions.upserts.single()
        assertThat(row.categoryId).isNull()
        assertThat(row.categorySource).isNull()
        assertThat(row.merchant).isEmpty()
        assertThat(row.merchantNormalized).isEmpty()
        assertThat(row.type).isEqualTo(TxType.EXPENSE)
        assertThat(row.amount).isEqualTo(Money.of(BigDecimal("200"), Money.SAR))
        assertThat(row.status).isEqualTo(TxStatus.CONFIRMED)
        assertThat(row.source).isEqualTo(IngestSource.MANUAL)
        assertThat(row.accountId).isEqualTo(MANUAL_ACCOUNT_ID)
        // No category learning fires from an unassigned save.
        assertThat(transactions.appliedPatterns).isEmpty()
    }

    @Test
    fun `assigned category is stored with user provenance`() = runTest(mainDispatcher) {
        val transactions = AddFakeTransactionRepository()
        val viewModel = viewModel(transactions)
        val saved = mutableListOf<AddTransactionResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { saved += it }
        }

        viewModel.onEvent(AddTransactionEvent.SetAmount("120"))
        viewModel.onEvent(AddTransactionEvent.SelectCategory("cat-groceries"))
        viewModel.onEvent(AddTransactionEvent.Save)
        advanceUntilIdle()

        assertThat(saved).containsExactly(AddTransactionResult.Saved)
        val row = transactions.upserts.single()
        assertThat(row.categoryId).isEqualTo("cat-groceries")
        assertThat(row.categorySource).isEqualTo(CategorySource.USER)
        assertThat(row.merchant).isEmpty()
        assertThat(row.status).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `non-positive amount still fails validation without saving`() = runTest(mainDispatcher) {
        val transactions = AddFakeTransactionRepository()
        val viewModel = viewModel(transactions)
        val saved = mutableListOf<AddTransactionResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { saved += it }
        }

        viewModel.onEvent(AddTransactionEvent.SetAmount("0"))
        viewModel.onEvent(AddTransactionEvent.Save)
        advanceUntilIdle()

        assertThat(saved).isEmpty()
        assertThat(transactions.upserts).isEmpty()
        assertThat(viewModel.state.value.validationError).isEqualTo(ValidationError.AMOUNT_INVALID)
    }

    private fun viewModel(
        transactions: AddFakeTransactionRepository,
    ): AddTransactionViewModel = AddTransactionViewModel(
        transactions = transactions,
        receipts = AddFakeReceiptAttachmentRepository(),
        categories = AddFakeCategoryRepository(
            listOf(
                Category(
                    id = "cat-groceries",
                    name = "Groceries",
                    nameAr = "بقالة",
                    kind = CategoryKind.EXPENSE,
                    icon = null,
                    monthlyTarget = null,
                    archived = false,
                    sortOrder = 0,
                ),
            ),
        ),
        prefs = AddFakeUserPreferencesRepository(),
        receiptTextRecognizer = unusedOcrRecognizer,
        clock = AddFixedClock,
    )

    /**
     * Receipt OCR is never exercised by these tests (no attachment is made), but the
     * ViewModel constructor requires the collaborator. Built over [StubContext] with
     * decoders that fail loudly if recognize() is ever reached.
     */
    private val unusedOcrRecognizer = ReceiptImageTextRecognizer(
        modelInstaller = OfflineOcrModelInstaller(context = StubContext),
        bitmapDecoder = { error("receipt OCR is not exercised by these tests") },
        engineFactory = { error("receipt OCR is not exercised by these tests") },
    )
}

private object AddFixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-06-13T12:00:00Z")
}

private class AddFakeTransactionRepository : TransactionRepository {
    val upserts = mutableListOf<Transaction>()
    val appliedPatterns = mutableListOf<Pair<String, String>>()

    override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> = flowOf(emptyList())
    override fun observePending(): Flow<List<Transaction>> = flowOf(emptyList())
    override fun observeAll(): Flow<List<Transaction>> = flowOf(emptyList())
    override suspend fun get(id: String): Transaction? = upserts.lastOrNull { it.id == id }
    override suspend fun upsertIngest(transaction: Transaction): String =
        error("Ingestion is outside this UI or import test fixture")

    override suspend fun upsert(transaction: Transaction) {
        upserts += transaction
    }
    override suspend fun delete(id: String) = Unit
    override suspend fun setStatus(id: String, status: TxStatus) = Unit
    override suspend fun clearPending(): Int = 0
    override suspend fun confirmAllConfident(minConfidence: Float): Int = 0
    override suspend fun dismissAllLowConfidence(maxConfidence: Float): Int = 0
    override suspend fun dismissAllPending(): Int = 0
    override suspend fun recoverDismissedToPending(): Int = 0
    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int {
        appliedPatterns += pattern to categoryId
        return 0
    }
}

private class AddFakeCategoryRepository(
    private val categories: List<Category>,
) : CategoryRepository {
    override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> =
        flowOf(categories.filter { category -> kind == null || category.kind == kind })

    override suspend fun get(id: String): Category? = categories.firstOrNull { it.id == id }
    override suspend fun upsert(category: Category) = Unit
    override suspend fun archive(id: String) = Unit
    override suspend fun reorder(ids: List<String>) = Unit
}

private class AddFakeReceiptAttachmentRepository : ReceiptAttachmentRepository {
    val saved = mutableListOf<ReceiptAttachment>()

    override suspend fun upsert(attachment: ReceiptAttachment) {
        saved += attachment
    }
    override suspend fun get(id: String): ReceiptAttachment? = saved.firstOrNull { it.id == id }
    override suspend fun getForTransaction(transactionId: String): ReceiptAttachment? =
        saved.firstOrNull { it.transactionId == transactionId }

    override suspend fun metadataListForTransaction(transactionId: String): List<ReceiptAttachmentMeta> = emptyList()
    override suspend fun metadataForTransaction(transactionId: String): ReceiptAttachmentMeta? = null
    override suspend fun delete(id: String) = Unit
    override suspend fun deleteForTransaction(transactionId: String) = Unit
}

private class AddFakeUserPreferencesRepository : UserPreferencesRepository {
    override fun onboardingComplete(): Flow<Boolean> = flowOf(true)
    override suspend fun setOnboardingComplete(complete: Boolean) = Unit
    override fun lastSmsBackfillEpochSeconds(): Flow<Long> = flowOf(0L)
    override suspend fun setLastSmsBackfillEpochSeconds(epoch: Long) = Unit
    override fun hijriEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setHijriEnabled(enabled: Boolean) = Unit
    override fun ownAccountNumbers(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setOwnAccountNumbers(numbers: List<String>) = Unit
    override fun displayCurrency(): Flow<String> = flowOf(Money.SAR)
    override suspend fun setDisplayCurrency(currency: String) = Unit
    override fun appLocale(): Flow<String> = flowOf("")
    override suspend fun setAppLocale(languageTag: String) = Unit
    override fun savingsRateTargetPercent(): Flow<Int> = flowOf(30)
    override suspend fun setSavingsRateTargetPercent(percent: Int) = Unit
    override fun emergencyFundTargetMonths(): Flow<Int> = flowOf(6)
    override suspend fun setEmergencyFundTargetMonths(months: Int) = Unit
    override fun billRemindersEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setBillRemindersEnabled(enabled: Boolean) = Unit
    override fun billReminderSentKeys(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun setBillReminderSentKeys(keys: Set<String>) = Unit
}
