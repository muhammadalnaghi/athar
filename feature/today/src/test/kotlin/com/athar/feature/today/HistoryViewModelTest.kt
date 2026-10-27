package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.TransactionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

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
    fun `bulk category assignment confirms compatible selected rows and skips transfers`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(id = "expense", type = TxType.EXPENSE, status = TxStatus.PENDING),
                tx(id = "transfer", type = TxType.TRANSFER, status = TxStatus.PENDING),
            ),
        )
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = HistoryFakeRuleRepository(),
            categories = HistoryFakeCategoryRepository(
                listOf(
                    category(id = "cat-food", kind = CategoryKind.EXPENSE),
                    category(id = "cat-salary", kind = CategoryKind.INCOME),
                ),
            ),
            clock = FixedHistoryClock,
        )

        viewModel.toggleSelected("expense")
        viewModel.toggleSelected("transfer")
        viewModel.applyBulkCategory("cat-food")
        advanceUntilIdle()

        assertThat(txRepo.upserts.map { it.id }).containsExactly("expense")
        val updated = txRepo.upserts.single()
        assertThat(updated.categoryId).isEqualTo("cat-food")
        assertThat(updated.status).isEqualTo(TxStatus.PENDING)
        assertThat(updated.updatedAt).isEqualTo(FixedHistoryClock.now())
        assertThat(viewModel.lastBulkCategory.value).isEqualTo(
            HistoryViewModel.BulkCategoryEvent(applied = 1, skipped = 1),
        )
        assertThat(viewModel.selectionMode.value).isFalse()
        assertThat(viewModel.selectedIds.value).isEmpty()
    }

    @Test
    fun `always categorize skips rule and backfill for generic merchant labels`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(
                    id = "generic-payment",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchant = "Payment",
                    merchantNormalized = "payment",
                ),
            ),
        )
        val ruleRepo = HistoryFakeRuleRepository()
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = ruleRepo,
            categories = HistoryFakeCategoryRepository(
                listOf(category(id = "cat-food", kind = CategoryKind.EXPENSE)),
            ),
            clock = FixedHistoryClock,
        )

        viewModel.updateTransaction(
            tx(
                id = "generic-payment",
                type = TxType.EXPENSE,
                status = TxStatus.PENDING,
                categoryId = "cat-food",
                merchant = "Payment",
                merchantNormalized = "payment",
            ),
            learnRule = true,
        )
        advanceUntilIdle()

        assertThat(txRepo.upserts.single().status).isEqualTo(TxStatus.PENDING)
        assertThat(ruleRepo.learnedRules).isEmpty()
        assertThat(viewModel.lastBackfill.value).isNull()
    }

    @Test
    fun `select visible rows selects the currently filtered rows only`() = runTest(mainDispatcher) {
        val viewModel = HistoryViewModel(
            transactions = HistoryFakeTransactionRepository(
                listOf(
                    tx(id = "expense-a", type = TxType.EXPENSE, status = TxStatus.PENDING),
                    tx(id = "income", type = TxType.INCOME, status = TxStatus.PENDING),
                    tx(id = "expense-b", type = TxType.EXPENSE, status = TxStatus.CONFIRMED),
                ),
            ),
            rules = HistoryFakeRuleRepository(),
            categories = HistoryFakeCategoryRepository(emptyList()),
            clock = FixedHistoryClock,
        )
        val collection = launch { viewModel.items.collect {} }

        viewModel.setType(HistoryTypeFilter.EXPENSE)
        advanceUntilIdle()
        viewModel.selectVisibleRows()

        assertThat(viewModel.selectionMode.value).isTrue()
        assertThat(viewModel.selectedIds.value).containsExactly("expense-a", "expense-b")

        collection.cancel()
    }

    @Test
    fun `select matching selected merchants selects visible rows sharing normalized merchant`() = runTest(mainDispatcher) {
        val viewModel = HistoryViewModel(
            transactions = HistoryFakeTransactionRepository(
                listOf(
                    tx(
                        id = "coffee-a",
                        type = TxType.EXPENSE,
                        status = TxStatus.PENDING,
                        merchantNormalized = "Coffee Shop",
                    ),
                    tx(
                        id = "coffee-b",
                        type = TxType.EXPENSE,
                        status = TxStatus.CONFIRMED,
                        merchantNormalized = "coffee shop",
                    ),
                    tx(
                        id = "coffee-income",
                        type = TxType.INCOME,
                        status = TxStatus.CONFIRMED,
                        merchantNormalized = "coffee shop",
                    ),
                    tx(
                        id = "grocery",
                        type = TxType.EXPENSE,
                        status = TxStatus.CONFIRMED,
                        merchantNormalized = "grocery",
                    ),
                ),
            ),
            rules = HistoryFakeRuleRepository(),
            categories = HistoryFakeCategoryRepository(emptyList()),
            clock = FixedHistoryClock,
        )
        val collection = launch { viewModel.items.collect {} }

        viewModel.setType(HistoryTypeFilter.EXPENSE)
        advanceUntilIdle()
        viewModel.toggleSelected("coffee-a")
        viewModel.selectMatchingSelectedMerchants()

        assertThat(viewModel.selectionMode.value).isTrue()
        assertThat(viewModel.selectedIds.value).containsExactly("coffee-a", "coffee-b")

        collection.cancel()
    }

    @Test
    fun `select top repeated backlog group selects largest visible merchant group`() = runTest(mainDispatcher) {
        val viewModel = HistoryViewModel(
            transactions = HistoryFakeTransactionRepository(
                listOf(
                    tx(
                        id = "coffee-a",
                        type = TxType.EXPENSE,
                        status = TxStatus.PENDING,
                        merchantNormalized = "coffee shop",
                    ),
                    tx(
                        id = "tea-a",
                        type = TxType.EXPENSE,
                        status = TxStatus.PENDING,
                        merchantNormalized = "tea shop",
                    ),
                    tx(
                        id = "coffee-b",
                        type = TxType.EXPENSE,
                        status = TxStatus.PENDING,
                        merchantNormalized = "coffee shop",
                    ),
                    tx(
                        id = "tea-b",
                        type = TxType.EXPENSE,
                        status = TxStatus.PENDING,
                        merchantNormalized = "tea shop",
                    ),
                    tx(
                        id = "tea-c",
                        type = TxType.EXPENSE,
                        status = TxStatus.PENDING,
                        merchantNormalized = "tea shop",
                    ),
                ),
            ),
            rules = HistoryFakeRuleRepository(),
            categories = HistoryFakeCategoryRepository(emptyList()),
            clock = FixedHistoryClock,
        )
        val collection = launch {
            viewModel.items.collect {}
        }

        viewModel.setCategory(HistoryCategoryFilter.REPEATED_UNCATEGORIZED)
        advanceUntilIdle()
        viewModel.selectTopRepeatedBacklogGroup()

        assertThat(viewModel.selectionMode.value).isTrue()
        assertThat(viewModel.selectedIds.value).containsExactly("tea-a", "tea-b", "tea-c")

        collection.cancel()
    }

    @Test
    fun `bulk category assignment learns one exact rule for repeated selected merchant`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(
                    id = "coffee-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "Coffee Shop",
                ),
                tx(
                    id = "coffee-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
            ),
        )
        val ruleRepo = HistoryFakeRuleRepository()
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = ruleRepo,
            categories = HistoryFakeCategoryRepository(
                listOf(category(id = "cat-food", kind = CategoryKind.EXPENSE)),
            ),
            clock = FixedHistoryClock,
        )

        viewModel.toggleSelected("coffee-a")
        viewModel.toggleSelected("coffee-b")
        viewModel.applyBulkCategory("cat-food")
        advanceUntilIdle()

        assertThat(ruleRepo.learnedRules).hasSize(1)
        val rule = ruleRepo.learnedRules.single()
        assertThat(rule.pattern).isEqualTo("coffee shop")
        assertThat(rule.patternType).isEqualTo(PatternType.EXACT)
        assertThat(rule.categoryId).isEqualTo("cat-food")
        assertThat(viewModel.lastBulkCategory.value).isEqualTo(
            HistoryViewModel.BulkCategoryEvent(
                applied = 2,
                skipped = 0,
                exactRuleLearned = true,
            ),
        )
    }

    @Test
    fun `bulk category assignment does not learn exact rule for mixed selected merchants`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(
                    id = "coffee",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "grocery",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "grocery",
                ),
            ),
        )
        val ruleRepo = HistoryFakeRuleRepository()
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = ruleRepo,
            categories = HistoryFakeCategoryRepository(
                listOf(category(id = "cat-food", kind = CategoryKind.EXPENSE)),
            ),
            clock = FixedHistoryClock,
        )

        viewModel.toggleSelected("coffee")
        viewModel.toggleSelected("grocery")
        viewModel.applyBulkCategory("cat-food")
        advanceUntilIdle()

        assertThat(ruleRepo.learnedRules).isEmpty()
        assertThat(viewModel.lastBulkCategory.value).isEqualTo(
            HistoryViewModel.BulkCategoryEvent(applied = 2, skipped = 0),
        )
    }

    @Test
    fun `top repeated suggestion apply keeps cleanup mode open for next group`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(
                    id = "tea-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-c",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "coffee-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "coffee-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "tea-old",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-cafe",
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "coffee-old",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-groceries",
                    merchantNormalized = "coffee shop",
                ),
            ),
        )
        val ruleRepo = HistoryFakeRuleRepository()
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = ruleRepo,
            categories = HistoryFakeCategoryRepository(
                listOf(
                    category(id = "cat-cafe", kind = CategoryKind.EXPENSE),
                    category(id = "cat-groceries", kind = CategoryKind.EXPENSE),
                ),
            ),
            clock = FixedHistoryClock,
        )
        val itemsCollection = launch { viewModel.items.collect {} }
        val bulkCollection = launch { viewModel.bulkCategoryState.collect {} }

        viewModel.setCategory(HistoryCategoryFilter.REPEATED_UNCATEGORIZED)
        viewModel.toggleSelectionMode()
        advanceUntilIdle()

        assertThat(viewModel.bulkCategoryState.value.topRepeatedSuggestedCategory?.id)
            .isEqualTo("cat-cafe")

        viewModel.applyTopRepeatedBacklogSuggestedCategory()
        advanceUntilIdle()

        assertThat(txRepo.upserts.map { it.id }).containsExactly("tea-a", "tea-b", "tea-c")
        assertThat(txRepo.upserts.map { it.categoryId }.distinct()).containsExactly("cat-cafe")
        assertThat(ruleRepo.learnedRules.map { it.pattern }).containsExactly("tea shop")
        assertThat(viewModel.lastBulkCategory.value).isEqualTo(
            HistoryViewModel.BulkCategoryEvent(
                applied = 3,
                skipped = 0,
                exactRuleLearned = true,
            ),
        )
        assertThat(viewModel.selectionMode.value).isTrue()
        assertThat(viewModel.selectedIds.value).isEmpty()
        assertThat(viewModel.items.value.map { it.id }).containsExactly("coffee-a", "coffee-b").inOrder()
        assertThat(viewModel.bulkCategoryState.value.topRepeatedSuggestedCategory?.id)
            .isEqualTo("cat-groceries")

        itemsCollection.cancel()
        bulkCollection.cancel()
    }

    @Test
    fun `safe repeated suggestions apply multiple groups and leave conflicts`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(
                    id = "tea-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-c",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "coffee-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "coffee-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "book-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "book shop",
                ),
                tx(
                    id = "book-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "book shop",
                ),
                tx(
                    id = "tea-old",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-cafe",
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "coffee-old",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-groceries",
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "book-old-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-cafe",
                    merchantNormalized = "book shop",
                ),
                tx(
                    id = "book-old-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-groceries",
                    merchantNormalized = "book shop",
                ),
            ),
        )
        val ruleRepo = HistoryFakeRuleRepository()
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = ruleRepo,
            categories = HistoryFakeCategoryRepository(
                listOf(
                    category(id = "cat-cafe", kind = CategoryKind.EXPENSE),
                    category(id = "cat-groceries", kind = CategoryKind.EXPENSE),
                ),
            ),
            clock = FixedHistoryClock,
        )
        val itemsCollection = launch { viewModel.items.collect {} }
        val bulkCollection = launch { viewModel.bulkCategoryState.collect {} }

        viewModel.setCategory(HistoryCategoryFilter.REPEATED_UNCATEGORIZED)
        viewModel.toggleSelectionMode()
        advanceUntilIdle()

        assertThat(viewModel.bulkCategoryState.value.safeRepeatedSuggestedGroupCount).isEqualTo(2)
        assertThat(viewModel.bulkCategoryState.value.safeRepeatedSuggestedTransactionCount).isEqualTo(5)
        assertThat(viewModel.bulkCategoryState.value.safeRepeatedSuggestedCategory?.id).isEqualTo("cat-cafe")
        assertThat(viewModel.bulkCategoryState.value.safeRepeatedSuggestedCategoryTransactionCount).isEqualTo(3)
        assertThat(viewModel.bulkCategoryState.value.canApplySingleSafeRepeatedSuggestedCategory).isFalse()
        assertThat(viewModel.bulkCategoryState.value.canApplySafeRepeatedSuggestedCategories).isTrue()

        viewModel.applySafeRepeatedBacklogSuggestedCategories()
        advanceUntilIdle()

        val updatedById = txRepo.upserts.associateBy { it.id }
        assertThat(updatedById.keys).containsExactly("tea-a", "tea-b", "tea-c", "coffee-a", "coffee-b")
        assertThat(updatedById.getValue("tea-a").categoryId).isEqualTo("cat-cafe")
        assertThat(updatedById.getValue("tea-b").categoryId).isEqualTo("cat-cafe")
        assertThat(updatedById.getValue("tea-c").categoryId).isEqualTo("cat-cafe")
        assertThat(updatedById.getValue("coffee-a").categoryId).isEqualTo("cat-groceries")
        assertThat(updatedById.getValue("coffee-b").categoryId).isEqualTo("cat-groceries")
        assertThat(ruleRepo.learnedRules.map { it.pattern }).containsExactly("tea shop", "coffee shop")
        assertThat(viewModel.lastBulkCategory.value).isEqualTo(
            HistoryViewModel.BulkCategoryEvent(
                applied = 5,
                skipped = 0,
                exactRuleLearned = true,
            ),
        )
        assertThat(viewModel.selectionMode.value).isTrue()
        assertThat(viewModel.selectedIds.value).isEmpty()
        assertThat(viewModel.items.value.map { it.id }).containsExactly("book-a", "book-b").inOrder()
        assertThat(viewModel.bulkCategoryState.value.canApplySingleSafeRepeatedSuggestedCategory).isFalse()
        assertThat(viewModel.bulkCategoryState.value.canApplySafeRepeatedSuggestedCategories).isFalse()

        itemsCollection.cancel()
        bulkCollection.cancel()
    }

    @Test
    fun `single safe repeated suggestion applies lower group when top conflicts`() = runTest(mainDispatcher) {
        val txRepo = HistoryFakeTransactionRepository(
            listOf(
                tx(
                    id = "tea-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-c",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "coffee-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "coffee-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.PENDING,
                    merchantNormalized = "coffee shop",
                ),
                tx(
                    id = "tea-old-a",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-cafe",
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "tea-old-b",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-groceries",
                    merchantNormalized = "tea shop",
                ),
                tx(
                    id = "coffee-old",
                    type = TxType.EXPENSE,
                    status = TxStatus.CONFIRMED,
                    categoryId = "cat-cafe",
                    merchantNormalized = "coffee shop",
                ),
            ),
        )
        val ruleRepo = HistoryFakeRuleRepository()
        val viewModel = HistoryViewModel(
            transactions = txRepo,
            rules = ruleRepo,
            categories = HistoryFakeCategoryRepository(
                listOf(
                    category(id = "cat-cafe", kind = CategoryKind.EXPENSE),
                    category(id = "cat-groceries", kind = CategoryKind.EXPENSE),
                ),
            ),
            clock = FixedHistoryClock,
        )
        val itemsCollection = launch { viewModel.items.collect {} }
        val bulkCollection = launch { viewModel.bulkCategoryState.collect {} }

        viewModel.setCategory(HistoryCategoryFilter.REPEATED_UNCATEGORIZED)
        viewModel.toggleSelectionMode()
        advanceUntilIdle()

        val initialState = viewModel.bulkCategoryState.value
        assertThat(initialState.topRepeatedGroupCount).isEqualTo(3)
        assertThat(initialState.topRepeatedSuggestedCategory).isNull()
        assertThat(initialState.canApplyTopRepeatedSuggestedCategory).isFalse()
        assertThat(initialState.safeRepeatedSuggestedGroupCount).isEqualTo(1)
        assertThat(initialState.safeRepeatedSuggestedTransactionCount).isEqualTo(2)
        assertThat(initialState.safeRepeatedSuggestedCategory?.id).isEqualTo("cat-cafe")
        assertThat(initialState.safeRepeatedSuggestedCategoryTransactionCount).isEqualTo(2)
        assertThat(initialState.canApplySingleSafeRepeatedSuggestedCategory).isTrue()
        assertThat(initialState.canApplySafeRepeatedSuggestedCategories).isFalse()

        viewModel.applySafeRepeatedBacklogSuggestedCategories()
        advanceUntilIdle()

        val updatedById = txRepo.upserts.associateBy { it.id }
        assertThat(updatedById.keys).containsExactly("coffee-a", "coffee-b")
        assertThat(updatedById.getValue("coffee-a").categoryId).isEqualTo("cat-cafe")
        assertThat(updatedById.getValue("coffee-b").categoryId).isEqualTo("cat-cafe")
        assertThat(ruleRepo.learnedRules.map { it.pattern }).containsExactly("coffee shop")
        assertThat(viewModel.lastBulkCategory.value).isEqualTo(
            HistoryViewModel.BulkCategoryEvent(
                applied = 2,
                skipped = 0,
                exactRuleLearned = true,
            ),
        )
        assertThat(viewModel.selectionMode.value).isTrue()
        assertThat(viewModel.selectedIds.value).isEmpty()
        assertThat(viewModel.items.value.map { it.id }).containsExactly("tea-a", "tea-b", "tea-c").inOrder()
        assertThat(viewModel.bulkCategoryState.value.canApplySingleSafeRepeatedSuggestedCategory).isFalse()
        assertThat(viewModel.bulkCategoryState.value.canApplySafeRepeatedSuggestedCategories).isFalse()

        itemsCollection.cancel()
        bulkCollection.cancel()
    }
}

