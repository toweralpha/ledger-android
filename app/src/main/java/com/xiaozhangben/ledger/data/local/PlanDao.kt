package com.xiaozhangben.ledger.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Budget persistence: the monthly total, its named slices, and the named slices of
 * the daily allowance.
 */
@Dao
interface PlanDao {

    // ---- monthly total -----------------------------------------------------------

    @Query("SELECT * FROM budget_periods WHERE yearMonth = :yearMonth LIMIT 1")
    suspend fun findPeriod(yearMonth: String): BudgetPeriodEntity?

    @Query("SELECT * FROM budget_periods WHERE yearMonth = :yearMonth LIMIT 1")
    fun observePeriod(yearMonth: String): Flow<BudgetPeriodEntity?>

    @Query("SELECT * FROM budget_periods ORDER BY yearMonth DESC")
    fun observeAllPeriods(): Flow<List<BudgetPeriodEntity>>

    @Query("SELECT * FROM budget_periods WHERE yearMonth < :yearMonth ORDER BY yearMonth DESC LIMIT 1")
    suspend fun findPreviousPeriod(yearMonth: String): BudgetPeriodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeriod(period: BudgetPeriodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeriods(periods: List<BudgetPeriodEntity>)

    @Update
    suspend fun updatePeriod(period: BudgetPeriodEntity)

    // ---- named monthly slices ----------------------------------------------------

    @Query("SELECT * FROM budget_allocations WHERE periodId = :periodId AND deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeAllocations(periodId: String): Flow<List<BudgetAllocationEntity>>

    @Query("SELECT * FROM budget_allocations WHERE periodId = :periodId AND deletedAt IS NULL ORDER BY sortOrder, name")
    suspend fun loadAllocations(periodId: String): List<BudgetAllocationEntity>

    @Query("SELECT * FROM budget_allocations WHERE id = :id")
    suspend fun findAllocation(id: String): BudgetAllocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllocation(allocation: BudgetAllocationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllocations(allocations: List<BudgetAllocationEntity>)

    @Query("UPDATE budget_allocations SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteAllocation(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM budget_allocations WHERE periodId = :periodId")
    suspend fun nextAllocationSortOrder(periodId: String): Int

    // ---- daily slots (global: 早饭 / 午饭 / 晚饭 / 机动 …) -------------------------
    // Not scoped to a period: the shape of a day does not change every month, and
    // scoping it there meant the user had to rebuild their meals every month.

    @Query("SELECT * FROM daily_pockets WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    fun observePockets(): Flow<List<DailyPocketEntity>>

    @Query("SELECT * FROM daily_pockets WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    suspend fun loadPockets(): List<DailyPocketEntity>

    @Query("SELECT COUNT(*) FROM daily_pockets WHERE deletedAt IS NULL")
    suspend fun countPockets(): Int

    @Query("SELECT * FROM daily_pockets WHERE id = :id")
    suspend fun findPocket(id: String): DailyPocketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPocket(pocket: DailyPocketEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPockets(pockets: List<DailyPocketEntity>)

    /**
     * Seeds a slot only if its id is not already present.
     *
     * `bootstrap()` can legitimately run twice at once (Application start plus the boot
     * receiver or the periodic worker), so the seed has to be safe under a race and
     * must never overwrite a slot the user has since renamed.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPocketsIfAbsent(pockets: List<DailyPocketEntity>)

    @Query("UPDATE daily_pockets SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeletePocket(id: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM daily_pockets")
    suspend fun nextPocketSortOrder(): Int

    // ---- backup / restore --------------------------------------------------------

    @Query("SELECT * FROM budget_periods")
    suspend fun loadAllPeriods(): List<BudgetPeriodEntity>

    @Query("SELECT * FROM budget_allocations")
    suspend fun loadAllAllocations(): List<BudgetAllocationEntity>

    @Query("SELECT * FROM daily_pockets")
    suspend fun loadAllPockets(): List<DailyPocketEntity>

    @Query("DELETE FROM budget_periods")
    suspend fun deleteAllPeriods()

    @Query("DELETE FROM budget_allocations")
    suspend fun deleteAllAllocations()

    @Query("DELETE FROM daily_pockets")
    suspend fun deleteAllPockets()
}
