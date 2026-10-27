package com.athar.feature.trends

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * Query-state behavior of the Trends ViewModel: single explicit query state,
 * currency override, filters applied to aggregates AND evidence, matched-elapsed
 * comparisons, independent custom prior windows, reset/back semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModelQueryTest {

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

    // Fixed "now" — 2026-02-14, i.e., mid-month, so matched-elapsed logic is observable.
    private val now: Instant = Instant.parse("2026-02-14T12:00:00Z")

    private fun viewModel(
        txs: List<Transaction>,
        cats: List<Category> = emptyList(),
        displayCurrency: String = Money.SAR,
        accounts: List<Account> = emptyList(),
        accountsRepo: FakeAccountRepository? = null,
        transactionsRepo: TransactionRepository? = null,
    ): TrendsViewModel = TrendsViewModel(
        transactions = transactionsRepo ?: FakeTransactionRepository(txs),
        categories = FakeCategoryRepository(cats),
        prefs = FakePreferencesRepository(displayCurrency),
        incomeSources = object : com.athar.core.domain.repo.IncomeSourcePreferencesRepository {
            override fun settings() = kotlinx.coroutines.flow.flowOf(
                com.athar.core.domain.repo.IncomeSourceSettings(),
            )
            override suspend fun update(settings: com.athar.core.domain.repo.IncomeSourceSettings) = Unit
        },
        accounts = accountsRepo ?: FakeAccountRepository(accounts),
        clock = FixedClock(now),
    )

    private fun account(
        id: String,
        name: String,
        archived: Boolean = false,
    ): Account = Account(
        id = id,
        name = name,
        type = AccountType.CHECKING,
        currency = Money.SAR,
        openingBalance = Money.zero(),
        smsSenders = emptyList(),
        notes = null,
        sortOrder = 0,
        archived = archived,
        createdAt = now,
        updatedAt = now,
    )

    /** stateIn(WhileSubscribed) only computes while someone collects — subscribe first. */
    private fun TrendsViewModel.collectInTest(scope: TestScope) {
        scope.backgroundScope.launch { state.collect { } }
    }

    private fun expense(
        id: String,
        amount: String,
        date: LocalDate = LocalDate(2026, 2, 5),
        accountId: String = "acc-1",
        categoryId: String? = "cat-1",
        currency: String = Money.SAR,
        type: TxType = TxType.EXPENSE,
        status: TxStatus = TxStatus.CONFIRMED,
        sourceRefId: String? = "sms-1",
        source: IngestSource = IngestSource.SMS,
    ): Transaction = txFixture(id, amount, date, accountId, categoryId, currency, type, status, sourceRefId, source)

    private fun txFixture(
        id: String,
        amount: String,
        date: LocalDate,
        accountId: String,
        categoryId: String?,
        currency: String,
        type: TxType,
        status: TxStatus,
        sourceRefId: String?,
        source: IngestSource,
    ): Transaction = Transaction(
        id = id,
        accountId = accountId,
        type = type,
        amount = Money.of(amount, currency),
        date = date,
        occurredAt = null,
        merchant = "M$id",
        merchantNormalized = "m$id",
        categoryId = categoryId,
        notes = null,
        source = source,
        sourceRefId = sourceRefId,
        status = status,
        confidence = 1f,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `in-progress month compares against an equal-elapsed matched window`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = listOf(expense("e1", "100")))
        vm.collectInTest(this)
        advanceUntilIdle()
        val s = vm.state.value
        // Feb has 14 elapsed days (Feb 1..14) → prior = Jan 1 .. Jan 15 (exclusive).
        assertThat(s.previousMatchedElapsed).isTrue()
        val prev = s.previousWindow
        assertThat(prev).isInstanceOf(Period.Custom::class.java)
        assertThat(prev!!.start).isEqualTo(LocalDate(2026, 1, 1))
        assertThat(prev.endExclusive).isEqualTo(LocalDate(2026, 1, 15))
    }

    @Test
    fun `completed custom period compares against the full previous window`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = emptyList())
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.SelectCustomRange(LocalDate(2026, 1, 1), LocalDate(2026, 2, 1)))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.previousMatchedElapsed).isFalse()
        assertThat(s.previousWindow!!.endExclusive).isEqualTo(LocalDate(2026, 1, 1))
    }

    @Test
    fun `independent custom prior window drives the comparison totals`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("cur", "100"),
                expense("prior", "77", date = LocalDate(2026, 1, 6)),
                expense("outside", "500", date = LocalDate(2026, 1, 2)),
            ),
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.SetComparisonCustomRange(LocalDate(2026, 1, 5), LocalDate(2026, 1, 10)))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.previousWindow!!.start).isEqualTo(LocalDate(2026, 1, 5))
        assertThat(s.previousWindow!!.endExclusive).isEqualTo(LocalDate(2026, 1, 10))
        assertThat(s.previousMatchedElapsed).isFalse()
        assertThat(s.previousExpense.amount.toInt()).isEqualTo(77)
    }

    @Test
    fun `currency override selects that currency only and flags excluded ones`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("sar", "100"),
                expense("usd", "20", currency = "USD"),
            ),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        assertThat(vm.state.value.totalExpense.currency).isEqualTo(Money.SAR)

        vm.onEvent(TrendsEvent.SetCurrencyOverride("USD"))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.reportingCurrency).isEqualTo("USD")
        // Selected currency only: the SAR row is excluded; the USD row is the real total.
        assertThat(s.totalExpense.currency).isEqualTo("USD")
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(20)
        assertThat(s.mixedCurrency).isTrue()
    }

    @Test
    fun `filters apply to full aggregates and to evidence alike`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("a1", "100", accountId = "acc-1"),
                expense("a2", "250", accountId = "acc-2"),
            ),
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.ToggleAccountFilter("acc-2"))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(250)

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val evidence = vm.state.value.evidence!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("a2")
        assertThat(evidence.total.amount.toInt()).isEqualTo(250)
    }

    @Test
    fun `other bucket evidence contains exactly the non-top-N membership`() = runTest(mainDispatcher) {
        val txs = (1..9).map { i -> expense("b$i", (10 * i).toString(), categoryId = "cat-$i") }
        val cats = (1..9).map { i -> Category("cat-$i", "Cat $i", "صنف $i", CategoryKind.EXPENSE, null, null, false, i) }
        val vm = viewModel(txs = txs, cats = cats)
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket(OTHER_KEY)))
        advanceUntilIdle()
        val evidence = vm.state.value.evidence!!
        // Top 8 categories are shown on screen; Other must hold exactly the 9th.
        assertThat(evidence.transactions.map { it.id }).containsExactly("b1")
        assertThat(evidence.total.amount.toInt()).isEqualTo(10)
    }

    @Test
    fun `clear filters restores defaults while preserving the period selection`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = listOf(expense("f1", "100")))
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.SelectPeriod(PeriodKey.YEAR))
        vm.onEvent(TrendsEvent.ToggleAccountFilter("acc-2"))
        vm.onEvent(TrendsEvent.SetMerchantQuery("star"))
        advanceUntilIdle()
        assertThat(vm.state.value.filters.isDefault).isFalse()

        vm.onEvent(TrendsEvent.ClearFilters)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.filters.isDefault).isTrue()
        assertThat(s.periodKey).isEqualTo(PeriodKey.YEAR)
    }

    @Test
    fun `closing evidence keeps the query state intact`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = listOf(expense("k1", "100", accountId = "acc-2")))
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.ToggleAccountFilter("acc-2"))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        assertThat(vm.state.value.evidence).isNotNull()

        vm.onEvent(TrendsEvent.CloseEvidence)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.evidence).isNull()
        assertThat(s.filters.accountIds).containsExactly("acc-2")
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(100)
    }

    @Test
    fun `future and out-of-range transactions are excluded from current aggregates`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("now1", "100"),
                expense("future", "999", date = LocalDate(2026, 3, 20)),
            ),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(100)
        // Evidence for the current headline cannot leak out-of-window rows either.
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        assertThat(vm.state.value.evidence!!.transactions.map { it.id }).containsExactly("now1")
    }

    @Test
    fun `monthly evidence bar opens only that month`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("jan", "42", date = LocalDate(2026, 1, 8)),
                expense("feb", "43", date = LocalDate(2026, 2, 8)),
            ),
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthBar(java.time.YearMonth.of(2026, 1), TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val evidence = vm.state.value.evidence!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("jan")
        assertThat(evidence.total.amount.toInt()).isEqualTo(42)
    }

    @Test
    fun `invalid custom range is rejected calmly without crashing`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = listOf(expense("e1", "100")))
        vm.collectInTest(this)

        // end <= start
        vm.onEvent(TrendsEvent.SelectCustomRange(LocalDate(2026, 2, 10), LocalDate(2026, 2, 5)))
        advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.customRangeError).isEqualTo(TrendsRangeError.END_BEFORE_START)
        assertThat(s.customStart).isNull()
        assertThat(s.periodKey).isEqualTo(PeriodKey.MONTH)

        // future start — the SelectCustomRange path goes through the same guard as prior
        vm.onEvent(TrendsEvent.SelectCustomRange(LocalDate(2026, 3, 1), LocalDate(2026, 3, 10)))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.customRangeError).isEqualTo(TrendsRangeError.START_IN_FUTURE)
        assertThat(s.customStart).isNull()

        // a valid range clears the disclosed error
        vm.onEvent(TrendsEvent.SelectCustomRange(LocalDate(2026, 2, 1), LocalDate(2026, 2, 10)))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.customRangeError).isNull()
        assertThat(s.customStart).isEqualTo(LocalDate(2026, 2, 1))
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(100)
    }

    @Test
    fun `future comparison range is rejected and prior stays automatic`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = listOf(expense("e1", "100")))
        vm.collectInTest(this)

        vm.onEvent(TrendsEvent.SetComparisonCustomRange(LocalDate(2026, 3, 1), LocalDate(2026, 3, 10)))
        advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.comparisonRangeError).isEqualTo(TrendsRangeError.START_IN_FUTURE)
        assertThat(s.previousMatchedElapsed).isTrue()

        vm.onEvent(TrendsEvent.SetComparisonCustomRange(LocalDate(2026, 1, 5), LocalDate(2026, 1, 10)))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.comparisonRangeError).isNull()
        assertThat(s.customPrior).isNotNull()
    }

    @Test
    fun `custom range ending in the future clamps populations to actuals`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("now1", "100", date = LocalDate(2026, 2, 5)),
                expense("future", "999", date = LocalDate(2026, 2, 20)),
            ),
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.SelectCustomRange(LocalDate(2026, 2, 1), LocalDate(2026, 3, 1)))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.customRangeError).isNull()
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(100)
    }

    @Test
    fun `headline comparison evidence distinguishes matched current and prior windows`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("cur", "100", date = LocalDate(2026, 2, 5)),
                expense("prior", "77", date = LocalDate(2026, 1, 6)),
                expense("late-prior", "55", date = LocalDate(2026, 1, 20)),
            ),
        )
        vm.collectInTest(this)

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.HeadlineComparison(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val evidence = vm.state.value.evidence!!
        val c = checkNotNull(evidence.comparison)
        // Current side of the delta: the matched elapsed window, NOT the full headline total.
        assertThat(c.currentRows.map { it.id }).containsExactly("cur")
        assertThat(c.currentTotal.amount.toInt()).isEqualTo(100)
        // Prior side: only rows inside the matched prior window (Jan 1–15).
        assertThat(c.priorRows.map { it.id }).containsExactly("prior")
        assertThat(c.priorTotal.amount.toInt()).isEqualTo(77)
        assertThat(c.priorWindow).isEqualTo(Period.Custom(LocalDate(2026, 1, 1), LocalDate(2026, 1, 15)))

        // Full-headline drilldown is preserved and has no comparison section.
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val full = vm.state.value.evidence!!
        assertThat(full.comparison).isNull()
        assertThat(full.transactions.map { it.id }).containsExactly("cur")
        assertThat(full.total.amount.toInt()).isEqualTo(100)
    }

    @Test
    fun `renamed account updates display without changing keys totals or evidence`() = runTest(mainDispatcher) {
        val accountsRepo = FakeAccountRepository(
            listOf(account("acc-1", "الحساب الأول"), account("acc-2", "Second")),
        )
        val vm = viewModel(
            txs = listOf(
                expense("a1", "100", accountId = "acc-1"),
                expense("a2", "40", accountId = "acc-2"),
            ),
            accountsRepo = accountsRepo,
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.ToggleAccountFilter("acc-1"))
        advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.accountNames["acc-1"]).isEqualTo("الحساب الأول")
        assertThat(s.filterAccountIds).containsExactly("acc-1", "acc-2")
        assertThat(s.filters.accountIds).containsExactly("acc-1")
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(100)

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val evidenceBefore = vm.state.value.evidence!!.transactions.map { it.id }

        // Live rename through the repository flow — display changes, keys/numbers do not.
        accountsRepo.upsert(account("acc-1", "Renamed"))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.accountNames["acc-1"]).isEqualTo("Renamed")
        assertThat(s.filters.accountIds).containsExactly("acc-1")
        assertThat(s.filterAccountIds).containsExactly("acc-1", "acc-2")
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(100)
        assertThat(vm.state.value.evidence!!.transactions.map { it.id }).isEqualTo(evidenceBefore)
    }

    @Test
    fun `missing account id never resolves to a name while archived still does`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("g1", "70", accountId = "acc-ghost"),
                expense("h1", "20", accountId = "acc-arch"),
            ),
            accounts = listOf(account("acc-1", "Known"), account("acc-arch", "مؤرشف", archived = true)),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        val s = vm.state.value
        // Both raw keys stay as filter options — including the deleted account's id.
        assertThat(s.filterAccountIds).containsExactly("acc-ghost", "acc-arch")
        // Archived accounts resolve by name; a deleted id gets NO fabricated entry —
        // the UI renders its localized unknown label instead of the raw id.
        assertThat(s.accountNames["acc-arch"]).isEqualTo("مؤرشف")
        assertThat(s.accountNames).doesNotContainKey("acc-ghost")
    }

    @Test
    fun `uncategorized matches null category only and orphaned ids keep their own evidence`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("no-cat", "30", categoryId = null),
                expense("orphan", "55", categoryId = "gone"),
            ),
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.SetCategoryFilter(UNCATEGORIZED_KEY))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket(UNCATEGORIZED_KEY)))
        advanceUntilIdle()
        var evidence = vm.state.value.evidence!!
        // Explicit null category = uncategorized; the orphaned id is NOT folded in.
        assertThat(evidence.transactions.map { it.id }).containsExactly("no-cat")

        vm.onEvent(TrendsEvent.SetCategoryFilter("gone"))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket("gone")))
        advanceUntilIdle()
        evidence = vm.state.value.evidence!!
        // A non-null unknown category id drills into exactly its own rows and is NOT
        // resolved as a known label — the UI must show the localized unknown label.
        assertThat(evidence.transactions.map { it.id }).containsExactly("orphan")
        assertThat(evidence.total.amount.toInt()).isEqualTo(55)
        assertThat(evidence.categoryLabels).doesNotContainKey("gone")
    }

    @Test
    fun `category labels and options carry both arabic and english names`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(expense("e1", "10", categoryId = "cat-1")),
            cats = listOf(Category("cat-1", "Groceries", "بقالة", CategoryKind.EXPENSE, null, null, false, 1)),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        assertThat(vm.state.value.filterCategoryOptions).contains(FilterOption("cat-1", "بقالة", "Groceries"))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        assertThat(vm.state.value.evidence!!.categoryLabels["cat-1"]).isEqualTo("بقالة" to "Groceries")
    }

    @Test
    fun `orphaned category ids are offered as filter options and filter exactly their own rows`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("known", "10", categoryId = "cat-1"),
                expense("orphan", "55", categoryId = "gone"),
                expense("no-cat", "30", categoryId = null),
            ),
            cats = listOf(Category("cat-1", "Groceries", "بقالة", CategoryKind.EXPENSE, null, null, false, 1)),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        var s = vm.state.value
        // Real categories first, then orphaned ids seen in the data (blank labels — the
        // UI renders the localized unknown-category fallback, never the raw id), then
        // the explicit uncategorized bucket.
        assertThat(s.filterCategoryOptions.map { it.id })
            .containsExactly("cat-1", "gone", UNCATEGORIZED_KEY)
            .inOrder()
        assertThat(s.filterCategoryOptions.first { it.id == "gone" }.labelAr).isEmpty()
        assertThat(s.filterCategoryOptions.first { it.id == "gone" }.labelEn).isEmpty()

        // Selecting the orphaned option filters every aggregate to exactly its own rows;
        // null-category and known-category rows are excluded.
        vm.onEvent(TrendsEvent.SetCategoryFilter("gone"))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(55)
        assertThat(s.totalExpense.currency).isEqualTo(Money.SAR)

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val evidence = vm.state.value.evidence!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("orphan")
        assertThat(evidence.total.amount.toInt()).isEqualTo(55)

        // Clearing restores default filters; totals return to the unfiltered population.
        vm.onEvent(TrendsEvent.ClearFilters)
        advanceUntilIdle()
        assertThat(vm.state.value.filters.isDefault).isTrue()
        assertThat(vm.state.value.totalExpense.amount.toInt()).isEqualTo(95)
    }

    @Test
    fun `source filter narrows aggregates and evidence and clearing restores both`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("sms-1", "120", source = IngestSource.SMS),
                expense("man-1", "45", source = IngestSource.MANUAL, sourceRefId = null),
            ),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        assertThat(vm.state.value.totalExpense.amount.toInt()).isEqualTo(165)

        vm.onEvent(TrendsEvent.ToggleSourceFilter(IngestSource.SMS))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(120)
        assertThat(s.evidence!!.transactions.map { it.id }).containsExactly("sms-1")

        // Dropping the filter changes the expected IDs and amounts again.
        vm.onEvent(TrendsEvent.CloseEvidence)
        vm.onEvent(TrendsEvent.ClearFilters)
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.evidence!!.transactions.map { it.id }).containsExactly("sms-1", "man-1")
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(165)
    }

    @Test
    fun `type filter selects exactly that type and transfer yields honest empty evidence`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("ex-1", "80"),
                txFixture("in-1", "500", LocalDate(2026, 2, 6), "acc-1", "cat-2", Money.SAR, TxType.INCOME, TxStatus.CONFIRMED, null, IngestSource.MANUAL),
                txFixture("tr-1", "300", LocalDate(2026, 2, 7), "acc-1", null, Money.SAR, TxType.TRANSFER, TxStatus.CONFIRMED, null, IngestSource.MANUAL),
            ),
        )
        vm.collectInTest(this)

        vm.onEvent(TrendsEvent.SetTypeFilter(TxType.INCOME))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(0)
        assertThat(s.evidence!!.transactions).isEmpty()
        assertThat(s.totalIncome).isEqualTo(Money.of("500"))

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.INCOME)))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.evidence!!.transactions.map { it.id }).containsExactly("in-1")
        assertThat(s.evidence.total).isEqualTo(Money.of("500"))

        vm.onEvent(TrendsEvent.CloseEvidence)
        vm.onEvent(TrendsEvent.SetTypeFilter(TxType.TRANSFER))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        s = vm.state.value
        // Transfers are never operating spend or income: an honest zero with the filter disclosed.
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(0)
        assertThat(s.totalIncome.amount.toInt()).isEqualTo(0)
        assertThat(s.filters.type).isEqualTo(TxType.TRANSFER)
        assertThat(s.evidence!!.transactions).isEmpty()
        assertThat(s.evidence.total).isEqualTo(Money.zero())

        vm.onEvent(TrendsEvent.SetTypeFilter(null))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.totalExpense.amount.toInt()).isEqualTo(80)
        assertThat(s.totalIncome.amount.toInt()).isEqualTo(500)
        assertThat(s.evidence!!.transactions.map { it.id }).containsExactly("ex-1")
        assertThat(s.evidence.total).isEqualTo(Money.of("80"))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.INCOME)))
        advanceUntilIdle()
        assertThat(vm.state.value.evidence!!.transactions.map { it.id }).containsExactly("in-1")
        assertThat(vm.state.value.evidence!!.total).isEqualTo(Money.of("500"))
    }

    @Test
    fun `clear filters preserves the independent comparison window and currency override`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("cur", "100"),
                expense("prior", "77", date = LocalDate(2026, 1, 6)),
            ),
        )
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.SetComparisonCustomRange(LocalDate(2026, 1, 5), LocalDate(2026, 1, 10)))
        vm.onEvent(TrendsEvent.SetCurrencyOverride("USD"))
        vm.onEvent(TrendsEvent.ToggleAccountFilter("acc-2"))
        vm.onEvent(TrendsEvent.SetMerchantQuery("m1"))
        advanceUntilIdle()

        vm.onEvent(TrendsEvent.ClearFilters)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.filters.isDefault).isTrue()
        // The independent period controls survive a filter reset — only filters clear.
        assertThat(s.customPrior).isNotNull()
        assertThat(s.previousWindow!!.start).isEqualTo(LocalDate(2026, 1, 5))
        assertThat(s.previousWindow.endExclusive).isEqualTo(LocalDate(2026, 1, 10))
        assertThat(s.currencyOverride).isEqualTo("USD")
        assertThat(s.reportingCurrency).isEqualTo("USD")
    }

    @Test
    fun `detail opens over evidence and closing returns to the same evidence and query`() = runTest(mainDispatcher) {
        val vm = viewModel(txs = listOf(expense("d1", "100"), expense("d2", "30", accountId = "acc-2")))
        vm.collectInTest(this)
        vm.onEvent(TrendsEvent.ToggleAccountFilter("acc-2"))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        assertThat(vm.state.value.evidence!!.transactions.map { it.id }).containsExactly("d2")

        vm.onEvent(TrendsEvent.OpenTransactionDetail("d2"))
        advanceUntilIdle()
        var s = vm.state.value
        assertThat(s.detailTransaction!!.id).isEqualTo("d2")
        // The evidence sheet stays open underneath the detail.
        assertThat(s.evidence).isNotNull()
        assertThat(s.evidenceCanGoBack).isFalse()

        vm.onEvent(TrendsEvent.CloseTransactionDetail)
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.detailTransaction).isNull()
        assertThat(s.evidence!!.request).isEqualTo(TrendsEvidenceRequest.Headline(TrendsMetric.EXPENSE))
        assertThat(s.evidence.transactions.map { it.id }).containsExactly("d2")
        assertThat(s.filters.accountIds).containsExactly("acc-2")
    }

    @Test
    fun `opening evidence replaces a stale detail and back walks the drill history`() = runTest(mainDispatcher) {
        val txs = (1..9).map { i -> expense("b$i", (10 * i).toString(), categoryId = "cat-$i") }
        val cats = (1..9).map { i -> Category("cat-$i", "Cat $i", "صنف $i", CategoryKind.EXPENSE, null, null, false, i) }
        val vm = viewModel(txs = txs, cats = cats)
        vm.collectInTest(this)

        vm.onEvent(TrendsEvent.OpenTransactionDetail("b1"))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket("cat-9")))
        advanceUntilIdle()
        var s = vm.state.value
        // Opening evidence replaces the stale open detail instead of stacking it.
        assertThat(s.detailTransaction).isNull()
        assertThat(s.evidenceCanGoBack).isFalse()
        assertThat(s.evidence!!.request).isEqualTo(TrendsEvidenceRequest.Bucket("cat-9"))

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthInBucket(java.time.YearMonth.of(2026, 2), "cat-9")))
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.evidenceCanGoBack).isTrue()
        assertThat(s.evidence!!.request)
            .isEqualTo(TrendsEvidenceRequest.MonthInBucket(java.time.YearMonth.of(2026, 2), "cat-9"))

        vm.onEvent(TrendsEvent.BackEvidence)
        advanceUntilIdle()
        s = vm.state.value
        assertThat(s.evidence!!.request).isEqualTo(TrendsEvidenceRequest.Bucket("cat-9"))
        assertThat(s.evidenceCanGoBack).isFalse()

        // Back on the last level closes the sheet entirely.
        vm.onEvent(TrendsEvent.BackEvidence)
        advanceUntilIdle()
        assertThat(vm.state.value.evidence).isNull()

        // Re-opening the same surface repeatedly never grows a phantom history.
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket("cat-9")))
        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.Bucket("cat-9")))
        advanceUntilIdle()
        assertThat(vm.state.value.evidenceCanGoBack).isFalse()
    }

    @Test
    fun `rapid period changes converge on the last selection without stale aggregates`() = runTest(mainDispatcher) {
        val rows = listOf(expense("w1", "100"))
        val olderSubscribed = CompletableDeferred<Unit>()
        val olderCancelled = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        var olderDelivered = false
        val base = FakeTransactionRepository(rows)
        val delayed = object : TransactionRepository by base {
            override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> =
                if (period.start == LocalDate(2026, 2, 8) && period.endExclusive == LocalDate(2026, 2, 15)) {
                    flow {
                        olderSubscribed.complete(Unit)
                        try {
                            releaseOlder.await()
                            emit(listOf(expense("stale", "999", date = LocalDate(2026, 2, 10))))
                            olderDelivered = true
                        } catch (cancelled: CancellationException) {
                            olderCancelled.complete(Unit)
                            throw cancelled
                        }
                    }
                } else {
                    base.observeByPeriod(period, status)
                }
        }
        val vm = viewModel(txs = rows, transactionsRepo = delayed)
        vm.collectInTest(this)
        advanceUntilIdle()
        vm.onEvent(TrendsEvent.SelectPeriod(PeriodKey.WEEK))
        advanceUntilIdle()
        // A subscribed, suspended WEEK request exists before YEAR is selected;
        // StateFlow conflation cannot skip the older request in this fixture.
        assertThat(olderSubscribed.isCompleted).isTrue()
        assertThat(olderCancelled.isCompleted).isFalse()
        vm.onEvent(TrendsEvent.SelectPeriod(PeriodKey.YEAR))
        advanceUntilIdle()
        assertThat(olderCancelled.isCompleted).isTrue()
        assertThat(vm.state.value.totalExpense).isEqualTo(Money.of("100"))

        // Releasing an already-cancelled older response must never publish 999.
        releaseOlder.complete(Unit)
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(olderDelivered).isFalse()
        assertThat(s.periodKey).isEqualTo(PeriodKey.YEAR)
        assertThat(s.period).isEqualTo(Period.Year(2026))
        assertThat(s.totalExpense).isEqualTo(Money.of("100"))
        // In-progress year: the comparison is the equal-elapsed slice of 2025.
        assertThat(s.previousMatchedElapsed).isTrue()
        assertThat(s.previousWindow!!.start).isEqualTo(LocalDate(2025, 1, 1))
        assertThat(s.previousWindow.endExclusive).isEqualTo(LocalDate(2025, 2, 15))
    }

    @Test
    fun `month bar evidence reconciles exactly with the dashboard monthly bar`() = runTest(mainDispatcher) {
        val vm = viewModel(
            txs = listOf(
                expense("jan-a", "40", date = LocalDate(2026, 1, 3)),
                expense("jan-b", "17", date = LocalDate(2026, 1, 28)),
                expense("feb-a", "60", date = LocalDate(2026, 2, 9)),
            ),
        )
        vm.collectInTest(this)
        advanceUntilIdle()
        val jan = java.time.YearMonth.of(2026, 1)
        val bar = vm.state.value.monthly.expense.first { it.month == jan }
        assertThat(bar.amount.amount.toInt()).isEqualTo(57)

        vm.onEvent(TrendsEvent.OpenEvidence(TrendsEvidenceRequest.MonthBar(jan, TrendsMetric.EXPENSE)))
        advanceUntilIdle()
        val evidence = vm.state.value.evidence!!
        assertThat(evidence.transactions.map { it.id }).containsExactly("jan-a", "jan-b")
        // Exact reconciliation: the drilled total equals the tapped bar amount (scale-safe compare).
        assertThat(evidence.total.amount.compareTo(bar.amount.amount)).isEqualTo(0)
        assertThat(evidence.total.currency).isEqualTo(bar.amount.currency)
    }
}

