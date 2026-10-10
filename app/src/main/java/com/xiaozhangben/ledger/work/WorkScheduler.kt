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
 * There is exactly one periodic job and it is cheap: it refreshes the widget and
 * evaluates budget alerts. Notification capture is event-driven and needs no
 * schedule at all, which is what keeps idle CPU at zero.
 *
 * Six hours (four runs a day) is deliberate: it is far inside WorkManager's
 * doze-friendly allowance, and budget alerts do not need minute precision.
 */
object WorkScheduler {

    private const val UNIQUE_PERIODIC = "ledger-periodic-check"
    private const val PERIOD_HOURS = 6L

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
