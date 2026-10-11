package com.xiaozhangben.ledger.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.xiaozhangben.ledger.core.LogX

/**
 * Notification-access and OEM background-policy helpers.
 *
 * The single most important check here is [isListenerEnabled]: a listener that is not
 * authorised simply never fires, and without surfacing that state the user would
 * conclude the app is broken rather than unconfigured.
 *
 * The second most important thing is [deviceProfile]: it works out which manufacturer's
 * battery manager this phone runs, because that is where the listener actually dies.
 */
object CapturePermissions {

    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    fun isListenerEnabled(context: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
        }.getOrNull() ?: return false
        if (flat.isBlank()) return false
        return flat.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == context.packageName
        }
    }

    /** Guidance for the phone we are actually running on. */
    fun deviceProfile(): OemProfile =
        OemGuidance.profile(OemGuidance.detect(Build.MANUFACTURER, Build.BRAND))

    /**
     * Whether the system currently has the listener **bound**, which is a different
     * question from [isListenerEnabled].
     *
     * `isListenerEnabled` reads a settings string: it is the user's grant, and it stays
     * `true` even after the system has stopped running the service. That gap is
     * invisible, and it cost two days of unrecorded spending. This reads the service's
     * own state instead.
     *
     * The listener shares the app's process (no `android:process` in the manifest), so
     * when this is `false` while the UI is running, the system genuinely has not bound it.
     */
    fun isListenerConnected(): Boolean = PaymentNotificationListenerService.isConnected

    /**
     * Asks the system to bind the listener again.
     *
     * `requestRebind` is a static entry point precisely so it can be called from outside
     * the running service — which is what is needed here, because the failed state is
     * "the service is not running" and there is no instance to ask. The system still
     * gets the final say, hence the boolean.
     */
    fun tryRebindListener(context: Context): Boolean = runCatching {
        val component = ComponentName(context, PaymentNotificationListenerService::class.java)
        android.service.notification.NotificationListenerService.requestRebind(component)
        true
    }.getOrDefault(false)

    fun openListenerSettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAppDetails(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:${context.packageName}".toUri()),
        )
    }

    /**
     * Opens this manufacturer's auto-start / background-launch whitelist.
     *
     * Several candidate activities are tried because OEMs rename and sometimes remove
     * them between ROM versions. When none resolve, the app details screen plus the
     * written steps in [OemProfile.setupSteps] are the honest fallback — a deep link that
     * silently does nothing would be worse than no button at all.
     */
    fun openAutoStartSettings(context: Context) {
        val profile = deviceProfile()
        if (tryActivities(context, profile.autoStartActivities)) return
        LogX.d("CapturePermissions") { "no auto-start activity for ${profile.oem}; falling back to app details" }
        openAppDetails(context)
    }

    /** Opens this manufacturer's battery / power-consumption screen. */
    fun openBatterySettings(context: Context) {
        val profile = deviceProfile()
        if (tryActivities(context, profile.batteryActivities)) return
        if (safeStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        if (safeStart(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) return
        openAppDetails(context)
    }

    /** True when the OS reports that this app may post notifications. */
    fun canPostNotifications(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun requiresRuntimeNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun tryActivities(context: Context, flattened: List<String>): Boolean {
        for (entry in flattened) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (safeStart(context, Intent().apply { this.component = component })) return true
        }
        return false
    }

    private fun safeStart(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        // Expected on ROMs that removed the activity: the caller tries the next
        // candidate, and eventually the app details screen.
        LogX.d("CapturePermissions") { "activity not available: ${intent.component?.flattenToShortString()}" }
        false
    }
}
