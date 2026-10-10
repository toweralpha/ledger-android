package com.xiaozhangben.ledger.data.repository

import com.xiaozhangben.ledger.data.local.BudgetAllocationEntity
import com.xiaozhangben.ledger.data.local.BudgetPeriodEntity
import com.xiaozhangben.ledger.data.local.DailyPocketEntity
import com.xiaozhangben.ledger.data.local.PlanDao
import com.xiaozhangben.ledger.data.local.Seed
import com.xiaozhangben.ledger.data.local.toKey
import com.xiaozhangben.ledger.data.local.toLite
import com.xiaozhangben.ledger.domain.budget.MonthPlan
import com.xiaozhangben.ledger.domain.model.CategoryKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.YearMonth
import java.util.UUID

/**
 * Reading and editing the monthly plan: the total, the user-named slices of it, and
 * the user-named slices of the daily allowance.
 *
 * Every mutation is idempotent-friendly (upserts by UUID) and touches only its own
 * row, so the UI can stay optimistic without a full reload.
 */
class BudgetRepository(private val planDao: PlanDao) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonthPlan(yearMonth: YearMonth): Flow<MonthPlan> =
        planDao.observePeriod(yearMonth.toKey()).flatMapLatest { period ->
            // Daily slots are global, so they are observed even when this month has no
            // plan yet: the 每日 page works from the first day, before any budget is set.
            val allocationsFlow = period
                ?.let { planDao.observeAllocations(it.id) }
                ?: flowOf(emptyList())
            combine(allocationsFlow, planDao.observePockets()) { allocations, pockets ->
                MonthPlan(
                    yearMonth = yearMonth,
                    period = period?.toLite(),
                    allocations = allocations.map { it.toLite() },
                    pockets = pockets.map { it.toLite() },
                )
            }
        }

    suspend fun findPeriod(yearMonth: YearMonth): BudgetPeriodEntity? =
        planDao.findPeriod(yearMonth.toKey())

    suspend fun loadMonthPlan(yearMonth: YearMonth): MonthPlan {
        val period = planDao.findPeriod(yearMonth.toKey())
        return MonthPlan(
            yearMonth = yearMonth,
            period = period?.toLite(),
            allocations = period?.let { planDao.loadAllocations(it.id).map { a -> a.toLite() } } ?: emptyList(),
            pockets = planDao.loadPockets().map { it.toLite() },
        )
    }

    fun observePockets(): Flow<List<DailyPocketEntity>> = planDao.observePockets()

    suspend fun loadPockets() = planDao.loadPockets()

    /** Seeds the default meal slots if they are absent. Safe to call concurrently. */
    suspend fun ensureDefaultPockets(now: Long = System.currentTimeMillis()): Boolean {
        if (planDao.countPockets() > 0) return false
        planDao.insertPocketsIfAbsent(Seed.pockets(now))
        return true
    }

    /** Creates the month if needed and sets its total. Returns the period id. */
    suspend fun setMonthlyTotal(
        yearMonth: YearMonth,
        totalCents: Long,
        now: Long = System.currentTimeMillis(),
    ): String {
        val existing = planDao.findPeriod(yearMonth.toKey())
        val id = existing?.id ?: UUID.randomUUID().toString()
        planDao.upsertPeriod(
            BudgetPeriodEntity(
                id = id,
                yearMonth = yearMonth.toKey(),
                totalAmountCents = totalCents,
                carryOverEnabled = existing?.carryOverEnabled ?: false,
                carryInCents = existing?.carryInCents ?: 0L,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        return id
    }

    suspend fun setCarryOver(
        yearMonth: YearMonth,
        enabled: Boolean,
        carryInCents: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = planDao.findPeriod(yearMonth.toKey()) ?: return
        planDao.upsertPeriod(
            existing.copy(
                carryOverEnabled = enabled,
                carryInCents = carryInCents,
                updatedAt = now,
            )
        )
    }

    suspend fun addAllocation(
        periodId: String,
        name: String,
        amountCents: Long,
        kind: CategoryKind,
        categoryKey: String?,
        now: Long = System.currentTimeMillis(),
    ): String {
        val id = UUID.randomUUID().toString()
        planDao.upsertAllocation(
            BudgetAllocationEntity(
                id = id,
                periodId = periodId,
                name = name.trim(),
                amountCents = amountCents,
                kind = kind.name,
                categoryKey = categoryKey,
                sortOrder = planDao.nextAllocationSortOrder(periodId),
                updatedAt = now,
            )
        )
        return id
    }

    suspend fun updateAllocation(
        id: String,
        name: String,
        amountCents: Long,
        kind: CategoryKind,
        categoryKey: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = planDao.findAllocation(id) ?: return
        planDao.upsertAllocation(
            existing.copy(
                name = name.trim(),
                amountCents = amountCents,
                kind = kind.name,
                categoryKey = categoryKey,
                updatedAt = now,
            )
        )
    }

    suspend fun removeAllocation(id: String, now: Long = System.currentTimeMillis()) {
        planDao.softDeleteAllocation(id, now)
    }

    suspend fun addPocket(
        name: String,
        dailyAmountCents: Long,
        categoryKey: String?,
        weekdaysMask: Int = 0b1111111,
        startMinute: Int? = null,
        endMinute: Int? = null,
        builtIn: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): String {
        val id = UUID.randomUUID().toString()
        planDao.upsertPocket(
            DailyPocketEntity(
                id = id,
                name = name.trim(),
                dailyAmountCents = dailyAmountCents,
                categoryKey = categoryKey,
                weekdaysMask = weekdaysMask,
                startMinute = startMinute,
                endMinute = endMinute,
                builtIn = builtIn,
                sortOrder = planDao.nextPocketSortOrder(),
                updatedAt = now,
            )
        )
        return id
    }

    suspend fun updatePocket(
        id: String,
        name: String,
        dailyAmountCents: Long,
        categoryKey: String?,
        weekdaysMask: Int,
        startMinute: Int?,
        endMinute: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = planDao.findPocket(id) ?: return
        planDao.upsertPocket(
            existing.copy(
                name = name.trim(),
                dailyAmountCents = dailyAmountCents,
                categoryKey = categoryKey,
                weekdaysMask = weekdaysMask,
                startMinute = startMinute,
                endMinute = endMinute,
                updatedAt = now,
            )
        )
    }

    suspend fun removePocket(id: String, now: Long = System.currentTimeMillis()) {
        planDao.softDeletePocket(id, now)
    }

    /**
     * Copies the previous month's **total and its named slices** into [yearMonth],
     * without touching an existing plan, so re-running it is safe.
     *
     * Daily slots are deliberately not copied: they are global now, and duplicating them
     * per month is exactly the modelling mistake this release corrects.
     */
    suspend fun copyStructureFromPreviousMonth(
        yearMonth: YearMonth,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (planDao.findPeriod(yearMonth.toKey()) != null) return false
        val previous = planDao.findPreviousPeriod(yearMonth.toKey()) ?: return false

        val newId = setMonthlyTotal(yearMonth, previous.totalAmountCents, now)
        planDao.loadAllocations(previous.id).forEach { a ->
            addAllocation(
                periodId = newId,
                name = a.name,
                amountCents = a.amountCents,
                kind = runCatching { CategoryKind.valueOf(a.kind) }.getOrDefault(CategoryKind.VARIABLE),
                categoryKey = a.categoryKey,
                now = now,
            )
        }
        return true
    }
}
