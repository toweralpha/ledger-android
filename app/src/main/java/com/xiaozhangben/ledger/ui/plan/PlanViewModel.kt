package com.xiaozhangben.ledger.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.budget.AllocationLite
import com.xiaozhangben.ledger.domain.budget.BudgetSnapshot
import com.xiaozhangben.ledger.domain.budget.PocketLite
import com.xiaozhangben.ledger.domain.budget.PeriodLite
import com.xiaozhangben.ledger.domain.model.CategoryKind
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

data class PlanUiState(
    val month: YearMonth,
    val period: PeriodLite? = null,
    val allocations: List<AllocationLite> = emptyList(),
    val pockets: List<PocketLite> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val snapshot: BudgetSnapshot? = null,
    val carryOverEnabled: Boolean = false,
) {
    val hasPlan: Boolean get() = period != null
    val allocatedTotalCents: Long get() = allocations.sumOf { it.amountCents }
    val unallocatedCents: Long get() = (period?.totalAmountCents ?: 0L) - allocatedTotalCents
    val pocketsTotalCents: Long get() = pockets.sumOf { it.dailyAmountCents }
}

/**
 * The plan editor: the month's total, the user-named slices of it, and the user-named
 * slices of the daily allowance.
 *
 * The daily allowance itself is computed by the domain calculator, so this screen can
 * show the user whether their named pockets add up to what they can actually spend
 * today, instead of letting them configure something unreachable.
 */
class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private val month = MutableStateFlow(DateX.yearMonthOf(System.currentTimeMillis()))
    private var loadedSnapshot: BudgetSnapshot? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<PlanUiState> = combine(
        month.flatMapLatest { container.budgets.observeMonthPlan(it) },
        container.ledger.observeCategories(),
        month.flatMapLatest { container.snapshot.observe(it) },
    ) { plan, categories, snapshot ->
        loadedSnapshot = snapshot
        PlanUiState(
            month = month.value,
            period = plan.period,
            allocations = plan.allocations,
            pockets = plan.pockets,
            categories = categories,
            snapshot = snapshot,
            carryOverEnabled = plan.period?.carryOverEnabled ?: false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlanUiState(month = month.value),
    )

    fun showPreviousMonth() = month.update { it.minusMonths(1) }

    fun showNextMonth() = month.update { it.plusMonths(1) }

    /** Creates the month if it does not exist, then stores the total. */
    fun setTotal(cents: Long) {
        viewModelScope.launch { container.budgets.setMonthlyTotal(month.value, cents) }
    }

    fun addAllocation(name: String, amountCents: Long, kind: CategoryKind, categoryKey: String?) {
        viewModelScope.launch {
            val periodId = ensurePeriod() ?: return@launch
            container.budgets.addAllocation(periodId, name, amountCents, kind, categoryKey)
        }
    }

    fun updateAllocation(id: String, name: String, amountCents: Long, kind: CategoryKind, categoryKey: String?) {
        viewModelScope.launch { container.budgets.updateAllocation(id, name, amountCents, kind, categoryKey) }
    }

    fun removeAllocation(id: String) {
        viewModelScope.launch { container.budgets.removeAllocation(id) }
    }

    fun addPocket(
        name: String,
        dailyAmountCents: Long,
        categoryKey: String?,
        mask: Int,
        startMinute: Int?,
        endMinute: Int?,
    ) {
        viewModelScope.launch {
            container.budgets.addPocket(
                name = name,
                dailyAmountCents = dailyAmountCents,
                categoryKey = categoryKey,
                weekdaysMask = mask,
                startMinute = startMinute,
                endMinute = endMinute,
            )
        }
    }

    fun updatePocket(
        id: String,
        name: String,
        dailyAmountCents: Long,
        categoryKey: String?,
        mask: Int,
        startMinute: Int?,
        endMinute: Int?,
    ) {
        viewModelScope.launch {
            container.budgets.updatePocket(
                id = id,
                name = name,
                dailyAmountCents = dailyAmountCents,
                categoryKey = categoryKey,
                weekdaysMask = mask,
                startMinute = startMinute,
                endMinute = endMinute,
            )
        }
    }

    fun removePocket(id: String) {
        viewModelScope.launch { container.budgets.removePocket(id) }
    }

    /** Splits the current daily allowance evenly across the existing pockets. */
    fun balancePocketsEvenly() {
        val snapshot = loadedSnapshot ?: return
        val pockets = state.value.pockets
        if (pockets.isEmpty()) return
        val each = snapshot.dailyAllowanceCents / pockets.size
        val remainder = snapshot.dailyAllowanceCents - each * pockets.size
        viewModelScope.launch {
            pockets.forEachIndexed { index, pocket ->
                container.budgets.updatePocket(
                    id = pocket.id,
                    name = pocket.name,
                    dailyAmountCents = each + if (index == 0) remainder else 0L,
                    categoryKey = pocket.categoryKey,
                    weekdaysMask = pocket.weekdaysMask,
                    startMinute = pocket.startMinute,
                    endMinute = pocket.endMinute,
                )
            }
        }
    }

    fun setCarryOver(enabled: Boolean, carryInCents: Long) {
        viewModelScope.launch { container.budgets.setCarryOver(month.value, enabled, carryInCents) }
    }

    fun copyFromPreviousMonth() {
        viewModelScope.launch { container.budgets.copyStructureFromPreviousMonth(month.value) }
    }

    private suspend fun ensurePeriod(): String? {
        val yearMonth = month.value
        container.budgets.findPeriod(yearMonth)?.let { return it.id }
        // Creating a plan on the fly keeps "add a slice" working from an empty month;
        // the total starts at zero and the unallocated hint tells the user what to do.
        return container.budgets.setMonthlyTotal(yearMonth, 0L)
    }
}
