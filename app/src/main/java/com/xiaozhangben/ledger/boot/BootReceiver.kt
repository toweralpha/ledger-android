package com.xiaozhangben.ledger.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.di.appContainer
import com.xiaozhangben.ledger.widget.WidgetUpdater
import com.xiaozhangben.ledger.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms the periodic work after a reboot or an app update.
 *
 * WorkManager persists its own schedule, so this is belt-and-braces; it also
 * refreshes the home-screen widget, which is not persisted across a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = runCatching { appContext.appContainer }.getOrNull()
                container?.bootstrap()
                WorkScheduler.ensureScheduled(appContext)
                WidgetUpdater.refresh(appContext)
                LogX.d("Boot") { "post-boot setup complete for $action" }
            } catch (t: Throwable) {
                LogX.e("Boot", t) { "post-boot setup failed" }
            } finally {
                pending.finish()
            }
        }
    }
}
