package com.xiaozhangben.ledger.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xiaozhangben.ledger.core.LogX
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Background scheduling.
 *
 * Two periodic jobs, both cheap:
 *
 *  - the daily-ish budget/widget check, which needs no precision
 *  - a 15-minute capture watchdog, which exists because a
 *    `NotificationListenerService` can be stopped by the system while its permission stays
 *    granted, and nothing else in the app runs to notice
 *
 * Notification capture itself is still event-driven and needs no schedule — this only
 * guards the binding, and returns immediately when it is healthy, which is almost always.
 */
object WorkScheduler {

    private const val UNIQUE_PERIODIC = "ledger-periodic-check"
    private const val PERIOD_HOURS = 6L

    private const val UNIQUE_WATCHDOG = "ledger-capture-watchdog"

    /** WorkManager's floor for periodic work, and the exposure window we accept. */
    private const val WATCHDOG_MINUTES = 15L

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<LedgerPeriodicWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().build())
            .addTag("ledger")
            .build()

        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP, not REPLACE: replacing on every launch would reset the timer
                // and, on a phone that is opened often, mean the job never runs.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { LogX.e("WorkScheduler", it) { "failed to schedule periodic work" } }

        ensureWatchdogScheduled(context)
    }

    /**
     * Schedules the capture watchdog.
     *
     * `UPDATE` rather than `KEEP` so that changing the interval or the worker class in a
     * future release actually takes effect on phones that already have it scheduled —
     * `KEEP` would leave them running the old definition for ever.
     */
    fun ensureWatchdogScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<CaptureWatchdogWorker>(
            WATCHDOG_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.Builder().build())
            .addTag("ledger")
            .build()

        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WATCHDOG,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }.onFailure { LogX.e("WorkScheduler", it) { "failed to schedule capture watchdog" } }
    }

    /** Aims the first run shortly after midnight, when the month/day rolls over. */
    private fun initialDelayMillis(): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 10)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(TimeUnit.MINUTES.toMillis(15))
    }
}