class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

private class FakeTransactionRepository(initial: List<Transaction>) : TransactionRepository {
    private val rows = MutableStateFlow(initial)

    override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> =
        rows.map { list -> list.filter { period.contains(it.date) && (status == null || it.status == status) } }

    override fun observeAll(): Flow<List<Transaction>> = rows
    override fun observePending(): Flow<List<Transaction>> = flowOf(emptyList())
    override suspend fun get(id: String): Transaction? = rows.value.firstOrNull { it.id == id }
    override suspend fun upsertIngest(transaction: Transaction): String =
        error("Ingestion is outside this UI or import test fixture")

    override suspend fun upsert(transaction: Transaction) {
        rows.value = rows.value.filterNot { it.id == transaction.id } + transaction
    }
    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }
    override suspend fun setStatus(id: String, status: TxStatus) {
        rows.value = rows.value.map { if (it.id == id) it.copy(status = status) else it }
    }
    override suspend fun clearPending(): Int = 0
    override suspend fun confirmAllConfident(minConfidence: Float): Int = 0
    override suspend fun dismissAllLowConfidence(maxConfidence: Float): Int = 0
    override suspend fun dismissAllPending(): Int = 0
    override suspend fun recoverDismissedToPending(): Int = 0
    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int = 0
}

private class FakeAccountRepository(initial: List<Account>) : AccountRepository {
    private val rows = MutableStateFlow(initial)

