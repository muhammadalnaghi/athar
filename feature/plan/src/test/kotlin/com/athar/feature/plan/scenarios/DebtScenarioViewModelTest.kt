package com.athar.feature.plan.scenarios

import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.feature.plan.debt.decodeDebtScenarioDocument
import com.athar.feature.plan.debt.encodeDebtScenarioDocument
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
class DebtScenarioViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeEach fun before() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun after() { Dispatchers.resetMain() }

    private fun savedDocument(
        id: String,
        title: String,
        draft: DebtScenarioDraft = validDebtDraft(),
        revision: Long = 7L,
    ) = SavedFinancialPlan(
        id = id, kind = SavedFinancialPlanKind.DEBT, title = title, currency = draft.currency,
        payloadVersion = 1, payloadJson = encodeDebtScenarioDocument(draft),
        revision = revision, createdAtEpochMillis = 1L, updatedAtEpochMillis = 2L,
    )

    @Test fun `compute publishes the real outcome and editing invalidates it`() = runTest(dispatcher) {
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), FakeSavedDebtPlans())
        vm.onEvent(DebtScenarioEvent.Compute)
        assertThat(vm.state.value.computing).isTrue()
        advanceUntilIdle()
        assertThat(vm.state.value.outcome).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.BUDGET, "60"))
        assertThat(vm.state.value.outcome).isNull()
        assertThat(vm.state.value.computing).isFalse()
    }
    @Test fun `currency change clears monetary draft and acknowledgement instead of relabeling`() = runTest(dispatcher) {
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), FakeSavedDebtPlans())
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.CURRENCY, "KWD"))
        assertThat(vm.state.value.draft.currency).isEqualTo("KWD")
        assertThat(vm.state.value.draft.budget).isEmpty()
        assertThat(vm.state.value.draft.loans.single().principal).isEmpty()
        assertThat(vm.state.value.draft.supportedTermsAcknowledged).isFalse()
        assertThat(vm.state.value.outcome).isNull()
    }
    @Test fun `late noncooperative result cannot overwrite edited inputs`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), FakeSavedDebtPlans(), solver = { draft ->
            withContext(NonCancellable) { release.await(); evaluateDebtDraft(draft) }
        })
        vm.onEvent(DebtScenarioEvent.Compute); runCurrent()
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.BUDGET, "60"))
        release.complete(Unit); advanceUntilIdle()
        assertThat(vm.state.value.outcome).isNull()
        assertThat(vm.state.value.draft.budget).isEqualTo("60")
        assertThat(vm.state.value.computing).isFalse()
    }
    @Test fun `reset invalidates queued solve and keeps only explicit currency`() = runTest(dispatcher) {
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), FakeSavedDebtPlans())
        vm.onEvent(DebtScenarioEvent.Compute)
        vm.onEvent(DebtScenarioEvent.Reset); advanceUntilIdle()
        assertThat(vm.state.value.outcome).isNull()
        assertThat(vm.state.value.draft).isEqualTo(DebtScenarioDraft(currency = "SAR"))
    }
    @Test fun `schedule opens actual complete ledger with pagination and back preserves result`() = runTest(dispatcher) {
        val base = validDebtDraft()
        val vm = DebtScenarioViewModel(dispatcher, base.copy(budget = "10", horizon = "24",
            loans = listOf(base.loans.single().copy(principal = "140", minimum = "10"))), FakeSavedDebtPlans())
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        val result = vm.state.value.outcome as DebtPayoffOutcome.Compared
        vm.onEvent(DebtScenarioEvent.OpenSchedule(DebtStrategy.SNOWBALL))
        assertThat(vm.state.value.schedule?.totalCycles).isEqualTo(14)
        assertThat(vm.state.value.schedule?.cycles).containsExactlyElementsIn(result.snowball.ledger.take(6)).inOrder()
        vm.onEvent(DebtScenarioEvent.SchedulePage(1))
        assertThat(vm.state.value.schedule?.cycles).containsExactlyElementsIn(result.snowball.ledger.drop(6).take(6)).inOrder()
        vm.onEvent(DebtScenarioEvent.SchedulePage(99))
        assertThat(vm.state.value.schedule?.cycles).hasSize(2)
        assertThat(vm.state.value.schedule?.hasNext).isFalse()
        vm.onEvent(DebtScenarioEvent.BackToResult)
        assertThat(vm.state.value.schedule).isNull()
        assertThat(vm.state.value.outcome).isSameInstanceAs(result)
    }
    @Test fun `loan edits remove and add stable identities without retaining old results`() = runTest(dispatcher) {
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), FakeSavedDebtPlans())
        vm.onEvent(DebtScenarioEvent.AddLoan)
        assertThat(vm.state.value.draft.loans.map { it.id }).containsExactly(1, 2)
        vm.onEvent(DebtScenarioEvent.RemoveLoan(1))
        vm.onEvent(DebtScenarioEvent.AddLoan)
        assertThat(vm.state.value.draft.loans.map { it.id }).containsExactly(2, 3)
    }
    @Test fun `solver failure exposes retryable safe state never an internal exception`() = runTest(dispatcher) {
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), FakeSavedDebtPlans(), solver = { error("internal synthetic failure") })
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        assertThat(vm.state.value.errors).containsExactly("calculation")
        assertThat(vm.state.value.outcome).isNull()
        assertThat(vm.state.value.computing).isFalse()
    }

    @Test fun `save as new creates with exact terms literal currency and binds the loaded plan`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft().copy(currency = "KWD", budget = "1.006"), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.EditSaveTitle("تسوية"))
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.SaveAsNew); advanceUntilIdle()
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.SAVED)
        val (plan, expectedRevision) = repo.saveCalls.single()
        assertThat(expectedRevision).isNull()
        assertThat(plan.kind).isEqualTo(SavedFinancialPlanKind.DEBT)
        assertThat(plan.currency).isEqualTo("KWD")
        assertThat(plan.payloadVersion).isEqualTo(1)
        assertThat(plan.title).isEqualTo("تسوية")
        assertThat(plan.payloadJson).contains("\"1.006\"")
        assertThat(vm.state.value.loaded?.id).isEqualTo(plan.id)
        assertThat(vm.state.value.loaded?.revision).isEqualTo(repo.stored.values.single().revision)
    }
    @Test fun `save without a usable title refuses without any write`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.SaveAsNew); advanceUntilIdle()
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.TITLE_INVALID)
        assertThat(repo.saveCalls).isEmpty()
    }
    @Test fun `late save completion cannot clobber a session edited meanwhile`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val saveGate = CompletableDeferred<Unit>()
        repo.gate = saveGate
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.EditSaveTitle("أصل"))
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.SaveAsNew); runCurrent()
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.BUDGET, "70"))
        saveGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.draft.budget).isEqualTo("70")
        assertThat(vm.state.value.loaded).isNull()
        assertThat(repo.stored.values.single().title).isEqualTo("أصل")
    }
    @Test fun `update keeps identity and sends the exactly captured revision`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.EditSaveTitle("أصل"))
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.SaveAsNew); advanceUntilIdle()
        val revisionAtSave = repo.stored.values.single().revision
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.BUDGET, "80"))
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.UpdateSaved); advanceUntilIdle()
        val (plan, expectedRevision) = repo.saveCalls.last()
        assertThat(expectedRevision).isEqualTo(revisionAtSave)
        assertThat(plan.id).isEqualTo(repo.stored.keys.single())
        val storedNow = repo.stored.values.single()
        assertThat(storedNow.revision).isNotEqualTo(revisionAtSave)
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.UPDATED)
        assertThat(vm.state.value.loaded?.revision).isEqualTo(storedNow.revision)
        assertThat(decodeDebtScenarioDocument(storedNow.payloadJson)?.budget).isEqualTo("80")
    }
    @Test fun `conflict refuses the write detaches the binding and preserves the newer edit`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.EditSaveTitle("أصل"))
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.SaveAsNew); advanceUntilIdle()
        val captured = repo.stored.values.single()
        repo.save(captured.copy(title = "خارجي"), captured.revision); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.UpdateSaved); advanceUntilIdle()
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.CONFLICT)
        assertThat(vm.state.value.loaded).isNull()
        assertThat(repo.stored.values.single().title).isEqualTo("خارجي")
        assertThat(repo.stored.values.single().payloadJson)
            .isEqualTo(encodeDebtScenarioDocument(validDebtDraft()))
    }
    @Test fun `load restores exact typed terms and recomputes through the engine`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val source = validDebtDraft().copy(currency = "KWD", budget = "1250.500",
            loans = listOf(DebtLoanDraft(name = "عقار", principal = "1250.500", annualPercent = "4.5", minimum = "120.250", fee = "1.500")))
        repo.seed(savedDocument("id-1", "خطة", source))
        val vm = DebtScenarioViewModel(dispatcher, DebtScenarioDraft(), repo)
        advanceUntilIdle()
        assertThat(vm.state.value.saved.single().available).isTrue()
        vm.onEvent(DebtScenarioEvent.LoadSaved("id-1")); advanceUntilIdle()
        assertThat(vm.state.value.draft).isEqualTo(source)
        assertThat(vm.state.value.loaded?.revision).isEqualTo(7L)
        assertThat(vm.state.value.outcome).isInstanceOf(DebtPayoffOutcome.Compared::class.java)
    }
    @Test fun `late load completion cannot overwrite a session edited meanwhile`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        repo.seed(savedDocument("id-1", "خطة"))
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        val loadGate = CompletableDeferred<Unit>()
        repo.getGate = loadGate
        vm.onEvent(DebtScenarioEvent.LoadSaved("id-1")); runCurrent()
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.BUDGET, "77"))
        loadGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.draft.budget).isEqualTo("77")
        assertThat(vm.state.value.loaded).isNull()
        assertThat(vm.state.value.outcome).isNull()
    }
    @Test fun `currency edit severs the saved binding so no stale update writes`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.EditSaveTitle("أصل"))
        vm.onEvent(DebtScenarioEvent.Compute); advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.SaveAsNew); advanceUntilIdle()
        assertThat(vm.state.value.loaded).isNotNull()
        vm.onEvent(DebtScenarioEvent.Edit(DebtField.CURRENCY, "KWD")); advanceUntilIdle()
        assertThat(vm.state.value.loaded).isNull()
        vm.onEvent(DebtScenarioEvent.UpdateSaved); advanceUntilIdle()
        assertThat(repo.saveCalls).hasSize(1)
        assertThat(repo.saveCalls.single().second).isNull()
    }
    @Test fun `delete sends the exact captured revision detaches loaded and refreshes the list`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        repo.seed(savedDocument("id-1", "واحد"))
        repo.seed(savedDocument("id-2", "اثنان", revision = 8L))
        val vm = DebtScenarioViewModel(dispatcher, DebtScenarioDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.LoadSaved("id-1")); advanceUntilIdle()
        assertThat(vm.state.value.loaded?.id).isEqualTo("id-1")
        val revision = repo.stored["id-1"]!!.revision
        vm.onEvent(DebtScenarioEvent.DeleteSaved("id-1")); advanceUntilIdle()
        assertThat(repo.deleteCalls.single()).isEqualTo("id-1" to revision)
        assertThat(repo.stored).doesNotContainKey("id-1")
        assertThat(vm.state.value.loaded).isNull()
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.DELETED)
        assertThat(vm.state.value.saved.map { it.plan.id }).containsExactly("id-2")
    }
    @Test fun `rename persists a trimmed title at the current revision`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        repo.seed(savedDocument("id-1", "قديم"))
        val vm = DebtScenarioViewModel(dispatcher, DebtScenarioDraft(), repo)
        advanceUntilIdle()
        vm.onEvent(DebtScenarioEvent.RenameSaved("id-1", "  جديد  ")); advanceUntilIdle()
        assertThat(repo.stored["id-1"]!!.title).isEqualTo("جديد")
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.RENAMED)
    }
    @Test fun `damaged and future documents stay retained with explicit unavailable state`() = runTest(dispatcher) {
        val repo = FakeSavedDebtPlans()
        repo.seed(SavedFinancialPlan("bad", SavedFinancialPlanKind.DEBT, "تالفة", "SAR", 1, "{not json", 3L, 1L, 2L))
        val vm = DebtScenarioViewModel(dispatcher, validDebtDraft(), repo)
        advanceUntilIdle()
        assertThat(vm.state.value.saved.single().available).isFalse()
        vm.onEvent(DebtScenarioEvent.LoadSaved("bad")); advanceUntilIdle()
        assertThat(vm.state.value.message).isEqualTo(DebtSaveMessage.UNAVAILABLE)
        assertThat(vm.state.value.draft).isEqualTo(validDebtDraft())
        assertThat(vm.state.value.loaded).isNull()
        repo.seed(SavedFinancialPlan("v2", SavedFinancialPlanKind.DEBT, "مستقبلية", "SAR", 2, "{\"version\":2}", 4L, 1L, 3L))
        advanceUntilIdle()
        assertThat(repo.stored).hasSize(2)
        assertThat(vm.state.value.saved.map { it.plan.id to it.available })
            .containsExactly("bad" to false, "v2" to false).inOrder()
    }
}
