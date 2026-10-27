package com.athar.recurring

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.athar.core.domain.calc.BillReminderPlanner
import com.athar.core.domain.repo.RecurringRuleRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import timber.log.Timber

@HiltWorker
class RecurringMaterializeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recurringRules: RecurringRuleRepository,
    private val prefs: UserPreferencesRepository,
    private val billReminderNotifier: BillReminderNotifier,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching {
            val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val remindersSent = sendBillReminders(today)
            // A scheduled amount is an expectation, not evidence of a payment.
            // Keep the legacy worker identity for queued jobs, but never create
            // approval-tray transactions merely because a due date arrived.
            Timber.i(
                "[recurring] sent %d schedule reminder(s) for %s",
                remindersSent,
                today,
            )
            Result.success()
        }.getOrElse { error ->
            Timber.e(error, "[recurring] schedule reminders failed")
            Result.retry()
        }

    private suspend fun sendBillReminders(today: kotlinx.datetime.LocalDate): Int {
        if (!prefs.billRemindersEnabled().first()) return 0

        val planned = BillReminderPlanner.plan(
            rules = recurringRules.observeAll().first(),
            pending = emptyList(), // Legacy generated rows are not proof of a missed payment.
            today = today,
        )
        val plannedKeys = planned.mapTo(mutableSetOf()) { it.key }
        val sentKeys = prefs.billReminderSentKeys().first()
        val delivered = planned
            .asSequence()
            .filterNot { it.key in sentKeys }
            .filter { billReminderNotifier.notify(it) }
            .mapTo(mutableSetOf()) { it.key }

        val retained = sentKeys.intersect(plannedKeys) + delivered
        if (retained != sentKeys) {
            prefs.setBillReminderSentKeys(retained)
        }
        return delivered.size
    }
}