    override fun observeActive(): Flow<List<Account>> = rows.map { list -> list.filter { !it.archived } }

    override fun observeAll(includeArchived: Boolean): Flow<List<Account>> =
        rows.map { list -> if (includeArchived) list else list.filter { !it.archived } }

    override suspend fun get(id: String): Account? = rows.value.firstOrNull { it.id == id }

    override suspend fun upsert(account: Account) {
        rows.value = rows.value.filterNot { it.id == account.id } + account
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        rows.value = rows.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> =
        flowOf(NetWorth(Money.zero(displayCurrency), emptyMap(), emptyList()))

    override fun observeBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())

    override suspend fun resolveForIngest(sender: String, body: String, counterparty: String?): Account? = null

    override suspend fun reconcile(accountId: String, target: Money, label: String, note: String?): ReconcileResult =
        ReconcileResult.Failed("not used in trends tests")
}

private class FakeCategoryRepository(cats: List<Category>) : CategoryRepository {
    private val rows = MutableStateFlow(cats)

    override fun observeAll(kind: CategoryKind?, includeArchived: Boolean): Flow<List<Category>> =
        rows.map { list -> list.filter { it.kind == kind || kind == null } }

    override suspend fun get(id: String): Category? = rows.value.firstOrNull { it.id == id }
    override suspend fun upsert(category: Category) {
        rows.value = rows.value.filterNot { it.id == category.id } + category
    }
    override suspend fun archive(id: String) {}
    override suspend fun reorder(ids: List<String>) {}
}

