package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategoryRule
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.PatternType
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.position.DeclaredPositionSnapshot
import com.athar.core.domain.repo.DeclaredPositionRepository
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.CategoryRuleRepository
import com.athar.core.domain.repo.IncomeSourcePreferencesRepository
import com.athar.core.domain.repo.IncomeSourceSettings
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.TransactionRepository
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WM069: Today's actual figures follow the injected clock's date. A confirmed row
 * dated after today is a future observation: excluded from actual monthly operating
 * totals and the today/recent actual feeds, never mutated, and counted once its own
 * date arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayAsOf069Test {

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

    private fun fixture(): List<Transaction> = listOf(
        transaction(id = "earlier-expense", type = TxType.EXPENSE, amount = Money.of("2.00"), date = LocalDate(2026, 10, 24)),
        transaction(id = "today-income", type = TxType.INCOME, amount = Money.of("1000"), date = LocalDate(2026, 10, 25)),
        transaction(id = "today-expense", type = TxType.EXPENSE, amount = Money.of("3.14"), date = LocalDate(2026, 10, 25)),
        transaction(id = "today-transfer", type = TxType.TRANSFER, amount = Money.of("500"), date = LocalDate(2026, 10, 25)),
        transaction(id = "usd-expense", type = TxType.EXPENSE, amount = Money.of("90", "USD"), date = LocalDate(2026, 10, 25)),
        // Future observation inside the same Period.Month bucket (bucket ends 2026-11-01).
        transaction(id = "tomorrow-expense", type = TxType.EXPENSE, amount = Money.of("5.00"), date = LocalDate(2026, 10, 26)),
    )

    private fun viewModel(clock: Clock, transactions: TransactionRepository): TodayViewModel = TodayViewModel(
        transactions = transactions,
        rules = AsOfCategoryRuleRepository(),
        categories = AsOfCategoryRepository(),
        prefs = AsOfUserPreferencesRepository(savingsTarget = 30, emergencyMonths = 6),
        incomeSources = AsOfIncomeSourcePreferencesRepository(),
        accounts = AsOfAccountRepository(liquidBalance = Money.of("5000")),
        clock = clock,
        declaredPositions = AsOfEmptyDeclaredPositions,
    )

    @Test
    fun `future confirmed rows stay out of actual monthly totals and today feeds`() = runTest(mainDispatcher) {
        val transactions = AsOfTransactionRepository(
            currentConfirmed = fixture(),
            goalConfirmed = listOf(
                transaction(id = "goal-income", type = TxType.INCOME, amount = Money.of("9000"), date = LocalDate(2026, 9, 10)),
                transaction(id = "goal-expense", type = TxType.EXPENSE, amount = Money.of("6000"), date = LocalDate(2026, 9, 11)),
            ),
        )
        val viewModel = viewModel(clock = fixedClock("2026-10-25T12:00:00Z"), transactions = transactions)

        val state = viewModel.state.first { !it.isLoading }

        // Monthly actuals: earlier-in-month and today rows count; the future SAR 5.00 row does not.
        assertThat(state.totalExpense.amount.toPlainString()).isEqualTo("5.14")
        assertThat(state.totalExpense.currency).isEqualTo("SAR")
        assertThat(state.totalIncome.amount.toPlainString()).isEqualTo("1000")
        assertThat(state.netFlow.amount.toPlainString()).isEqualTo("994.86")
        // Today's net excludes the future row and keeps the transfer/USD exclusions.
        assertThat(state.todayNet.amount.toPlainString()).isEqualTo("996.86")
        // Feeds keep full confirmed actual rows (transfer + foreign stay visible) but never the future row.
        assertThat(state.today.map { it.id }).containsExactly(
            "today-income",
            "today-expense",
            "today-transfer",
            "usd-expense",
        ).inOrder()
        assertThat(state.recent.map { it.id }).containsExactly("earlier-expense").inOrder()
        // Source money/records unchanged: the ViewModel issues no writes for future observations.
        assertThat(transactions.upserts).isEmpty()
        // Existing goal lookback contract: nudge still derives from the separate 3-month observation.
        assertThat(state.goalNudge!!.savingsRatePercent).isEqualTo(java.math.BigDecimal("33.3"))
    }

    @Test
    fun `a future confirmed row becomes actual once the clock reaches its date`() = runTest(mainDispatcher) {
        val transactions = AsOfTransactionRepository(currentConfirmed = fixture())
        val viewModel = viewModel(clock = fixedClock("2026-10-26T12:00:00Z"), transactions = transactions)

        val state = viewModel.state.first { !it.isLoading }

        // Inclusive as-of boundary: the stored row becomes actual on its exact date.
        assertThat(state.totalExpense.amount.toPlainString()).isEqualTo("10.14")
        assertThat(state.totalIncome.amount.toPlainString()).isEqualTo("1000")
        assertThat(state.today.map { it.id }).containsExactly("tomorrow-expense")
        assertThat(state.recent.map { it.id }).containsExactly(
            "earlier-expense", "today-income", "today-expense", "today-transfer", "usd-expense",
        ).inOrder()
        assertThat(transactions.upserts).isEmpty()
    }
}

private fun fixedClock(instant: String): Clock = object : Clock {
    override fun now(): Instant = Instant.parse(instant)
}

private object AsOfEmptyDeclaredPositions : DeclaredPositionRepository {
    override fun observeSnapshots(): Flow<List<DeclaredPositionSnapshot>> = flowOf(emptyList())
}

private class AsOfIncomeSourcePreferencesRepository(
    private val senders: Set<String> = emptySet(),
) : IncomeSourcePreferencesRepository {
    override fun settings(): Flow<IncomeSourceSettings> = flowOf(IncomeSourceSettings(senders))
    override suspend fun update(settings: IncomeSourceSettings) = Unit
}

private class AsOfTransactionRepository(
    private val currentConfirmed: List<Transaction> = emptyList(),
    private val goalConfirmed: List<Transaction> = emptyList(),
    private val pending: List<Transaction> = emptyList(),
    private val dismissed: List<Transaction> = emptyList(),
) : TransactionRepository {
    val upserts = mutableListOf<Transaction>()

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
        (currentConfirmed + goalConfirmed + pending + dismissed).firstOrNull { it.id == id }
    override suspend fun upsertIngest(transaction: Transaction): String =
        error("Ingestion is outside this as-of fixture")
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
    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int = 0
}

private class AsOfCategoryRuleRepository : CategoryRuleRepository {
    override fun observeAll(): Flow<List<CategoryRule>> = flowOf(emptyList())
    override suspend fun findMatching(merchantNormalized: String): List<CategoryRule> = emptyList()
    override suspend fun upsert(rule: CategoryRule) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun learnFromCorrection(
        merchantNormalized: String,
        categoryId: String,
        patternType: PatternType,
    ): CategoryRule = CategoryRule(
        id = "learned-$merchantNormalized",
        pattern = merchantNormalized,
        patternType = patternType,
        categoryId = categoryId,
        priority = 100,
        learnedFromUser = true,
        createdAt = Instant.parse("2026-10-25T12:00:00Z"),
    )
}

private class AsOfCategoryRepository(
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

private class AsOfUserPreferencesRepository(
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

private class AsOfAccountRepository(
    private val liquidBalance: Money,
) : AccountRepository {
    private val account = Fixtures.account(
        id = "acc-1",
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

private fun transaction(
    id: String,
    type: TxType,
    amount: Money,
    date: LocalDate,
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
