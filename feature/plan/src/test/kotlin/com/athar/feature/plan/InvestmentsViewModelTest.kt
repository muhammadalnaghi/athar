package com.athar.feature.plan

import com.athar.core.common.money.Money
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.athar.core.domain.repo.InvestmentRepository
import com.athar.feature.plan.goals.FakeSavedFinancialPlanRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ViewModel tests proving the headline pool rows and the inspectable breakdowns are
 * derived from the SAME repository records in one pass — including the mixed-currency
 * case that previously crashed the corpus fold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentsViewModelTest {

    private lateinit var mainDispatcher: TestDispatcher
    private val store = MutableStateFlow<Map<InvestmentPool, List<InvestmentContribution>>>(emptyMap())

    private val fakeRepo = object : InvestmentRepository {
        override fun observePoolsWithContributions(): Flow<Map<InvestmentPool, List<InvestmentContribution>>> = store
        override suspend fun upsertPool(pool: InvestmentPool) = store.update { current ->
            val records = current.entries.firstOrNull { it.key.id == pool.id }?.value.orEmpty()
            current.filterKeys { it.id != pool.id } + (pool to records)
        }
        override suspend fun upsertContribution(contribution: InvestmentContribution) = store.update { map ->
            map.mapValues { (pool, contributions) ->
                if (pool.id == contribution.poolId) {
                    contributions.filterNot { it.id == contribution.id } + contribution
                } else {
                    contributions
                }
            }
        }
        override suspend fun deletePool(id: String) = store.update { map -> map.filterKeys { it.id != id } }
        override suspend fun deleteContribution(id: String) = store.update { map ->
            map.mapValues { (_, contributions) -> contributions.filterNot { it.id == id } }
        }
    }

    @BeforeEach
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        store.value = emptyMap()
    }

    private fun pool(id: String = "p1", name: String = "Marble fund") = InvestmentPool(
        id = id,
        name = name,
        period = "3 months",
        totalReturn = Money.of("100.00"),
    )

    private fun contribution(poolId: String, id: String, owner: String, amount: String, currency: String = "SAR") =
        InvestmentContribution(id = id, poolId = poolId, ownerName = owner, amount = Money.of(amount, currency))

    @Test
    fun `headline rows and breakdowns derive from the same records`() = runTest(mainDispatcher) {
        val poolRecord = pool()
        store.value = mapOf(
            poolRecord to listOf(
                contribution("p1", "c1", "Ammar", "300.00"),
                contribution("p1", "c2", "Sara", "100.00"),
            ),
        )
        val viewModel = InvestmentsViewModel(fakeRepo, FakeSavedFinancialPlanRepository(), mainDispatcher)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.pools).hasSize(1)
        assertThat(state.breakdowns).hasSize(1)
        val row = state.pools.single()
        val breakdown = state.breakdowns.single()
        assertThat(breakdown.poolId).isEqualTo(row.pool.id)
        // Same corpus base for headline and details.
        assertThat(row.totalCorpus.amount.compareTo(breakdown.currencyGroups.first { it.isPoolReturnCurrency }.corpus.amount))
            .isEqualTo(0)
        // Every same-currency contribution appears in both views of the records.
        assertThat(row.contributors.map { it.id })
            .containsExactlyElementsIn(breakdown.contributors.map { it.id })
        assertThat(breakdown.contributionCount).isEqualTo(2)
    }

    @Test
    fun `mixed-currency pool no longer crashes and never merges currencies`() = runTest(mainDispatcher) {
        val poolRecord = pool()
        store.value = mapOf(poolRecord to listOf(contribution("p1", "c1", "Ammar", "300.00")))
        val viewModel = InvestmentsViewModel(fakeRepo, FakeSavedFinancialPlanRepository(), mainDispatcher)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        // Previously this fold threw IllegalArgumentException (Money currency guard).
        viewModel.onEvent(InvestmentsEvent.SaveContribution(poolRecord.id, "Omar", Money.of("50.00", "USD")))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.breakdowns.single().mixedCurrency).isTrue()
        assertThat(state.breakdowns.single().currencyGroups.map { it.currency }).containsExactly("SAR", "USD")
        // Keep every record visible/removable, but withhold ALL whole-pool allocations.
        assertThat(state.pools.single().contributors).hasSize(2)
        assertThat(state.pools.single().contributors.all { it.shareReturn == null && it.netTotal == null }).isTrue()
        assertThat(state.pools.single().declaredReturnPercent).isNull()
        assertThat(state.breakdowns.single().contributionCount).isEqualTo(2)
    }

    @Test
    fun `delete contribution updates headline rows and breakdown together`() = runTest(mainDispatcher) {
        val poolRecord = pool()
        store.value = mapOf(
            poolRecord to listOf(
                contribution("p1", "c1", "Ammar", "300.00"),
                contribution("p1", "c2", "Sara", "100.00"),
            ),
        )
        val viewModel = InvestmentsViewModel(fakeRepo, FakeSavedFinancialPlanRepository(), mainDispatcher)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        viewModel.onEvent(InvestmentsEvent.DeleteContribution("c2"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.pools.single().contributors.map { it.id }).containsExactly("c1")
        assertThat(state.breakdowns.single().contributionCount).isEqualTo(1)
        assertThat(state.breakdowns.single().contributors.map { it.id }).containsExactly("c1")
    }

    @Test
    fun `decimal percentage edits update one real-currency pool without duplicate mock keys`() = runTest(mainDispatcher) {
        val record = pool()
        store.value = mapOf(record to listOf(contribution("p1", "c1", "Ammar", "100")))
        val vm = InvestmentsViewModel(fakeRepo, FakeSavedFinancialPlanRepository(), mainDispatcher)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onEvent(InvestmentsEvent.UpdatePoolReturnPercent("p1", BigDecimal("25")))
        advanceUntilIdle()
        assertThat(store.value).hasSize(1)
        assertThat(store.value.keys.single().totalReturn.amount.compareTo(BigDecimal("25.00"))).isEqualTo(0)
        assertThat(vm.state.value.writeProblem).isNull()
    }

    @Test
    fun `mixed-currency percentage editing cannot turn the SAR subset into a whole-pool base`() = runTest(mainDispatcher) {
        val record = pool()
        store.value = mapOf(record to listOf(contribution("p1", "c1", "Ammar", "100"), contribution("p1", "c2", "Omar", "100", "USD")))
        val vm = InvestmentsViewModel(fakeRepo, FakeSavedFinancialPlanRepository(), mainDispatcher)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onEvent(InvestmentsEvent.UpdatePoolReturnPercent("p1", BigDecimal("50")))
        advanceUntilIdle()
        assertThat(store.value.keys.single().totalReturn).isEqualTo(record.totalReturn)
        assertThat(vm.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.UNSUPPORTED_ALLOCATION)
        assertThat(vm.state.value.pools.single().contributors).hasSize(2)
    }

    @Test
    fun `unsupported legacy precision never reaches contribution persistence`() = runTest(mainDispatcher) {
        val record = pool()
        store.value = mapOf(record to emptyList())
        val vm = InvestmentsViewModel(fakeRepo, FakeSavedFinancialPlanRepository(), mainDispatcher)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onEvent(InvestmentsEvent.SaveContribution("p1", "Ammar", Money.of("10.123", "KWD")))
        advanceUntilIdle()
        assertThat(store.value.values.single()).isEmpty()
        assertThat(vm.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.UNSUPPORTED_AMOUNT)
    }
}
