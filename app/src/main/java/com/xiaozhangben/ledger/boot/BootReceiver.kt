package com.xiaozhangben.ledger.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xiaozhangben.ledger.capture.CaptureSelfHealer
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.di.appContainer
import com.xiaozhangben.ledger.widget.WidgetUpdater
import com.xiaozhangben.ledger.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms background work after a reboot or an app update, and reconnects the listener.
 *
 * A reboot drops every bound service, and an app update revokes notification access on
 * some ROMs — both are moments when capture stops without anything telling the user.
 * WorkManager persists its own schedule, so this is belt-and-braces for the schedule,
 * but the reconnect is not: it is the only chance to fix things before the user next
 * looks at the app.
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

                // Best effort and silent. With no container we cannot read the user's
                // capture setting, so assume it is on: reconnecting a listener the user
                // turned off is harmless, since the toggle is honoured at capture time.
                val enabled = runCatching {
                    container?.settings?.captureEnabled?.first()
                }.getOrNull() ?: true
                val outcome = CaptureSelfHealer.ensureConnected(appContext, enabled)
                if (outcome == CaptureSelfHealer.Outcome.REBOUND) {
                    container?.ledger?.recordCaptureRepair()
                }

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
