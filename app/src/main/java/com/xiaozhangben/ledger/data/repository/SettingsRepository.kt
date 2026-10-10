package com.xiaozhangben.ledger.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ledger_settings")

/** Which theme the user pinned; `SYSTEM` follows the device. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User preferences.
 *
 * All defaults are chosen so a first-run user gets a working product without
 * opening settings: capture on, alerts on, follow the system theme.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val CAPTURE_ENABLED = booleanPreferencesKey("capture_enabled")
        val SHOW_CAPTURE_CARD = booleanPreferencesKey("show_capture_card")
        val BUDGET_ALERTS = booleanPreferencesKey("budget_alerts")
        val DAILY_SUMMARY = booleanPreferencesKey("daily_summary")
        val THEME = stringPreferencesKey("theme_mode")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LAST_VERSION_SEEN = stringPreferencesKey("last_version_seen")
    }

    /** Master switch for the notification listener; turning it off must stop the service. */
    val captureEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.CAPTURE_ENABLED] ?: true }

    /** The "已记录：星巴克 ¥32 → 餐饮 [改分类]" prompt shown right after a capture. */
    val showCaptureCard: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.SHOW_CAPTURE_CARD] ?: true }

    val budgetAlerts: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.BUDGET_ALERTS] ?: true }

    /** Off by default: an extra notification every evening is noise for most people. */
    val dailySummary: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.DAILY_SUMMARY] ?: false }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val onboarded: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.ONBOARDED] ?: false }

    suspend fun setCaptureEnabled(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.CAPTURE_ENABLED] = value }

    suspend fun setShowCaptureCard(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.SHOW_CAPTURE_CARD] = value }

    suspend fun setBudgetAlerts(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.BUDGET_ALERTS] = value }

    suspend fun setDailySummary(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.DAILY_SUMMARY] = value }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.settingsDataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setOnboarded(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.ONBOARDED] = value }

    suspend fun markVersionSeen(version: String) =
        context.settingsDataStore.edit { it[Keys.LAST_VERSION_SEEN] = version }

    // ---- budget alert de-duplication --------------------------------------------
    // The highest band already reported for a month, so a user is warned once per
    // crossing (70% -> 90% -> over) instead of on every background check.

    fun alertLevel(yearMonthKey: String): Flow<Int> =
        context.settingsDataStore.data.map { it[alertKey(yearMonthKey)] ?: 0 }

    suspend fun setAlertLevel(yearMonthKey: String, level: Int) =
        context.settingsDataStore.edit { it[alertKey(yearMonthKey)] = level }

    private fun alertKey(yearMonthKey: String) = androidx.datastore.preferences.core.intPreferencesKey("alert_$yearMonthKey")
}
