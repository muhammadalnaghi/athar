package com.athar.feature.plan.portfolio

import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.athar.core.domain.repo.InvestmentRepository
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.feature.plan.InvestmentWriteProblem
import com.athar.feature.plan.InvestmentsEvent
import com.athar.feature.plan.InvestmentsViewModel
import com.athar.feature.plan.PortfolioEntryDraft
import com.athar.feature.plan.editDraftOf
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
 * WM033 in-place ledger correction: exact prefill (`editDraftOf`), explicit save with
 * the SAME stable `eventId` at the SAME list position, whole-document engine replay
 * before any write, revision-CAS conflict refusal, and cancel/dismiss writes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentsPortfolioEditTest {

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

    private fun decodedDocument(): PortfolioPlanDocument =
        (PortfolioPlanCodec.decode(savedPlans.stored.single().payloadJson) as PortfolioDecodeResult.Success).document

    @Test
    fun `allocation save preserves every financial field and synchronously blocks repeated save`() = runTest(mainDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.onEvent(InvestmentsEvent.CreatePortfolio("Allocation", "SAR", "1000.00"))
        advanceUntilIdle()
        val id = savedPlans.stored.single().id
        vm.onEvent(InvestmentsEvent.AddPortfolioEntry(id, PortfolioEntryDraft(
            kind = PortfolioEntryKind.CONTRIBUTION, dateText = "2026-03-01", amountText = "25.00")))
        advanceUntilIdle()
        val before = decodedDocument()
        val stored = savedPlans.stored.single()
        val requests = savedPlans.saveRequests
        val draft = allocationDraftOf(before.allocationTargets).copy(percentages = mapOf("CASH" to "50.00", "EQUITY" to "50.00"))
        val event = InvestmentsEvent.SavePortfolioAllocation(id, stored.revision, "receipt", draft)
        vm.onEvent(event)
        vm.onEvent(event)
        advanceUntilIdle()
        assertThat(savedPlans.saveRequests).isEqualTo(requests + 1)
        assertThat(decodedDocument().copy(allocationTargets = before.allocationTargets)).isEqualTo(before)
        assertThat(decodedDocument().allocationTargets).hasSize(2)
        assertThat(vm.state.value.allocationWrite?.saved).isTrue()
        assertThat(vm.state.value.allocationWrite?.planId).isEqualTo(id)
        vm.onEvent(InvestmentsEvent.ClosePortfolio)
        vm.onEvent(InvestmentsEvent.OpenPortfolio(id))
        advanceUntilIdle()
        assertThat(vm.state.value.portfolioOpen!!.workbench.document.allocationTargets).isEqualTo(decodedDocument().allocationTargets)
        assertThat(vm.state.value.allocationWrite).isNull()
    }

    @Test
    fun `allocation invalid total and revision conflict leave stored document unchanged`() = runTest(mainDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.onEvent(InvestmentsEvent.CreatePortfolio("Allocation", "SAR", "1000.00"))
        advanceUntilIdle()
        val before = savedPlans.stored.single()
        val bad = allocationDraftOf(emptyList()).copy(percentages = mapOf("CASH" to "90"))
        vm.onEvent(InvestmentsEvent.SavePortfolioAllocation(before.id, before.revision, "bad", bad))
        advanceUntilIdle()
        assertThat(savedPlans.stored.single()).isEqualTo(before)
        assertThat(vm.state.value.allocationWrite?.problem).isEqualTo(AllocationWriteProblem.TOTAL)
        savedPlans.bumpRevisionExternally(before.id)
        val changed = savedPlans.stored.single()
        val valid = bad.copy(percentages = mapOf("CASH" to "100"))
        vm.onEvent(InvestmentsEvent.SavePortfolioAllocation(before.id, before.revision, "conflict", valid))
        advanceUntilIdle()
        assertThat(savedPlans.stored.single()).isEqualTo(changed)
        assertThat(vm.state.value.allocationWrite?.problem).isEqualTo(AllocationWriteProblem.CONFLICT)
    }

    @Test
    fun `superseded preview callback cannot approve the newer candidate`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("Preview receipt", "SAR", "1000.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        viewModel.onEvent(InvestmentsEvent.AddPortfolioEntry(planId, PortfolioEntryDraft(
            kind = PortfolioEntryKind.CONTRIBUTION, dateText = "2026-03-01", amountText = "25.00",
        )))
        advanceUntilIdle()
        val original = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()
        val storedBefore = savedPlans.stored.single()
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioEntry(planId, original.eventId, editDraftOf(original).copy(amountText = "30.00")))
        advanceUntilIdle()
        val oldToken = viewModel.state.value.portfolioOpen!!.entryPreview!!.token
        viewModel.onEvent(InvestmentsEvent.PreviewPortfolioEntry(planId, original.eventId, editDraftOf(original).copy(amountText = "40.00")))
        advanceUntilIdle()
        val currentToken = viewModel.state.value.portfolioOpen!!.entryPreview!!.token
        assertThat(currentToken).isNotEqualTo(oldToken)
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(oldToken))
        advanceUntilIdle()
        assertThat(savedPlans.stored.single()).isEqualTo(storedBefore)
        assertThat(viewModel.state.value.portfolioOpen!!.entryPreview!!.token).isEqualTo(currentToken)
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(currentToken))
        advanceUntilIdle()
        assertThat(decodedDocument().events.single().amount).isEqualTo("40.00")
        assertThat(decodedDocument().events.single().eventId).isEqualTo(original.eventId)
    }

    @Test
    fun `editDraftOf carries every stored literal exactly`() {
        val buy = EventInput(
            kind = PortfolioEntryKind.BUY, eventId = "evt-a", date = "2026-03-01",
            securityId = "TEN", quantity = "5", unitPrice = "20.000", fee = "1.00",
            note = "قيد مستورد",
        )
        val buyDraft = editDraftOf(buy)
        assertThat(buyDraft.kind).isEqualTo(PortfolioEntryKind.BUY)
        assertThat(buyDraft.securityId).isEqualTo("TEN")
        assertThat(buyDraft.dateText).isEqualTo("2026-03-01")
        assertThat(buyDraft.quantityText).isEqualTo("5")
        assertThat(buyDraft.priceText).isEqualTo("20.000")
        assertThat(buyDraft.feeText).isEqualTo("1.00")
        assertThat(buyDraft.note).isEqualTo("قيد مستورد")

        val dividend = EventInput(
            kind = PortfolioEntryKind.CASH_DIVIDEND, eventId = "evt-b", date = "2026-04-01",
            securityId = "TEN", amount = "12.50", destination = "EXTERNAL_ACCOUNT",
        )
        val dividendDraft = editDraftOf(dividend)
        assertThat(dividendDraft.externalDestination).isTrue()
        assertThat(dividendDraft.amountText).isEqualTo("12.50")
        assertThat(dividendDraft.feeText).isEmpty()
        assertThat(dividendDraft.note).isEmpty()

        assertThat(editDraftOf(dividend.copy(destination = "PORTFOLIO_CASH")).externalDestination).isFalse()
    }

    @Test
    fun `edit roundtrip keeps eventId order and untouched literals and replays corrected engines`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.state.collect {} }
            viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة التعديل", "SAR", "1000.00"))
            advanceUntilIdle()
            val planId = savedPlans.stored.single().id
            viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق العشرة", "EQUITY"))
            advanceUntilIdle()
            viewModel.onEvent(
                InvestmentsEvent.AddPortfolioEntry(
                    planId = planId,
                    entry = PortfolioEntryDraft(
                        kind = PortfolioEntryKind.BUY, securityId = "TEN",
                        dateText = "2026-03-01", quantityText = "5", priceText = "20.000",
                        feeText = "1.00", note = "شراء تأسيس",
                    ),
                ),
            )
            advanceUntilIdle()
            viewModel.onEvent(
                InvestmentsEvent.AddPortfolioEntry(
                    planId = planId,
                    entry = PortfolioEntryDraft(
                        kind = PortfolioEntryKind.CONTRIBUTION,
                        dateText = "2026-03-02", amountText = "250.00",
                    ),
                ),
            )
            advanceUntilIdle()

            val eventsBefore = viewModel.state.value.portfolioOpen!!.workbench.document.events
            val buyEvent = eventsBefore.first()
            val contribEvent = eventsBefore.last()
            val payloadBefore = savedPlans.stored.single().payloadJson

            // Prefill equals the stored literals exactly; only the quantity changes.
            val draft = editDraftOf(buyEvent)
            assertThat(draft.quantityText).isEqualTo("5")
            assertThat(draft.priceText).isEqualTo("20.000")
            assertThat(draft.feeText).isEqualTo("1.00")
            assertThat(draft.note).isEqualTo("شراء تأسيس")
            viewModel.onEvent(InvestmentsEvent.UpdatePortfolioEntry(planId, buyEvent.eventId, draft.copy(quantityText = "7")))
            advanceUntilIdle()

            assertThat(viewModel.state.value.writeProblem).isNull()
            assertThat(viewModel.state.value.portfolioProblem).isNull()

            val doc = decodedDocument()
            // Same stable eventId, same list position (BUY first, CONTRIBUTION second).
            assertThat(doc.events[0].eventId).isEqualTo(buyEvent.eventId)
            assertThat(doc.events[0].quantity).isEqualTo("7")
            // Untouched literals persist verbatim, byte-identical field values.
            assertThat(doc.events[0].unitPrice).isEqualTo("20.000")
            assertThat(doc.events[0].fee).isEqualTo("1.00")
            assertThat(doc.events[0].note).isEqualTo("شراء تأسيس")
            assertThat(doc.events[1].eventId).isEqualTo(contribEvent.eventId)
            assertThat(doc.events.map { it.eventId }).containsNoDuplicates()
            assertThat(savedPlans.stored.single().payloadJson).isNotEqualTo(payloadBefore)

            // Reopen through close/open reproduces the corrected inputs verbatim (strict codec).
            viewModel.onEvent(InvestmentsEvent.ClosePortfolio)
            advanceUntilIdle()
            viewModel.onEvent(InvestmentsEvent.OpenPortfolio(planId))
            advanceUntilIdle()
            val open = viewModel.state.value.portfolioOpen
            assertThat(open).isNotNull()
            assertThat(open!!.workbench.document.events[0].quantity).isEqualTo("7")
            assertThat(open.workbench.document.events[0].fee).isEqualTo("1.00")
            // Recomputed engines show the corrected cash: 1000.00 - 7×20.000 - 1.00 + 250.00 = 1109.000.
            assertThat(open.workbench.accounting!!.cash.amount.toPlainString()).isEqualTo("1109.000")
        }

    @Test
    fun `dismissing the prefilled editor without saving writes nothing`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة الإلغاء", "SAR", "500.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق", null))
        advanceUntilIdle()
        viewModel.onEvent(
            InvestmentsEvent.AddPortfolioEntry(
                planId = planId,
                entry = PortfolioEntryDraft(
                    kind = PortfolioEntryKind.BUY, securityId = "TEN",
                    dateText = "2026-03-01", quantityText = "2", priceText = "10.00",
                ),
            ),
        )
        advanceUntilIdle()

        val storedBefore = savedPlans.stored.single()
        val savesBefore = savedPlans.saveRequests
        val event = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()

        // The user opens the prefilled editor, changes a field, then dismisses: NO event is sent.
        val reviewedDraft = editDraftOf(event).copy(quantityText = "9")
        assertThat(reviewedDraft.quantityText).isEqualTo("9")
        advanceUntilIdle()

        val storedAfter = savedPlans.stored.single()
        assertThat(storedAfter.payloadJson).isEqualTo(storedBefore.payloadJson)
        assertThat(storedAfter.revision).isEqualTo(storedBefore.revision)
        assertThat(savedPlans.saveRequests).isEqualTo(savesBefore)
    }

    @Test
    fun `invalid corrections are refused before any write with the exact tokens`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة الرفض", "SAR", "1000.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق", "EQUITY"))
        advanceUntilIdle()
        viewModel.onEvent(
            InvestmentsEvent.AddPortfolioEntry(
                planId = planId,
                entry = PortfolioEntryDraft(
                    kind = PortfolioEntryKind.BUY, securityId = "TEN",
                    dateText = "2026-03-01", quantityText = "5", priceText = "20.000", feeText = "1.00",
                ),
            ),
        )
        advanceUntilIdle()
        val event = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()
        val revisionBefore = savedPlans.stored.single().revision

        // Unknown security: refused as INVALID_INPUT, nothing stored.
        viewModel.onEvent(
            InvestmentsEvent.UpdatePortfolioEntry(planId, event.eventId, editDraftOf(event).copy(securityId = "GHOST")),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)

        // Kind switch to CONTRIBUTION without the now-required amount: INVALID_INPUT.
        viewModel.onEvent(
            InvestmentsEvent.UpdatePortfolioEntry(
                planId, event.eventId,
                editDraftOf(event).copy(kind = PortfolioEntryKind.CONTRIBUTION, amountText = ""),
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.INVALID_INPUT)

        // Engine-refused correction (selling 50 while holding 5): safe engine token, nothing stored.
        viewModel.onEvent(
            InvestmentsEvent.UpdatePortfolioEntry(
                planId, event.eventId,
                editDraftOf(event).copy(kind = PortfolioEntryKind.SELL, quantityText = "50", priceText = "21.000"),
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioProblem).isEqualTo("UNSUPPORTED_SHORT_SALE")

        // All three refusals left the stored document byte-identical.
        assertThat(savedPlans.stored.single().revision).isEqualTo(revisionBefore)
        assertThat(decodedDocument().events.single().quantity).isEqualTo("5")
    }

    @Test
    fun `stale revision edit is refused as CONFLICT and the newer external edit survives`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.state.collect {} }
            viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة التعارض", "SAR", "100.00"))
            advanceUntilIdle()
            val planId = savedPlans.stored.single().id
            viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق", null))
            advanceUntilIdle()
            viewModel.onEvent(
                InvestmentsEvent.AddPortfolioEntry(
                    planId = planId,
                    entry = PortfolioEntryDraft(
                        kind = PortfolioEntryKind.BUY, securityId = "TEN",
                        dateText = "2026-03-01", quantityText = "5", priceText = "20.000",
                    ),
                ),
            )
            advanceUntilIdle()
            val eventId = viewModel.state.value.portfolioOpen!!.workbench.document.events.single().eventId
            val externalPayload = savedPlans.stored.single().payloadJson

            // An external writer bumps the stored revision behind this session's back.
            savedPlans.bumpRevisionExternally(planId)
            viewModel.onEvent(
                InvestmentsEvent.UpdatePortfolioEntry(
                    planId, eventId,
                    editDraftOf(
                        viewModel.state.value.portfolioOpen!!.workbench.document.events.single(),
                    ).copy(quantityText = "7"),
                ),
            )
            advanceUntilIdle()

            assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.CONFLICT)
            // The newer external edit survives; our stale correction never landed.
            assertThat(savedPlans.stored.single().payloadJson).isEqualTo(externalPayload)
            assertThat(decodedDocument().events.single().quantity).isEqualTo("5")
        }

    @Test
    fun `preview shows engine before and after then explicit save lands exactly once`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.state.collect {} }
            viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة المعاينة", "SAR", "1000.00"))
            advanceUntilIdle()
            val planId = savedPlans.stored.single().id
            viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق", "EQUITY"))
            advanceUntilIdle()
            viewModel.onEvent(
                InvestmentsEvent.AddPortfolioEntry(
                    planId = planId,
                    entry = PortfolioEntryDraft(
                        kind = PortfolioEntryKind.BUY, securityId = "TEN",
                        dateText = "2026-03-01", quantityText = "5", priceText = "20.000",
                        feeText = "1.00", note = "شراء تأسيس",
                    ),
                ),
            )
            advanceUntilIdle()
            viewModel.onEvent(InvestmentsEvent.AddPortfolioQuote(planId, "TEN", "2026-03-03", "21.000"))
            advanceUntilIdle()
            val buyEvent = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()
            val savesBeforePreview = savedPlans.saveRequests

            // Review first: engines recompute before/after off Main; nothing is written.
            viewModel.onEvent(
                InvestmentsEvent.PreviewPortfolioEntry(
                    planId, buyEvent.eventId, editDraftOf(buyEvent).copy(quantityText = "7"),
                ),
            )
            advanceUntilIdle()

            assertThat(savedPlans.saveRequests).isEqualTo(savesBeforePreview)
            val preview = viewModel.state.value.portfolioOpen!!.entryPreview!!
            assertThat(preview.planId).isEqualTo(planId)
            assertThat(preview.eventId).isEqualTo(buyEvent.eventId)
            assertThat(preview.currency).isEqualTo("SAR")
            // Exact engine outputs: cash 1000.00 − qty×20.000 − 1.00; holdings at the
            // 21.000 quote; total = cash + priced holdings. Literal currency separate.
            assertThat(preview.before.cashAmount!!.toPlainString()).isEqualTo("899.000")
            assertThat(preview.before.pricedHoldingsAmount!!.toPlainString()).isEqualTo("105.000")
            assertThat(preview.before.totalAmount!!.toPlainString()).isEqualTo("1004.000")
            assertThat(preview.after.cashAmount!!.toPlainString()).isEqualTo("859.000")
            assertThat(preview.after.pricedHoldingsAmount!!.toPlainString()).isEqualTo("147.000")
            assertThat(preview.after.totalAmount!!.toPlainString()).isEqualTo("1006.000")

            // Explicit final save bound to the captured preview, landing exactly once.
            val reviewedToken = requireNotNull(viewModel.state.value.portfolioOpen?.entryPreview).token
            viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(reviewedToken))
            advanceUntilIdle()
            assertThat(viewModel.state.value.writeProblem).isNull()
            assertThat(viewModel.state.value.portfolioProblem).isNull()
            assertThat(savedPlans.saveRequests).isEqualTo(savesBeforePreview + 1)

            val saved = decodedDocument().events.single()
            assertThat(saved.eventId).isEqualTo(buyEvent.eventId)
            assertThat(saved.quantity).isEqualTo("7")
            assertThat(saved.date).isEqualTo("2026-03-01")
            assertThat(saved.unitPrice).isEqualTo("20.000")
            assertThat(saved.fee).isEqualTo("1.00")
            assertThat(saved.note).isEqualTo("شراء تأسيس")
            assertThat(saved.securityId).isEqualTo("TEN")

            // The review is consumed exactly once: a repeated confirm is a no-op.
            viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(reviewedToken))
            advanceUntilIdle()
            assertThat(savedPlans.saveRequests).isEqualTo(savesBeforePreview + 1)
            assertThat(viewModel.state.value.writeProblem).isNull()
        }

    @Test
    fun `canceling the preview writes nothing and preserves the edited input`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة إلغاء المعاينة", "SAR", "500.00"))
        advanceUntilIdle()
        val planId = savedPlans.stored.single().id
        viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق", null))
        advanceUntilIdle()
        viewModel.onEvent(
            InvestmentsEvent.AddPortfolioEntry(
                planId = planId,
                entry = PortfolioEntryDraft(
                    kind = PortfolioEntryKind.BUY, securityId = "TEN",
                    dateText = "2026-03-01", quantityText = "2", priceText = "10.00",
                ),
            ),
        )
        advanceUntilIdle()
        val buyEvent = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()
        val storedBefore = savedPlans.stored.single()
        val savesBefore = savedPlans.saveRequests

        viewModel.onEvent(
            InvestmentsEvent.PreviewPortfolioEntry(
                planId, buyEvent.eventId,
                editDraftOf(buyEvent).copy(quantityText = "7", note = "مسودة معلّقة"),
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen!!.entryPreview).isNotNull()

        // Cancel: nothing written, the exact reviewed draft comes back to the editor.
        viewModel.onEvent(InvestmentsEvent.CancelPortfolioEntryPreview)
        advanceUntilIdle()

        assertThat(viewModel.state.value.portfolioOpen!!.entryPreview).isNull()
        val restored = viewModel.state.value.portfolioOpen!!.restoredEntryDraft!!
        assertThat(restored.planId).isEqualTo(planId)
        assertThat(restored.eventId).isEqualTo(buyEvent.eventId)
        assertThat(restored.draft.quantityText).isEqualTo("7")
        assertThat(restored.draft.priceText).isEqualTo("10.00")
        assertThat(restored.draft.dateText).isEqualTo("2026-03-01")
        assertThat(restored.draft.note).isEqualTo("مسودة معلّقة")
        assertThat(savedPlans.saveRequests).isEqualTo(savesBefore)
        assertThat(savedPlans.stored.single().payloadJson).isEqualTo(storedBefore.payloadJson)
        assertThat(savedPlans.stored.single().revision).isEqualTo(storedBefore.revision)

        // Giving up the preserved draft (editor dismiss) also writes nothing.
        viewModel.onEvent(InvestmentsEvent.CancelPortfolioEntryPreview)
        advanceUntilIdle()
        assertThat(viewModel.state.value.portfolioOpen!!.restoredEntryDraft).isNull()
        assertThat(savedPlans.saveRequests).isEqualTo(savesBefore)
    }

    @Test
    fun `stale preview is refused as CONFLICT and the newer external edit survives`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.state.collect {} }
            viewModel.onEvent(InvestmentsEvent.CreatePortfolio("محفظة تعارض المعاينة", "SAR", "1000.00"))
            advanceUntilIdle()
            val planId = savedPlans.stored.single().id
            viewModel.onEvent(InvestmentsEvent.AddPortfolioSecurity(planId, "TEN", "صندوق", null))
            advanceUntilIdle()
            viewModel.onEvent(
                InvestmentsEvent.AddPortfolioEntry(
                    planId = planId,
                    entry = PortfolioEntryDraft(
                        kind = PortfolioEntryKind.BUY, securityId = "TEN",
                        dateText = "2026-03-01", quantityText = "5", priceText = "20.000",
                    ),
                ),
            )
            advanceUntilIdle()
            val buyEvent = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()
            val externalPayload = savedPlans.stored.single().payloadJson

            viewModel.onEvent(
                InvestmentsEvent.PreviewPortfolioEntry(
                    planId, buyEvent.eventId, editDraftOf(buyEvent).copy(quantityText = "7"),
                ),
            )
            advanceUntilIdle()
            assertThat(viewModel.state.value.portfolioOpen!!.entryPreview).isNotNull()
            val savesBeforeConfirm = savedPlans.saveRequests

            // An external writer lands AFTER the review was captured: the preview is
            // superseded and its explicit confirm must refuse without writing.
            savedPlans.bumpRevisionExternally(planId)
            val reviewedToken = requireNotNull(viewModel.state.value.portfolioOpen?.entryPreview).token
            viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(reviewedToken))
            advanceUntilIdle()

            assertThat(viewModel.state.value.writeProblem).isEqualTo(InvestmentWriteProblem.CONFLICT)
            assertThat(viewModel.state.value.portfolioOpen!!.entryPreview).isNull()
            assertThat(savedPlans.saveRequests).isEqualTo(savesBeforeConfirm)
            assertThat(savedPlans.stored.single().payloadJson).isEqualTo(externalPayload)
            assertThat(decodedDocument().events.single().quantity).isEqualTo("5")

            // The consumed preview cannot be replayed into a second write attempt.
            viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(reviewedToken))
            advanceUntilIdle()
            assertThat(savedPlans.saveRequests).isEqualTo(savesBeforeConfirm)
        }

    @Test
    fun `untouched cosmetic literals and exact ids survive both save paths`() = runTest(mainDispatcher) {
        // Seed a stored document whose literals carry cosmetic zeros, exactly as an
        // imported statement might have stored them.
        val seededDocument = PortfolioPlanDocument(
            currency = "SAR",
            openingCashAmount = "1000.00",
            securities = listOf(SecurityInput("TEN", "صندوق", "EQUITY")),
            events = listOf(
                EventInput(
                    kind = PortfolioEntryKind.BUY, eventId = "evt-seeded-1", date = "2026-03-01",
                    securityId = "TEN", quantity = "007.500", unitPrice = "020.000",
                    fee = "0001.00", note = "قيد مؤرخ",
                ),
            ),
        )
        savedPlans.seedExisting(
            SavedFinancialPlan(
                id = "plan-seeded",
                kind = SavedFinancialPlanKind.PORTFOLIO,
                title = "محفظة الأصفار",
                currency = "SAR",
                payloadVersion = PortfolioPlanCodec.VERSION,
                payloadJson = PortfolioPlanCodec.encode(seededDocument),
                revision = 7,
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            ),
        )
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.onEvent(InvestmentsEvent.OpenPortfolio("plan-seeded"))
        advanceUntilIdle()
        val seededEvent = viewModel.state.value.portfolioOpen!!.workbench.document.events.single()

        // Preview seam: only the fee changes; every untouched field keeps its exact literal.
        viewModel.onEvent(
            InvestmentsEvent.PreviewPortfolioEntry(
                "plan-seeded", seededEvent.eventId,
                editDraftOf(seededEvent).copy(feeText = "2.00"),
            ),
        )
        advanceUntilIdle()
        val reviewedToken = requireNotNull(viewModel.state.value.portfolioOpen?.entryPreview).token
        viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioEntryPreview(reviewedToken))
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isNull()
        val afterSeam = decodedDocument().events.single()
        assertThat(afterSeam.eventId).isEqualTo("evt-seeded-1")
        assertThat(afterSeam.quantity).isEqualTo("007.500")
        assertThat(afterSeam.unitPrice).isEqualTo("020.000")
        assertThat(afterSeam.date).isEqualTo("2026-03-01")
        assertThat(afterSeam.securityId).isEqualTo("TEN")
        assertThat(afterSeam.note).isEqualTo("قيد مؤرخ")
        assertThat(afterSeam.fee).isEqualTo("2.00")

        // The legacy correction path keeps the same untouched-literal promise.
        viewModel.onEvent(
            InvestmentsEvent.UpdatePortfolioEntry(
                "plan-seeded", seededEvent.eventId,
                editDraftOf(afterSeam).copy(quantityText = "8"),
            ),
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.writeProblem).isNull()
        val afterLegacy = decodedDocument().events.single()
        assertThat(afterLegacy.quantity).isEqualTo("8")
        assertThat(afterLegacy.unitPrice).isEqualTo("020.000")
        assertThat(afterLegacy.date).isEqualTo("2026-03-01")
        assertThat(afterLegacy.securityId).isEqualTo("TEN")
        assertThat(afterLegacy.note).isEqualTo("قيد مؤرخ")
        assertThat(afterLegacy.fee).isEqualTo("2.00")
    }
}
