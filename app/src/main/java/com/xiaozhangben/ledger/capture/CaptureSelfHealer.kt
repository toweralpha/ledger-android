package com.xiaozhangben.ledger.capture

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Reconnects the notification listener without asking the user anything.
 *
 * The failure this exists for: the system stops running a `NotificationListenerService`
 * while notification access remains granted, so no notification is ever delivered and
 * nothing is recorded — with no error anywhere. Checking a diagnostics screen on the off
 * chance is not a reasonable thing to ask of a user, so the app repairs itself and the
 * repair is silent.
 *
 * This is the single implementation used by every entry point that might run while the
 * listener is down:
 *
 *  - `LedgerApp.onCreate` — any process start, for any reason
 *  - `BootReceiver` — after a reboot or an app update, both of which drop the binding
 *  - `CaptureWatchdogWorker` — every 15 minutes, the only path that works when the user
 *    never opens the app
 *  - the "修复" button, for the case where the user got there first
 *
 * Known limit, stated plainly: if the app's process is not running and the system refuses
 * to start it, nothing here executes. That is why the home screen still explains the
 * situation rather than pretending the problem cannot happen.
 */
object CaptureSelfHealer {

    enum class Outcome {
        /** The user switched automatic capture off. Nothing to repair. */
        DISABLED,

        /** Notification access is not granted. Only the user can fix this. */
        NOT_AUTHORISED,

        /** Already bound — the common case, and it costs nothing. */
        ALREADY_CONNECTED,

        /** Was down, and the system rebound it. */
        REBOUND,

        /** Was down and the system refused. The UI has to say so. */
        STILL_DOWN,
    }

    /**
     * How long to wait before concluding the listener is not going to connect.
     *
     * A process that has just started legitimately reports "not connected" for a moment
     * while the system binds the service, and declaring failure during that window would
     * cause pointless rebinds on every launch.
     */
    const val SETTLE_MILLIS = 1_200L

    /** Extra time for the setting toggle to be picked up and the bind to be attempted. */
    const val TOGGLE_SETTLE_MILLIS = 2_000L

    suspend fun ensureConnected(context: Context, captureEnabled: Boolean): Outcome {
        if (!captureEnabled) return Outcome.DISABLED
        if (!CapturePermissions.isListenerEnabled(context)) return Outcome.NOT_AUTHORISED
        if (CapturePermissions.isListenerConnected()) return Outcome.ALREADY_CONNECTED

        // Give a cold-started process a moment before assuming the worst.
        delay(SETTLE_MILLIS)
        if (CapturePermissions.isListenerConnected()) return Outcome.ALREADY_CONNECTED

        // Least invasive first: cheap, and it is the documented API. It just happens not
        // to cover the post-reboot case — see CapturePermissions.tryRebindListener.
        CapturePermissions.tryRebindListener(context)
        delay(SETTLE_MILLIS)
        if (CapturePermissions.isListenerConnected()) return Outcome.REBOUND

        // The one that measurably works: remove and re-add our entry in
        // `enabled_notification_listeners`, which is what a user does by toggling
        // notification access by hand. It needs an optional `adb`-granted permission; when
        // that is absent this is a no-op and the UI has to say so.
        if (CapturePermissions.rebindByTogglingAuthorization(context)) {
            delay(TOGGLE_SETTLE_MILLIS)
            if (CapturePermissions.isListenerConnected()) return Outcome.REBOUND
        }

        return Outcome.STILL_DOWN
    }
}