private class HistoryFakeTransactionRepository(rows: List<Transaction>) : TransactionRepository {
    private val rowsById = rows.associateBy { it.id }.toMutableMap()
    private val rowsFlow = MutableStateFlow(rowsById.values.toList())
    val upserts = mutableListOf<Transaction>()

    override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> =
        rowsFlow.map { rows -> rows.filter { status == null || it.status == status } }

    override fun observePending(): Flow<List<Transaction>> =
        rowsFlow.map { rows -> rows.filter { it.status == TxStatus.PENDING } }

    override fun observeAll(): Flow<List<Transaction>> = rowsFlow
    override suspend fun get(id: String): Transaction? = rowsById[id]
    override suspend fun upsertIngest(transaction: Transaction): String =
        error("Ingestion is outside this UI or import test fixture")

    override suspend fun upsert(transaction: Transaction) {
        rowsById[transaction.id] = transaction
        rowsFlow.value = rowsById.values.toList()
        upserts += transaction
    }

    override suspend fun delete(id: String) = Unit
    override suspend fun setStatus(id: String, status: TxStatus) = Unit
    override suspend fun clearPending(): Int = 0
    override suspend fun confirmAllConfident(minConfidence: Float): Int = 0
    override suspend fun dismissAllLowConfidence(maxConfidence: Float): Int = 0
    override suspend fun dismissAllPending(): Int = 0
    override suspend fun recoverDismissedToPending(): Int = 0
    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int = 0
}

