package com.xiaozhangben.ledger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.capture.CaptureHealth
import com.xiaozhangben.ledger.capture.CaptureHealthEvaluator
import com.xiaozhangben.ledger.capture.CapturePermissions
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.budget.BudgetSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
    val listenerConnected: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val lastCaptureAt: Long? = null,
    val lastNotificationSeenAt: Long? = null,
    val captureHealth: CaptureHealth = CaptureHealth.OK,
    val unclassifiedCount: Int = 0,
    val hasTransactions: Boolean = false,
    val loaded: Boolean = false,
) {
    /** Granted but not running — the failure that used to pass unnoticed. */
    val captureBroken: Boolean
        get() = captureHealth == CaptureHealth.NOT_CONNECTED || captureHealth == CaptureHealth.NOT_RECEIVING

    val needsNotificationPermission: Boolean
        get() = captureEnabled && listenerAuthorised && !notificationsAllowed
}

/**
 * Home screen state.
 *
 * The month is a `StateFlow` so switching months re-subscribes the underlying Room
 * queries rather than filtering in memory — the snapshot flow is already keyed by
 * date range, so a month change is just a new subscription.
 *
 * Capture health is computed here rather than in the screen because it needs the
 * service's live binding state, which is only knowable in-process.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private data class Env(
        val listenerAuthorised: Boolean = false,
        val listenerConnected: Boolean = false,
        val notificationsAllowed: Boolean = true,
        val hasTransactions: Boolean = false,
    )

    private val month = MutableStateFlow(DateX.yearMonthOf(System.currentTimeMillis()))
    private val env = MutableStateFlow(Env())

    init {
        refreshEnvironment()
        // A process that has just started legitimately has no listener connection for a
        // moment, so ask the system to bind before any warning is shown. Without this the
        // banner would flash on every cold start and the user would learn to ignore it.
        viewModelScope.launch { ensureListenerConnected() }
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
        val now = System.currentTimeMillis()
        HomeUiState(
            month = snapshot.yearMonth,
            snapshot = snapshot,
            categories = categories.associateBy { it.key },
            captureEnabled = captureEnabled,
            listenerAuthorised = env.listenerAuthorised,
            listenerConnected = env.listenerConnected,
            notificationsAllowed = env.notificationsAllowed,
            lastCaptureAt = stats?.lastCaptureAt,
            lastNotificationSeenAt = stats?.lastNotificationSeenAt,
            captureHealth = CaptureHealthEvaluator.evaluate(
                captureEnabled = captureEnabled,
                authorised = env.listenerAuthorised,
                connected = env.listenerConnected,
                lastNotificationSeenAt = stats?.lastNotificationSeenAt,
                lastListenerConnectedAt = stats?.lastListenerConnectedAt,
                now = now,
            ),
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
                listenerConnected = CapturePermissions.isListenerConnected(),
                notificationsAllowed = CapturePermissions.canPostNotifications(context),
            )
        }
    }

    /**
     * Ask the system to bind the listener if it is not bound, then re-read the state.
     *
     * `requestRebind` can be refused and the user may still have to toggle notification
     * access by hand, so this is a best effort — the point is that the app tries before
     * it starts complaining.
     */
    private suspend fun ensureListenerConnected() {
        val context = container.appContext
        if (!CapturePermissions.isListenerEnabled(context)) return
        if (CapturePermissions.isListenerConnected()) return
        CapturePermissions.tryRebindListener(context)
        delay(REBIND_SETTLE_MILLIS)
        refreshEnvironment()
    }

    /** Invoked by the user from the warning banner. */
    fun repairCapture() {
        viewModelScope.launch {
            ensureListenerConnected()
            refreshEnvironment()
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
        /** How long to give the system to bind before believing it did not. */
        const val REBIND_SETTLE_MILLIS = 1_500L
    }
}
