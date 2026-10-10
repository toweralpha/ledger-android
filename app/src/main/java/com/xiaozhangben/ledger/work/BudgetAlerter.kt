package com.xiaozhangben.ledger.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xiaozhangben.ledger.LedgerApp
import com.xiaozhangben.ledger.R
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.ui.MainActivity
import kotlinx.coroutines.flow.first

/**
 * Budget warnings at the 70% / 90% / over-budget thresholds.
 *
 * Alerts fire once per crossing, not once per check: a notification that reappears
 * every background run is how users learn to swipe warnings away without reading
 * them. The highest band already reported for the month is persisted, so the
 * behaviour is stable across reboots and worker runs.
 */
object BudgetAlerter {

    suspend fun evaluate(context: Context, container: AppContainer) {
        val enabled = runCatching { container.settings.budgetAlerts.first() }.getOrDefault(true)
        if (!enabled) return

        val snapshot = runCatching { container.snapshot.load() }.getOrNull() ?: return
        if (!snapshot.hasBudget) return

        val level = when (snapshot.alert) {
            AlertLevel.OK -> 0
            AlertLevel.NEAR -> 1
            AlertLevel.ALERT -> 2
            AlertLevel.OVER -> 3
        }
        if (level == 0) return

        val monthKey = snapshot.yearMonth.toString()
        val alreadyReported = runCatching { container.settings.alertLevel(monthKey).first() }.getOrDefault(0)
        if (level <= alreadyReported) return

        runCatching { container.settings.setAlertLevel(monthKey, level) }

        val title = when (level) {
            1 -> "本月已用 70%"
            2 -> "本月已用 90%，注意了"
            else -> "本月已超支"
        }
        val body = if (snapshot.remainingCents >= 0L) {
            "已用 ${Money.formatCompact(snapshot.spentCents)} · 还剩 ${Money.formatCompact(snapshot.remainingCents)}"
        } else {
            "已超出 ${Money.formatCompact(-snapshot.remainingCents)}，共花 ${Money.formatCompact(snapshot.spentCents)}"
        }

        notify(context, title, body, level)
    }

    private fun notify(context: Context, title: String, body: String, level: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            ALERT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, LedgerApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(
                if (level >= 2) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .build()

        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
            .onFailure { LogX.e("Alerter", it) { "alert notification failed" } }
    }

    private const val ALERT_NOTIFICATION_ID = 0x4C47
    private const val ALERT_REQUEST_CODE = 0x4C48
}
