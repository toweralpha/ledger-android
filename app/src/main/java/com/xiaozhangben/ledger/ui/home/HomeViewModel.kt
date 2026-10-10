package com.xiaozhangben.ledger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.capture.CapturePermissions
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.budget.BudgetSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

data class HomeUiState(
    val month: YearMonth,
    val snapshot: BudgetSnapshot? = null,
    val categories: Map<String, CategoryEntity> = emptyMap(),
    val captureEnabled: Boolean = true,
    val listenerAuthorised: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val lastCaptureAt: Long? = null,
    val captureSilent: Boolean = false,
    val unclassifiedCount: Int = 0,
    val hasTransactions: Boolean = false,
    val loaded: Boolean = false,
) {
    val needsCaptureSetup: Boolean get() = captureEnabled && !listenerAuthorised
    val needsNotificationPermission: Boolean get() = captureEnabled && listenerAuthorised && !notificationsAllowed
}

/**
 * Home screen state.
 *
 * The month is a `StateFlow` so switching months re-subscribes the underlying Room
 * queries rather than filtering in memory — the snapshot flow is already keyed by
 * date range, so a month change is just a new subscription.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private data class Env(
        val listenerAuthorised: Boolean = false,
        val notificationsAllowed: Boolean = true,
        val hasTransactions: Boolean = false,
    )

    private val month = MutableStateFlow(DateX.yearMonthOf(System.currentTimeMillis()))
    private val env = MutableStateFlow(Env())

    init {
        refreshEnvironment()
        viewModelScope.launch {
            container.ledger.observeCount().collect { count ->
                env.update { it.copy(hasTransactions = count > 0) }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeUiState> = combine(
        month.flatMapLatest { container.snapshot.observe(it) },
        container.ledger.observeCategories(),
        container.ledger.observeCaptureStats(),
        container.settings.captureEnabled,
        env,
    ) { snapshot, categories, stats, captureEnabled, env ->
        val lastCapture = stats?.lastCaptureAt
        HomeUiState(
            month = snapshot.yearMonth,
            snapshot = snapshot,
            categories = categories.associateBy { it.key },
            captureEnabled = captureEnabled,
            listenerAuthorised = env.listenerAuthorised,
            notificationsAllowed = env.notificationsAllowed,
            lastCaptureAt = lastCapture,
            captureSilent = captureEnabled &&
                env.listenerAuthorised &&
                env.hasTransactions &&
                (lastCapture == null || System.currentTimeMillis() - lastCapture > SILENT_AFTER_MILLIS),
            unclassifiedCount = snapshot.unclassifiedCount,
            hasTransactions = env.hasTransactions,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(month = month.value),
    )

    /** Re-read things that can only change outside the app (permissions, OEM settings). */
    fun refreshEnvironment() {
        val context = container.appContext
        env.update {
            it.copy(
                listenerAuthorised = CapturePermissions.isListenerEnabled(context),
                notificationsAllowed = CapturePermissions.canPostNotifications(context),
            )
        }
    }

    fun showPreviousMonth() {
        month.update { it.minusMonths(1) }
    }

    fun showNextMonth() {
        month.update { it.plusMonths(1) }
    }

    fun showCurrentMonth() {
        month.value = DateX.yearMonthOf(System.currentTimeMillis())
    }

    fun setMonthlyTotal(cents: Long) {
        val target = month.value
        viewModelScope.launch {
            container.budgets.setMonthlyTotal(target, cents)
        }
    }

    fun setCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setCaptureEnabled(enabled) }
    }

    /** Creates the month's plan as a copy of the previous month, if one exists. */
    fun copyFromPreviousMonth() {
        val target = month.value
        viewModelScope.launch { container.budgets.copyStructureFromPreviousMonth(target) }
    }

    companion object {
        /** Three quiet days with capture on is worth telling the user about. */
        const val SILENT_AFTER_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
