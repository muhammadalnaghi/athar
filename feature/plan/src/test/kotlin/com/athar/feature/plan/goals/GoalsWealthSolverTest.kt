package com.athar.feature.plan.goals

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Account
import com.athar.core.domain.model.AccountBalance
import com.athar.core.domain.model.AccountType
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.NetWorth
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.wealth.ContributionTiming
import com.athar.core.domain.model.wealth.GoalInputProblemCode
import com.athar.core.domain.model.wealth.GoalNotReachableReason
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.feature.plan.GoalsEvent
import com.athar.feature.plan.GoalsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
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
import java.math.BigDecimal
import java.time.YearMonth

/**
 * WM028-P-goals: the goal-solver adapter and its ViewModel wiring, tested against the
 * accepted `GoalPlanCalc` for real outcomes — exact cash-only arithmetic, the labeled
 * cash-only twin, honest reach/not-reachable answers from a fixed clock, and invalid
 * inputs that never become numbers. Existing derivation and persisted target events are
 * asserted unchanged.
 *
 * WM028-P-goals-finalize regressions (AUTHORED NOT RUN — parent owns builds):
 * no compute/reset before preferences bind a currency, literal currency binding that
 * discards instead of relabeling on SAR→KWD, late superseded solves that commit
 * nothing (edit or switch during a pending computation), exact sub-cent seed text
 * (KWD 3 dp), and foreign-currency inputs excluded from totals instead of relabeled.
 * The VM is built through the internal `solveDispatcher` seam so every solve is
 * deterministic on the shared test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalScenarioDraftTest {

    private val startMonth = YearMonth.of(2026, 6)

    @Test
    fun `arabic indic digits and separators normalize before parsing`() {
        assertThat(parseDecimal("١٢٫٥")!!.compareTo(BigDecimal("12.5"))).isEqualTo(0)
        assertThat(parseDecimal("1٬000")!!.compareTo(BigDecimal("1000"))).isEqualTo(0)
        assertThat(parseDecimal(" ")).isNull()
        assertThat(parseDecimal("abc")).isNull()
    }

    @Test
    fun `seeding uses actual liquid balance and clamps negative savings`() {
        val seeded = GoalScenarioDraft.seeded(Money.of("7500.00", "SAR"), Money.of("-500", "SAR"))
        assertThat(seeded.currentAllocationText).isEqualTo("7500.00")
        // Exact literal text, no 2-dp re-scale: the zero clamp prints as "0".
        assertThat(seeded.monthlyContributionText).isEqualTo("0")

        val positive = GoalScenarioDraft.seeded(Money.of("0", "SAR"), Money.of("3000.50", "SAR"))
        assertThat(positive.monthlyContributionText).isEqualTo("3000.50")
    }

    @Test
    fun `seeded kwd amounts keep their exact literal decimals`() {
        val seeded = GoalScenarioDraft.seeded(Money.of("10.123", "KWD"), Money.of("0.500", "KWD"))
        assertThat(seeded.currentAllocationText).isEqualTo("10.123")
        assertThat(seeded.monthlyContributionText).isEqualTo("0.500")
    }

    @Test
    fun `seed precision beyond two decimals reaches the solver unrounded`() {
        // Projection and funding are different questions. Verify exact projected
        // money and zero cash growth too; an AlreadyFunded assertion alone hid output rounding.
        val seeded = GoalScenarioDraft.seeded(Money.of("10.123", "KWD"), Money.of("0.500", "KWD"))
            .copy(targetAmountText = "10.123", horizonMonthsText = "12")

        val projected = seeded.solve("KWD", startMonth) as GoalSolveUi.Projected
        assertThat(moneyIs(projected.projection.futureAmount, "16.123")).isTrue()
        assertThat(moneyIs(projected.projection.growthAmount, "0")).isTrue()
        assertThat(moneyIs(projected.projection.nominalTargetAtHorizon, "10.123")).isTrue()
        assertThat(seeded.copy(solveMode = GoalSolveMode.REQUIRED_MONTHLY_CONTRIBUTION).solve("KWD", startMonth))
            .isEqualTo(GoalSolveUi.AlreadyFunded)
    }

    @Test
    fun `cash only projection is exact with zero growth`() {
        val result = draft(target = "12000", allocation = "1000", contribution = "100", horizon = "12")
            .solve("SAR", startMonth) as GoalSolveUi.Projected

        assertThat(moneyIs(result.projection.futureAmount, "2200.00")).isTrue()
        assertThat(moneyIs(result.projection.totalContributions, "1200")).isTrue()
        assertThat(moneyIs(result.projection.growthAmount, "0")).isTrue()
        assertThat(moneyIs(result.projection.nominalTargetAtHorizon, "12000.00")).isTrue()
        // The scenario is already cash-only, so there is no separate baseline twin.
        assertThat(result.cashOnlyFutureAmount).isNull()
    }

    @Test
    fun `apr projection carries a labeled cash only twin`() {
        val result = draft(target = "12000", allocation = "1000", contribution = "100", horizon = "12")
            .copy(
                assumptionsKind = GoalAssumptionsKind.FIXED_APR,
                aprPercentText = "12",
                feePercentText = "1",
            )
            .solve("SAR", startMonth) as GoalSolveUi.Projected

        val baseline = result.cashOnlyFutureAmount!!
        assertThat(baseline.compareTo(Money.of("2200.00", "SAR"))).isEqualTo(0)
        assertThat(result.projection.futureAmount > baseline).isTrue()
    }

    @Test
    fun `months mode reports the real reach month from the caller clock`() {
        val result = draft(target = "1200", allocation = "0", contribution = "100", horizon = "")
            .copy(solveMode = GoalSolveMode.REQUIRED_MONTHS)
            .solve("SAR", startMonth) as GoalSolveUi.RequiredMonthsUi

        assertThat(result.value.months).isEqualTo(12)
        assertThat(result.value.reachMonth).isEqualTo(YearMonth.of(2027, 6))
        assertThat(moneyIs(result.value.nominalTargetAtReach, "1200.00")).isTrue()
    }

    @Test
    fun `unreachable scenarios report typed reasons never fabricated results`() {
        val never = draft(target = "1200", allocation = "100", contribution = "0", horizon = "")
            .copy(solveMode = GoalSolveMode.REQUIRED_MONTHS)
            .solve("SAR", startMonth) as GoalSolveUi.NotReachable
        assertThat(never.reason).isEqualTo(GoalNotReachableReason.NEVER_REACHES)

        val beyondHorizon = draft(target = "1000000", allocation = "0", contribution = "1", horizon = "")
            .copy(solveMode = GoalSolveMode.REQUIRED_MONTHS)
            .solve("SAR", startMonth) as GoalSolveUi.NotReachable
        assertThat(beyondHorizon.reason).isEqualTo(GoalNotReachableReason.BEYOND_TECHNICAL_HORIZON)

        val bounds = draft(target = "1000000000000000000", allocation = "1000", contribution = "0", horizon = "12")
            .copy(
                assumptionsKind = GoalAssumptionsKind.FIXED_APR,
                aprPercentText = "10",
                feePercentText = "0",
                solveMode = GoalSolveMode.REQUIRED_NOMINAL_APR,
            )
            .solve("SAR", startMonth) as GoalSolveUi.NotReachable
        assertThat(bounds.reason).isEqualTo(GoalNotReachableReason.BEYOND_SOLVER_RATE_BOUNDS)
    }

    @Test
    fun `horizon funded without contributions reports already funded`() {
        val result = draft(target = "12000", allocation = "12000", contribution = "0", horizon = "12")
            .copy(
                assumptionsKind = GoalAssumptionsKind.FIXED_APR,
                aprPercentText = "6",
                feePercentText = "1",
                solveMode = GoalSolveMode.REQUIRED_MONTHLY_CONTRIBUTION,
            )
            .solve("SAR", startMonth)

        assertThat(result).isEqualTo(GoalSolveUi.AlreadyFunded)
    }

    @Test
    fun `local field errors block the solver before coercion`() {
        val invalid = draft(target = "0", allocation = "1000", contribution = "-5", horizon = "abc")
            .solve("SAR", startMonth) as GoalSolveUi.Invalid
        assertThat(invalid.fieldErrors).containsExactly(
            GoalField.TARGET_AMOUNT,
            GoalField.MONTHLY_CONTRIBUTION,
            GoalField.HORIZON_MONTHS,
        )
        assertThat(invalid.problems).isEmpty()

        val missingFee = draft(target = "12000", allocation = "1000", contribution = "100", horizon = "12")
            .copy(assumptionsKind = GoalAssumptionsKind.FIXED_APR, aprPercentText = "5")
            .solve("SAR", startMonth) as GoalSolveUi.Invalid
        assertThat(missingFee.fieldErrors).containsExactly(GoalField.FEE_PERCENT)

        val missingInflation = draft(target = "12000", allocation = "1000", contribution = "100", horizon = "12")
            .copy(targetBasis = GoalTargetBasis.TODAY_PRICE)
            .solve("SAR", startMonth) as GoalSolveUi.Invalid
        assertThat(missingInflation.fieldErrors).containsExactly(GoalField.TARGET_INFLATION)
    }

    @Test
    fun `solver input contract problems pass through as codes`() {
        val result = draft(target = "12000", allocation = "1000", contribution = "100", horizon = "12")
            .copy(targetBasis = GoalTargetBasis.TODAY_PRICE, inflationPercentText = "-2000")
            .solve("SAR", startMonth) as GoalSolveUi.Invalid

        assertThat(result.fieldErrors).isEmpty()
        assertThat(result.problems).containsExactly(GoalInputProblemCode.INFLATION_MONTHLY_RATE_OUT_OF_RANGE)
    }

    @Test
    fun `timing is declared and reaches the solver unchanged`() {
        val declared = draft(target = "1160", allocation = "0", contribution = "100", horizon = "")
            .copy(solveMode = GoalSolveMode.REQUIRED_MONTHS, assumptionsKind = GoalAssumptionsKind.FIXED_APR,
                aprPercentText = "12", feePercentText = "0")
        val beginning = declared.copy(timing = ContributionTiming.BEGINNING_OF_MONTH)
            .solve("SAR", startMonth) as GoalSolveUi.RequiredMonthsUi
        val end = declared.copy(timing = ContributionTiming.END_OF_MONTH)
            .solve("SAR", startMonth) as GoalSolveUi.RequiredMonthsUi
        // The independently recomputed positive-growth recurrence reaches1160 at month11 vs12.
        // CashOnly gains no extra deposit merely from its timing label.
        assertThat(beginning.value.months).isEqualTo(11)
        assertThat(end.value.months).isEqualTo(12)
    }

    private fun draft(target: String, allocation: String, contribution: String, horizon: String): GoalScenarioDraft =
        GoalScenarioDraft(
            targetAmountText = target,
            currentAllocationText = allocation,
            monthlyContributionText = contribution,
            horizonMonthsText = horizon,
        )

    private fun moneyIs(money: Money, expected: String): Boolean =
        money.amount.compareTo(BigDecimal(expected)) == 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelScenarioTest {

    private lateinit var mainDispatcher: TestDispatcher
    private lateinit var clock: MutableTestClock

    @BeforeEach
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        clock = MutableTestClock(Instant.parse("2026-06-15T12:00:00Z"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scenario starts pristine and existing derivation is unchanged`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.scenario.result).isNull()
        assertThat(state.scenario.draft).isEqualTo(GoalScenarioDraft.empty())
        // Real figures from the fakes: 10_000 income, 3_000 expense, 7_500 liquid.
        assertThat(moneyIs(state.monthlyIncome, "10000")).isTrue()
        assertThat(moneyIs(state.monthlyExpense, "3000")).isTrue()
        assertThat(moneyIs(state.monthlySavings, "7000")).isTrue()
        assertThat(moneyIs(state.liquidBalance, "7500")).isTrue()
        assertThat(state.currency).isEqualTo("SAR")
    }

    @Test
    fun `compute solves cash only projection and edits clear the stale result`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        // A real screen keeps collecting. advanceUntilIdle otherwise advances the
        // WhileSubscribed stop timeout after first(), leaving only a cached old state.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.state.collect() }
        viewModel.state.first { !it.isLoading }

        viewModel.onEvent(
            GoalsEvent.UpdateScenario(
                GoalScenarioDraft(
                    targetAmountText = "12000",
                    currentAllocationText = "1000",
                    monthlyContributionText = "100",
                    horizonMonthsText = "12",
                ),
            ),
        )
        viewModel.onEvent(GoalsEvent.ComputeScenario)
        advanceUntilIdle()

        val projected = viewModel.state.value.scenario.result as GoalSolveUi.Projected
        assertThat(moneyIs(projected.projection.futureAmount, "2200.00")).isTrue()
        assertThat(projected.cashOnlyFutureAmount).isNull()

        viewModel.onEvent(
            GoalsEvent.UpdateScenario(
                viewModel.state.value.scenario.draft.copy(targetAmountText = "13000"),
            ),
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.scenario.result).isNull()
    }

    @Test
    fun `reset seeds the draft from actual liquid balance and monthly savings`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        viewModel.state.first { !it.isLoading }

        viewModel.onEvent(GoalsEvent.ResetScenario)
        advanceUntilIdle()

        val draft = viewModel.state.value.scenario.draft
        assertThat(draft.currentAllocationText).isEqualTo("7500.00")
        assertThat(draft.monthlyContributionText).isEqualTo("7000.00")
        assertThat(viewModel.state.value.scenario.result).isNull()
    }

    @Test
    fun `existing persisted target events are untouched`() = runTest(mainDispatcher) {
        val prefs = FakeUserPreferencesRepository()
        val viewModel = goalsViewModel(prefs = prefs)
        viewModel.state.first { !it.isLoading }

        viewModel.onEvent(GoalsEvent.SetSavingsRateTarget(35))
        advanceUntilIdle()

        assertThat(prefs.savingsRateCalls).containsExactly(35)
    }

    @Test
    fun `compute and reset before preferences load stay no ops`() = runTest(mainDispatcher) {
        // No collector has run yet, so no preference has ever bound a currency here.
        val viewModel = goalsViewModel()
        backgroundScope.launch { viewModel.state.collect() }

        viewModel.onEvent(GoalsEvent.ComputeScenario)
        viewModel.onEvent(GoalsEvent.ResetScenario)
        advanceUntilIdle()

        val state = viewModel.state.value
        // Preferences did bind "SAR" afterwards — but it was never used to solve above.
        assertThat(state.currency).isEqualTo("SAR")
        assertThat(state.scenario.result).isNull()
        assertThat(state.scenario.draft).isEqualTo(GoalScenarioDraft.empty())
    }

    @Test
    fun `switching display currency discards the scenario instead of relabeling it`() = runTest(mainDispatcher) {
        val prefs = FakeUserPreferencesRepository(initialCurrency = "SAR")
        val viewModel = goalsViewModel(prefs = prefs)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        assertThat(viewModel.state.value.isLoading).isFalse()

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.ComputeScenario)
        advanceUntilIdle()
        assertThat(viewModel.state.value.scenario.result).isNotNull() // solved under SAR

        prefs.setDisplayCurrency("KWD")
        advanceUntilIdle()

        val scenario = viewModel.state.value.scenario
        assertThat(viewModel.state.value.currency).isEqualTo("KWD")
        assertThat(scenario.currency).isEqualTo("KWD")
        assertThat(scenario.draft).isEqualTo(GoalScenarioDraft.empty())
        assertThat(scenario.result).isNull()
    }

    @Test
    fun `edit while a computation is pending discards the late result`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        val draft = scenarioDraft()
        viewModel.onEvent(GoalsEvent.UpdateScenario(draft))
        viewModel.onEvent(GoalsEvent.ComputeScenario) // queued, not yet solved
        viewModel.onEvent(GoalsEvent.UpdateScenario(draft.copy(targetAmountText = "13000")))
        advanceUntilIdle()

        assertThat(viewModel.state.value.scenario.result).isNull()

        viewModel.onEvent(GoalsEvent.ComputeScenario) // a fresh computation still commits
        advanceUntilIdle()
        val committed = viewModel.state.value.scenario
        assertThat(committed.draft.targetAmountText).isEqualTo("13000")
        assertThat(committed.result).isInstanceOf(GoalSolveUi.Projected::class.java)
    }

    @Test
    fun `currency switch while a computation is pending discards its late result`() = runTest(mainDispatcher) {
        val prefs = FakeUserPreferencesRepository(initialCurrency = "SAR")
        val viewModel = goalsViewModel(prefs = prefs)
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.ComputeScenario) // queued, not yet solved
        prefs.setDisplayCurrency("KWD")
        advanceUntilIdle()

        val scenario = viewModel.state.value.scenario
        assertThat(viewModel.state.value.currency).isEqualTo("KWD")
        assertThat(scenario.currency).isEqualTo("KWD")
        assertThat(scenario.draft).isEqualTo(GoalScenarioDraft.empty())
        assertThat(scenario.result).isNull()
    }

    @Test
    fun `foreign currency inputs are excluded from totals not relabeled`() = runTest(mainDispatcher) {
        val usdBalance = AccountBalance(
            account = Account(
                id = "acc-usd",
                name = "acc-usd",
                type = AccountType.SAVINGS,
                currency = "USD",
                openingBalance = Money.zero("USD"),
                smsSenders = emptyList(),
                notes = null,
                sortOrder = 0,
                archived = false,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
            current = Money.of("2500.00", "USD"),
        )
        val viewModel = goalsViewModel(
            transactions = defaultTransactions() +
                tx("inc-usd", LocalDate(2026, 5, 1), 1000000, TxType.INCOME, currency = "USD"),
            accounts = FakeAccountRepository(liquidBalances = listOf(
                usdBalance.copy(
                    account = usdBalance.account.copy(id = "acc-sar", name = "acc-sar", currency = "SAR", openingBalance = Money.zero("SAR")),
                    current = Money.of("5000.00", "SAR"),
                ),
                usdBalance,
            )),
        )
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()

        val state = viewModel.state.value
        // The 10_000 USD income and 2_500 USD balance are excluded (no FX), never
        // relabeled 1:1 into the SAR totals.
        assertThat(moneyIs(state.monthlyIncome, "10000")).isTrue()
        assertThat(moneyIs(state.monthlySavings, "7000")).isTrue()
        assertThat(moneyIs(state.liquidBalance, "5000")).isTrue()
        assertThat(state.excludedCurrencies).containsExactly("USD")
    }

    @Test
    fun `negative liquid balance stays signed while optional seed funding remains zero`() = runTest(mainDispatcher) {
        val defaults = FakeAccountRepository()
        val checking = defaults.observeAll(includeArchived = true).first().first()
        val viewModel = goalsViewModel(accounts = FakeAccountRepository(liquidBalances = listOf(
            AccountBalance(checking, Money.of("-200.00", "SAR")),
        )))
        backgroundScope.launch { viewModel.state.collect() }
        advanceUntilIdle()
        assertThat(moneyIs(viewModel.state.value.liquidBalance, "-200")).isTrue()
        viewModel.onEvent(GoalsEvent.ResetScenario)
        advanceUntilIdle()
        assertThat(viewModel.state.value.scenario.draft.currentAllocationText).isEqualTo("0")
    }

    @Test
    fun `reset before financial facts load cannot replace manual assumptions with invented zero seeds`() = runTest(mainDispatcher) {
        val defaults = FakeAccountRepository()
        val gate = MutableStateFlow<NetWorth?>(null)
        val delayed = object : AccountRepository by defaults {
            override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> = gate.filterNotNull()
        }
        val viewModel = goalsViewModel(accounts = delayed)
        backgroundScope.launch { viewModel.state.collect() }
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.ResetScenario)
        gate.value = defaults.observeNetWorth("SAR").first()
        advanceUntilIdle()
        assertThat(viewModel.state.value.scenario.draft).isEqualTo(scenarioDraft())
    }

    private fun goalsViewModel(
        prefs: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
        transactions: List<Transaction> = defaultTransactions(),
        accounts: AccountRepository = FakeAccountRepository(),
    ): GoalsViewModel = GoalsViewModel(
        transactions = FakeTransactionRepository(transactions),
        accounts = accounts,
        prefs = prefs,
        clock = clock,
        savedPlans = FakeSavedFinancialPlanRepository(),
        solveDispatcher = mainDispatcher,
    )

    private fun scenarioDraft(): GoalScenarioDraft = GoalScenarioDraft(
        targetAmountText = "12000",
        currentAllocationText = "1000",
        monthlyContributionText = "100",
        horizonMonthsText = "12",
    )

    private fun defaultTransactions(): List<Transaction> = listOf(
        tx("inc-1", LocalDate(2026, 4, 1), 1000000, TxType.INCOME),
        tx("inc-2", LocalDate(2026, 5, 1), 1000000, TxType.INCOME),
        tx("inc-3", LocalDate(2026, 6, 1), 1000000, TxType.INCOME),
        tx("exp-1", LocalDate(2026, 5, 1), 900000, TxType.EXPENSE),
    )

    private fun moneyIs(money: Money, expected: String): Boolean =
        money.amount.compareTo(BigDecimal(expected)) == 0

    private class FakeTransactionRepository(
        private val rows: List<Transaction>,
    ) : TransactionRepository {
        override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> =
            flowOf(rows.filter { it.date >= period.start && it.date < period.endExclusive })

        override fun observePending(): Flow<List<Transaction>> =
            flowOf(rows.filter { it.status == TxStatus.PENDING })

        override fun observeAll(): Flow<List<Transaction>> = flowOf(rows)
        override suspend fun get(id: String): Transaction? = rows.firstOrNull { it.id == id }
        override suspend fun upsertIngest(transaction: Transaction): String =
            error("Ingestion is outside this UI or import test fixture")

        override suspend fun upsert(transaction: Transaction) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun setStatus(id: String, status: TxStatus) = Unit
        override suspend fun clearPending(): Int = 0
        override suspend fun confirmAllConfident(minConfidence: Float): Int = 0
        override suspend fun dismissAllLowConfidence(maxConfidence: Float): Int = 0
        override suspend fun dismissAllPending(): Int = 0
        override suspend fun recoverDismissedToPending(): Int = 0
        override suspend fun applyCategoryToMatching(pattern: String, categoryId: String): Int = 0
    }

    private class FakeAccountRepository(
        /** Overrides the default same-currency balances with a mixed-currency set. */
        private val liquidBalances: List<AccountBalance>? = null,
    ) : AccountRepository {
        private val checking = account("acc-checking", AccountType.CHECKING)
        private val savings = account("acc-savings", AccountType.SAVINGS)

        override fun observeActive(): Flow<List<Account>> = flowOf(listOf(checking, savings))
        override fun observeAll(includeArchived: Boolean): Flow<List<Account>> = flowOf(listOf(checking, savings))
        override suspend fun get(id: String): Account? = null
        override suspend fun upsert(account: Account) = Unit
        override suspend fun setArchived(id: String, archived: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
        override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> = flowOf(
            liquidBalances?.let { balances ->
                // Deliberately ignores displayCurrency: real per-account balances keep
                // their native currency, and the label alone does not homogenize them.
                NetWorth(
                    total = Money.sumAmounts(balances.map { it.current }, displayCurrency),
                    byCurrency = emptyMap(),
                    accounts = balances,
                )
            } ?: NetWorth(
                total = Money.of("7500.00", displayCurrency),
                byCurrency = mapOf(displayCurrency to Money.of("7500.00", displayCurrency)),
                accounts = listOf(
                    AccountBalance(checking, Money.of("5000.00", displayCurrency)),
                    AccountBalance(savings, Money.of("2500.00", displayCurrency)),
                ),
            ),
        )
        override fun observeBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())
        override suspend fun resolveForIngest(sender: String, body: String, counterparty: String?): Account? = null
        override suspend fun reconcile(
            accountId: String,
            target: Money,
            label: String,
            note: String?,
        ): ReconcileResult = ReconcileResult.Failed("unused in goals tests")

        private fun account(id: String, type: AccountType): Account = Account(
            id = id,
            name = id,
            type = type,
            currency = "SAR",
            openingBalance = Money.zero("SAR"),
            smsSenders = emptyList(),
            notes = null,
            sortOrder = 0,
            archived = false,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
    }

    private class FakeUserPreferencesRepository(
        /** null simulates preferences that have not loaded any display currency yet. */
        initialCurrency: String? = "SAR",
    ) : UserPreferencesRepository {
        val savingsRateCalls = mutableListOf<Int>()
        val emergencyCalls = mutableListOf<Int>()
        private val currencyFlow = initialCurrency?.let { MutableStateFlow(it) }

        override fun onboardingComplete(): Flow<Boolean> = flowOf(true)
        override suspend fun setOnboardingComplete(complete: Boolean) = Unit
        override fun lastSmsBackfillEpochSeconds(): Flow<Long> = flowOf(0L)
        override suspend fun setLastSmsBackfillEpochSeconds(epoch: Long) = Unit
        override fun hijriEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setHijriEnabled(enabled: Boolean) = Unit
        override fun ownAccountNumbers(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOwnAccountNumbers(numbers: List<String>) = Unit
        override fun displayCurrency(): Flow<String> = currencyFlow ?: emptyFlow()
        override suspend fun setDisplayCurrency(currency: String) {
            currencyFlow?.value = currency
        }
        override fun appLocale(): Flow<String> = flowOf("")
        override suspend fun setAppLocale(languageTag: String) = Unit
        override fun savingsRateTargetPercent(): Flow<Int> = flowOf(20)
        override suspend fun setSavingsRateTargetPercent(percent: Int) {
            savingsRateCalls += percent
        }
        override fun emergencyFundTargetMonths(): Flow<Int> = flowOf(6)
        override suspend fun setEmergencyFundTargetMonths(months: Int) {
            emergencyCalls += months
        }
        override fun billRemindersEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setBillRemindersEnabled(enabled: Boolean) = Unit
        override fun billReminderSentKeys(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun setBillReminderSentKeys(keys: Set<String>) = Unit
    }

    private class MutableTestClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}

private fun tx(
    id: String,
    date: LocalDate,
    amountMinor: Long,
    type: TxType,
    currency: String = "SAR",
): Transaction = Transaction(
    id = id,
    accountId = "acc-checking",
    type = type,
    amount = Money.ofMinor(amountMinor, currency),
    date = date,
    occurredAt = null,
    merchant = "Sample",
    merchantNormalized = "sample",
    categoryId = "cat-sample",
    notes = null,
    source = IngestSource.MANUAL,
    sourceRefId = null,
    status = TxStatus.CONFIRMED,
    confidence = 1f,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)
