package com.athar.feature.plan.scenarios

import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CashScenarioViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun before() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun after() { Dispatchers.resetMain() }

    @Test fun `compute retains exact KWD and editing removes previous forecast`() = runTest(dispatcher) {
        val vm = CashScenarioViewModel(dispatcher, validCashDraft(), savedPlans = FakeSavedPlans(), clock = FixedClock())
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        val result = vm.state.value.outcome as CashForecastOutcome.Forecast
        assertThat(result.result.partitions.single().headline?.finalBalance?.amount?.toPlainString()).isEqualTo("10.623")
        vm.onEvent(CashScenarioEvent.Edit(CashField.OPENING_CASH, "11.123"))
        assertThat(vm.state.value.outcome).isNull()
    }
    @Test fun `currency change clears money and assumptions rather than relabeling`() = runTest(dispatcher) {
        val vm = CashScenarioViewModel(dispatcher, validCashDraft(), savedPlans = FakeSavedPlans(), clock = FixedClock())
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(CashScenarioEvent.Edit(CashField.CURRENCY, "JPY"))
        assertThat(vm.state.value.draft).isEqualTo(CashScenarioDraft(currency = "JPY"))
        assertThat(vm.state.value.outcome).isNull()
    }
    @Test fun `false assumptions keep partial result without a headline`() = runTest(dispatcher) {
        val vm = CashScenarioViewModel(dispatcher, validCashDraft().copy(scopeComplete = false, anchorFresh = false),
            savedPlans = FakeSavedPlans(), clock = FixedClock())
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        val partition = (vm.state.value.outcome as CashForecastOutcome.Forecast).result.partitions.single()
        assertThat(partition.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(partition.headline).isNull()
    }
    @Test fun `late solve cannot commit after reset`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val vm = CashScenarioViewModel(
            dispatcher,
            validCashDraft(),
            solver = { draft -> withContext(NonCancellable) { release.await(); evaluateCashDraft(draft) } },
            savedPlans = FakeSavedPlans(),
            clock = FixedClock(),
        )
        vm.onEvent(CashScenarioEvent.Compute); runCurrent()
        vm.onEvent(CashScenarioEvent.Reset)
        release.complete(Unit); advanceUntilIdle()
        assertThat(vm.state.value.outcome).isNull()
        assertThat(vm.state.value.computing).isFalse()
        assertThat(vm.state.value.draft.currency).isEqualTo("KWD")
    }
    @Test fun `full path retains every dated point and provenance across pages`() = runTest(dispatcher) {
        val flows = (1..8).map { CashFlowDraft(it, "2026-11-${(21 + it)}", "0.001", CashFlowDirection.INFLOW) }
        val vm = CashScenarioViewModel(dispatcher, validCashDraft().copy(horizonEnd = "2026-11-30", flows = flows),
            savedPlans = FakeSavedPlans(), clock = FixedClock())
        vm.onEvent(CashScenarioEvent.Compute); advanceUntilIdle()
        val result = (vm.state.value.outcome as CashForecastOutcome.Forecast).result.partitions.single()
        vm.onEvent(CashScenarioEvent.OpenPath("SAR"))
        assertThat(vm.state.value.path).isNull()
        vm.onEvent(CashScenarioEvent.OpenPath("KWD"))
        assertThat(vm.state.value.path?.totalPoints).isEqualTo(result.path.size)
        assertThat(vm.state.value.path?.points?.map { it.day }).containsExactlyElementsIn(result.path.take(6)).inOrder()
        assertThat(vm.state.value.path?.points?.first()?.flowNumbers).isEmpty()
        assertThat(vm.state.value.path?.points?.get(1)?.flowNumbers).containsExactly(1)
        vm.onEvent(CashScenarioEvent.PathPage(99))
        assertThat(vm.state.value.path?.points?.map { it.day }).containsExactlyElementsIn(result.path.drop(6)).inOrder()
        assertThat(vm.state.value.path?.hasNext).isFalse()
        vm.onEvent(CashScenarioEvent.BackToResult)
        assertThat(vm.state.value.path).isNull()
        assertThat(vm.state.value.outcome).isNotNull()
    }
    @Test fun `adding removing and signing rows is state driven with stable identities`() = runTest(dispatcher) {
        val vm = CashScenarioViewModel(dispatcher, validCashDraft(), savedPlans = FakeSavedPlans(), clock = FixedClock())
        vm.onEvent(CashScenarioEvent.AddFlow)
        vm.onEvent(CashScenarioEvent.Direction(2, CashFlowDirection.INFLOW))
        vm.onEvent(CashScenarioEvent.EditFlow(2, CashFlowField.AMOUNT, "2"))
        vm.onEvent(CashScenarioEvent.RemoveFlow(1))
        assertThat(vm.state.value.draft.flows.single()).isEqualTo(CashFlowDraft(2, amount = "2", direction = CashFlowDirection.INFLOW))
        vm.onEvent(CashScenarioEvent.AddFlow)
        assertThat(vm.state.value.draft.flows.map { it.id }).containsExactly(2, 3)
    }
}

private fun validCashDraft() = CashScenarioDraft(currency = "KWD", openingDate = "2026-11-21", openingCash = "10.123",
    reserve = "0", horizonEnd = "2026-11-23", scopeComplete = true, anchorFresh = true,
    flows = listOf(CashFlowDraft(1, "2026-11-22", "0.500", CashFlowDirection.INFLOW)))
