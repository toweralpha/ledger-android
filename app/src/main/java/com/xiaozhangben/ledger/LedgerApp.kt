package com.xiaozhangben.ledger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.di.AppContainer

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
