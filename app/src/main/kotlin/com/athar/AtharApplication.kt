package com.athar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.athar.core.data.AppDataInitializer
import com.athar.recurring.RecurringWorkScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class AtharApplication : Application(), Configuration.Provider {

    // Do not construct the seed -> DAO -> encrypted-database graph at field injection.
    @Inject lateinit var dataInitializer: Lazy<AppDataInitializer>
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val startupErrorHandler = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "Athar startup initialization failed")
        writeCrashLog("Athar startup initialization threw", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + startupErrorHandler)

    override fun onCreate() {
        super.onCreate()
        // Plant logging in all builds. Logcat is the canonical surface (`adb logcat | grep athar`).
        // Beta + production builds also persist uncaught exceptions to `files/crash.log` so the
        // user can share it without a connected dev machine.
        Timber.plant(Timber.DebugTree())
        installCrashHandler()
        Timber.i("Athar starting · versionName=%s", BuildConfig.VERSION_NAME)

        scope.launch {
            runDeferredStartupJobs(dataInitializer, BuildConfig.SEED_ON_FIRST_LAUNCH) {
                RecurringWorkScheduler.schedule(this@AtharApplication)
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog("Uncaught on thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(prefix: String, t: Throwable) {
        runCatching {
            val log = File(filesDir, "crash.log")
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            log.appendText(
                "===== $timestamp =====\n" +
                    "$prefix\n" +
                    "versionName=${BuildConfig.VERSION_NAME}\n" +
                    sw.toString() +
                    "\n",
            )
        }
    }
}

/** Defer this dependency request and initialization to IO; preserve failure/cancellation. */
internal suspend fun runDeferredStartupJobs(
    dataInitializer: Lazy<AppDataInitializer>,
    seedTmoap: Boolean,
    scheduleRecurringWork: () -> Unit,
) = withContext(Dispatchers.IO) {
    dataInitializer.get().initialize(seedTmoap = seedTmoap)
    scheduleRecurringWork()
}
