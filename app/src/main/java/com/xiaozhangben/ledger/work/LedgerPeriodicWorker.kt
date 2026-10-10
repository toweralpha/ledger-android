package com.xiaozhangben.ledger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.di.appContainer
import com.xiaozhangben.ledger.widget.WidgetUpdater

/**
 * The only recurring background work in the app.
 *
 * It refreshes the widget (so a new day/month shows the right allowance even if the
 * app was never opened) and evaluates budget alerts. It holds no wakelock and does
 * no polling — it is simply woken, does a bounded amount of work, and returns.
 */
class LedgerPeriodicWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val context = applicationContext
        val container = context.appContainer
        container.bootstrap()

        WidgetUpdater.refresh(context)
        BudgetAlerter.evaluate(context, container)

        LogX.d("Worker") { "periodic check complete" }
        Result.success()
    } catch (t: Throwable) {
        LogX.e("Worker", t) { "periodic check failed" }
        Result.retry()
    }
}
