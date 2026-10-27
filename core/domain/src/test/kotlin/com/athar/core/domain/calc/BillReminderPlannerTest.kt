package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class BillReminderPlannerTest {

    @Test
    fun `plans upcoming and due reminders for active expense rules`() {
        val today = LocalDate(2026, 6, 13)
        val reminders = BillReminderPlanner.plan(
            rules = listOf(
                rule(id = "rent", displayName = "Rent", nextRunDate = today),
                rule(id = "gym", displayName = "Gym", nextRunDate = LocalDate(2026, 6, 15)),
                rule(id = "later", displayName = "Later", nextRunDate = LocalDate(2026, 6, 16)),
            ),
            pending = emptyList(),
            today = today,
        )

        assertThat(reminders.map { it.kind }).containsExactly(
            BillReminderKind.DUE_TODAY,
            BillReminderKind.UPCOMING,
        ).inOrder()
        assertThat(reminders.map { it.label }).containsExactly("Rent", "Gym").inOrder()
        assertThat(reminders.map { it.key }).containsExactly(
            "recurring:due_today:rent:2026-06-13",
            "recurring:upcoming:gym:2026-06-15",
        ).inOrder()
    }

    @Test
    fun `ignores inactive and income rules`() {
        val today = LocalDate(2026, 6, 13)
        val reminders = BillReminderPlanner.plan(
            rules = listOf(
                rule(id = "salary", type = TxType.INCOME, nextRunDate = today),
                rule(id = "inactive", isActive = false, nextRunDate = today),
            ),
            pending = emptyList(),
            today = today,
        )

        assertThat(reminders).isEmpty()
    }

    @Test
    fun `plans one missed reminder for overdue pending recurring expenses`() {
        val today = LocalDate(2026, 6, 13)
        val reminders = BillReminderPlanner.plan(
            rules = emptyList(),
            pending = listOf(
                transaction(
                    id = "tx-rent",
                    amount = Money.of("2500"),
                    merchant = "Rent",
                    date = LocalDate(2026, 6, 10),
                    source = IngestSource.RECURRING,
                    sourceRefId = "recurring:rent:2026-06-10",
                ),
                transaction(
                    id = "tx-today",
                    amount = Money.of("150"),
                    merchant = "Today",
                    date = today,
                    source = IngestSource.RECURRING,
                    sourceRefId = "recurring:today:2026-06-13",
                ),
                transaction(
                    id = "tx-sms",
                    amount = Money.of("99"),
                    merchant = "SMS",
                    date = LocalDate(2026, 6, 10),
                    source = IngestSource.SMS,
                ),
            ),
            today = today,
        )

        assertThat(reminders).hasSize(1)
        assertThat(reminders.single().kind).isEqualTo(BillReminderKind.MISSED_REVIEW)
        assertThat(reminders.single().key).isEqualTo("pending:missed:recurring:rent:2026-06-10:2026-06-10")
    }

    @Test
    fun `stale history cannot suppress today and upcoming reminders`() {
        val today = LocalDate(2026, 6, 13)
        val reminders = BillReminderPlanner.plan(
            rules = listOf(
                rule(id = "stale-due", displayName = "Stale Due", nextRunDate = LocalDate(2020, 1, 13)),
                rule(id = "stale-upcoming", displayName = "Stale Upcoming", nextRunDate = LocalDate(2020, 1, 15)),
            ),
            pending = emptyList(),
            today = today,
        )

        // A decades-stale anchor must not spend the occurrence budget on overdue
        // history: due-today and horizon reminders still fire, and no synthetic
        // older obligations are invented from the re-anchored schedule.
        assertThat(reminders.map { it.kind }).containsExactly(
            BillReminderKind.DUE_TODAY,
            BillReminderKind.UPCOMING,
        ).inOrder()
        assertThat(reminders.map { it.key }).containsExactly(
            "recurring:due_today:stale-due:2026-06-13",
            "recurring:upcoming:stale-upcoming:2026-06-15",
        ).inOrder()
    }

    private fun rule(
        id: String,
        displayName: String = id,
        type: TxType = TxType.EXPENSE,
        nextRunDate: LocalDate = LocalDate(2026, 6, 13),
        isActive: Boolean = true,
    ): RecurringRule {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        return RecurringRule(
            id = id,
            displayName = displayName,
            merchant = displayName,
            amount = Money.of("100"),
            type = type,
            accountId = MANUAL_ACCOUNT_ID,
            categoryId = "cat-bills",
            cadence = Cadence.MONTHLY,
            dayOfMonth = nextRunDate.dayOfMonth,
            dayOfWeek = null,
            monthOfYear = null,
            nextRunDate = nextRunDate,
            lastRunDate = null,
            isActive = isActive,
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun transaction(
        id: String,
        amount: Money,
        merchant: String,
        date: LocalDate,
        source: IngestSource,
        sourceRefId: String? = null,
    ): Transaction {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        return Transaction(
            id = id,
            accountId = MANUAL_ACCOUNT_ID,
            type = TxType.EXPENSE,
            amount = amount,
            date = date,
            occurredAt = now,
            merchant = merchant,
            merchantNormalized = merchant.lowercase(),
            categoryId = null,
            notes = null,
            source = source,
            sourceRefId = sourceRefId,
            status = TxStatus.PENDING,
            confidence = 1.0f,
            createdAt = now,
            updatedAt = now,
        )
    }
}
