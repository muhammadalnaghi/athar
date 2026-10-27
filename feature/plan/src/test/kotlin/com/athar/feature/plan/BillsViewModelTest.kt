package com.athar.feature.plan

import com.athar.core.common.money.Money
import com.athar.core.common.time.Period
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.RecurringRuleRepository
import com.athar.core.domain.repo.RecurringSuggestionRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class BillsViewModelTest {

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
    fun `pending uncategorized backlog is not treated as bills`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = emptyList(),
            transactions = listOf(
                tx(id = "backlog-1", date = LocalDate(2026, 6, 1), status = TxStatus.PENDING, categoryId = null),
                tx(id = "backlog-2", date = LocalDate(2026, 6, 2), status = TxStatus.CONFIRMED, categoryId = null),
            ),
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.items).isEmpty()
        assertThat(state.calendarDays.all { it.count == 0 }).isTrue()
        assertThat(state.outgoingNext30ByCurrency).isEmpty()
    }

    @Test
    fun `past occurrence with one matching charge is paid with evidence`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(tx(id = "tx-gym-1", date = LocalDate(2026, 6, 12))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val past = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(past.status).isEqualTo(BillStatus.PAID)
        assertThat(past.evidenceTransactionId).isEqualTo("tx-gym-1")
    }

    @Test
    fun `past occurrence without payment evidence stays unconfirmed not overdue`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = emptyList(),
        )

        val state = viewModel.state.first { !it.isLoading }
        val past = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(past.status).isEqualTo(BillStatus.UNCONFIRMED)
        assertThat(past.evidenceTransactionId).isNull()
    }

    @Test
    fun `ambiguous double charge is not asserted paid`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(
                tx(id = "tx-a", date = LocalDate(2026, 6, 10)),
                tx(id = "tx-b", date = LocalDate(2026, 6, 10)),
            ),
        )

        val state = viewModel.state.first { !it.isLoading }
        val past = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(past.status).isEqualTo(BillStatus.UNCONFIRMED)
    }

    @Test
    fun `charge outside amount variation does not count as paid`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(tx(id = "tx-big", date = LocalDate(2026, 6, 10), amountMinor = 15000)),
        )

        val state = viewModel.state.first { !it.isLoading }
        val past = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(past.status).isEqualTo(BillStatus.UNCONFIRMED)
    }

    @Test
    fun `next30 totals are per currency and exclude older occurrences`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(id = "rule-gym", merchant = "Gym Club", amount = Money.ofMinor(10000, "SAR"), dayOfMonth = 20, nextRunDate = LocalDate(2026, 6, 20)),
                rule(id = "rule-usd", merchant = "Cloud App", amount = Money.ofMinor(1500, "USD"), dayOfMonth = 25, nextRunDate = LocalDate(2026, 6, 25)),
                rule(id = "rule-old", merchant = "Old Service", amount = Money.ofMinor(5000, "SAR"), dayOfMonth = 10, nextRunDate = LocalDate(2026, 6, 10)),
            ),
            transactions = emptyList(),
        )

        val state = viewModel.state.first { !it.isLoading }
        val totals = state.outgoingNext30ByCurrency.associate { it.currency to it.total }

        assertThat(totals.keys).containsExactly("SAR", "USD").inOrder()
        // SAR: gym on Jun 20 plus Old Service's caught-up July occurrence (Jul 10).
        // The Jun 10 Old Service occurrence is older than today and excluded.
        assertThat(totals.getValue("SAR")).isEqualTo(Money.ofMinor(15000, "SAR"))
        assertThat(totals.getValue("USD")).isEqualTo(Money.ofMinor(1500, "USD"))
    }

    @Test
    fun `due today occurrence is due today`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 15))),
            transactions = emptyList(),
        )

        val state = viewModel.state.first { !it.isLoading }
        val due = state.items.first { it.dueDate == LocalDate(2026, 6, 15) }

        assertThat(due.status).isEqualTo(BillStatus.DUE_TODAY)
    }

    @Test
    fun `date refresh after clock advance recomputes today and statuses`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 16))),
            transactions = emptyList(),
        )
        val before = viewModel.state.first { !it.isLoading }
        assertThat(before.today).isEqualTo(LocalDate(2026, 6, 15))
        assertThat(before.items.first().status).isEqualTo(BillStatus.UPCOMING)

        clock.instant = Instant.parse("2026-06-17T12:00:00Z")
        viewModel.onEvent(BillsEvent.RefreshDate)

        val after = viewModel.state.first { it.today == LocalDate(2026, 6, 17) }
        assertThat(after.items.first { it.dueDate == LocalDate(2026, 6, 16) }.status)
            .isEqualTo(BillStatus.UNCONFIRMED)
    }

    @Test
    fun `quarterly rule maps to quarterly cadence label`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(
                    id = "rule-isp",
                    merchant = "Isp Bundles",
                    cadence = Cadence.QUARTERLY,
                    dayOfMonth = 5,
                    nextRunDate = LocalDate(2026, 7, 5),
                ),
            ),
            transactions = emptyList(),
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.items.first().cadence).isEqualTo(BillCadenceLabel.QUARTERLY)
        assertThat(Cadence.QUARTERLY.billLabelKey()).isEqualTo(BillCadenceLabel.QUARTERLY)
    }

    @Test
    fun `suggested commitments surface from genuine recurrence discovery`() = runTest(mainDispatcher) {
        val suggestion = suggestion()
        val viewModel = billsViewModel(
            rules = emptyList(),
            transactions = emptyList(),
            suggestions = listOf(suggestion),
        )

        val state = viewModel.state.first { !it.isLoading }

        assertThat(state.suggestedCommitments).hasSize(1)
        val commitment = state.suggestedCommitments.single()
        assertThat(commitment.merchant).isEqualTo("Isp Bundles")
        assertThat(commitment.cadence).isEqualTo(BillCadenceLabel.QUARTERLY)
        // Suggestions are proposals: they never become obligation items or totals.
        assertThat(state.items).isEmpty()
        assertThat(state.outgoingNext30ByCurrency).isEmpty()
    }

    @Test
    fun `one confirmed charge cannot satisfy two overlapping weekly occurrences`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(
                    id = "rule-weekly",
                    cadence = Cadence.WEEKLY,
                    dayOfMonth = null,
                    nextRunDate = LocalDate(2026, 6, 1),
                ).copy(dayOfWeek = 1),
            ),
            transactions = listOf(
                tx(id = "tx-clean", date = LocalDate(2026, 6, 2)),
                tx(id = "tx-contest", date = LocalDate(2026, 6, 14)),
            ),
        )

        val state = viewModel.state.first { !it.isLoading }
        val jun1 = state.items.first { it.dueDate == LocalDate(2026, 6, 1) }
        val jun8 = state.items.first { it.dueDate == LocalDate(2026, 6, 8) }
        val jun15 = state.items.first { it.dueDate == LocalDate(2026, 6, 15) }

        assertThat(jun1.status).isEqualTo(BillStatus.PAID)
        assertThat(jun1.evidenceTransactionId).isEqualTo("tx-clean")
        // Jun 14 sits inside the Jun 8 and Jun 15 windows at once: contested, so neither
        // occurrence may claim it. Ambiguity stays unconfirmed instead of a false paid.
        assertThat(jun8.status).isEqualTo(BillStatus.UNCONFIRMED)
        assertThat(jun8.evidenceTransactionId).isNull()
        assertThat(jun15.evidenceTransactionId).isNull()
        // Invariant: no transaction is bound as evidence to more than one occurrence.
        assertThat(state.items.mapNotNull { it.evidenceTransactionId }).containsNoDuplicates()
    }

    @Test
    fun `duplicate monthly and quarterly rules cannot consume the same charge`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(id = "rule-monthly", dayOfMonth = 10, nextRunDate = LocalDate(2026, 6, 10)),
                rule(
                    id = "rule-quarterly",
                    cadence = Cadence.QUARTERLY,
                    dayOfMonth = 10,
                    nextRunDate = LocalDate(2026, 6, 10),
                ),
            ),
            transactions = listOf(tx(id = "tx-shared", date = LocalDate(2026, 6, 10))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val monthly = state.items.first { it.id == "recurring:rule-monthly:2026-06-10" }
        val quarterly = state.items.first { it.id == "recurring:rule-quarterly:2026-06-10" }

        assertThat(monthly.status).isEqualTo(BillStatus.UNCONFIRMED)
        assertThat(monthly.evidenceTransactionId).isNull()
        assertThat(quarterly.status).isEqualTo(BillStatus.UNCONFIRMED)
        assertThat(quarterly.evidenceTransactionId).isNull()
    }

    @Test
    fun `charge on another account never satisfies the rule`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(
                tx(id = "tx-other-account", date = LocalDate(2026, 6, 12), accountId = "acc-savings"),
            ),
        )

        val state = viewModel.state.first { !it.isLoading }
        val past = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(past.status).isEqualTo(BillStatus.UNCONFIRMED)
        assertThat(past.evidenceTransactionId).isNull()
    }

    @Test
    fun `due today bill charged yesterday is paid and next30 excludes it`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(dayOfMonth = 15, nextRunDate = LocalDate(2026, 6, 15))),
            transactions = listOf(tx(id = "tx-early", date = LocalDate(2026, 6, 14))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val due = state.items.first { it.dueDate == LocalDate(2026, 6, 15) }

        assertThat(due.status).isEqualTo(BillStatus.PAID)
        assertThat(due.evidenceTransactionId).isEqualTo("tx-early")
        assertThat(state.outgoingNext30ByCurrency).isEmpty()
    }

    @Test
    fun `early payment of an upcoming bill marks it paid without reserving it in next30`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(dayOfMonth = 17, nextRunDate = LocalDate(2026, 6, 17))),
            transactions = listOf(tx(id = "tx-prepaid", date = LocalDate(2026, 6, 15))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val upcoming = state.items.first { it.dueDate == LocalDate(2026, 6, 17) }

        assertThat(upcoming.status).isEqualTo(BillStatus.PAID)
        assertThat(upcoming.evidenceTransactionId).isEqualTo("tx-prepaid")
        assertThat(state.outgoingNext30ByCurrency).isEmpty()
    }

    @Test
    fun `generic merchant keys never bind a payment to a rule`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(
                tx(id = "tx-generic", date = LocalDate(2026, 6, 12), merchantNormalized = "purchase"),
            ),
        )

        val state = viewModel.state.first { !it.isLoading }
        val past = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(past.status).isEqualTo(BillStatus.UNCONFIRMED)
        assertThat(past.evidenceTransactionId).isNull()
    }

    @Test
    fun `future dated confirmed charge cannot mark an occurrence paid as of today`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(id = "rule-stream", merchant = "Streamflix", dayOfMonth = 16, nextRunDate = LocalDate(2026, 6, 16)),
                rule(id = "rule-gym", dayOfMonth = 15, nextRunDate = LocalDate(2026, 6, 15)),
            ),
            transactions = listOf(
                tx(id = "tx-future-stream", date = LocalDate(2026, 6, 21), merchant = "Streamflix"),
                tx(id = "tx-future-gym", date = LocalDate(2026, 6, 25)),
            ),
        )

        val state = viewModel.state.first { !it.isLoading }
        val upcoming = state.items.first { it.id == "recurring:rule-stream:2026-06-16" }
        val due = state.items.first { it.id == "recurring:rule-gym:2026-06-15" }

        // Both charges are dated after today: as of today they have not happened yet, so
        // neither the due-today nor the upcoming occurrence may claim them as payment.
        assertThat(upcoming.status).isEqualTo(BillStatus.UPCOMING)
        assertThat(upcoming.evidenceTransactionId).isNull()
        assertThat(due.status).isEqualTo(BillStatus.DUE_TODAY)
        assertThat(due.evidenceTransactionId).isNull()
    }

    @Test
    fun `charge competing with a hidden prior weekly occurrence cannot mark it paid`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(
                    id = "rule-weekly-current",
                    cadence = Cadence.WEEKLY,
                    dayOfMonth = null,
                    dayOfWeek = 1,
                    nextRunDate = LocalDate(2026, 6, 15),
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            ),
            transactions = listOf(tx(id = "tx-yesterday", date = LocalDate(2026, 6, 14))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val dueToday = state.items.first { it.dueDate == LocalDate(2026, 6, 15) }

        // The rule's real schedule holds last Monday (Jun 8) too, but the UI walk starts
        // at the rule's anchor, so that occurrence is hidden from the list. The guard
        // universe still accounts for it: yesterday's charge is a candidate for both real
        // payment windows and therefore settles neither occurrence. Without evidence the
        // calendar decides — due today stays due today, never a false paid.
        assertThat(dueToday.status).isEqualTo(BillStatus.DUE_TODAY)
        assertThat(dueToday.evidenceTransactionId).isNull()
        assertThat(state.coverage?.matchGuardComplete).isTrue()
    }

    @Test
    fun `long lived monthly rule with truncated history still confirms its unique payment`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(
                rule(
                    id = "rule-old-monthly",
                    dayOfMonth = 10,
                    nextRunDate = LocalDate(2026, 3, 1),
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            ),
            transactions = listOf(tx(id = "tx-may", date = LocalDate(2026, 5, 12))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val may = state.items.first { it.dueDate == LocalDate(2026, 5, 10) }

        // History truncation never disables matching: the May charge sits inside exactly
        // one real payment window, so it confirms that occurrence even though the shown
        // history is a bounded window around today.
        assertThat(may.status).isEqualTo(BillStatus.PAID)
        assertThat(may.evidenceTransactionId).isEqualTo("tx-may")
        assertThat(state.coverage?.uiHistoryTruncated).isTrue()
    }

    @Test
    fun `bounded coverage exposes separate ui and guard windows`() = runTest(mainDispatcher) {
        val truncated = billsViewModel(
            rules = listOf(
                rule(
                    id = "rule-old-monthly",
                    dayOfMonth = 10,
                    nextRunDate = LocalDate(2026, 3, 1),
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
            ),
            transactions = emptyList(),
        )
        val truncatedState = truncated.state.first { !it.isLoading }

        // UI coverage describes the displayed list; guard coverage describes what the
        // paid-matching uniqueness graph accounted for. They are reported separately.
        assertThat(truncatedState.coverage?.uiHistoryTruncated).isTrue()
        assertThat(truncatedState.coverage?.uiHistoryWindowStart).isEqualTo(LocalDate(2026, 3, 15))
        assertThat(truncatedState.coverage?.uiFutureTruncated).isFalse()
        assertThat(truncatedState.coverage?.matchGuardWindowStart).isEqualTo(LocalDate(2026, 2, 15))
        assertThat(truncatedState.coverage?.matchGuardComplete).isTrue()

        val fresh = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = emptyList(),
        )
        val freshState = fresh.state.first { !it.isLoading }

        // A rule whose anchor sits inside the shown window reports no history truncation.
        assertThat(freshState.coverage?.uiHistoryTruncated).isFalse()
        assertThat(freshState.coverage?.uiHistoryWindowStart).isEqualTo(LocalDate(2026, 3, 15))
        assertThat(freshState.coverage?.matchGuardComplete).isTrue()
    }

    private fun billsViewModel(
        rules: List<RecurringRule>,
        transactions: List<Transaction>,
        suggestions: List<RecurringSuggestion> = emptyList(),
        ruleRepository: FakeRecurringRuleRepository = FakeRecurringRuleRepository(rules),
    ): BillsViewModel =
        BillsViewModel(
            rules = ruleRepository,
            transactions = FakeTransactionRepository(transactions),
            suggestions = FakeSuggestionRepository(suggestions),
            prefs = FakeUserPreferencesRepository(),
            clock = clock,
        )

    @Test
    fun `creating a commitment from a suggestion stores an explicit rule with detected anchors`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        val viewModel = billsViewModel(
            rules = emptyList(),
            transactions = emptyList(),
            suggestions = listOf(suggestion()),
            ruleRepository = repo,
        )

        viewModel.onEvent(BillsEvent.CreateRuleFromSuggestion(suggestion()))
        advanceUntilIdle()

        val created = repo.upserts.single()
        assertThat(created.cadence).isEqualTo(Cadence.QUARTERLY)
        assertThat(created.dayOfMonth).isEqualTo(5)
        assertThat(created.dayOfWeek).isNull()
        assertThat(created.monthOfYear).isNull()
        assertThat(created.nextRunDate).isEqualTo(LocalDate(2026, 7, 5))
        // No unambiguous account in history: the safe manual account, never a guess.
        assertThat(created.accountId).isEqualTo(MANUAL_ACCOUNT_ID)
        assertThat(created.isActive).isTrue()

        // The explicit rule yields a real expected dated occurrence on its schedule.
        val state = viewModel.state.first { it.items.any { item -> item.ruleId == created.id } }
        val expected = state.items.first { it.ruleId == created.id }
        assertThat(expected.id).isEqualTo("recurring:${created.id}:2026-07-05")
        assertThat(expected.status).isEqualTo(BillStatus.UPCOMING)
        assertThat(expected.cadence).isEqualTo(BillCadenceLabel.QUARTERLY)
        assertThat(expected.evidenceTransactionId).isNull()
    }

    @Test
    fun `pausing a commitment removes its expected occurrences`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(listOf(rule(dayOfMonth = 15, nextRunDate = LocalDate(2026, 6, 15))))
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)
        val before = viewModel.state.first { !it.isLoading }
        assertThat(before.items).isNotEmpty()

        viewModel.onEvent(BillsEvent.SetRuleActive("rule-gym", active = false))
        advanceUntilIdle()

        assertThat(repo.activeChanges).containsExactly("rule-gym" to false)
        val after = viewModel.state.first { it.items.isEmpty() }
        assertThat(after.items).isEmpty()
    }

    @Test
    fun `cancelling a commitment deletes the rule and its expected occurrences`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(listOf(rule(dayOfMonth = 15, nextRunDate = LocalDate(2026, 6, 15))))
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.CancelRule("rule-gym"))
        advanceUntilIdle()

        assertThat(repo.deletedIds).containsExactly("rule-gym")
        val after = viewModel.state.first { it.items.isEmpty() }
        assertThat(after.items).isEmpty()
    }

    @Test
    fun `paid evidence exposes the matching charge date`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(tx(id = "tx-june", date = LocalDate(2026, 6, 12))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val paid = state.items.first { it.dueDate == LocalDate(2026, 6, 10) }

        assertThat(paid.status).isEqualTo(BillStatus.PAID)
        assertThat(paid.evidenceTransactionId).isEqualTo("tx-june")
        assertThat(paid.evidenceDate).isEqualTo(LocalDate(2026, 6, 12))
        // Every non-paid occurrence stays honestly traceable as lacking evidence.
        assertThat(
            state.items.filter { it.status != BillStatus.PAID }.all { it.evidenceTransactionId == null },
        ).isTrue()
    }

    @Test
    fun `paid evidence record carries the exact bound transaction fields`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(
            rules = listOf(rule(nextRunDate = LocalDate(2026, 6, 10))),
            transactions = listOf(tx(id = "tx-exact", date = LocalDate(2026, 6, 12))),
        )

        val state = viewModel.state.first { !it.isLoading }
        val paid = state.items.first { it.status == BillStatus.PAID }

        // The drilldown record is the matcher's own bound transaction — same id, date,
        // amount, account, and merchant, never a synthesized or capped row.
        assertThat(paid.evidence).isEqualTo(
            BillEvidenceRecord(
                transactionId = "tx-exact",
                date = LocalDate(2026, 6, 12),
                amount = Money.ofMinor(9900, "SAR"),
                accountId = "acc-checking",
                merchant = "Gym Club",
            ),
        )
        assertThat(paid.evidence?.transactionId).isEqualTo(paid.evidenceTransactionId)
        assertThat(paid.evidence?.date).isEqualTo(paid.evidenceDate)
        // Review states never invent evidence — no record without a confirmed match.
        assertThat(
            state.items.filter { it.status != BillStatus.PAID }.all { it.evidence == null },
        ).isTrue()
    }

    @Test
    fun `manual editor opens with display currency seeded and dismiss discards the draft`() = runTest(mainDispatcher) {
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList())
        assertThat(viewModel.state.first { !it.isLoading }.manualRuleEditor).isNull()

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        val opened = viewModel.state.first { it.manualRuleEditor != null }

        assertThat(opened.manualRuleEditor?.draft).isEqualTo(
            ManualCommitmentDraft(currency = "SAR", nextDateText = "2026-06-15"),
        )
        assertThat(opened.manualRuleEditor?.status).isEqualTo(ManualRuleStatus.Editing)

        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(ManualCommitmentDraft(name = "Home Rent", currency = "SAR")),
        )
        viewModel.onEvent(BillsEvent.DismissManualRuleEditor)

        assertThat(viewModel.state.first { it.manualRuleEditor == null }.manualRuleEditor).isNull()
        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        assertThat(viewModel.state.first { it.manualRuleEditor != null }.manualRuleEditor?.draft).isEqualTo(
            ManualCommitmentDraft(currency = "SAR", nextDateText = "2026-06-15"),
        )
    }

    @Test
    fun `valid manual draft saves exactly one rule with explicit anchors and closes the editor`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        viewModel.state.first { it.manualRuleEditor != null }
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(
                ManualCommitmentDraft(
                    name = "  Home Rent ",
                    amountText = "1450.5",
                    currency = "sar",
                    cadence = BillCadenceLabel.MONTHLY,
                    nextDateText = "2026-07-01",
                    notes = "  annual renewal  ",
                ),
            ),
        )
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle()

        // Exactly one repository write; the rule arrives through observeAll — no shadow copy.
        assertThat(repo.upserts).hasSize(1)
        val saved = repo.upserts.single()
        assertThat(saved.displayName).isEqualTo("Home Rent")
        assertThat(saved.amount).isEqualTo(Money.ofMinor(145050, "SAR"))
        assertThat(saved.type).isEqualTo(TxType.EXPENSE)
        assertThat(saved.accountId).isEqualTo(MANUAL_ACCOUNT_ID)
        assertThat(saved.cadence).isEqualTo(Cadence.MONTHLY)
        assertThat(saved.dayOfMonth).isEqualTo(1)
        assertThat(saved.dayOfWeek).isNull()
        assertThat(saved.monthOfYear).isNull()
        assertThat(saved.nextRunDate).isEqualTo(LocalDate(2026, 7, 1))
        assertThat(saved.isActive).isTrue()
        assertThat(saved.notes).isEqualTo("annual renewal")

        val closed = viewModel.state.first { it.manualRuleEditor == null }
        val expected = closed.items.first { it.ruleId == saved.id }
        assertThat(expected.id).isEqualTo("recurring:${saved.id}:2026-07-01")
        assertThat(expected.status).isEqualTo(BillStatus.UPCOMING)
        assertThat(expected.evidence).isNull()
    }

    @Test
    fun `double submit while a save is in flight stores only one rule`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        val gate = CompletableDeferred<Unit>()
        repo.upsertGate = gate
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        viewModel.state.first { it.manualRuleEditor != null }
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(
                ManualCommitmentDraft(name = "Home Rent", amountText = "99", nextDateText = "2026-07-01"),
            ),
        )

        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle() // the save coroutine is parked inside the repository at the gate
        assertThat(repo.upserts).hasSize(1)

        // A second submit while the save is in flight is a synchronous no-op.
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle()
        assertThat(repo.upserts).hasSize(1)

        gate.complete(Unit)
        advanceUntilIdle()

        val closed = viewModel.state.first { it.manualRuleEditor == null }
        assertThat(closed.items.any { it.ruleId == repo.upserts.single().id }).isTrue()
    }

    @Test
    fun `amount beyond persisted precision is refused without touching storage`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        viewModel.state.first { it.manualRuleEditor != null }
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(
                ManualCommitmentDraft(
                    name = "Home Rent",
                    amountText = "12.345",
                    nextDateText = "2026-07-01",
                ),
            ),
        )
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle()

        val failed = viewModel.state.first { it.manualRuleEditor?.status is ManualRuleStatus.Failed }
        val error = (failed.manualRuleEditor?.status as ManualRuleStatus.Failed).error
        assertThat(error).isEqualTo(BillsManualRuleError.AMOUNT_PRECISION_UNSUPPORTED)
        // The draft is kept verbatim so the user can correct it, and no rule row is written.
        assertThat(failed.manualRuleEditor?.draft?.amountText).isEqualTo("12.345")
        assertThat(repo.upserts).isEmpty()
    }

    @Test
    fun `invalid draft refusal surfaces a typed error and never launches storage work`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        viewModel.state.first { it.manualRuleEditor != null }
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(
                ManualCommitmentDraft(name = "   ", amountText = "99", nextDateText = "2026-07-01"),
            ),
        )
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle()

        val failed = viewModel.state.first { it.manualRuleEditor?.status is ManualRuleStatus.Failed }
        val error = (failed.manualRuleEditor?.status as ManualRuleStatus.Failed).error
        assertThat(error).isEqualTo(BillsManualRuleError.NAME_REQUIRED)
        assertThat(failed.manualRuleEditor?.draft?.name).isEqualTo("   ")
        assertThat(repo.upserts).isEmpty()

        // Editing after a refusal is a fresh correction attempt, not a retry.
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(ManualCommitmentDraft(name = "Home Rent", currency = "SAR")),
        )
        val corrected = viewModel.state.first { it.manualRuleEditor?.status == ManualRuleStatus.Editing }
        assertThat(corrected.manualRuleEditor?.status).isEqualTo(ManualRuleStatus.Editing)
    }

    @Test
    fun `storage failure keeps the draft for retry and a retry succeeds`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        repo.upsertsToFail = 1
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        viewModel.state.first { it.manualRuleEditor != null }
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(
                ManualCommitmentDraft(name = "Home Rent", amountText = "99", nextDateText = "2026-07-01"),
            ),
        )
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle()

        val failed = viewModel.state.first { it.manualRuleEditor?.status is ManualRuleStatus.Failed }
        val error = (failed.manualRuleEditor?.status as ManualRuleStatus.Failed).error
        assertThat(error).isEqualTo(BillsManualRuleError.SAVE_FAILED)
        assertThat(failed.manualRuleEditor?.draft?.name).isEqualTo("Home Rent")
        // The failed attempt never recorded a write.
        assertThat(repo.upserts).isEmpty()

        // Retry resubmits the same preserved draft.
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        advanceUntilIdle()

        assertThat(viewModel.state.first { it.manualRuleEditor == null }.manualRuleEditor).isNull()
        assertThat(repo.upserts).hasSize(1)
        assertThat(repo.upserts.single().displayName).isEqualTo("Home Rent")
    }

    @Test
    fun `closing the editor during an in-flight save never resurrects it on failure`() = runTest(mainDispatcher) {
        val repo = FakeRecurringRuleRepository(emptyList())
        repo.upsertsToFail = 1
        val viewModel = billsViewModel(rules = emptyList(), transactions = emptyList(), ruleRepository = repo)

        viewModel.onEvent(BillsEvent.OpenManualRuleEditor)
        viewModel.state.first { it.manualRuleEditor != null }
        viewModel.onEvent(
            BillsEvent.UpdateManualRuleDraft(
                ManualCommitmentDraft(name = "Home Rent", amountText = "99", nextDateText = "2026-07-01"),
            ),
        )
        viewModel.onEvent(BillsEvent.SubmitManualRule)
        // The user cancels before the save coroutine even runs (standard test dispatcher).
        viewModel.onEvent(BillsEvent.DismissManualRuleEditor)
        advanceUntilIdle() // the write fails; the closed sheet must stay closed

        val settled = viewModel.state.first { !it.isLoading }
        assertThat(settled.manualRuleEditor).isNull()
        assertThat(repo.upserts).isEmpty()
    }

    private class FakeRecurringRuleRepository(
        initial: List<RecurringRule>,
    ) : RecurringRuleRepository {
        private val state = MutableStateFlow(initial)
        val upserts = mutableListOf<RecurringRule>()
        val activeChanges = mutableListOf<Pair<String, Boolean>>()
        val deletedIds = mutableListOf<String>()

        /** Injected storage failures: each pending upsert throws before recording. */
        var upsertsToFail = 0

        /** When set, each upsert parks here after recording — a save left in flight. */
        var upsertGate: CompletableDeferred<Unit>? = null

        override fun observeAll(includeInactive: Boolean): Flow<List<RecurringRule>> = state

        override suspend fun get(id: String): RecurringRule? = state.value.firstOrNull { it.id == id }

        override suspend fun upsert(rule: RecurringRule) {
            if (upsertsToFail > 0) {
                upsertsToFail -= 1
                throw IllegalStateException("injected storage failure")
            }
            upserts += rule
            upsertGate?.await()
            state.value = state.value.filterNot { it.id == rule.id } + rule
        }

        override suspend fun delete(id: String) {
            deletedIds += id
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun setActive(id: String, active: Boolean) {
            activeChanges += id to active
            state.value = state.value.map { if (it.id == id) it.copy(isActive = active) else it }
        }

        override suspend fun materializeDue(today: LocalDate): Int = 0
    }

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

    private class FakeSuggestionRepository(
        private val suggestions: List<RecurringSuggestion>,
    ) : RecurringSuggestionRepository {
        override fun observeSuggestions(): Flow<List<RecurringSuggestion>> = flowOf(suggestions)
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        override fun onboardingComplete(): Flow<Boolean> = flowOf(true)
        override suspend fun setOnboardingComplete(complete: Boolean) = Unit
        override fun lastSmsBackfillEpochSeconds(): Flow<Long> = flowOf(0L)
        override suspend fun setLastSmsBackfillEpochSeconds(epoch: Long) = Unit
        override fun hijriEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setHijriEnabled(enabled: Boolean) = Unit
        override fun ownAccountNumbers(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOwnAccountNumbers(numbers: List<String>) = Unit
        override fun displayCurrency(): Flow<String> = flowOf("SAR")
        override suspend fun setDisplayCurrency(currency: String) = Unit
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
    amountMinor: Long = 9900,
    merchant: String = "Gym Club",
    status: TxStatus = TxStatus.CONFIRMED,
    categoryId: String? = "cat-gym",
    accountId: String = "acc-checking",
    merchantNormalized: String = merchant.lowercase().trim(),
): Transaction =
    Transaction(
        id = id,
        accountId = accountId,
        type = TxType.EXPENSE,
        amount = Money.ofMinor(amountMinor, "SAR"),
        date = date,
        occurredAt = null,
        merchant = merchant,
        merchantNormalized = merchantNormalized,
        categoryId = categoryId,
        notes = null,
        source = IngestSource.MANUAL,
        sourceRefId = null,
        status = status,
        confidence = 1f,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun rule(
    id: String = "rule-gym",
    merchant: String = "Gym Club",
    amount: Money = Money.ofMinor(9900, "SAR"),
    cadence: Cadence = Cadence.MONTHLY,
    dayOfMonth: Int? = 10,
    dayOfWeek: Int? = null,
    nextRunDate: LocalDate,
    accountId: String = "acc-checking",
    createdAt: Instant = Instant.parse("2026-06-01T12:00:00Z"),
): RecurringRule {
    val now = Instant.parse("2026-06-01T12:00:00Z")
    return RecurringRule(
        id = id,
        displayName = merchant,
        merchant = merchant,
        amount = amount,
        type = TxType.EXPENSE,
        accountId = accountId,
        categoryId = "cat-gym",
        cadence = cadence,
        dayOfMonth = dayOfMonth,
        dayOfWeek = dayOfWeek,
        monthOfYear = null,
        nextRunDate = nextRunDate,
        lastRunDate = null,
        isActive = true,
        notes = null,
        createdAt = createdAt,
        updatedAt = now,
    )
}

private fun suggestion(): RecurringSuggestion =
    RecurringSuggestion(
        merchant = "Isp Bundles",
        merchantNormalized = "isp bundles",
        amount = Money.ofMinor(29900, "SAR"),
        type = TxType.EXPENSE,
        suggestedAccountId = null,
        suggestedCategoryId = null,
        occurrenceCount = 3,
        typicalDayOfMonth = 5,
        cadence = Cadence.QUARTERLY,
        dayOfWeek = null,
        monthOfYear = null,
        lastSeen = LocalDate(2026, 4, 5),
        suggestedNextRun = LocalDate(2026, 7, 5),
    )
