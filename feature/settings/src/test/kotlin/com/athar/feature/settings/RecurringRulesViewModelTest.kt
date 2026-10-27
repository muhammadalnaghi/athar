package com.athar.feature.settings

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.RecurringRuleRepository
import com.athar.core.domain.repo.RecurringSuggestionRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class RecurringRulesViewModelTest {

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
    fun `add rule uses selected account`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = emptyList())

        viewModel.add(
            displayName = "Rent",
            merchant = "Landlord",
            amountText = "2500",
            currency = "SAR",
            type = TxType.EXPENSE,
            accountId = "acc-rent",
            categoryId = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 1,
            startDate = LocalDate(2026, 7, 1),
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().accountId).isEqualTo("acc-rent")
    }

    @Test
    fun `add rule without selected account keeps manual account fallback`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = emptyList())

        viewModel.add(
            displayName = "Rent",
            merchant = "Landlord",
            amountText = "2500",
            currency = "SAR",
            type = TxType.EXPENSE,
            accountId = null,
            categoryId = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 1,
            startDate = LocalDate(2026, 7, 1),
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().accountId).isEqualTo(MANUAL_ACCOUNT_ID)
    }

    @Test
    fun `accept suggestion falls back to safe suggested category`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion(suggestedCategoryId = "cat-gym")
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 5,
            accountId = null,
            categoryId = null,
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().categoryId).isEqualTo("cat-gym")
    }

    @Test
    fun `accept suggestion keeps explicit category override`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion(suggestedCategoryId = "cat-gym")
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 5,
            accountId = null,
            categoryId = "cat-health",
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().categoryId).isEqualTo("cat-health")
    }

    @Test
    fun `accept suggestion keeps explicit account override`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion(suggestedAccountId = "acc-checking")
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 5,
            accountId = "acc-credit",
            categoryId = null,
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().accountId).isEqualTo("acc-credit")
    }

    @Test
    fun `accept suggestion falls back to safe suggested account`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion(suggestedAccountId = "acc-checking")
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 5,
            accountId = null,
            categoryId = null,
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().accountId).isEqualTo("acc-checking")
    }

    @Test
    fun `accept suggestion without account hint keeps manual account fallback`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion(suggestedAccountId = null)
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.MONTHLY,
            dayOfMonth = 5,
            accountId = null,
            categoryId = null,
        )
        advanceUntilIdle()

        assertThat(rules.upserts.single().accountId).isEqualTo(MANUAL_ACCOUNT_ID)
    }

    @Test
    fun `accept quarterly suggestion preserves cadence anchors`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion()
            .copy(cadence = Cadence.QUARTERLY, dayOfWeek = null, monthOfYear = null)
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.QUARTERLY,
            dayOfMonth = 5,
            accountId = null,
            categoryId = null,
        )
        advanceUntilIdle()

        val created = rules.upserts.single()
        assertThat(created.cadence).isEqualTo(Cadence.QUARTERLY)
        assertThat(created.dayOfMonth).isEqualTo(5)
        assertThat(created.dayOfWeek).isNull()
        assertThat(created.monthOfYear).isNull()
        assertThat(created.nextRunDate).isEqualTo(LocalDate(2026, 4, 5))
    }

    @Test
    fun `accept weekly suggestion carries weekday anchor and no day of month`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion().copy(cadence = Cadence.WEEKLY, dayOfWeek = 2)
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.WEEKLY,
            dayOfMonth = null,
            dayOfWeek = 2,
            accountId = null,
            categoryId = null,
        )
        advanceUntilIdle()

        val created = rules.upserts.single()
        assertThat(created.cadence).isEqualTo(Cadence.WEEKLY)
        assertThat(created.dayOfWeek).isEqualTo(2)
        assertThat(created.dayOfMonth).isNull()
    }

    @Test
    fun `cadence override at acceptance recomputes the next run anchor`() = runTest(mainDispatcher) {
        val rules = RecordingRecurringRuleRepository()
        val suggestion = recurringSuggestion()
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = listOf(suggestion))

        viewModel.acceptSuggestion(
            suggestion = suggestion,
            notes = null,
            cadence = Cadence.WEEKLY,
            dayOfMonth = null,
            dayOfWeek = 1,
            accountId = null,
            categoryId = null,
        )
        advanceUntilIdle()

        val created = rules.upserts.single()
        assertThat(created.cadence).isEqualTo(Cadence.WEEKLY)
        // Recomputed from lastSeen (2026-03-05, a Thursday) onto the user's Monday
        // anchor — never the stale suggestedNextRun (2026-04-05, a Sunday).
        assertThat(created.nextRunDate).isGreaterThan(LocalDate(2026, 3, 5))
        assertThat(created.nextRunDate.dayOfWeek.ordinal + 1).isEqualTo(1)
        assertThat(created.nextRunDate).isNotEqualTo(LocalDate(2026, 4, 5))
    }

    @Test
    fun `update routing changes account and category on existing rule`() = runTest(mainDispatcher) {
        val existing = recurringRule(
            id = "rule-rent",
            accountId = MANUAL_ACCOUNT_ID,
            categoryId = "cat-rent",
        )
        val rules = RecordingRecurringRuleRepository(initial = listOf(existing))
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = emptyList())

        viewModel.updateRouting(
            id = "rule-rent",
            accountId = "acc-checking",
            categoryId = "cat-home",
        )
        advanceUntilIdle()

        val updated = rules.upserts.single()
        assertThat(updated.id).isEqualTo(existing.id)
        assertThat(updated.accountId).isEqualTo("acc-checking")
        assertThat(updated.categoryId).isEqualTo("cat-home")
        assertThat(updated.displayName).isEqualTo(existing.displayName)
        assertThat(updated.amount).isEqualTo(existing.amount)
        assertThat(updated.updatedAt).isEqualTo(FixedClock.now())
    }

    @Test
    fun `update routing with blank account keeps existing account and can clear category`() = runTest(mainDispatcher) {
        val existing = recurringRule(
            id = "rule-rent",
            accountId = "acc-current",
            categoryId = "cat-rent",
        )
        val rules = RecordingRecurringRuleRepository(initial = listOf(existing))
        val viewModel = recurringRulesViewModel(rules = rules, suggestions = emptyList())

        viewModel.updateRouting(
            id = "rule-rent",
            accountId = " ",
            categoryId = null,
        )
        advanceUntilIdle()

        val updated = rules.upserts.single()
        assertThat(updated.accountId).isEqualTo("acc-current")
        assertThat(updated.categoryId).isNull()
    }
}