private class FakePreferencesRepository(displayCurrency: String) : UserPreferencesRepository {
    private val currency = MutableStateFlow(displayCurrency)

    override fun onboardingComplete(): Flow<Boolean> = flowOf(true)
    override suspend fun setOnboardingComplete(complete: Boolean) {}
    override fun lastSmsBackfillEpochSeconds(): Flow<Long> = flowOf(0L)
    override suspend fun setLastSmsBackfillEpochSeconds(epoch: Long) {}
    override fun hijriEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setHijriEnabled(enabled: Boolean) {}
    override fun ownAccountNumbers(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setOwnAccountNumbers(numbers: List<String>) {}
    override fun displayCurrency(): Flow<String> = currency
    override suspend fun setDisplayCurrency(currency: String) {}
    override fun appLocale(): Flow<String> = flowOf("")
    override suspend fun setAppLocale(languageTag: String) {}
    override fun savingsRateTargetPercent(): Flow<Int> = flowOf(20)
    override suspend fun setSavingsRateTargetPercent(percent: Int) {}
    override fun emergencyFundTargetMonths(): Flow<Int> = flowOf(6)
    override suspend fun setEmergencyFundTargetMonths(months: Int) {}
    override fun billRemindersEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setBillRemindersEnabled(enabled: Boolean) {}
    override fun billReminderSentKeys(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun setBillReminderSentKeys(keys: Set<String>) {}
}
