package com.athar.feature.plan.portfolio

import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.athar.core.domain.repo.InvestmentRepository
import com.athar.feature.plan.InvestmentWriteProblem
import com.athar.feature.plan.InvestmentsEvent
import com.athar.feature.plan.InvestmentsState
import com.athar.feature.plan.InvestmentsViewModel
import com.athar.feature.plan.PortfolioDocState
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Saved-portfolio flow over the frozen boundary (test fake standing in for the
 * store lane's encrypted Room binding): create → open → edit → statement import
 * apply/cancel → conflict refusal → foreign-currency document retained-unavailable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentsPortfolioSavedTest {

    private lateinit var mainDispatcher: TestDispatcher
    private val store = MutableStateFlow<Map<InvestmentPool, List<InvestmentContribution>>>(emptyMap())

    private val fakeRepo = object : InvestmentRepository {
        override fun observePoolsWithContributions(): Flow<Map<InvestmentPool, List<InvestmentContribution>>> = store
        override suspend fun upsertPool(pool: InvestmentPool) = store.update { current ->
            val records = current.entries.firstOrNull { (key, _) -> key.id == pool.id }?.value.orEmpty()
            current.filterKeys { key -> key.id != pool.id } + (pool to records)
        }
        override suspend fun upsertContribution(contribution: InvestmentContribution) = Unit
        override suspend fun deletePool(id: String) = store.update { map -> map.filterKeys { key -> key.id != id } }
        override suspend fun deleteContribution(id: String) = Unit
    }

    private val savedPlans = FakeSavedFinancialPlanRepository()

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

    private fun viewModel(): InvestmentsViewModel = InvestmentsViewModel(fakeRepo, savedPlans, mainDispatcher)

    @Test
    fun `explicit opening date survives create statement preview apply and reopen`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("Dated opening054", "SAR", "1000.000", "2026-01-01"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        val before = savedPlans.stored.single()
        assertThat(viewModel.state.value.portfolioOpen!!.workbench.document.openingCashDate).isEqualTo("2026-01-01")
        val csv = PortfolioStatementImport.COLUMNS.joinToString(",") +
            "\nBUY,date054,2026-01-01,QA054,Public synthetic,EQUITY,10,100,0,,," +
            "\nQUOTE,,2026-04-01,QA054,,,,120,,,,"
        viewModel.onEvent(InvestmentsEvent.ImportPortfolioStatement(planId, csv))
        advanceUntilIdle()
        assertThat(viewModel.state.value.importPreview!!.preview.canApply).isTrue()
        assertThat(savedPlans.stored.single()).isEqualTo(before)
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioImport)
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.ClosePortfolio)
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.OpenPortfolio(planId))
        advanceUntilIdle()
        val bench = viewModel.state.value.portfolioOpen!!.workbench
        assertThat(bench.document.openingCashDate).isEqualTo("2026-01-01")
        assertThat(bench.document.openingCashAmount).isEqualTo("1000.000")
        assertThat(bench.accounting!!.cash.amount.signum()).isEqualTo(0)
        assertThat(bench.accounting!!.valuation.total!!.amount.compareTo(java.math.BigDecimal("1200"))).isEqualTo(0)
        assertThat(bench.xirr).isInstanceOf(com.athar.core.domain.calc.portfolio.XirrResult.Available::class.java)
    }

    @Test
    fun `invalid optional opening date refuses creation without a stored row`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        for (date in listOf("2026-02-30", "01/01/2026", "not-a-date")) {
            viewModel.onEvent(InvestmentsEvent.CreatePortfolio("Invalid date054", "SAR", "1000", date))
            advanceUntilIdle()
            assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)
            assertThat(savedPlans.stored).isEmpty()
        }
    }

    @Test
    fun `create then reopen keeps exact inputs and shows an honest empty workbench`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظتي", "KWD", "1250.750"))
        advanceUntilIdle()

        val stored = savedPlans.stored.single()
        assertThat(stored.currency).isEqualTo("KWD")
        assertThat(stored.payloadJson).contains("\"openingCashAmount\":\"1250.750\"")
        assertThat(stored.payloadJson).contains("\"portfolioPlanVersion\":1")

        val state = viewModel.state.value
        val row = state.portfolios.single()
        assertThat(row.state).isInstanceOf(PortfolioDocState.Ready::class.java)
        // Opening-only portfolio: an honest empty state, not a zero valuation.
        assertThat(state.portfolioOpen).isNotNull()
        assertThat(state.portfolioOpen!!.workbench.isEmpty).isTrue()

        // Reopen from the row list reproduces the same document inputs verbatim.
        viewModel.onEvent(InvestmentsEvent.ClosePortfolio)
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen).isNull()
        viewModel.onEvent(InvestmentsEvent.OpenPortfolio(row.planId))
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen?.workbench?.document?.openingCashAmount).isEqualTo("1250.750")
    }

    @Test
    fun `dated entry validates through the engines and refuses an invalid sell`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة", "SAR", "1000.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id

        viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق العشرة", "EQUITY"))
        advanceUntilIdle()
        assertThat(savedPlans.stored.single().payloadJson).contains("TEN")

        viewModel.onEvent(
            InvestmentsEvent.AddPortfolioEntry(
                planId = planId,
                entry = com.athar.feature.plan.PortfolioEntryDraft(
                    kind = PortfolioEntryKind.BUY,
                    securityId = "TEN",
                    dateText = "2026-03-01",
                    quantityText = "5",
                    priceText = "20.000",
                    feeText = "1.00",
                ),
            ),
        )
        advanceUntilIdle()
        val accounting = viewModel.state.value.portfolioOpen?.workbench?.accounting
        assertThat(accounting).isNotNull()
        assertThat(accounting!!.holdings.single().securityId).isEqualTo("TEN")
        // Independent decimal oracle: 1000.00 - 5 * 20.000 - 1.00 = 899.000.
        assertThat(accounting.cash.amount.toPlainString()).isEqualTo("899.000")
        assertThat(viewModel.state.value.writeProblem).isNull()

        // Selling more than owned is refused by the reviewed engine BEFORE persistence.
        viewModel.onEvent(
            InvestmentsEvent.AddPortfolioEntry(
                planId = planId,
                entry = com.athar.feature.plan.PortfolioEntryDraft(
                    kind = PortfolioEntryKind.SELL,
                    securityId = "TEN",
                    dateText = "2026-03-05",
                    quantityText = "50",
                    priceText = "21.000",
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioProblem).isEqualTo("UNSUPPORTED_SHORT_SALE")
        assertThat(savedPlans.stored.single().payloadJson).doesNotContain("2026-03-05")
    }

    @Test
    fun `statement import previews then applies only on explicit confirmation`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة الاستيراد", "SAR", "500.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id

        val csv = listOf(
            "type,eventId,date,securityId,securityName,assetClass,quantity,unitPrice,fee,amount,destination,note",
            "CONTRIBUTION,imp-1,2026-04-01,,,,,,,250.00,,إيداع",
            "QUOTE,,2026-04-02,GOLD,الذهب,,,110.500,,,",
        ).joinToString("\n")
        viewModel.onEvent(InvestmentsEvent.ImportPortfolioStatement(planId, csv))
        advanceUntilIdle()

        val preview = viewModel.state.value.importPreview
        assertThat(preview).isNotNull()
        assertThat(preview!!.preview.validEventCount).isEqualTo(1)
        assertThat(preview.preview.validQuoteCount).isEqualTo(1)
        assertThat(preview.preview.canApply).isTrue()

        // Cancel writes nothing.
        viewModel.onEvent(InvestmentsEvent.CancelPortfolioImport)
        advanceUntilIdle()
        assertThat(viewModel.state.value.importPreview).isNull()
        assertThat(savedPlans.stored.single().payloadJson).doesNotContain("imp-1")

        // Re-preview then apply persists the merged document through the revision fence.
        viewModel.onEvent(InvestmentsEvent.ImportPortfolioStatement(planId, csv))
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioImport)
        advanceUntilIdle()
        assertThat(viewModel.state.value.importPreview).isNull()
        assertThat(savedPlans.stored.single().payloadJson).contains("imp-1")
        assertThat(viewModel.state.value.portfolioOpen?.workbench?.document?.events?.single()?.eventId).isEqualTo("imp-1")
    }

    @Test
    fun `imported annotated trade saves and reopens through the same codec`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("Synthetic annotated import", "SAR", "1000"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        val csv = PortfolioStatementImport.COLUMNS.joinToString(",") +
            "\nBUY,wm029-buy,2026-10-20,QA029,QA Synthetic Holding,EQUITY,5,20,1,,,local synthetic" +
            "\nQUOTE,,2026-10-21,QA029,,,,22,,,,"
        viewModel.onEvent(InvestmentsEvent.ImportPortfolioStatement(planId, csv))
        advanceUntilIdle()
        assertThat(viewModel.state.value.importPreview?.preview?.canApply).isTrue()
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioImport)
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolios.single().state).isInstanceOf(PortfolioDocState.Ready::class.java)
        viewModel.onEvent(InvestmentsEvent.ClosePortfolio)
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.OpenPortfolio(planId))
        advanceUntilIdle()
        val open = requireNotNull(viewModel.state.value.portfolioOpen)
        assertThat(open.workbench.document.events.single().note).isEqualTo("local synthetic")
        assertThat(open.workbench.accounting!!.cash.amount.compareTo(java.math.BigDecimal("899"))).isEqualTo(0)
        assertThat(open.workbench.accounting!!.valuation.total!!.amount.compareTo(java.math.BigDecimal("1009"))).isEqualTo(0)
    }

    @Test
    fun `stale revision conflict refuses without overwriting the newer edit`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة التعارض", "SAR", "10.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id

        // An external writer (another surface) bumps the stored revision behind our back.
        savedPlans.bumpRevisionExternally(planId)
        viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "NEW1", "أداة", null))
        advanceUntilIdle()

        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.CONFLICT)
        // The newer external edit survives untouched; our stale change never landed.
        assertThat(savedPlans.stored.single().payloadJson).doesNotContain("NEW1")
    }

    @Test
    fun `payload currency disagreeing with the stored row stays retained but unavailable`() = runTest(mainDispatcher) {
        // Simulate an outer row (SAR) whose payload was authored in USD.
        savedPlans.save(
            com.athar.core.domain.repo.SavedFinancialPlan(
                id = "foreign",
                kind = com.athar.core.domain.repo.SavedFinancialPlanKind.PORTFOLIO,
                title = "محفظة بعملة مخالفة",
                currency = "SAR",
                payloadVersion = PortfolioPlanCodec.VERSION,
                payloadJson = PortfolioPlanCodec.encode(
                    PortfolioPlanDocument(currency = "USD", openingCashAmount = "100.00"),
                ),
                revision = 0,
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            ),
            expectedRevision = null,
        )
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()

        val row = viewModel.state.value.portfolios.single { it.planId == "foreign" }
        assertThat(row.state).isEqualTo(PortfolioDocState.Unavailable)
        assertThat(viewModel.state.value.portfolioOpen).isNull()
        // Retained: the document is still in storage, never silently deleted.
        assertThat(savedPlans.stored.single { it.id == "foreign" }).isNotNull()
    }
}
