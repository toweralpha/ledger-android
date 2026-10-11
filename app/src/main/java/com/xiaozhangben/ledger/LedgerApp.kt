package com.xiaozhangben.ledger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.xiaozhangben.ledger.capture.CaptureSelfHealer
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LedgerApp : Application() {

    /** Created eagerly so the notification listener (a system-bound service) can rely on it. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Logging is wired only in debug builds, and the sink never receives amounts,
        // payee names or raw notification text — see LogX.
        LogX.enabled = BuildConfig.DEBUG
        LogX.sink = { level, tag, message ->
            val full = "Ledger/$tag"
            when (level) {
                'W' -> Log.w(full, message)
                'E' -> Log.e(full, message)
                else -> Log.d(full, message)
            }
        }

        createNotificationChannels(this)

        container = AppContainer(this)
        container.bootstrap()

        // Armed from the Application, not only from the boot receiver: on a fresh install
        // no BOOT_COMPLETED has happened yet, and MY_PACKAGE_REPLACED is not guaranteed
        // either — so the schedule could previously stay empty until the next reboot.
        WorkScheduler.ensureScheduled(this)

        // Every process start is a free chance to notice that the system has stopped
        // running the listener, and it is the most common case in practice (the app is
        // opened far more often than any timer fires). Silent by design: a repair that
        // worked does not need to be announced, and the user should not have to check.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val enabled = container.settings.captureEnabled.first()
                val outcome = CaptureSelfHealer.ensureConnected(this@LedgerApp, enabled)
                if (outcome == CaptureSelfHealer.Outcome.REBOUND) {
                    container.ledger.recordCaptureRepair()
                }
            }
        }
    }

    companion object {
        const val CHANNEL_CAPTURE = "capture"
        const val CHANNEL_ALERT = "alert"
        const val CHANNEL_SERVICE = "service"

        fun createNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_CAPTURE,
                    context.getString(R.string.channel_capture_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_capture_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_ALERT,
                    context.getString(R.string.channel_alert_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_alert_desc)
                },
                NotificationChannel(
                    CHANNEL_SERVICE,
                    context.getString(R.string.channel_service_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = context.getString(R.string.channel_service_desc)
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannels(channels)
        }
    }
}