private class HistoryFakeRuleRepository(
    private val existingRules: List<CategoryRule> = emptyList(),
) : CategoryRuleRepository {
    val learnedRules = mutableListOf<CategoryRule>()
    val deletedRuleIds = mutableListOf<String>()

    override fun observeAll(): Flow<List<CategoryRule>> = flowOf(existingRules + learnedRules)

    override suspend fun findMatching(merchantNormalized: String): List<CategoryRule> {
        val name = merchantNormalized.lowercase().trim()
        return (existingRules + learnedRules)
            .filter { rule ->
                when (rule.patternType) {
                    PatternType.EXACT -> rule.pattern.equals(name, ignoreCase = true)
                    PatternType.SUBSTRING -> name.contains(rule.pattern.lowercase().trim())
                    PatternType.REGEX -> runCatching { Regex(rule.pattern).containsMatchIn(name) }
                        .getOrDefault(false)
                }
            }
            .sortedByDescending { it.priority }
    }

    override suspend fun upsert(rule: CategoryRule) {
        learnedRules += rule
    }

    override suspend fun delete(id: String) {
        deletedRuleIds += id
        learnedRules.removeAll { it.id == id }
    }

    override suspend fun learnFromCorrection(
        merchantNormalized: String,
        categoryId: String,
        patternType: PatternType,
    ): CategoryRule = CategoryRule(
        id = "learned-${learnedRules.size + 1}",
        pattern = merchantNormalized,
        patternType = patternType,
        categoryId = categoryId,
        priority = 200,
        learnedFromUser = true,
        createdAt = FixedHistoryClock.now(),
    ).also { learnedRules += it }
}

