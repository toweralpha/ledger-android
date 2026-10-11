package com.xiaozhangben.ledger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xiaozhangben.ledger.capture.CaptureSelfHealer
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.di.appContainer
import kotlinx.coroutines.flow.first

/**
 * Keeps real-time capture alive without the user ever thinking about it.
 *
 * Why this is the important one: the failure mode is "the system stopped running the
 * listener while the permission stayed granted". Nothing in the app runs when the user
 * is not looking, so without a scheduled check the only discovery path is the user
 * noticing that a fortnight of spending is missing — which is exactly what happened.
 *
 * Fifteen minutes is WorkManager's minimum period, and it bounds the exposure: a
 * listener that drops is restored within a quarter of an hour rather than never. The
 * work is trivial when nothing is wrong — one boolean read and a return — so it costs
 * no measurable battery, and it deliberately posts **no notification**: the user asked
 * not to be prompted, and a repair that worked needs no announcement.
 *
 * Under Doze this is deferred, but a payment happens with the screen on, which is
 * exactly when Doze is not in effect.
 */
class CaptureWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val context = applicationContext
        val container = context.appContainer
        container.bootstrap()

        val captureEnabled = runCatching { container.settings.captureEnabled.first() }.getOrDefault(true)
        when (val outcome = CaptureSelfHealer.ensureConnected(context, captureEnabled)) {
            CaptureSelfHealer.Outcome.REBOUND -> {
                container.ledger.recordCaptureRepair()
                LogX.count("Watchdog", "listenerReconnected", 1)
            }

            CaptureSelfHealer.Outcome.STILL_DOWN ->
                LogX.d("Watchdog") { "listener still down after rebind attempt" }

            else -> Unit
        }
        Result.success()
    } catch (t: Throwable) {
        LogX.e("Watchdog", t) { "watchdog failed" }
        // Never retry-spam: the next period is 15 minutes away and the check is cheap.
        Result.success()
    }
}
