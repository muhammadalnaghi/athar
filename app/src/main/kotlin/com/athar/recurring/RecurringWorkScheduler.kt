package com.athar.recurring

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RecurringWorkScheduler {
    private const val STARTUP_WORK_NAME = "recurring-rule-materializer-startup"
    private const val PERIODIC_WORK_NAME = "recurring-rule-materializer-daily"
    private const val TAG = "recurring-rule-materializer"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)

        val startup = OneTimeWorkRequestBuilder<RecurringMaterializeWorker>()
            // Keep the launch window free of DB work: the eager startup pass used to
            // contend with first composition on the SQLCipher connection pool
            // (cold-start first frame measured ~3.8s late on API35). Materializing
            // due recurring instances 20s after process start is still prompt and
            // runs identically; the 24h periodic pass is unchanged.
            .setInitialDelay(20, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(STARTUP_WORK_NAME, ExistingWorkPolicy.KEEP, startup)

        val daily = PeriodicWorkRequestBuilder<RecurringMaterializeWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, daily)
    }
}
