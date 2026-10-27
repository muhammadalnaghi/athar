package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import com.athar.core.domain.model.position.DeclaredPositionStatus
import com.athar.core.domain.repo.DeclaredPositionRepository
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.IncomeSourcePreferencesRepository
import com.athar.core.domain.repo.IncomeSourceSettings
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

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
    fun `goal nudge follows persisted targets and last three month averages`() = runTest(mainDispatcher) {
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(
                currentConfirmed = listOf(
                    transaction(id = "today-income", type = TxType.INCOME, amount = Money.of("1000")),
                    transaction(id = "today-expense", type = TxType.EXPENSE, amount = Money.of("250")),
                ),
                goalConfirmed = listOf(
                    transaction(id = "goal-income", type = TxType.INCOME, amount = Money.of("9000")),
                    transaction(id = "goal-expense", type = TxType.EXPENSE, amount = Money.of("6000")),
                ),
            ),
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.goalNudge).isNotNull()
        val nudge = state.goalNudge!!
        assertThat(nudge.savingsRatePercent).isEqualTo(java.math.BigDecimal("33.3"))
        assertThat(nudge.savingsRateTargetPercent).isEqualTo(30)
        assertThat(nudge.savingsRateProgress).isEqualTo(1f)
        assertThat(nudge.emergencyMonthsCovered).isEqualTo(java.math.BigDecimal("2.5"))
        assertThat(nudge.emergencyFundTargetMonths).isEqualTo(6)
        assertThat(nudge.emergencyFundProgress).isWithin(0.0001f).of(0.4167f)
    }

    @Test
    fun `explicit category corrections replace engine provenance with user`() = runTest(mainDispatcher) {
        val originals = listOf(CategorySource.CATALOG, CategorySource.CLASSIFIER).map { source ->
            transaction(id = "corrected-${source.name}", type = TxType.EXPENSE, amount = Money.of("42"))
                .copy(categoryId = "cat-coffee", categorySource = source)
        }
        val transactions = FakeTransactionRepository(currentConfirmed = originals)
        val viewModel = TodayViewModel(
            transactions = transactions,
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )
        originals.forEach { original ->
            viewModel.updateTransaction(original.copy(categoryId = "cat-restaurant"), learnRule = false)
        }
        advanceUntilIdle()
        assertThat(transactions.upserts).hasSize(originals.size)
        transactions.upserts.forEach { corrected ->
            assertThat(corrected.categorySource).isEqualTo(CategorySource.USER)
            assertThat(corrected.categoryId).isEqualTo("cat-restaurant")
            assertThat(corrected.status).isEqualTo(TxStatus.CONFIRMED)
            assertThat(corrected.amount).isEqualTo(Money.of("42"))
        }
    }

    @Test
    fun `note-only edit keeps the original catalog provenance`() = runTest(mainDispatcher) {
        val original = transaction(id = "note-only", type = TxType.EXPENSE, amount = Money.of("42"))
            .copy(categoryId = "cat-coffee", categorySource = CategorySource.CATALOG)
        val transactions = FakeTransactionRepository(currentConfirmed = listOf(original))
        val viewModel = TodayViewModel(
            transactions = transactions,
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )
        viewModel.updateTransaction(original.copy(notes = "Synthetic note"), learnRule = false)
        advanceUntilIdle()
        assertThat(transactions.upserts.single().categorySource).isEqualTo(CategorySource.CATALOG)
    }

    @Test
    fun `always categorize emits backfill event and can be cleared`() = runTest(mainDispatcher) {
        val transactions = FakeTransactionRepository(backfillCount = 3)
        val rules = RecordingCategoryRuleRepository()
        val viewModel = TodayViewModel(
            transactions = transactions,
            rules = rules,
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        viewModel.updateTransaction(
            transaction(
                id = "hemmah",
                type = TxType.EXPENSE,
                amount = Money.of("42"),
            ).copy(
                merchant = "Hemmah",
                merchantNormalized = "hemmah",
                categoryId = "cat-home-maintenance",
                status = TxStatus.PENDING,
            ),
            learnRule = true,
        )
        advanceUntilIdle()

        assertThat(transactions.upserts.single().status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learned).containsExactly(
            LearnedRule(
                merchantNormalized = "hemmah",
                categoryId = "cat-home-maintenance",
                patternType = PatternType.SUBSTRING,
            ),
        )
        assertThat(transactions.appliedPatterns).containsExactly(
            "hemmah" to "cat-home-maintenance",
        )
        assertThat(viewModel.lastBackfill.value)
            .isEqualTo(TodayViewModel.BackfillEvent(pattern = "Hemmah", count = 3))

        viewModel.clearBackfill()

        assertThat(viewModel.lastBackfill.value).isNull()
    }

    @Test
    fun `always categorize skips rule and backfill for generic merchant labels`() = runTest(mainDispatcher) {
        val transactions = FakeTransactionRepository(backfillCount = 3)
        val rules = RecordingCategoryRuleRepository()
        val viewModel = TodayViewModel(
            transactions = transactions,
            rules = rules,
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        viewModel.updateTransaction(
            transaction(
                id = "generic-payment",
                type = TxType.EXPENSE,
                amount = Money.of("42"),
            ).copy(
                merchant = "Payment",
                merchantNormalized = "payment",
                categoryId = "cat-home-maintenance",
                status = TxStatus.PENDING,
            ),
            learnRule = true,
        )
        advanceUntilIdle()

        assertThat(transactions.upserts.single().status).isEqualTo(TxStatus.PENDING)
        assertThat(rules.learned).isEmpty()
        assertThat(transactions.appliedPatterns).isEmpty()
        assertThat(viewModel.lastBackfill.value).isNull()
    }

    @Test
    fun `state exposes readable category labels including archived categories`() = runTest(mainDispatcher) {
        val archivedCoffee = Fixtures.category(
            id = "cat-coffee",
            name = "Coffee",
            nameAr = "قهوة",
            kind = CategoryKind.EXPENSE,
        ).copy(archived = true)
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(
                currentConfirmed = listOf(
                    transaction(
                        id = "coffee",
                        type = TxType.EXPENSE,
                        amount = Money.of("18"),
                    ).copy(categoryId = "cat-coffee"),
                ),
            ),
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(listOf(archivedCoffee)),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.categoryLabels["cat-coffee"]).isEqualTo(CategoryLabel(name = "Coffee", nameAr = "قهوة"))
    }

    @Test
    fun `state suggests unambiguous confirmed category for pending same merchant`() = runTest(mainDispatcher) {
        val cafe = Fixtures.category(
            id = "cat-cafe",
            name = "Cafe",
            nameAr = "مقهى",
            kind = CategoryKind.EXPENSE,
        )
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(
                currentConfirmed = listOf(
                    transaction(id = "brew-1", type = TxType.EXPENSE, amount = Money.of("18")).copy(
                        merchant = "Brew Lab",
                        merchantNormalized = "brew lab",
                        categoryId = "cat-cafe",
                    ),
                    transaction(id = "brew-2", type = TxType.EXPENSE, amount = Money.of("20")).copy(
                        merchant = "Brew Lab",
                        merchantNormalized = "brew lab",
                        categoryId = "cat-cafe",
                    ),
                ),
                pending = listOf(
                    transaction(id = "pending-brew", type = TxType.EXPENSE, amount = Money.of("22")).copy(
                        merchant = "Brew Lab",
                        merchantNormalized = "brew lab",
                        categoryId = null,
                        status = TxStatus.PENDING,
                    ),
                ),
            ),
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(listOf(cafe)),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.pendingCategorySuggestions).containsEntry(
            "pending-brew",
            PendingCategorySuggestion(categoryId = "cat-cafe", useCount = 2),
        )
        assertThat(state.pendingCategorySuggestionSummary).containsExactly(
            PendingCategorySuggestionSummary(categoryId = "cat-cafe", count = 1),
        )
    }

    @Test
    fun `pending category suggestion summary groups and sorts by impact`() {
        val summary = buildPendingCategorySuggestionSummary(
            persistentMapOf(
                "pending-cafe-1" to PendingCategorySuggestion(categoryId = "cat-cafe", useCount = 4),
                "pending-grocery" to PendingCategorySuggestion(categoryId = "cat-grocery", useCount = 2),
                "pending-cafe-2" to PendingCategorySuggestion(categoryId = "cat-cafe", useCount = 4),
                "pending-bills" to PendingCategorySuggestion(categoryId = "cat-bills", useCount = 1),
            ),
        )

        assertThat(summary).containsExactly(
            PendingCategorySuggestionSummary(categoryId = "cat-cafe", count = 2),
            PendingCategorySuggestionSummary(categoryId = "cat-bills", count = 1),
            PendingCategorySuggestionSummary(categoryId = "cat-grocery", count = 1),
        ).inOrder()
    }

    @Test
    fun `pending category suggestions hide conflicting generic and inactive history`() {
        val cafe = Fixtures.category(
            id = "cat-cafe",
            name = "Cafe",
            nameAr = "مقهى",
            kind = CategoryKind.EXPENSE,
        )
        val grocery = Fixtures.category(
            id = "cat-grocery",
            name = "Grocery",
            nameAr = "تموين",
            kind = CategoryKind.EXPENSE,
        )
        val archived = Fixtures.category(
            id = "cat-archived",
            name = "Old",
            nameAr = "قديم",
            kind = CategoryKind.EXPENSE,
        ).copy(archived = true)

        val suggestions = buildPendingCategorySuggestions(
            pending = listOf(
                transaction(id = "pending-conflict", type = TxType.EXPENSE, amount = Money.of("22")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
                transaction(id = "pending-generic", type = TxType.EXPENSE, amount = Money.of("12")).copy(
                    merchant = "Bank",
                    merchantNormalized = "bank",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
                transaction(id = "pending-arabic-generic", type = TxType.EXPENSE, amount = Money.of("11")).copy(
                    merchant = "كاش",
                    merchantNormalized = "كاش",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
                transaction(id = "pending-archived", type = TxType.EXPENSE, amount = Money.of("10")).copy(
                    merchant = "Old Shop",
                    merchantNormalized = "old shop",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
            ),
            allTransactions = listOf(
                transaction(id = "brew-cafe", type = TxType.EXPENSE, amount = Money.of("18")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = "cat-cafe",
                ),
                transaction(id = "brew-grocery", type = TxType.EXPENSE, amount = Money.of("20")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = "cat-grocery",
                ),
                transaction(id = "bank-cafe", type = TxType.EXPENSE, amount = Money.of("12")).copy(
                    merchant = "Bank",
                    merchantNormalized = "bank",
                    categoryId = "cat-cafe",
                ),
                transaction(id = "arabic-cash-cafe", type = TxType.EXPENSE, amount = Money.of("11")).copy(
                    merchant = "كاش",
                    merchantNormalized = "كاش",
                    categoryId = "cat-cafe",
                ),
                transaction(id = "old-archived", type = TxType.EXPENSE, amount = Money.of("10")).copy(
                    merchant = "Old Shop",
                    merchantNormalized = "old shop",
                    categoryId = "cat-archived",
                ),
            ),
            activeCategories = listOf(cafe, grocery, archived).filterNot { it.archived },
        )

        assertThat(suggestions).doesNotContainKey("pending-conflict")
        assertThat(suggestions).doesNotContainKey("pending-generic")
        assertThat(suggestions).doesNotContainKey("pending-arabic-generic")
        assertThat(suggestions).doesNotContainKey("pending-archived")
    }

    @Test
    fun `state exposes repeated backlog nudge using history repeated backlog rules`() = runTest(mainDispatcher) {
        val rows = listOf(
            transaction(id = "coffee-a", type = TxType.EXPENSE, amount = Money.of("18")).copy(
                merchant = "Brew Lab",
                merchantNormalized = "brew lab",
                categoryId = null,
                status = TxStatus.PENDING,
            ),
            transaction(id = "coffee-b", type = TxType.EXPENSE, amount = Money.of("21")).copy(
                merchant = "Brew Lab",
                merchantNormalized = "brew lab",
                categoryId = null,
                status = TxStatus.DISMISSED,
            ),
            transaction(id = "market-a", type = TxType.EXPENSE, amount = Money.of("40")).copy(
                merchant = "Corner Market",
                merchantNormalized = "corner market",
                categoryId = null,
                status = TxStatus.PENDING,
            ),
            transaction(id = "market-b", type = TxType.EXPENSE, amount = Money.of("41")).copy(
                merchant = "Corner Market",
                merchantNormalized = "corner market",
                categoryId = null,
                status = TxStatus.PENDING,
            ),
            transaction(id = "market-c", type = TxType.EXPENSE, amount = Money.of("42")).copy(
                merchant = "Corner Market",
                merchantNormalized = "corner market",
                categoryId = null,
                status = TxStatus.PENDING,
            ),
            transaction(id = "generic-a", type = TxType.EXPENSE, amount = Money.of("10")).copy(
                merchant = "Payment",
                merchantNormalized = "payment",
                categoryId = null,
                status = TxStatus.PENDING,
            ),
            transaction(id = "generic-b", type = TxType.EXPENSE, amount = Money.of("11")).copy(
                merchant = "Payment",
                merchantNormalized = "payment",
                categoryId = null,
                status = TxStatus.PENDING,
            ),
            transaction(id = "categorized", type = TxType.EXPENSE, amount = Money.of("12")).copy(
                merchant = "Brew Lab",
                merchantNormalized = "brew lab",
                categoryId = "cat-cafe",
            ),
        )
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(
                currentConfirmed = rows.filter { it.status == TxStatus.CONFIRMED },
                pending = rows.filter { it.status == TxStatus.PENDING },
                dismissed = rows.filter { it.status == TxStatus.DISMISSED },
            ),
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        val state = viewModel.state.first { !it.isLoading }
        val historyRepeatedRows = filterHistoryTransactions(
            all = rows,
            query = "",
            status = HistoryStatusFilter.ALL,
            type = HistoryTypeFilter.ALL,
            source = HistorySourceFilter.ALL,
            category = HistoryCategoryFilter.REPEATED_UNCATEGORIZED,
        )

        assertThat(historyRepeatedRows.map { it.id })
            .containsExactly("market-a", "market-b", "market-c", "coffee-a", "coffee-b")
            .inOrder()
        assertThat(state.repeatedBacklogNudge).isEqualTo(
            TodayRepeatedBacklogNudge(
                groupCount = 2,
                transactionCount = historyRepeatedRows.size,
                largestGroupCount = 3,
            ),
        )
    }

    @Test
    fun `apply pending category suggestion confirms one row without learning a rule`() = runTest(mainDispatcher) {
        val cafe = Fixtures.category(
            id = "cat-cafe",
            name = "Cafe",
            nameAr = "مقهى",
            kind = CategoryKind.EXPENSE,
        )
        val transactions = FakeTransactionRepository(
            currentConfirmed = listOf(
                transaction(id = "brew-confirmed", type = TxType.EXPENSE, amount = Money.of("18")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = "cat-cafe",
                ),
            ),
            pending = listOf(
                transaction(id = "pending-brew", type = TxType.EXPENSE, amount = Money.of("22")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
            ),
        )
        val rules = RecordingCategoryRuleRepository()
        val viewModel = TodayViewModel(
            transactions = transactions,
            rules = rules,
            categories = TodayFakeCategoryRepository(listOf(cafe)),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )
        viewModel.state.first { !it.isLoading }

        viewModel.onEvent(TodayEvent.ApplyPendingCategorySuggestion("pending-brew"))
        advanceUntilIdle()

        assertThat(transactions.upserts.single()).isEqualTo(
            transaction(id = "pending-brew", type = TxType.EXPENSE, amount = Money.of("22")).copy(
                merchant = "Brew Lab",
                merchantNormalized = "brew lab",
                categoryId = "cat-cafe",
                // WM033: applying a category never certifies the payment.
                status = TxStatus.PENDING,
                categorySource = CategorySource.USER,
                updatedAt = FixedClock.now(),
            ),
        )
        assertThat(rules.learned).isEmpty()
    }

    @Test
    fun `bulk apply pending category suggestions confirms only safe rows and reports count`() = runTest(mainDispatcher) {
        val cafe = Fixtures.category(
            id = "cat-cafe",
            name = "Cafe",
            nameAr = "مقهى",
            kind = CategoryKind.EXPENSE,
        )
        val grocery = Fixtures.category(
            id = "cat-grocery",
            name = "Grocery",
            nameAr = "تموين",
            kind = CategoryKind.EXPENSE,
        )
        val transactions = FakeTransactionRepository(
            currentConfirmed = listOf(
                transaction(id = "brew-confirmed", type = TxType.EXPENSE, amount = Money.of("18")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = "cat-cafe",
                ),
                transaction(id = "tamimi-confirmed", type = TxType.EXPENSE, amount = Money.of("90")).copy(
                    merchant = "Tamimi",
                    merchantNormalized = "tamimi",
                    categoryId = "cat-grocery",
                ),
                transaction(id = "mixed-cafe", type = TxType.EXPENSE, amount = Money.of("18")).copy(
                    merchant = "Mixed Shop",
                    merchantNormalized = "mixed shop",
                    categoryId = "cat-cafe",
                ),
                transaction(id = "mixed-grocery", type = TxType.EXPENSE, amount = Money.of("22")).copy(
                    merchant = "Mixed Shop",
                    merchantNormalized = "mixed shop",
                    categoryId = "cat-grocery",
                ),
            ),
            pending = listOf(
                transaction(id = "pending-brew", type = TxType.EXPENSE, amount = Money.of("22")).copy(
                    merchant = "Brew Lab",
                    merchantNormalized = "brew lab",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
                transaction(id = "pending-tamimi", type = TxType.EXPENSE, amount = Money.of("40")).copy(
                    merchant = "Tamimi",
                    merchantNormalized = "tamimi",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
                transaction(id = "pending-mixed", type = TxType.EXPENSE, amount = Money.of("30")).copy(
                    merchant = "Mixed Shop",
                    merchantNormalized = "mixed shop",
                    categoryId = null,
                    status = TxStatus.PENDING,
                ),
            ),
        )
        val rules = RecordingCategoryRuleRepository()
        val viewModel = TodayViewModel(
            transactions = transactions,
            rules = rules,
            categories = TodayFakeCategoryRepository(listOf(cafe, grocery)),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )
        val state = viewModel.state.first { !it.isLoading }
        assertThat(state.pendingCategorySuggestions.keys).containsExactly("pending-brew", "pending-tamimi")

        viewModel.onEvent(TodayEvent.BulkApplyPendingCategorySuggestions)
        advanceUntilIdle()

        assertThat(transactions.upserts.map { it.id to it.categoryId }).containsExactly(
            "pending-brew" to "cat-cafe",
            "pending-tamimi" to "cat-grocery",
        ).inOrder()
        // WM033: optional classification preserves both financial decisions.
        assertThat(transactions.upserts.map { it.status }).containsExactly(TxStatus.PENDING, TxStatus.PENDING)
        assertThat(viewModel.lastPendingSuggestionApply.value)
            .isEqualTo(TodayViewModel.PendingSuggestionApplyEvent(count = 2))
        assertThat(rules.learned).isEmpty()

        viewModel.clearPendingSuggestionApply()

        assertThat(viewModel.lastPendingSuggestionApply.value).isNull()
    }

    @Test
    fun `confirmed same-day transfer never becomes operating expense`() = runTest(mainDispatcher) {
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(
                currentConfirmed = listOf(
                    transaction(id = "today-income", type = TxType.INCOME, amount = Money.of("1000")),
                    transaction(id = "today-expense", type = TxType.EXPENSE, amount = Money.of("200")),
                    transaction(id = "today-transfer", type = TxType.TRANSFER, amount = Money.of("500")),
                ),
            ),
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.totalIncome.amount.toPlainString()).isEqualTo("1000")
        assertThat(state.totalExpense.amount.toPlainString()).isEqualTo("200")
        assertThat(state.netFlow.amount.toPlainString()).isEqualTo("800")
        assertThat(state.todayNet.amount.toPlainString()).isEqualTo("800")
        // The transfer is money movement, not consumption: it must stay out of the
        // operating totals yet remain visible in the Today feed.
        assertThat(state.today.map { it.id }).contains("today-transfer")
    }

    @Test
    fun `manual reconciliation rows never change operating totals`() = runTest(mainDispatcher) {
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(
                currentConfirmed = listOf(
                    transaction(id = "today-income", type = TxType.INCOME, amount = Money.of("1000")),
                    transaction(id = "today-expense", type = TxType.EXPENSE, amount = Money.of("200")),
                    transaction(id = "today-transfer", type = TxType.TRANSFER, amount = Money.of("500")),
                    transaction(id = "reconcile-income", type = TxType.INCOME, amount = Money.of("99000"))
                        .copy(sourceRefId = "reconcile-2026-06-13"),
                    transaction(id = "reconcile-expense", type = TxType.EXPENSE, amount = Money.of("43000"))
                        .copy(sourceRefId = "reconcile-2026-06-13"),
                ),
            ),
            rules = RecordingCategoryRuleRepository(),
            categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")),
            clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )

        val state = viewModel.state.first { !it.isLoading }

        // Reconciliation adjustments only true-up balances; they never become income/expense.
        assertThat(state.totalIncome.amount.toPlainString()).isEqualTo("1000")
        assertThat(state.totalExpense.amount.toPlainString()).isEqualTo("200")
        assertThat(state.netFlow.amount.toPlainString()).isEqualTo("800")
        assertThat(state.todayNet.amount.toPlainString()).isEqualTo("800")
        // Feeds keep full confirmed rows: reconciliations stay visible evidence.
        assertThat(state.today.map { it.id }).containsExactly(
            "today-income",
            "today-expense",
            "today-transfer",
            "reconcile-income",
            "reconcile-expense",
        ).inOrder()
    }
    @Test
    fun `foreign currency stays visible but never becomes a one to one display currency total`() = runTest(mainDispatcher) {
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(currentConfirmed = listOf(
                transaction(id = "sar-income", type = TxType.INCOME, amount = Money.of("1000")),
                transaction(id = "sar-expense", type = TxType.EXPENSE, amount = Money.of("200")),
                transaction(id = "usd-expense", type = TxType.EXPENSE, amount = Money.of("90000", "USD")),
            )),
            rules = RecordingCategoryRuleRepository(), categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")), clock = FixedClock, declaredPositions = EmptyDeclaredPositions,
        )
        val state = viewModel.state.first { !it.isLoading }
        assertThat(state.totalIncome.amount.toPlainString()).isEqualTo("1000")
        assertThat(state.totalExpense.amount.toPlainString()).isEqualTo("200")
        assertThat(state.netFlow.amount.toPlainString()).isEqualTo("800")
        assertThat(state.todayNet.amount.toPlainString()).isEqualTo("800")
        assertThat(state.totalExpense.currency).isEqualTo("SAR")
        assertThat(state.today.map { it.id }).contains("usd-expense")
    }

    @Test
    fun `saved declared position stays separate from recorded account money and spending`() = runTest(mainDispatcher) {
        val position = DeclaredPositionSnapshot(
            id = "position", title = "Synthetic position", revision = 2L,
            asOfDate = "2026-10-24", updatedAtEpochMillis = 1L,
            status = DeclaredPositionStatus.USER_DECLARED, hasUnstructuredOwnershipLabels = false,
            entryCount = 1, assetsByCurrency = emptyMap(),
            liabilitiesByCurrency = mapOf("SAR" to "1000.02"), netByCurrency = mapOf("SAR" to "-1000.02"),
        )
        val viewModel = TodayViewModel(
            transactions = FakeTransactionRepository(currentConfirmed = listOf(
                transaction(id = "expense", type = TxType.EXPENSE, amount = Money.of("42")),
            )),
            rules = RecordingCategoryRuleRepository(), categories = TodayFakeCategoryRepository(),
            prefs = FakeUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
            incomeSources = FakeIncomeSourcePreferencesRepository(),
            accounts = FakeAccountRepository(liquidBalance = Money.of("5000")), clock = FixedClock,
            declaredPositions = object : DeclaredPositionRepository {
                override fun observeSnapshots() = flowOf(listOf(position))
            },
        )
        val state = viewModel.state.first { !it.isLoading }
        assertThat(state.declaredPosition?.net).isEqualTo(Money.of("-1000.02"))
        assertThat(state.declaredPosition?.revision).isEqualTo(2L)
        assertThat(state.totalExpense).isEqualTo(Money.of("42"))
        assertThat(state.netWorth).isEqualTo(Money.of("5000"))
    }
}

private object EmptyDeclaredPositions : DeclaredPositionRepository {
    override fun observeSnapshots(): Flow<List<DeclaredPositionSnapshot>> = flowOf(emptyList())
}

private class FakeTransactionRepository(
    private val currentConfirmed: List<Transaction> = emptyList(),
    private val goalConfirmed: List<Transaction> = emptyList(),
    private val pending: List<Transaction> = emptyList(),
    private val dismissed: List<Transaction> = emptyList(),
    private val backfillCount: Int = 0,
) : TransactionRepository {
    val upserts = mutableListOf<Transaction>()
    val appliedPatterns = mutableListOf<Pair<String, String>>()

    override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> {
        val rows = when (period) {
            is Period.Last -> goalConfirmed
            else -> when (status) {
                TxStatus.CONFIRMED -> currentConfirmed
                TxStatus.DISMISSED -> dismissed
                else -> currentConfirmed + dismissed
            }
        }
        return flowOf(rows.filter { status == null || it.status == status })
    }

    override fun observePending(): Flow<List<Transaction>> = flowOf(pending)
    override fun observeAll(): Flow<List<Transaction>> = flowOf(currentConfirmed + goalConfirmed + pending + dismissed)
    override suspend fun get(id: String): Transaction? =
        upserts.lastOrNull { it.id == id }
            ?: (currentConfirmed + goalConfirmed + pending + dismissed).firstOrNull { it.id == id }
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
        return backfillCount
    }
}

private class RecordingCategoryRuleRepository : CategoryRuleRepository {
    val learned = mutableListOf<LearnedRule>()

    override fun observeAll(): Flow<List<CategoryRule>> = flowOf(emptyList())
    override suspend fun findMatching(merchantNormalized: String): List<CategoryRule> = emptyList()
    override suspend fun upsert(rule: CategoryRule) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun learnFromCorrection(
        merchantNormalized: String,
        categoryId: String,
        patternType: PatternType,
    ): CategoryRule {
        learned += LearnedRule(merchantNormalized, categoryId, patternType)
        return CategoryRule(
            id = "learned-$merchantNormalized",
            pattern = merchantNormalized,
            patternType = patternType,
            categoryId = categoryId,
            priority = 100,
            learnedFromUser = true,
            createdAt = FixedClock.now(),
        )
    }
}

private data class LearnedRule(
    val merchantNormalized: String,
    val categoryId: String,
    val patternType: PatternType,
)

private class TodayFakeCategoryRepository(
    private val categories: List<Category> = emptyList(),
) : CategoryRepository {
    override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> =
        flowOf(
            categories.filter { category ->
                (kind == null || category.kind == kind) && (includeArchived || !category.archived)
            },
        )

    override suspend fun get(id: String): Category? = categories.firstOrNull { it.id == id }
    override suspend fun upsert(category: Category) = Unit
    override suspend fun archive(id: String) = Unit
    override suspend fun reorder(ids: List<String>) = Unit
}


private class FakeIncomeSourcePreferencesRepository(
    private val senders: Set<String> = emptySet(),
) : IncomeSourcePreferencesRepository {
    override fun settings() = kotlinx.coroutines.flow.flowOf(IncomeSourceSettings(senders))
    override suspend fun update(settings: IncomeSourceSettings) = Unit
}

private class FakeUserPreferencesRepository(
    private val savingsTarget: Int,
    private val emergencyMonths: Int,
) : UserPreferencesRepository {
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
    override fun savingsRateTargetPercent(): Flow<Int> = flowOf(savingsTarget)
    override suspend fun setSavingsRateTargetPercent(percent: Int) = Unit
    override fun emergencyFundTargetMonths(): Flow<Int> = flowOf(emergencyMonths)
    override suspend fun setEmergencyFundTargetMonths(months: Int) = Unit
    override fun billRemindersEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setBillRemindersEnabled(enabled: Boolean) = Unit
    override fun billReminderSentKeys(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun setBillReminderSentKeys(keys: Set<String>) = Unit
}

private class FakeAccountRepository(
    private val liquidBalance: Money,
) : AccountRepository {
    private val account = Fixtures.account(
        id = "checking",
        type = AccountType.CHECKING,
        openingBalance = Money.zero(),
    )
    private val balance = AccountBalance(account = account, current = liquidBalance)
    private val netWorth = NetWorth(
        total = liquidBalance,
        byCurrency = mapOf(liquidBalance.currency to liquidBalance),
        accounts = listOf(balance),
    )

    override fun observeActive(): Flow<List<Account>> = flowOf(listOf(account))
    override fun observeAll(includeArchived: Boolean): Flow<List<Account>> = flowOf(listOf(account))
    override suspend fun get(id: String): Account? = account.takeIf { it.id == id }
    override suspend fun upsert(account: Account) = Unit
    override suspend fun setArchived(id: String, archived: Boolean) = Unit
    override suspend fun delete(id: String) = Unit
    override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> = flowOf(netWorth)
    override fun observeBalances(): Flow<List<AccountBalance>> = flowOf(listOf(balance))
    override suspend fun resolveForIngest(sender: String, body: String, counterparty: String?): Account? = null
    override suspend fun reconcile(accountId: String, target: Money, label: String, note: String?): ReconcileResult =
        ReconcileResult.Failed("Not used")
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-06-13T12:00:00Z")
}

private fun transaction(
    id: String,
    type: TxType,
    amount: Money,
    date: LocalDate = LocalDate(2026, 6, 13),
): Transaction = Fixtures.transaction(
    id = id,
    amount = amount,
    merchant = id,
    status = TxStatus.CONFIRMED,
).copy(
    type = type,
    date = date,
    source = IngestSource.MANUAL,
)
