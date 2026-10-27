package com.athar.feature.plan.position

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.feature.plan.goals.FakeSavedFinancialPlanRepository
import com.athar.feature.plan.scenarios.FixedClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WM031 saved-position persistence slice over the test-only fake that mirrors the
 * frozen `SavedFinancialPlanRepository` conflict semantics: exact-input save/reopen,
 * the synchronous double-save guard, stale-revision refusals that never overwrite,
 * cancel and invalid-input no-writes, and malformed/foreign CASH rows retained
 * untouched, never repaired or dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionSavedStateTest {

    private lateinit var mainDispatcher: TestDispatcher
    private lateinit var savedPlans: FakeSavedFinancialPlanRepository

    private val today = LocalDate(2026, 10, 24)

    @BeforeEach
    fun setUp() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        savedPlans = FakeSavedFinancialPlanRepository()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PositionViewModel(
        mainDispatcher,
        savedPlans,
        FixedClock(today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()),
        TimeZone.UTC,
    )

    private fun fillDraft(viewModel: PositionViewModel) {
        viewModel.onEvent(PositionEvent.OpenCreateEditor)
        viewModel.onEvent(PositionEvent.EditField(PositionTextField.TITLE, "مركز أكتوبر"))
        viewModel.onEvent(PositionEvent.EditField(PositionTextField.AS_OF_DATE, "2026-10-10"))
        viewModel.onEvent(PositionEvent.EditField(PositionTextField.NOTE, "أرصدة معلنة 2026"))
        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.LABEL, "حساب جاري"))
        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.CURRENCY, "SAR"))
        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.AMOUNT, "15000.00"))
        viewModel.onEvent(PositionEvent.AddEntry)
        viewModel.onEvent(PositionEvent.EditEntry(1, PositionEntryField.LABEL, "قرض سيارة"))
        viewModel.onEvent(PositionEvent.EditEntry(1, PositionEntryField.SIDE, "ASSET"))
        viewModel.onEvent(PositionEvent.EditEntry(1, PositionEntryField.CURRENCY, "SAR"))
        viewModel.onEvent(PositionEvent.EditEntry(1, PositionEntryField.AMOUNT, "4200.50"))
    }

    private fun saveDraft(viewModel: PositionViewModel) {
        fillDraft(viewModel)
        viewModel.onEvent(PositionEvent.SaveEditor)
    }

    @Test
    fun `save persists exact inputs and reopen recomputes dated totals`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        saveDraft(viewModel)
        advanceUntilIdle()

        val stored = savedPlans.stored.single()
        assertThat(stored.kind).isEqualTo(SavedFinancialPlanKind.CASH)
        assertThat(stored.title).isEqualTo("مركز أكتوبر")
        assertThat(stored.payloadVersion).isEqualTo(POSITION_PAYLOAD_VERSION)
        assertThat(stored.payloadJson).contains("\"amount\":\"15000.00\"")
        assertThat(stored.payloadJson).contains("\"documentType\":\"balance_anchor\"")
        assertThat(viewModel.state.value.editor).isNull()
        assertThat(viewModel.state.value.isBusy).isFalse()

        viewModel.onEvent(PositionEvent.OpenDoc(stored.id))
        advanceUntilIdle()
        val open = viewModel.state.value.open!!
        assertThat(open.revision).isEqualTo(stored.revision)
        assertThat(open.document.asOfDate).isEqualTo("2026-10-10")
        assertThat(open.document.note).isEqualTo("أرصدة معلنة 2026")
        assertThat(open.document.entries.map { it.amount }).containsExactly("15000.00", "4200.50").inOrder()
        assertThat(open.document.entries[1].side).isEqualTo(PositionSide.LIABILITY)
        assertThat(open.totals.ageDays).isEqualTo(14)
        val sar = open.totals.perCurrency.single()
        assertThat(sar.currency).isEqualTo("SAR")
        assertThat(sar.assets.toPlainString()).isEqualTo("15000.00")
        assertThat(sar.liabilities.toPlainString()).isEqualTo("4200.50")
        assertThat(sar.net.toPlainString()).isEqualTo("10799.50")
        val row = viewModel.state.value.rows.single()
        assertThat(row.entryCount).isEqualTo(2)
        assertThat(row.unavailable).isFalse()
    }

    @Test
    fun `repeated save taps while busy issue a single repository request`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        fillDraft(viewModel)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        savedPlans.saveGate = gate

        viewModel.onEvent(PositionEvent.SaveEditor)
        runCurrent()
        assertThat(viewModel.state.value.isBusy).isTrue()
        viewModel.onEvent(PositionEvent.SaveEditor)
        runCurrent()

        val requestsBeforeRelease = savedPlans.saveRequests
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(requestsBeforeRelease).isEqualTo(1)
        assertThat(savedPlans.stored).hasSize(1)
        assertThat(viewModel.state.value.isBusy).isFalse()
        assertThat(viewModel.state.value.editor).isNull()
    }

    @Test
    fun `update writes with the captured revision and refreshes the open document`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        saveDraft(viewModel)
        advanceUntilIdle()
        val created = savedPlans.stored.single()
        assertThat(created.revision).isEqualTo(1L)

        viewModel.onEvent(PositionEvent.OpenDoc(created.id))
        advanceUntilIdle()
        viewModel.onEvent(PositionEvent.OpenUpdateEditor(created.id))
        advanceUntilIdle()
        assertThat(viewModel.state.value.editor!!.expectedRevision).isEqualTo(1L)

        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.AMOUNT, "16000.00"))
        viewModel.onEvent(PositionEvent.SaveEditor)
        advanceUntilIdle()

        val updated = savedPlans.stored.single()
        assertThat(updated.revision).isEqualTo(2L)
        assertThat(viewModel.state.value.problem).isNull()
        assertThat(viewModel.state.value.editor).isNull()
        assertThat(viewModel.state.value.open!!.revision).isEqualTo(2L)
        assertThat(viewModel.state.value.open!!.document.entries[0].amount).isEqualTo("16000.00")
        assertThat(viewModel.state.value.open!!.totals.perCurrency.single().assets.toPlainString())
            .isEqualTo("16000.00")
        assertThat(viewModel.state.value.rows.single().revision).isEqualTo(2L)
    }

    @Test
    fun `stale captured revision refuses the update and never overwrites the newer document`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel()
            saveDraft(viewModel)
            advanceUntilIdle()
            val id = savedPlans.stored.single().id
            savedPlans.bumpRevisionExternally(id)
            advanceUntilIdle()

            viewModel.onEvent(PositionEvent.OpenUpdateEditor(id))
            advanceUntilIdle()
            assertThat(viewModel.state.value.editor!!.expectedRevision).isEqualTo(101L)

            savedPlans.bumpRevisionExternally(id)
            advanceUntilIdle()
            val storedBeforeSave = savedPlans.stored.single().payloadJson
            viewModel.onEvent(PositionEvent.EditField(PositionTextField.NOTE, "تحديث خارجي"))
            viewModel.onEvent(PositionEvent.SaveEditor)
            advanceUntilIdle()

            assertThat(viewModel.state.value.problem).isEqualTo(PositionProblem.CONFLICT)
            assertThat(viewModel.state.value.isBusy).isFalse()
            assertThat(viewModel.state.value.editor).isNotNull()
            assertThat(savedPlans.stored.single().payloadJson).isEqualTo(storedBeforeSave)
            assertThat(savedPlans.stored.single().revision).isEqualTo(201L)
        }

    @Test
    fun `cancel discards the draft and never reaches storage`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        fillDraft(viewModel)
        assertThat(viewModel.state.value.editor).isNotNull()

        viewModel.onEvent(PositionEvent.DismissEditor)

        assertThat(viewModel.state.value.editor).isNull()
        assertThat(savedPlans.stored).isEmpty()
        assertThat(savedPlans.saveRequests).isEqualTo(0)
    }

    @Test
    fun `invalid drafts are refused without a storage write`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(PositionEvent.OpenCreateEditor)

        viewModel.onEvent(PositionEvent.SaveEditor)
        assertThat(viewModel.state.value.problem).isEqualTo(PositionProblem.INVALID_INPUT)
        assertThat(viewModel.state.value.editor).isNotNull()
        assertThat(savedPlans.stored).isEmpty()
        assertThat(savedPlans.saveRequests).isEqualTo(0)

        viewModel.onEvent(PositionEvent.EditField(PositionTextField.TITLE, "عنوان"))
        viewModel.onEvent(PositionEvent.EditField(PositionTextField.AS_OF_DATE, "10-2026-24"))
        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.LABEL, "بند"))
        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.CURRENCY, "SAR"))
        viewModel.onEvent(PositionEvent.EditEntry(0, PositionEntryField.AMOUNT, "12.3.4"))
        viewModel.onEvent(PositionEvent.SaveEditor)
        assertThat(viewModel.state.value.problem).isEqualTo(PositionProblem.INVALID_INPUT)
        assertThat(savedPlans.stored).isEmpty()
    }

    @Test
    fun `malformed balance anchor rows stay retained and unloadable across refreshes`() =
        runTest(mainDispatcher) {
            val brokenJson = """
                {"documentType":"balance_anchor","balanceAnchorVersion":1,"asOfDate":"2026-10-01",
                 "entries":[{"id":"e1","label":"x","side":"ASSET","currency":"SAR","amount":"12.3.4","ownerScope":"s"}]}
            """.trimIndent().replace("\n", "")
            savedPlans.seedExisting(
                SavedFinancialPlan("broken", SavedFinancialPlanKind.CASH, "قديم", "SAR", 1, brokenJson, 7, 1, 1),
            )
            val viewModel = viewModel()
            advanceUntilIdle()

            val row = viewModel.state.value.rows.single()
            assertThat(row.unavailable).isTrue()
            assertThat(row.entryCount).isEqualTo(0)

            viewModel.onEvent(PositionEvent.OpenDoc("broken"))
            advanceUntilIdle()
            assertThat(viewModel.state.value.open).isNull()
            assertThat(viewModel.state.value.problem).isEqualTo(PositionProblem.UNSUPPORTED_DOCUMENT)

            saveDraft(viewModel)
            advanceUntilIdle()
            assertThat(savedPlans.stored).hasSize(2)
            assertThat(savedPlans.stored.first { it.id == "broken" }.payloadJson).isEqualTo(brokenJson)
            assertThat(viewModel.state.value.rows.first { it.planId == "broken" }.unavailable).isTrue()
        }

    @Test
    fun `cash scenario siblings stay stored but never appear as position rows`() = runTest(mainDispatcher) {
        savedPlans.seedExisting(
            SavedFinancialPlan(
                "cash1",
                SavedFinancialPlanKind.CASH,
                "سيناريو",
                "SAR",
                1,
                """{"documentType":"cash_scenario","cashScenarioVersion":1}""",
                4,
                1,
                1,
            ),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.rows).isEmpty()
        assertThat(savedPlans.stored).hasSize(1)
    }

    @Test
    fun `confirmed delete removes the document and clears dependent views`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        saveDraft(viewModel)
        advanceUntilIdle()
        val id = savedPlans.stored.single().id
        viewModel.onEvent(PositionEvent.OpenDoc(id))
        advanceUntilIdle()

        viewModel.onEvent(PositionEvent.RequestDelete(id))
        assertThat(viewModel.state.value.confirmDelete!!.planId).isEqualTo(id)
        viewModel.onEvent(PositionEvent.ConfirmDelete)
        advanceUntilIdle()

        assertThat(savedPlans.stored).isEmpty()
        assertThat(viewModel.state.value.open).isNull()
        assertThat(viewModel.state.value.confirmDelete).isNull()
        assertThat(viewModel.state.value.rows).isEmpty()
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `refused delete keeps the stored document and surfaces a conflict`() = runTest(mainDispatcher) {
        val viewModel = viewModel()
        saveDraft(viewModel)
        advanceUntilIdle()
        val id = savedPlans.stored.single().id
        savedPlans.refuseNextDelete = true

        viewModel.onEvent(PositionEvent.RequestDelete(id))
        viewModel.onEvent(PositionEvent.ConfirmDelete)
        advanceUntilIdle()

        assertThat(viewModel.state.value.problem).isEqualTo(PositionProblem.CONFLICT)
        assertThat(viewModel.state.value.isBusy).isFalse()
        assertThat(viewModel.state.value.confirmDelete).isNull()
        assertThat(savedPlans.stored).hasSize(1)
        assertThat(viewModel.state.value.rows.single().planId).isEqualTo(id)
    }
}