private class HistoryFakeCategoryRepository(
    private val rows: List<Category>,
) : CategoryRepository {
    override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> = flowOf(
        rows.filter { (kind == null || it.kind == kind) && (includeArchived || !it.archived) },
    )

    override suspend fun get(id: String): Category? = rows.firstOrNull { it.id == id }
    override suspend fun upsert(category: Category) = Unit
    override suspend fun archive(id: String) = Unit
    override suspend fun reorder(ids: List<String>) = Unit
}

private object FixedHistoryClock : Clock {
    override fun now(): Instant = Instant.parse("2026-06-14T12:00:00Z")
}

private fun tx(
    id: String,
    type: TxType,
    status: TxStatus,
    categoryId: String? = null,
    merchant: String = id,
    merchantNormalized: String = id,
): Transaction = Transaction(
    id = id,
    accountId = "account",
    type = type,
    amount = Money.of("10"),
    date = LocalDate(2026, 6, 14),
    occurredAt = Instant.parse("2026-06-14T12:00:00Z"),
    merchant = merchant,
    merchantNormalized = merchantNormalized,
    categoryId = categoryId,
    notes = null,
    source = IngestSource.SMS,
    sourceRefId = "sms-$id",
    status = status,
    confidence = null,
    createdAt = Instant.parse("2026-06-14T12:00:00Z"),
    updatedAt = Instant.parse("2026-06-14T12:00:00Z"),
)

private fun category(id: String, kind: CategoryKind): Category = Category(
    id = id,
    name = id,
    nameAr = id,
    kind = kind,
    icon = null,
    monthlyTarget = null,
    archived = false,
    sortOrder = 0,
)
