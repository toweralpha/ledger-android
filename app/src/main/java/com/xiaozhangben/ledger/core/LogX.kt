package com.xiaozhangben.ledger.core

/**
 * Logging with a privacy contract.
 *
 * Amounts, merchant names, and raw notification text must never reach logcat:
 * adb-accessible logs are a real exfiltration path for a bill database.
 * Callers therefore pass a lambda, so nothing is even built when logging is off,
 * and this object is pure Kotlin (no android.util.Log) so the domain layer stays
 * unit-testable.
 *
 * [LedgerApp] flips [enabled] to `BuildConfig.DEBUG` and installs an
 * android.util.Log sink at startup.
 */
object LogX {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var sink: (level: Char, tag: String, message: String) -> Unit = { _, _, _ -> }

    fun d(tag: String, message: () -> String) {
        if (enabled) sink('D', tag, message())
    }

    fun w(tag: String, message: () -> String) {
        if (enabled) sink('W', tag, message())
    }

    fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (!enabled) return
        val suffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        sink('E', tag, message() + suffix)
    }

    /** Logs a count only — never the rows themselves. */
    fun count(tag: String, what: String, n: Int) {
        if (enabled) sink('D', tag, "$what=$n")
    }
}
