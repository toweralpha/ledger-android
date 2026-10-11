package com.xiaozhangben.ledger.capture

/**
 * Whether automatic capture is actually working — as opposed to merely being permitted.
 *
 * This distinction is the whole point. The app used to ask only
 * "is notification access granted?", which stays `true` while the system has quietly
 * unbound the listener service. The result was two days of purchases that were never
 * recorded, with nothing on screen suggesting anything was wrong.
 */
enum class CaptureHealth {
    /** The user turned automatic capture off. Working as configured. */
    OFF,

    /** Notification access was never granted (or was revoked). */
    NOT_AUTHORISED,

    /**
     * Access is granted but the system is not running the listener service.
     *
     * Definitive, with no threshold to tune: the service shares the app's process, so
     * when the UI is up and the service has never reported a connection, nothing is
     * being delivered.
     */
    NOT_CONNECTED,

    /** Bound, but no notification from a payment app has arrived for a long time. */
    NOT_RECEIVING,

    OK,
}

object CaptureHealthEvaluator {

    /**
     * How long the listener may go without seeing *any* notification from WeChat or
     * Alipay before it is worth a mention. Deliberately generous: a quiet weekend is not
     * a fault, and a false alarm trains the user to ignore the warning.
     */
    const val NOT_RECEIVING_AFTER_MILLIS = 48L * 60 * 60 * 1000

    fun evaluate(
        captureEnabled: Boolean,
        authorised: Boolean,
        connected: Boolean,
        lastNotificationSeenAt: Long?,
        lastListenerConnectedAt: Long?,
        now: Long,
    ): CaptureHealth {
        if (!captureEnabled) return CaptureHealth.OFF
        if (!authorised) return CaptureHealth.NOT_AUTHORISED
        // Checked before any time-based heuristic, because it is a fact rather than a
        // guess — and it is the failure that actually happened in the field.
        if (!connected) return CaptureHealth.NOT_CONNECTED

        // Fall back to when we connected, so "bound for three days and never a single
        // notification" is still caught on a device that has not recorded a heartbeat yet.
        val baseline = lastNotificationSeenAt ?: lastListenerConnectedAt
        if (baseline != null && now - baseline > NOT_RECEIVING_AFTER_MILLIS) {
            return CaptureHealth.NOT_RECEIVING
        }
        return CaptureHealth.OK
    }
}