private fun recurringRulesViewModel(
    rules: RecordingRecurringRuleRepository,
    suggestions: List<RecurringSuggestion>,
): RecurringRulesViewModel =
    RecurringRulesViewModel(
        rules = rules,
        suggestionRepo = FakeRecurringSuggestionRepository(suggestions),
        categoryRepo = FakeCategoryRepository(),
        accountRepo = FakeRecurringAccountRepository(),
        clock = FixedClock,
    )

private class RecordingRecurringRuleRepository(
    private val initial: List<RecurringRule> = emptyList(),
) : RecurringRuleRepository {
    val upserts = mutableListOf<RecurringRule>()

    override fun observeAll(includeInactive: Boolean): Flow<List<RecurringRule>> = flowOf(initial)

    override suspend fun get(id: String): RecurringRule? =
        upserts.firstOrNull { it.id == id } ?: initial.firstOrNull { it.id == id }

    override suspend fun upsert(rule: RecurringRule) {
        upserts += rule
    }

    override suspend fun delete(id: String) = Unit

    override suspend fun setActive(id: String, active: Boolean) = Unit

    override suspend fun materializeDue(today: LocalDate): Int = 0
}

private class FakeRecurringSuggestionRepository(
    private val suggestions: List<RecurringSuggestion>,
) : RecurringSuggestionRepository {
    override fun observeSuggestions(): Flow<List<RecurringSuggestion>> = flowOf(suggestions)
}

private class FakeCategoryRepository : CategoryRepository {
    override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> = flowOf(emptyList())

    override suspend fun get(id: String): Category? = null

    override suspend fun upsert(category: Category) = Unit

    override suspend fun archive(id: String) = Unit

    override suspend fun reorder(ids: List<String>) = Unit
}

private class FakeRecurringAccountRepository : AccountRepository {
    override fun observeActive(): Flow<List<Account>> = flowOf(listOf(account(MANUAL_ACCOUNT_ID, "Manual")))

    override fun observeAll(includeArchived: Boolean): Flow<List<Account>> = observeActive()

    override suspend fun get(id: String): Account? = observeActiveAccounts.firstOrNull { it.id == id }

    override suspend fun upsert(account: Account) = Unit

    override suspend fun setArchived(id: String, archived: Boolean) = Unit

    override suspend fun delete(id: String) = Unit

    override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> =
        flowOf(NetWorth(total = Money.zero(displayCurrency), byCurrency = emptyMap(), accounts = emptyList()))

    override fun observeBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())

    override suspend fun resolveForIngest(sender: String, body: String, counterparty: String?): Account? = null

    override suspend fun reconcile(accountId: String, target: Money, label: String, note: String?): ReconcileResult =
        ReconcileResult.Failed("not used")

    private val observeActiveAccounts = listOf(account(MANUAL_ACCOUNT_ID, "Manual"))
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-06-15T12:00:00Z")
}

private fun account(id: String, name: String): Account =
    Account(
        id = id,
        name = name,
        type = AccountType.CHECKING,
        currency = "SAR",
        openingBalance = Money.zero("SAR"),
        smsSenders = emptyList(),
        notes = null,
        sortOrder = 0,
        archived = false,
        createdAt = FixedClock.now(),
        updatedAt = FixedClock.now(),
    )

private fun recurringRule(
    id: String,
    accountId: String,
    categoryId: String?,
): RecurringRule =
    RecurringRule(
        id = id,
        displayName = "Rent",
        merchant = "Landlord",
        amount = Money.ofMinor(250_000, "SAR"),
        type = TxType.EXPENSE,
        accountId = accountId,
        categoryId = categoryId,
        cadence = Cadence.MONTHLY,
        dayOfMonth = 1,
        dayOfWeek = null,
        monthOfYear = null,
        nextRunDate = LocalDate(2026, 7, 1),
        lastRunDate = null,
        isActive = true,
        notes = "Existing note",
        createdAt = Instant.parse("2026-06-01T12:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T12:00:00Z"),
    )

private fun recurringSuggestion(
    suggestedAccountId: String? = "acc-checking",
    suggestedCategoryId: String? = null,
): RecurringSuggestion =
    RecurringSuggestion(
        merchant = "Gym Club",
        merchantNormalized = "gym club",
        amount = Money.ofMinor(9900, "SAR"),
        type = TxType.EXPENSE,
        suggestedAccountId = suggestedAccountId,
        suggestedCategoryId = suggestedCategoryId,
        occurrenceCount = 3,
        typicalDayOfMonth = 5,
        lastSeen = LocalDate(2026, 3, 5),
        suggestedNextRun = LocalDate(2026, 4, 5),
    )
