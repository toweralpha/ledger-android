package com.xiaozhangben.ledger.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.local.TransactionEntity
import com.xiaozhangben.ledger.data.local.toDirection
import com.xiaozhangben.ledger.data.local.toLite
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.domain.budget.BudgetBucket
import com.xiaozhangben.ledger.domain.budget.DailySlotAssigner
import com.xiaozhangben.ledger.domain.budget.PocketLite
import com.xiaozhangben.ledger.domain.model.TxDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One day's worth of one slot. */
data class DailySlotSection(
    val slot: PocketLite,
    val spentCents: Long,
    val items: List<TransactionEntity>,
) {
    val remainingCents: Long get() = slot.dailyAmountCents - spentCents
    val overspentCents: Long get() = if (spentCents > slot.dailyAmountCents) spentCents - slot.dailyAmountCents else 0L
    val ratio: Float
        get() = if (slot.dailyAmountCents <= 0L) 0f else spentCents.toFloat() / slot.dailyAmountCents.toFloat()
    val alert: AlertLevel get() = BudgetBucket.levelFor(ratio)
}

data class DailyUiState(
    val dayStartMillis: Long = DateX.startOfTodayMillis(),
    val isToday: Boolean = true,
    val sections: List<DailySlotSection> = emptyList(),
    /** Spending the slot rules could not place. Shown, never guessed at. */
    val unassigned: List<TransactionEntity> = emptyList(),
    val unassignedCents: Long = 0L,
    /** Rows on this day that do not count as spending (transfers, refunds, income). */
    val notCountedCount: Int = 0,
    val dayTotalCents: Long = 0L,
    val categories: Map<String, CategoryEntity> = emptyMap(),
    val loading: Boolean = true,
) {
    val slotsConfigured: Boolean get() = sections.isNotEmpty()
    val hasAnything: Boolean get() = dayTotalCents > 0L || notCountedCount > 0
}

/**
 * The 每日 page: one day at a time, spending broken into the user's own slots.
 *
 * The page exists because a payment notification carries an amount and nothing else —
 * it cannot say whether ¥15 was lunch or dinner. So the flow is: capture places the
 * payment where it can (see [DailySlotAssigner]), and this page shows the user what it
 * could not place and lets them fix it in one tap.
 */
class DailyViewModel(private val container: AppContainer) : ViewModel() {

    private val dayStart = MutableStateFlow(DateX.startOfTodayMillis())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DailyUiState> = dayStart.flatMapLatest { start ->
        val end = DateX.endOfDayExclusiveMillis(start)
        combine(
            container.ledger.observeInRange(start, end),
            container.budgets.observePockets(),
            container.ledger.observeCategories(),
        ) { rows, slots, categories ->
            build(start, rows, slots, categories)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DailyUiState(),
    )

    private fun build(
        start: Long,
        rows: List<TransactionEntity>,
        slots: List<com.xiaozhangben.ledger.data.local.DailyPocketEntity>,
        categories: List<CategoryEntity>,
    ): DailyUiState {
        val slotsLite = slots.map { it.toLite() }.sortedBy { it.sortOrder }

        val spending = rows.filter { it.direction.toDirection() == TxDirection.EXPENSE && !it.excluded }
        val bySection = LinkedHashMap<String, MutableList<TransactionEntity>>()
        val unassigned = ArrayList<TransactionEntity>()

        for (row in spending) {
            val slotId = DailySlotAssigner.assign(row.toLite(), slotsLite)
            if (slotId == null) unassigned += row else bySection.getOrPut(slotId) { ArrayList() }.add(row)
        }

        val sections = slotsLite.mapNotNull { slot ->
            val items = bySection[slot.id].orEmpty()
            // A slot bound to a category the user also removed from the day still earns
            // its place in the list when it has spending; empty, unused slots are noise.
            if (items.isEmpty() && !slot.isEveryDay) return@mapNotNull null
            DailySlotSection(
                slot = slot,
                spentCents = items.sumOf { it.amountCents },
                items = items.sortedByDescending { it.occurredAt },
            )
        }

        return DailyUiState(
            dayStartMillis = start,
            isToday = start == DateX.startOfTodayMillis(),
            sections = sections,
            unassigned = unassigned.sortedByDescending { it.occurredAt },
            unassignedCents = unassigned.sumOf { it.amountCents },
            notCountedCount = rows.size - spending.size,
            dayTotalCents = spending.sumOf { it.amountCents },
            categories = categories.associateBy { it.key },
            loading = false,
        )
    }

    fun showPreviousDay() = dayStart.update { DateX.startOfDayMillis(it - 1) }

    fun showNextDay() = dayStart.update { DateX.startOfDayMillis(it + 1) }

    fun showToday() {
        dayStart.value = DateX.startOfTodayMillis()
    }

    /** Places a row by hand. Explicit placements always beat the automatic rules. */
    fun assign(txnId: String, slotId: String?) {
        viewModelScope.launch { container.ledger.assignDailySlot(txnId, slotId) }
    }

    /** One tap from the "not placed yet" list. */
    fun assignAutoForCandidate(txn: TransactionEntity, slotId: String) {
        assign(txn.id, slotId)
    }
}
