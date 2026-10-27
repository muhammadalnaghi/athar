package com.athar.feature.plan.portfolio

import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.core.domain.calc.portfolio.XirrResult
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.athar.core.domain.repo.InvestmentRepository
import com.athar.feature.plan.InvestmentWriteProblem
import com.athar.feature.plan.InvestmentsEvent
import com.athar.feature.plan.InvestmentsViewModel
import com.athar.feature.plan.PortfolioEntryDraft
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WM058: add/correct/clear the opening-capital evidence date of an EXISTING saved
 * portfolio through the reviewed preview → explicit save → revision-CAS protocol.
 * Invalid, no-op, and order-violating edits must never write; cancel writes
 * nothing; a stale revision refuses before the write and never overwrites.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioOpeningDate058EditTest {

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

    /** Dated SAR portfolio: opening 2000.000 @ 2026-01-01, BUY 5×100.000 fee 1.00, QUOTE 120.000 @ 2026-04-01. */
    private suspend fun TestScope.datedPortfolio(viewModel: InvestmentsViewModel): String {
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("Edit date 058", "SAR", "2000.000", "2026-01-01"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "Public synthetic", "EQUITY"))
        advanceUntilIdle()
        viewModel.onEvent(
            InvestmentsEvent.AddPortfolioEntry(
                planId = planId,
                entry = PortfolioEntryDraft(
                    kind = PortfolioEntryKind.BUY,
                    securityId = "TEN",
                    dateText = "2026-03-01",
                    quantityText = "5",
                    priceText = "100.000",
                    feeText = "1.00",
                    note = "opening buy 058",
                ),
            ),
        )
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.AddPortfolioQuote(planId, "TEN", "2026-04-01", "120.000"))
        advanceUntilIdle()
        return planId
    }

    private fun storedDocument() = savedPlans.stored.single().let {
        (PortfolioPlanCodec.decode(it.payloadJson) as PortfolioDecodeResult.Success).document
    }

    @Test
    fun `invalid noop and order refusals write nothing then a valid edit previews saves and reopens`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val payloadBefore = savedPlans.stored.single().payloadJson
        val eventsBefore = storedDocument().events

        // Impossible calendar date: refused, nothing captured, nothing stored.
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2026-02-30"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)
        assertThat(viewModel.state.value.portfolioOpen?.openingDatePreview).isNull()
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)

        // Identical value (also wrapped in whitespace): a no-op refusal, never persisted.
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, " 2026-01-01 "))
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2026-01-01"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)

        // Date after the first entry: refused by the reviewed engines before any capture.
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2026-07-01"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioProblem).isEqualTo("MALFORMED_OPENING_DATE")
        assertThat(viewModel.state.value.portfolioOpen?.openingDatePreview).isNull()
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)

        // Valid earlier date: preview carries the exact stored prefill and draft, writes nothing yet.
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2025-12-31"))
        advanceUntilIdle()
        val preview = viewModel.state.value.portfolioOpen?.openingDatePreview
        assertThat(preview).isNotNull()
        assertThat(preview!!.currentText).isEqualTo("2026-01-01")
        assertThat(preview.draftText).isEqualTo("2025-12-31")
        assertThat(preview.before.totalAmount!!.compareTo(java.math.BigDecimal("2099"))).isEqualTo(0)
        // The date moves only the return boundary, not the engine totals.
        assertThat(preview.after).isEqualTo(preview.before)
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)

        // One explicit save: only openingCashDate changes; every other literal survives.
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(preview.token))
        advanceUntilIdle()
        val edited = storedDocument()
        assertThat(edited.openingCashDate).isEqualTo("2025-12-31")
        assertThat(edited.openingCashAmount).isEqualTo("2000.000")
        assertThat(edited.currency).isEqualTo("SAR")
        assertThat(edited.events).isEqualTo(eventsBefore)
        assertThat(edited.quotes.single().unitPrice).isEqualTo("120.000")

        // Close and reopen from the row list: the edited date survives the round trip.
        viewModel.onEvent(InvestmentsEvent.ClosePortfolio)
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.OpenPortfolio(planId))
        advanceUntilIdle()
        val reopened = viewModel.state.value.portfolioOpen!!
        assertThat(reopened.workbench.document.openingCashDate).isEqualTo("2025-12-31")
        assertThat(reopened.workbench.xirr).isInstanceOf(XirrResult.Available::class.java)
    }

    @Test
    fun `clearing deliberately withholds annual return and re-dating restores it`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val eventsBefore = storedDocument().events

        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, ""))
        advanceUntilIdle()
        val preview = viewModel.state.value.portfolioOpen?.openingDatePreview
        assertThat(preview).isNotNull()
        assertThat(preview!!.draftText).isEmpty()
        assertThat(preview.currentText).isEqualTo("2026-01-01")
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(preview.token))
        advanceUntilIdle()

        // Cleared: the key is absent (legacy undated), annual return honestly withheld.
        assertThat(savedPlans.stored.single().payloadJson).doesNotContain("openingCashDate")
        assertThat(viewModel.state.value.portfolioOpen!!.workbench.xirr)
            .isEqualTo(XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION))
        assertThat(storedDocument().events).isEqualTo(eventsBefore)

        // Re-dating the same saved portfolio restores availability.
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2026-01-01"))
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(viewModel.state.value.portfolioOpen!!.openingDatePreview!!.token))
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen!!.workbench.document.openingCashDate).isEqualTo("2026-01-01")
        assertThat(viewModel.state.value.portfolioOpen!!.workbench.xirr).isInstanceOf(XirrResult.Available::class.java)
        assertThat(storedDocument().events).isEqualTo(eventsBefore)
    }

    @Test
    fun `cancel writes nothing and a repeated confirm is a no-op`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val payloadBefore = savedPlans.stored.single().payloadJson
        val revisionBefore = savedPlans.stored.single().revision

        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2025-12-31"))
        advanceUntilIdle()
        val token = viewModel.state.value.portfolioOpen!!.openingDatePreview!!.token
        viewModel.onEvent(InvestmentsEvent.CancelPortfolioOpeningDatePreview)
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen?.openingDatePreview).isNull()
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)
        assertThat(savedPlans.stored.single().revision).isEqualTo(revisionBefore)

        // The consumed review can never be confirmed afterwards.
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(token))
        advanceUntilIdle()
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)
        assertThat(savedPlans.stored.single().revision).isEqualTo(revisionBefore)
    }

    @Test
    fun `stale revision refuses the save and preserves the newer stored revision`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val payloadBefore = savedPlans.stored.single().payloadJson

        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2025-12-31"))
        advanceUntilIdle()
        val token = viewModel.state.value.portfolioOpen!!.openingDatePreview!!.token

        // An external writer bumps the stored revision behind the captured preview.
        savedPlans.bumpRevisionExternally(planId)
        val bumpedRevision = savedPlans.stored.single().revision
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(token))
        advanceUntilIdle()

        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.CONFLICT)
        // The newer external state survives untouched; our stale edit never landed.
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(payloadBefore)
        assertThat(savedPlans.stored.single().revision).isEqualTo(bumpedRevision)
    }

    @Test
    fun `nonempty padded dates and whitespace cannot silently change or clear evidence`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val before = savedPlans.stored.single()
        val writes = savedPlans.saveRequests
        for (draft in listOf(" 2025-12-31 ", " ", "\t", "2026-1-1")) {
            viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, draft))
            advanceUntilIdle()
            assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)
            assertThat(viewModel.state.value.portfolioOpen?.openingDatePreview).isNull()
            assertThat(savedPlans.stored.single()).isEqualTo(before)
        }
        assertThat(savedPlans.saveRequests).isEqualTo(writes)
    }

    @Test
    fun `repeated confirmation issues one save of the exact reviewed document`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val before = storedDocument()
        val revision = savedPlans.stored.single().revision
        val writes = savedPlans.saveRequests
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2025-12-31"))
        advanceUntilIdle()
        val token = viewModel.state.value.portfolioOpen!!.openingDatePreview!!.token
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(token))
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioOpeningDatePreview(token))
        advanceUntilIdle()
        assertThat(savedPlans.saveRequests).isEqualTo(writes + 1)
        assertThat(savedPlans.stored.single().revision).isEqualTo(revision + 1)
        assertThat(storedDocument()).isEqualTo(before.copy(openingCashDate = "2025-12-31"))
    }

    @Test
    fun `cancel during a suspended preview never resurrects a review or writes`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        val planId = datedPortfolio(viewModel)
        val before = savedPlans.stored.single()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        savedPlans.getGate = gate
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioOpeningDate(planId, "2025-12-31"))
        advanceUntilIdle()
        viewModel.onEvent(InvestmentsEvent.CancelPortfolioOpeningDatePreview)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen?.openingDatePreview).isNull()
        assertThat(savedPlans.stored.single()).isEqualTo(before)
    }
}
