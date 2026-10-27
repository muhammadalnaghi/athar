package com.athar.feature.plan.scenarios

import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.feature.plan.cashforecast.CashDocumentTypes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CashScenarioSavedViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val saved = FakeSavedPlans()
    private val clock = FixedClock()

    @BeforeEach fun before() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun after() { Dispatchers.resetMain() }

    private fun viewModel(draft: CashScenarioDraft = savedCashDraft()) =
        CashScenarioViewModel(dispatcher, draft, savedPlans = saved, clock = clock)

    private fun TestScope.computed(vm: CashScenarioViewModel): CashScenarioViewModel {
        vm.onEvent(CashScenarioEvent.Compute)
        advanceUntilIdle()
        return vm
    }

    private fun savedState(vm: CashScenarioViewModel) = vm.state.value.saved

    @Test fun `save stores inputs only as a version one cash scenario with create-only fence`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("رحلة نوفمبر"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()

        val (id, expectedRevision) = saved.saveCalls.single()
        assertThat(expectedRevision).isNull()
        val stored = saved.store.getValue(id)
        assertThat(stored.kind).isEqualTo(SavedFinancialPlanKind.CASH)
        assertThat(stored.title).isEqualTo("رحلة نوفمبر")
        assertThat(stored.currency).isEqualTo("KWD")
        assertThat(stored.payloadVersion).isEqualTo(1)
        val root = Json.parseToJsonElement(stored.payloadJson).jsonObject
        assertThat(root.getValue("documentType").jsonPrimitive.content).isEqualTo(CashDocumentTypes.CASH_SCENARIO)
        assertThat(root.getValue("openingCash").jsonPrimitive.content).isEqualTo("10.123")
        val outcome = vm.state.value.outcome as CashForecastOutcome.Forecast
        assertThat(outcome.result.partitions.single().headline?.finalBalance?.amount?.toPlainString())
            .isEqualTo("10.623")
        assertThat(savedState(vm).message).isEqualTo(CashSaveMessage.SAVED)
        assertThat(savedState(vm).loaded).isNotNull()
        advanceUntilIdle()
        assertThat(vm.state.value.savedList.summaries.single().id).isEqualTo(id)
    }

    @Test fun `update uses the captured revision and a stale revision never overwrites`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("أول"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        val id = saved.saveCalls.single().first

        vm.onEvent(CashScenarioEvent.Edit(CashField.OPENING_CASH, "11.123"))
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        assertThat(saved.saveCalls.last().second).isEqualTo(1L)
        assertThat(savedState(vm).message).isEqualTo(CashSaveMessage.UPDATED)
        assertThat(saved.store.getValue(id).revision).isEqualTo(2L)
        assertThat(Json.parseToJsonElement(saved.store.getValue(id).payloadJson).jsonObject
            .getValue("openingCash").jsonPrimitive.content).isEqualTo("11.123")

        saved.externallyBumpRevision(id)
        val externalDocument = saved.store.getValue(id)
        advanceUntilIdle()
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        assertThat(savedState(vm).message).isEqualTo(CashSaveMessage.CONFLICT)
        assertThat(saved.store.getValue(id).revision).isEqualTo(99L)
        assertThat(saved.store.getValue(id)).isEqualTo(externalDocument) // every external field intact
    }

    @Test fun `loading restores exact inputs and recomputes the outcome from them`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("محفوظ"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        val id = saved.saveCalls.single().first

        vm.onEvent(CashScenarioEvent.Reset)
        advanceUntilIdle()
        assertThat(vm.state.value.draft.openingCash).isEmpty()
        assertThat(savedState(vm).loaded).isNull()

        vm.onEvent(CashScenarioEvent.LoadSaved(id))
        advanceUntilIdle()
        val draft = vm.state.value.draft
        assertThat(draft.currency).isEqualTo("KWD")
        assertThat(draft.openingDate).isEqualTo("2026-11-21")
        assertThat(draft.openingCash).isEqualTo("10.123")
        assertThat(draft.reserve).isEqualTo("0")
        assertThat(draft.flows.single()).isEqualTo(CashFlowDraft(1, "2026-11-22", "0.500", CashFlowDirection.INFLOW))
        val outcome = vm.state.value.outcome as CashForecastOutcome.Forecast
        assertThat(outcome.result.partitions.single().headline?.finalBalance?.amount?.toPlainString())
            .isEqualTo("10.623")
        assertThat(savedState(vm).loaded?.id).isEqualTo(id)
        assertThat(savedState(vm).titleDraft).isEqualTo("محفوظ")
    }

    @Test fun `deletion is armed confirmed revision fenced and detaches a loaded document`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("سيمحذف"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        val id = saved.saveCalls.single().first

        vm.onEvent(CashScenarioEvent.DeleteSaved(id))
        assertThat(vm.state.value.confirmDeleteId).isEqualTo(id)
        assertThat(saved.store).hasSize(1)
        vm.onEvent(CashScenarioEvent.CancelDeleteSaved)
        assertThat(vm.state.value.confirmDeleteId).isNull()
        assertThat(saved.store).hasSize(1)

        vm.onEvent(CashScenarioEvent.DeleteSaved(id))
        vm.onEvent(CashScenarioEvent.ConfirmDeleteSaved)
        advanceUntilIdle()
        assertThat(saved.store).isEmpty()
        assertThat(savedState(vm).message).isEqualTo(CashSaveMessage.DELETED)
        assertThat(savedState(vm).loaded).isNull()
        assertThat(vm.state.value.savedList.summaries).isEmpty()
    }

    @Test fun `stale deletion refuses without changing storage`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("محمي"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        val id = saved.saveCalls.single().first
        saved.externallyBumpRevision(id)
        advanceUntilIdle()

        vm.onEvent(CashScenarioEvent.DeleteSaved(id))
        vm.onEvent(CashScenarioEvent.ConfirmDeleteSaved)
        advanceUntilIdle()
        assertThat(savedState(vm).message).isEqualTo(CashSaveMessage.CONFLICT)
        assertThat(saved.store).hasSize(1)
    }

    @Test fun `foreign anchors never list and unreadable documents are only counted`() = runTest(dispatcher) {
        saved.store["anchor"] = plan("anchor", """{"documentType":"balance_anchor","payloadVersion":3}""")
        saved.store["future"] = plan("future", """{"documentType":"cash_scenario","payloadVersion":2}""")
        saved.store["junk"] = plan("junk", "{oops")
        saved.refresh()
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.savedList.summaries).isEmpty()
        assertThat(vm.state.value.savedList.unavailable).isEqualTo(2)
        assertThat(saved.store).hasSize(3) // all retained, none deleted

        vm.onEvent(CashScenarioEvent.LoadSaved("anchor"))
        advanceUntilIdle()
        assertThat(savedState(vm).message).isEqualTo(CashSaveMessage.MISSING)
        assertThat(vm.state.value.draft.currency).isEqualTo("KWD") // form untouched
    }

    @Test fun `comparison only accepts one opening identity and rows are recomputed`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("أساس"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()

        vm.onEvent(CashScenarioEvent.Edit(CashField.OPENING_CASH, "11.123"))
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(CashScenarioEvent.SaveTitle("أعلى"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        val ids = vm.state.value.savedList.summaries.map { it.id }
        assertThat(ids).hasSize(2)

        vm.onEvent(CashScenarioEvent.CompareOpen)
        vm.onEvent(CashScenarioEvent.CompareToggle(ids[0]))
        vm.onEvent(CashScenarioEvent.CompareToggle(ids[1]))
        vm.onEvent(CashScenarioEvent.CompareRun)
        advanceUntilIdle()
        val rows = vm.state.value.compare.rows
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.title }).containsExactly("أساس", "أعلى")
        assertThat(rows.map { it.minimum?.amount?.toPlainString() }).containsExactly("10.123", "11.123")
        assertThat(rows[0].status).isNotNull()
        assertThat(rows[0].shortfall).isNull()

        // A different horizon is an incomparable identity: selection is refused.
        vm.onEvent(CashScenarioEvent.Edit(CashField.HORIZON_END, "2026-11-24"))
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(CashScenarioEvent.SaveTitle("آخر"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        val third = vm.state.value.savedList.summaries.last().id
        vm.onEvent(CashScenarioEvent.CompareToggle(third))
        assertThat(vm.state.value.compare.selected).doesNotContain(third)
    }

    @Test fun `saving without a computed outcome or a valid title is refused`() = runTest(dispatcher) {
        val vm = viewModel(validCashDraft().copy(openingCash = ""))
        vm.onEvent(CashScenarioEvent.SaveTitle("بلا نتيجة"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        assertThat(saved.saveCalls).isEmpty()

        val vm2 = computed(viewModel())
        vm2.onEvent(CashScenarioEvent.SaveScenario) // blank title
        advanceUntilIdle()
        assertThat(savedState(vm2).message).isEqualTo(CashSaveMessage.TITLE_REQUIRED)
        assertThat(saved.saveCalls).isEmpty()
    }

    @Test fun `currency edit detaches the loaded document instead of relabeling it`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        vm.onEvent(CashScenarioEvent.SaveTitle("ديناري"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        advanceUntilIdle()
        assertThat(savedState(vm).loaded).isNotNull()

        vm.onEvent(CashScenarioEvent.Edit(CashField.CURRENCY, "JPY"))
        advanceUntilIdle()
        assertThat(savedState(vm).loaded).isNull()
        assertThat(savedState(vm).titleDraft).isEmpty()
        assertThat(vm.state.value.outcome).isNull()
        val stored = saved.store.values.single()
        assertThat(Json.parseToJsonElement(stored.payloadJson).jsonObject.getValue("currency").jsonPrimitive.content)
            .isEqualTo("KWD")
    }
    @Test fun `late save cannot reattach a document after changing the form currency`() = runTest(dispatcher) {
        val vm = computed(viewModel())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        saved.saveGate = gate
        vm.onEvent(CashScenarioEvent.SaveTitle("طلب محفوظ"))
        vm.onEvent(CashScenarioEvent.SaveScenario)
        runCurrent()
        vm.onEvent(CashScenarioEvent.Edit(CashField.CURRENCY, "JPY"))
        gate.complete(Unit)
        advanceUntilIdle()

        val committed = saved.store.values.single()
        assertThat(committed.currency).isEqualTo("KWD")
        assertThat(Json.parseToJsonElement(committed.payloadJson).jsonObject
            .getValue("openingCash").jsonPrimitive.content).isEqualTo("10.123")
        assertThat(vm.state.value.draft.currency).isEqualTo("JPY")
        assertThat(vm.state.value.saved.loaded).isNull()
        assertThat(vm.state.value.saved.titleDraft).isEmpty()
        assertThat(vm.state.value.saved.saving).isFalse()
        assertThat(vm.state.value.saved.message).isNull()
    }
}

private fun plan(id: String, payloadJson: String) = SavedFinancialPlan(
    id = id,
    kind = SavedFinancialPlanKind.CASH,
    title = id,
    currency = "KWD",
    payloadVersion = 1,
    payloadJson = payloadJson,
    revision = 1,
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 1,
)

private fun savedCashDraft() = CashScenarioDraft(
    currency = "KWD", openingDate = "2026-11-21", openingCash = "10.123",
    reserve = "0", horizonEnd = "2026-11-23", scopeComplete = true, anchorFresh = true,
    flows = listOf(CashFlowDraft(1, "2026-11-22", "0.500", CashFlowDirection.INFLOW)),
)

private fun validCashDraft() = savedCashDraft()
