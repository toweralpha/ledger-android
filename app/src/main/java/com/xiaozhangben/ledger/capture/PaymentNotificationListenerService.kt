package com.xiaozhangben.ledger.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xiaozhangben.ledger.LedgerApp
import com.xiaozhangben.ledger.R
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.data.repository.InsertOutcome
import com.xiaozhangben.ledger.data.repository.NewTransaction
import com.xiaozhangben.ledger.di.appContainer
import com.xiaozhangben.ledger.domain.model.TxSource
import com.xiaozhangben.ledger.ui.MainActivity
import com.xiaozhangben.ledger.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The real-time channel: a system-bound service that turns a payment notification
 * into a ledger row within a second, with no user action.
 *
 * Why this survives when an ordinary background service would not: a
 * `NotificationListenerService` is bound by the system, is restarted automatically
 * after being reclaimed, and needs no foreground notification. That is exactly the
 * "keep working in the background" requirement, achieved without the wakelock /
 * mutual-restart tricks that Android 14 has closed off.
 *
 * Design notes:
 *  - work happens on an IO coroutine, never on the binder thread
 *  - de-duplication is layered: `sbn.key` for re-posts, then the database's unique
 *    fingerprint, then a cross-channel fuzzy match against the monthly bill import
 *  - nothing about the notification is ever logged (see LogX)
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** `sbn.key` -> when it was handled. Bounded; notifications do re-post. */
    private val handledKeys = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 256
    }

    /** Single-element holder for the last "skip reason" write, to throttle those writes. */
    private val lastSkipRecord = longArrayOf(0L)

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogX.d("Capture") { "listener connected" }
        scope.launch {
            runCatching { appContainer.ledger.onListenerConnected() }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        scope.launch {
            runCatching { appContainer.ledger.onListenerDisconnected() }
        }
        // The sanctioned way for a listener to come back: ask the system to rebind.
        // This is not a keep-alive hack — it is the API's documented recovery path
        // and the system is free to refuse.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { requestRebind(ComponentName(this, PaymentNotificationListenerService::class.java)) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val source = sourceOf(notification.packageName) ?: return
        // Every notification from the two payment apps is logged (debug builds only) so
        // that "capture produced nothing" can always be traced to a concrete cause.
        LogX.d("Capture") { "received a notification from ${source.name}" }

        val key = notification.key
        val now = System.currentTimeMillis()
        synchronized(handledKeys) {
            val previous = handledKeys[key]
            if (previous != null && now - previous < REPOST_WINDOW_MILLIS) return
            handledKeys[key] = now
        }

        val extras = notification.notification?.extras
        val outcome = NotificationTextParser.parseOutcome(
            source = source,
            title = extras?.let { extractTitle(it) },
            texts = extras?.let { extractTexts(it) } ?: emptyList(),
        )

        if (outcome is ParseOutcome.Rejected) {
            // Debug-only, and content-free: this single line is what separates "the
            // payment app never posted a notification" from "we saw one and refused it".
            LogX.d("Capture") { "notification from ${source.name} rejected: ${outcome.reason.label}" }
            recordSkipIfWorthIt(outcome.reason, extras, now)
            return
        }
        val payment = (outcome as ParseOutcome.Captured).payment

        // Use the notification's own timestamp: it is when the payment happened, and
        // it is immune to our process being woken up late.
        val occurredAt = notification.postTime.takeIf { it > 0L } ?: now

        scope.launch {
            try {
                val enabled = appContainer.settings.captureEnabled.first()
                if (!enabled) return@launch

                val result = appContainer.ledger.insert(
                    NewTransaction(
                        source = payment.source,
                        direction = payment.direction,
                        occurredAt = occurredAt,
                        amountCents = payment.amountCents,
                        // Carrying the kind through means a transfer is recorded *and*
                        // excluded from spending, instead of distorting the month.
                        rawType = payment.kindHint,
                        status = null,
                        counterpartyRaw = payment.counterparty,
                        productDesc = null,
                        notificationKey = key,
                    ),
                    now = now,
                )

                when (result) {
                    is InsertOutcome.Inserted -> {
                        runCatching { appContainer.ledger.recordCapture(occurredAt) }
                        maybeNotify(result.id, payment, result.classification.categoryKey)
                        WidgetUpdater.refresh(this@PaymentNotificationListenerService)
                        LogX.count("Capture", "captured", 1)
                    }
                    is InsertOutcome.Duplicate -> LogX.d("Capture") { "duplicate ignored" }
                    is InsertOutcome.Rejected -> LogX.d("Capture") { "rejected" }
                }
            } catch (t: Throwable) {
                LogX.e("Capture", t) { "capture failed" }
            }
        }
    }

    /**
     * Remembers the reason a notification was refused — but only when the notification
     * actually carried an amount.
     *
     * We subscribe to all of WeChat, so writing on every rejected chat message would
     * mean a database write per message. A notification containing 元 or ¥ is the only
     * kind where "we refused it" is diagnostically interesting, and it is rare.
     */
    private fun recordSkipIfWorthIt(reason: ParseOutcome.Rejection, extras: android.os.Bundle?, now: Long) {
        val looksLikeMoney = extras?.let { extractTexts(it) }?.any { text ->
            text.contains('元') || text.contains('¥') || text.contains('￥')
        } ?: false
        if (!looksLikeMoney) return

        synchronized(lastSkipRecord) {
            if (now - lastSkipRecord[0] < SKIP_RECORD_THROTTLE_MILLIS) return
            lastSkipRecord[0] = now
        }
        scope.launch {
            runCatching { appContainer.ledger.recordSkipped(reason.label, now) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun maybeNotify(
        txnId: String,
        payment: CapturedPayment,
        categoryKey: String,
    ) {
        val show = runCatching { appContainer.settings.showCaptureCard.first() }.getOrDefault(true)
        if (!show) return

        val label = buildString {
            payment.counterparty?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
            append(Money.format(payment.amountCents))
            append(" · ")
            append(categoryLabel(categoryKey))
        }

        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TXN_ID, txnId)
        }
        val pending = PendingIntent.getActivity(
            this,
            txnId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, LedgerApp.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(label))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .addAction(0, getString(R.string.capture_action_reclassify), pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { manager.notify(txnId.hashCode(), notification) }
    }

    private fun categoryLabel(key: String): String = when (key) {
        "food" -> "餐饮"
        "transport" -> "交通"
        "shopping" -> "购物"
        "housing" -> "居住"
        "telecom" -> "通讯"
        "entertainment" -> "娱乐"
        "health" -> "医疗健康"
        "social" -> "人情往来"
        "education" -> "学习成长"
        "services" -> "生活服务"
        "travel" -> "旅行住宿"
        "digital" -> "数码订阅"
        "unclassified" -> "待归类"
        else -> "其他"
    }

    private fun sourceOf(packageName: String?): TxSource? = when (packageName) {
        PACKAGE_ALIPAY -> TxSource.ALIPAY
        PACKAGE_WECHAT -> TxSource.WECHAT
        else -> null
    }

    private fun extractTitle(extras: android.os.Bundle): String? =
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /**
     * Every field the amount or payee might hide in. Reading only `EXTRA_TEXT` is the
     * classic reason a capture silently stops working after an OS update.
     */
    private fun extractTexts(extras: android.os.Bundle): List<String> {
        val out = ArrayList<String>(6)
        fun add(key: String) {
            extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        add(Notification.EXTRA_TEXT)
        add(Notification.EXTRA_BIG_TEXT)
        add(Notification.EXTRA_SUB_TEXT)
        add(Notification.EXTRA_SUMMARY_TEXT)
        add(Notification.EXTRA_INFO_TEXT)
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.forEach { line -> line?.toString()?.takeIf { it.isNotBlank() }?.let { out += it } }
        return out
    }

    companion object {
        const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
        const val PACKAGE_WECHAT = "com.tencent.mm"

        private const val REPOST_WINDOW_MILLIS = 60_000L
        private const val SKIP_RECORD_THROTTLE_MILLIS = 5_000L
    }
}
