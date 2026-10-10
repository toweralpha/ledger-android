package com.xiaozhangben.ledger.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.capture.CapturePermissions
import com.xiaozhangben.ledger.data.backup.BackupCodec
import com.xiaozhangben.ledger.data.backup.BackupFile
import com.xiaozhangben.ledger.data.local.CaptureStatsEntity
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.local.CategoryRuleEntity
import com.xiaozhangben.ledger.data.local.ImportBatchEntity
import com.xiaozhangben.ledger.data.repository.SettingsRepository
import com.xiaozhangben.ledger.data.repository.ThemeMode
import com.xiaozhangben.ledger.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val captureEnabled: Boolean = true,
    val showCaptureCard: Boolean = true,
    val budgetAlerts: Boolean = true,
    val dailySummary: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val listenerAuthorised: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val stats: CaptureStatsEntity? = null,
    val transactionCount: Int = 0,
    val unclassifiedCount: Int = 0,
    val categoryCount: Int = 0,
    val learnedRuleCount: Int = 0,
    val lastImportAt: Long? = null,
) {
    val captureHealthy: Boolean get() = captureEnabled && listenerAuthorised
    val silentDays: Int?
        get() {
            val last = stats?.lastCaptureAt ?: return null
            return ((System.currentTimeMillis() - last) / (24 * 60 * 60 * 1000L)).toInt()
        }
}

sealed interface BackupEvent {
    data object Idle : BackupEvent
    data class Exported(val bytes: ByteArray, val suggestedName: String) : BackupEvent
    data class ReadyToRestore(val file: BackupFile, val bytes: ByteArray) : BackupEvent
    data class Restored(val transactions: Int) : BackupEvent
    data class Failed(val message: String) : BackupEvent
}

/**
 * Settings, diagnostics and backup in one place.
 *
 * The diagnostics panel is not an afterthought: notification capture is the feature
 * most likely to silently stop working after an OS update or an OEM battery sweep, and
 * a user who cannot see *why* will assume the app is broken and uninstall it.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val environment = MutableStateFlow(EnvironmentState())

    private data class EnvironmentState(
        val listenerAuthorised: Boolean = false,
        val notificationsAllowed: Boolean = true,
    )

    private val _backupEvent = MutableStateFlow<BackupEvent>(BackupEvent.Idle)
    val backupEvent: StateFlow<BackupEvent> = _backupEvent

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        refreshEnvironment()
    }

    private data class Prefs(
        val captureEnabled: Boolean,
        val showCaptureCard: Boolean,
        val budgetAlerts: Boolean,
        val dailySummary: Boolean,
        val themeMode: ThemeMode,
    )

    private data class DataState(
        val stats: CaptureStatsEntity?,
        val transactionCount: Int,
        val unclassifiedCount: Int,
        val customCategoryCount: Int,
        val lastImportAt: Long?,
    )

    // combine() is typed up to five flows, so the sources are grouped first and the
    // groups are combined — clearer than one giant positional lambda anyway.
    private val prefsFlow = combine(
        container.settings.captureEnabled,
        container.settings.showCaptureCard,
        container.settings.budgetAlerts,
        container.settings.dailySummary,
        container.settings.themeMode,
    ) { capture, card, alerts, summary, theme ->
        Prefs(capture, card, alerts, summary, theme)
    }

    private val dataFlow = combine(
        container.ledger.observeCaptureStats(),
        container.ledger.observeCount(),
        container.ledger.observeUnclassifiedCount(),
        container.ledger.observeCategories(),
        container.ledger.observeImportBatches(),
    ) { stats, count, unclassified, categories, batches ->
        DataState(
            stats = stats,
            transactionCount = count,
            unclassifiedCount = unclassified,
            customCategoryCount = categories.count { !it.builtIn },
            lastImportAt = batches.firstOrNull()?.importedAt,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        prefsFlow,
        dataFlow,
        environment,
    ) { prefs, data, env ->
        SettingsUiState(
            captureEnabled = prefs.captureEnabled,
            showCaptureCard = prefs.showCaptureCard,
            budgetAlerts = prefs.budgetAlerts,
            dailySummary = prefs.dailySummary,
            themeMode = prefs.themeMode,
            listenerAuthorised = env.listenerAuthorised,
            notificationsAllowed = env.notificationsAllowed,
            stats = data.stats,
            transactionCount = data.transactionCount,
            unclassifiedCount = data.unclassifiedCount,
            categoryCount = data.customCategoryCount,
            lastImportAt = data.lastImportAt,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun refreshEnvironment() {
        val context = container.appContext
        environment.value = EnvironmentState(
            listenerAuthorised = CapturePermissions.isListenerEnabled(context),
            notificationsAllowed = CapturePermissions.canPostNotifications(context),
        )
    }

    fun setCaptureEnabled(value: Boolean) {
        viewModelScope.launch { container.settings.setCaptureEnabled(value) }
    }

    fun setShowCaptureCard(value: Boolean) {
        viewModelScope.launch { container.settings.setShowCaptureCard(value) }
    }

    fun setBudgetAlerts(value: Boolean) {
        viewModelScope.launch { container.settings.setBudgetAlerts(value) }
    }

    fun setDailySummary(value: Boolean) {
        viewModelScope.launch { container.settings.setDailySummary(value) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settings.setThemeMode(mode) }
    }

    // ---- backup ------------------------------------------------------------------

    fun exportBackup(passphrase: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { container.backup.export(passphrase.toCharArray()) }
            }
            _backupEvent.value = result.fold(
                onSuccess = {
                    BackupEvent.Exported(it, "小账本备份-${System.currentTimeMillis()}.xzb")
                },
                onFailure = { BackupEvent.Failed(it.message ?: "导出失败") },
            )
        }
    }

    fun readBackup(bytes: ByteArray, passphrase: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { container.backup.read(bytes, passphrase.toCharArray()) }
            }
            _backupEvent.value = result.fold(
                onSuccess = { BackupEvent.ReadyToRestore(it, bytes) },
                onFailure = { BackupEvent.Failed(it.message ?: "无法读取备份") },
            )
        }
    }

    fun confirmRestore(file: BackupFile) {
        viewModelScope.launch {
            val result = runCatching { container.backup.restore(file) }
            _backupEvent.value = result.fold(
                onSuccess = { BackupEvent.Restored(it) },
                onFailure = { BackupEvent.Failed(it.message ?: "恢复失败") },
            )
            if (result.isSuccess) {
                // The rule cache is now stale: the restore replaced the rule table.
                container.classifier.refresh()
            }
        }
    }

    fun consumeBackupEvent() {
        _backupEvent.value = BackupEvent.Idle
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun clearAllData() {
        viewModelScope.launch {
            container.ledger.clearAllData()
            _message.value = "已清空全部流水与导入记录"
        }
    }

    /** Reseeds the shipped category set and rule library after a wipe. */
    fun resetToDefaults() {
        viewModelScope.launch {
            container.bootstrap()
            _message.value = "已恢复默认分类与规则库"
        }
    }

    companion object {
        const val MIN_PASSPHRASE = BackupCodec.MIN_PASSPHRASE_LENGTH
    }
}

