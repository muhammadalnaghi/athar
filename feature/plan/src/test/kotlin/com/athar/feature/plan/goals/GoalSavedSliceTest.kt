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
import com.athar.core.domain.repo.AccountRepository
import com.athar.core.domain.repo.ReconcileResult
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.athar.feature.plan.GoalsEvent
import com.athar.feature.plan.GoalsViewModel
import com.athar.feature.plan.SavedGoalDocState
import com.athar.feature.plan.SavedGoalNotice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WM028-fast-goals: the saved-goal vertical slice over the frozen
 * `SavedFinancialPlanRepository` contract, tested against a test-only in-memory fake.
 * Exact-input codec round trips, create/load/update-with-captured-revision/delete,
 * restart-style reload, honest conflict/currency/unsupported refusals that never
 * overwrite storage or mutate the visible draft, and inputs-only persistence (loading
 * restores inputs and clears results; outcomes are recomputed by the accepted solver).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalSavedDocCodecTest {

    @Test
    fun `round trip preserves every exact literal input`() {
        val draft = GoalScenarioDraft(
            targetAmountText = "25000",
            targetBasis = GoalTargetBasis.TODAY_PRICE,
            inflationPercentText = "3",
            currentAllocationText = "7000.00",
            monthlyContributionText = "3000.000",
            horizonMonthsText = "24",
            assumptionsKind = GoalAssumptionsKind.FIXED_APR,
            aprPercentText = "6",
            feePercentText = "1",
            timing = ContributionTiming.BEGINNING_OF_MONTH,
            solveMode = GoalSolveMode.REQUIRED_NOMINAL_APR,
        )

        val decoded = GoalSavedDocCodec.decode(GoalSavedDocCodec.encode(draft))

        assertThat(decoded).isEqualTo(GoalSavedDocResult.V1(draft))
    }

    @Test
    fun `kwd style three decimal texts survive byte exact`() {
        val json = GoalSavedDocCodec.encode(
            GoalScenarioDraft(targetAmountText = "12.300", currentAllocationText = "0.500"),
        )

        // Literal strings, not numbers: no scale change, no rounding, no coercion.
        assertThat(json).contains("\"targetAmountText\":\"12.300\"")
        assertThat(json).contains("\"currentAllocationText\":\"0.500\"")
        assertThat(GoalSavedDocCodec.decode(json))
            .isEqualTo(GoalSavedDocResult.V1(GoalScenarioDraft(targetAmountText = "12.300", currentAllocationText = "0.500")))
    }

    @Test
    fun `foreign and broken documents are unsupported never fabricated`() {
        val unsupported = listOf(
            // A future version this build must retain but cannot load.
            """{"payloadVersion":2,"targetAmountText":"5"}""",
            // Missing version.
            """{"targetAmountText":"5"}""",
            // Non-numeric version.
            """{"payloadVersion":true}""",
            // Malformed JSON.
            """{"payloadVersion":1,"targetAmountText""",
            // Missing a required input field.
            """{"payloadVersion":1,"solveMode":"PROJECT_FUTURE_AMOUNT"}""",
            // Unknown enum names (a version-1 document with unrepresentable variants).
            """{"payloadVersion":1,"targetAmountText":"5","solveMode":"TIME_TRAVEL"}""",
            """{"payloadVersion":1,"targetAmountText":"5","timing":"SOMETIMES"}""",
        )
        unsupported.forEach { payload ->
            assertThat(GoalSavedDocCodec.decode(payload)).isEqualTo(GoalSavedDocResult.Unsupported)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GoalSavedSliceTest {

    private lateinit var mainDispatcher: TestDispatcher
    private lateinit var prefs: FakeUserPreferencesRepository
    private lateinit var savedPlans: FakeSavedFinancialPlanRepository

    @BeforeEach
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        prefs = FakeUserPreferencesRepository()
        savedPlans = FakeSavedFinancialPlanRepository()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `overlapping save taps create only one goal document`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("One intended goal"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        savedPlans.saveGate = gate
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        runCurrent()
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        runCurrent()
        val requestsBeforeRelease = savedPlans.saveRequests
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(requestsBeforeRelease).isEqualTo(1)
        assertThat(savedPlans.stored).hasSize(1)
    }

    @Test
    fun `saving the visible draft creates a literal document and lists it loadable`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("سيارة"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()

        val state = viewModel.state.value
        val doc = savedPlans.stored.single()
        assertThat(doc.kind).isEqualTo(SavedFinancialPlanKind.GOAL)
        assertThat(doc.title).isEqualTo("سيارة")
        assertThat(doc.currency).isEqualTo("SAR")
        assertThat(doc.payloadVersion).isEqualTo(1)
        // Exact literal inputs, never a cached solver outcome.
        assertThat(doc.payloadJson).contains("\"targetAmountText\":\"12000\"")
        assertThat(doc.payloadJson).contains("\"monthlyContributionText\":\"100.000\"")
        assertThat(state.savedGoals.single().state).isEqualTo(SavedGoalDocState.LOADABLE)
        assertThat(state.activeSavedGoalId).isEqualTo(doc.id)
        assertThat(state.savedGoalNotice).isEqualTo(SavedGoalNotice.Saved)
    }

    @Test
    fun `loading a saved goal restores exact inputs and clears results for recompute`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val savedId = savedPlans.stored.single().id

        // Diverge the live scenario, then load the document back.
        viewModel.onEvent(GoalsEvent.UpdateScenario(GoalScenarioDraft(targetAmountText = "1")))
        viewModel.onEvent(GoalsEvent.ComputeScenario)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.LoadSavedGoal(savedId))
        advanceUntilIdle()

        val scenario = viewModel.state.value.scenario
        assertThat(scenario.draft).isEqualTo(scenarioDraft())
        assertThat(scenario.result).isNull() // inputs, not outcomes
        assertThat(scenario.currency).isEqualTo("SAR")
        assertThat(viewModel.state.value.activeSavedGoalId).isEqualTo(savedId)
        assertThat(viewModel.state.value.savedGoalTitleText).isEqualTo("هدف")
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.Loaded)

        viewModel.onEvent(GoalsEvent.ComputeScenario)
        advanceUntilIdle()
        assertThat(viewModel.state.value.scenario.result)
            .isInstanceOf(GoalSolveUi.Projected::class.java)
    }

    @Test
    fun `updating the loaded goal writes with the captured revision`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val saved = savedPlans.stored.single()
        val createdAt = saved.createdAtEpochMillis

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft().copy(targetAmountText = "13000")))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()

        val updated = savedPlans.stored.single()
        assertThat(updated.id).isEqualTo(saved.id)
        assertThat(updated.revision).isEqualTo(saved.revision + 1)
        assertThat(updated.title).isEqualTo("هدف")
        assertThat(updated.createdAtEpochMillis).isEqualTo(createdAt) // immutable creation
        assertThat(updated.payloadJson).contains("\"targetAmountText\":\"13000\"")
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.Updated)
    }

    @Test
    fun `stale captured revision refuses the update and keeps the newer document`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val external = savedPlans.stored.single().payloadJson
        val externalTitle = savedPlans.stored.single().title
        // A concurrent writer moved the document on after this session captured its revision.
        savedPlans.bumpRevisionExternally(savedPlans.stored.single().id)
        advanceUntilIdle()

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft().copy(targetAmountText = "999")))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()

        val doc = savedPlans.stored.single()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.Conflict)
        assertThat(doc.payloadJson).isEqualTo(external) // no silent overwrite
        assertThat(doc.title).isEqualTo(externalTitle)
    }

    @Test
    fun `delete removes the document and a refused delete keeps it stored`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val id = savedPlans.stored.single().id
        assertThat(viewModel.state.value.savedGoals.single().state).isEqualTo(SavedGoalDocState.LOADABLE)

        // The visible snapshot loses a race with an external writer: refuse once.
        savedPlans.refuseNextDelete = true
        viewModel.onEvent(GoalsEvent.DeleteSavedGoal(id))
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.Conflict)
        assertThat(savedPlans.stored).hasSize(1)

        viewModel.onEvent(GoalsEvent.DeleteSavedGoal(id))
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.Deleted)
        assertThat(savedPlans.stored).isEmpty()
        assertThat(viewModel.state.value.savedGoals).isEmpty()
        assertThat(viewModel.state.value.activeSavedGoalId).isNull()
    }

    @Test
    fun `restart style reload restores saved goals and their exact inputs`() = runTest(mainDispatcher) {
        val exotic = GoalScenarioDraft(
            targetAmountText = "25000",
            targetBasis = GoalTargetBasis.TODAY_PRICE,
            inflationPercentText = "3",
            currentAllocationText = "7000.00",
            monthlyContributionText = "3000.000",
            horizonMonthsText = "24",
            assumptionsKind = GoalAssumptionsKind.FIXED_APR,
            aprPercentText = "6",
            feePercentText = "1",
            timing = ContributionTiming.BEGINNING_OF_MONTH,
            solveMode = GoalSolveMode.REQUIRED_NOMINAL_APR,
        )
        val first = goalsViewModel()
        backgroundCollect(first)
        advanceUntilIdle()
        first.onEvent(GoalsEvent.UpdateScenario(exotic))
        first.onEvent(GoalsEvent.SetSavedGoalTitle("عمرة"))
        first.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()

        // A brand-new ViewModel over the same storage: process-recreation equivalent.
        val second = goalsViewModel()
        backgroundCollect(second)
        advanceUntilIdle()

        val row = second.state.value.savedGoals.single()
        assertThat(row.title).isEqualTo("عمرة")
        assertThat(row.state).isEqualTo(SavedGoalDocState.LOADABLE)
        second.onEvent(GoalsEvent.LoadSavedGoal(row.id))
        advanceUntilIdle()
        assertThat(second.state.value.scenario.draft).isEqualTo(exotic)
        assertThat(second.state.value.scenario.result).isNull()
        assertThat(second.state.value.activeSavedGoalId).isEqualTo(row.id)
    }

    @Test
    fun `other currency documents are refused without mutating the draft and stay retained`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val id = savedPlans.stored.single().id

        prefs.setDisplayCurrency("KWD")
        advanceUntilIdle()
        val afterSwitch = viewModel.state.value
        assertThat(afterSwitch.currency).isEqualTo("KWD")
        assertThat(afterSwitch.savedGoals.single().state).isEqualTo(SavedGoalDocState.OTHER_CURRENCY)
        assertThat(afterSwitch.activeSavedGoalId).isNull() // the load session ended

        val liveDraft = GoalScenarioDraft(targetAmountText = "42")
        viewModel.onEvent(GoalsEvent.UpdateScenario(liveDraft))
        viewModel.onEvent(GoalsEvent.LoadSavedGoal(id))
        advanceUntilIdle()

        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.CurrencyMismatch)
        assertThat(viewModel.state.value.scenario.draft).isEqualTo(liveDraft) // untouched
        assertThat(viewModel.state.value.activeSavedGoalId).isNull()
        assertThat(savedPlans.stored.single().currency).isEqualTo("SAR") // retained, never relabeled
    }

    @Test
    fun `unsupported documents stay listed unloadable and explicitly deletable`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        savedPlans.save(
            SavedFinancialPlan(
                id = "legacy-doc",
                kind = SavedFinancialPlanKind.GOAL,
                title = "قديم",
                currency = "SAR",
                payloadVersion = 7,
                payloadJson = """{"payloadVersion":7,"whatever":true}""",
                revision = 5,
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            ),
            expectedRevision = null,
        )
        advanceUntilIdle()

        val listed = viewModel.state.value.savedGoals.single()
        assertThat(listed.title).isEqualTo("قديم")
        assertThat(listed.state).isEqualTo(SavedGoalDocState.UNSUPPORTED)

        viewModel.onEvent(GoalsEvent.LoadSavedGoal("legacy-doc"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.UnsupportedDocument)
        assertThat(viewModel.state.value.scenario.draft).isEqualTo(GoalScenarioDraft.empty())
        assertThat(viewModel.state.value.activeSavedGoalId).isNull()
        assertThat(savedPlans.stored.single().payloadJson).contains("payloadVersion\":7") // retained

        viewModel.onEvent(GoalsEvent.DeleteSavedGoal("legacy-doc"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.Deleted)
        assertThat(savedPlans.stored).isEmpty()

        viewModel.onEvent(GoalsEvent.DismissSavedGoalNotice)
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isNull()
    }

    @Test
    fun `blank titles refuse and nothing saves before preferences bind a currency`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        // No collection, no preference ever loaded: boundCurrency is still null.
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        assertThat(savedPlans.stored).isEmpty()

        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("   "))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.NeedsTitle)
        assertThat(savedPlans.stored).isEmpty()

        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalNotice).isNull() // typing cleared the refusal
    }

    @Test
    fun `future outer version cannot load a supported inner payload`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        savedPlans.save(
            SavedFinancialPlan(
                id = "future-outer", kind = SavedFinancialPlanKind.GOAL, title = "Future",
                currency = "SAR", payloadVersion = 7,
                payloadJson = GoalSavedDocCodec.encode(GoalScenarioDraft(targetAmountText = "999")),
                revision = 0, createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            ), expectedRevision = null,
        )
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoals.single().state).isEqualTo(SavedGoalDocState.UNSUPPORTED)

        viewModel.onEvent(GoalsEvent.LoadSavedGoal("future-outer"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.scenario.draft).isEqualTo(scenarioDraft())
        assertThat(viewModel.state.value.activeSavedGoalId).isNull()
        assertThat(viewModel.state.value.savedGoalNotice).isEqualTo(SavedGoalNotice.UnsupportedDocument)
        assertThat(savedPlans.stored.single().payloadVersion).isEqualTo(7)
    }

    @Test
    fun `save commits the draft and title captured at request time not a later edit`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        // Both land after the request was captured but before its coroutine body runs.
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft().copy(targetAmountText = "999")))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("لاحق"))
        advanceUntilIdle()

        val doc = savedPlans.stored.single()
        assertThat(doc.title).isEqualTo("هدف")
        assertThat(doc.payloadJson).contains("\"targetAmountText\":\"12000\"")
        assertThat(doc.payloadJson).doesNotContain("\"targetAmountText\":\"999\"")
        // The live form keeps the newer edit; only the storage write was frozen.
        assertThat(viewModel.state.value.savedGoalTitleText).isEqualTo("لاحق")
    }

    @Test
    fun `a load landing after a newer edit never overwrites the edited draft`() = runTest(mainDispatcher) {
        savedPlans.seedExisting(SavedFinancialPlan(
            id = "late-load", kind = SavedFinancialPlanKind.GOAL, title = "محفوظ",
            currency = "SAR", payloadVersion = 1,
            payloadJson = GoalSavedDocCodec.encode(scenarioDraft()), revision = 7,
            createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
        ))
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        val loadGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        savedPlans.getGate = loadGate
        viewModel.onEvent(GoalsEvent.LoadSavedGoal("late-load"))
        runCurrent()
        val edited = GoalScenarioDraft(targetAmountText = "777")
        viewModel.onEvent(GoalsEvent.UpdateScenario(edited))
        loadGate.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value.scenario.draft).isEqualTo(edited)
        assertThat(viewModel.state.value.activeSavedGoalId).isNull()
        assertThat(viewModel.state.value.savedGoalNotice).isNull()
        assertThat(savedPlans.stored.single().payloadJson)
            .contains("\"targetAmountText\":\"12000\"")
    }

    @Test
    fun `a save completing after an applied load writes only its own document`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("أول"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val first = savedPlans.stored.single()

        // A second document created elsewhere in the same storage.
        savedPlans.seedExisting(
            SavedFinancialPlan(
                id = "second",
                kind = SavedFinancialPlanKind.GOAL,
                title = "ثان",
                currency = "SAR",
                payloadVersion = 1,
                payloadJson = GoalSavedDocCodec.encode(GoalScenarioDraft(targetAmountText = "555")),
                revision = 41,
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            ),
        )
        advanceUntilIdle()

        val edited = scenarioDraft().copy(monthlyContributionText = "250")
        viewModel.onEvent(GoalsEvent.UpdateScenario(edited))
        viewModel.onEvent(GoalsEvent.LoadSavedGoal("second")) // queued load
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal) // queued save of the first session
        advanceUntilIdle()

        // The save still wrote the draft it captured — into ITS OWN document only.
        val savedFirst = savedPlans.stored.first { it.id == first.id }
        assertThat(savedFirst.revision).isEqualTo(first.revision + 1)
        assertThat(savedFirst.payloadJson).contains("\"monthlyContributionText\":\"250\"")
        // The loaded document was not rewritten as a side effect of the stale save.
        val second = savedPlans.stored.first { it.id == "second" }
        assertThat(second.revision).isEqualTo(41)
        assertThat(second.payloadJson).contains("\"targetAmountText\":\"555\"")
        // The visible session belongs to the load, not the superseded save.
        assertThat(viewModel.state.value.activeSavedGoalId).isEqualTo("second")
        assertThat(viewModel.state.value.scenario.draft.targetAmountText).isEqualTo("555")
        assertThat(viewModel.state.value.savedGoalTitleText).isEqualTo("ثان")
    }

    @Test
    fun `a save queued before a currency switch stamps the captured currency and ends the session`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        prefs.setDisplayCurrency("KWD")
        advanceUntilIdle()

        // The captured literal currency is what was stamped, never the new display one.
        assertThat(savedPlans.stored.single().currency).isEqualTo("SAR")
        assertThat(viewModel.state.value.currency).isEqualTo("KWD")
        assertThat(viewModel.state.value.savedGoals.single().state).isEqualTo(SavedGoalDocState.OTHER_CURRENCY)
        assertThat(viewModel.state.value.activeSavedGoalId).isNull()
    }

    @Test
    fun `compare opens a read only side by side snapshot and any edit closes it`() = runTest(mainDispatcher) {
        val viewModel = goalsViewModel()
        backgroundCollect(viewModel)
        advanceUntilIdle()
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft()))
        viewModel.onEvent(GoalsEvent.SetSavedGoalTitle("هدف"))
        viewModel.onEvent(GoalsEvent.SaveCurrentGoal)
        advanceUntilIdle()
        val stored = savedPlans.stored.single()
        val id = stored.id

        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft().copy(targetAmountText = "20000")))
        viewModel.onEvent(GoalsEvent.CompareSavedGoal(id))
        advanceUntilIdle()

        val compare = viewModel.state.value.savedGoalCompare
        assertThat(compare).isNotNull()
        assertThat(compare!!.savedId).isEqualTo(id)
        assertThat(compare.savedTitle).isEqualTo("هدف")
        assertThat(compare.savedCurrency).isEqualTo("SAR")
        assertThat(compare.savedDraft).isEqualTo(scenarioDraft())
        assertThat(compare.liveDraft.targetAmountText).isEqualTo("20000")
        // Both sides recomputed by the accepted solver, not cached outcomes.
        assertThat(compare.savedResult).isInstanceOf(GoalSolveUi.Projected::class.java)
        assertThat(compare.liveResult).isInstanceOf(GoalSolveUi.Projected::class.java)
        // A comparison writes nothing: same single document, same revision.
        assertThat(savedPlans.stored.single().id).isEqualTo(id)
        assertThat(savedPlans.stored.single().revision).isEqualTo(stored.revision)

        // Any visible edit discards the snapshot instead of mislabeling it.
        viewModel.onEvent(GoalsEvent.UpdateScenario(scenarioDraft().copy(targetAmountText = "1")))
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalCompare).isNull()

        viewModel.onEvent(GoalsEvent.DismissSavedGoalCompare)
        advanceUntilIdle()
        assertThat(viewModel.state.value.savedGoalCompare).isNull()
    }

    // ---- harness ------------------------------------------------------------------

    private fun TestScope.backgroundCollect(viewModel: GoalsViewModel) {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { viewModel.state.collect { } }
    }

    private fun goalsViewModel(): GoalsViewModel = GoalsViewModel(
        transactions = FakeTransactionRepository(defaultTransactions()),
        accounts = FakeAccountRepository(),
        prefs = prefs,
        clock = MutableTestClock(Instant.parse("2026-10-22T09:00:00Z")),
        savedPlans = savedPlans,
        solveDispatcher = mainDispatcher,
    )

    private fun scenarioDraft(): GoalScenarioDraft = GoalScenarioDraft(
        targetAmountText = "12000",
        currentAllocationText = "1000.00",
        monthlyContributionText = "100.000",
        horizonMonthsText = "12",
    )

    private fun defaultTransactions(): List<Transaction> = listOf(
        tx("inc-1", LocalDate(2026, 8, 1), 1000000, TxType.INCOME),
        tx("exp-1", LocalDate(2026, 9, 1), 300000, TxType.EXPENSE),
    )

    private class FakeTransactionRepository(private val rows: List<Transaction>) : TransactionRepository {
        override fun observeByPeriod(period: Period, status: TxStatus?): Flow<List<Transaction>> =
            flowOf(rows.filter { it.date >= period.start && it.date < period.endExclusive })

        override fun observePending(): Flow<List<Transaction>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<Transaction>> = flowOf(rows)
        override suspend fun get(id: String): Transaction? = null
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

    private class FakeAccountRepository : AccountRepository {
        private val checking = account("acc-checking", AccountType.CHECKING)
        private val savings = account("acc-savings", AccountType.SAVINGS)

        override fun observeActive(): Flow<List<Account>> = flowOf(listOf(checking, savings))
        override fun observeAll(includeArchived: Boolean): Flow<List<Account>> = flowOf(listOf(checking, savings))
        override suspend fun get(id: String): Account? = null
        override suspend fun upsert(account: Account) = Unit
        override suspend fun setArchived(id: String, archived: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
        override fun observeNetWorth(displayCurrency: String): Flow<NetWorth> = flowOf(
            NetWorth(
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
        override suspend fun reconcile(accountId: String, target: Money, label: String, note: String?): ReconcileResult =
            ReconcileResult.Failed("unused in saved-goal tests")

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

    private class FakeUserPreferencesRepository(initialCurrency: String? = "SAR") : UserPreferencesRepository {
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
        override suspend fun setSavingsRateTargetPercent(percent: Int) = Unit
        override fun emergencyFundTargetMonths(): Flow<Int> = flowOf(6)
        override suspend fun setEmergencyFundTargetMonths(months: Int) = Unit
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