/** Rules are exposed separately so the rules screen can keep its own state. */
class RulesViewModel(private val container: AppContainer) : ViewModel() {

    val rules: StateFlow<List<CategoryRuleEntity>> = container.database.ruleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = container.ledger.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { container.classifier.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { container.classifier.deleteCustomRule(id) }
    }

    fun setCategory(id: Long, categoryKey: String) {
        viewModelScope.launch {
            container.database.ruleDao().setCategory(id, categoryKey, System.currentTimeMillis())
            container.classifier.refresh()
        }
    }

    fun addRule(pattern: String, categoryKey: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            container.database.ruleDao().insert(
                CategoryRuleEntity(
                    id = 0L,
                    priority = 400,
                    pattern = pattern.trim(),
                    target = "COUNTERPARTY",
                    matchType = "CONTAINS",
                    categoryKey = categoryKey,
                    kind = "VARIABLE",
                    origin = "USER",
                    label = "$pattern → $categoryKey（手动）",
                    updatedAt = now,
                )
            )
            container.classifier.refresh()
        }
    }
}

/** Categories screen state. */
class CategoriesViewModel(private val container: AppContainer) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = container.ledger.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String, iconKey: String, colorHex: String, kind: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val order = container.database.categoryDao().nextSortOrder()
            container.database.categoryDao().upsert(
                CategoryEntity(
                    key = "custom_" + java.util.UUID.randomUUID().toString().take(8),
                    name = name.trim(),
                    iconKey = iconKey,
                    colorHex = colorHex,
                    kind = kind,
                    sortOrder = order,
                    builtIn = false,
                    updatedAt = now,
                )
            )
        }
    }

    fun update(category: CategoryEntity, name: String, iconKey: String, colorHex: String) {
        viewModelScope.launch {
            container.database.categoryDao().upsert(
                category.copy(name = name.trim(), iconKey = iconKey, colorHex = colorHex, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun remove(category: CategoryEntity) {
        viewModelScope.launch {
            if (category.builtIn) {
                container.database.categoryDao().upsert(
                    category.copy(archived = true, updatedAt = System.currentTimeMillis())
                )
            } else {
                container.database.categoryDao().softDeleteCustom(category.key, System.currentTimeMillis())
            }
        }
    }
}
