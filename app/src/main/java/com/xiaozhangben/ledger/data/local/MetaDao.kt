package com.xiaozhangben.ledger.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY sortOrder, name")
    suspend fun loadAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE key = :key")
    suspend fun findByKey(key: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Query("UPDATE categories SET deletedAt = :now, updatedAt = :now WHERE key = :key AND builtIn = 0")
    suspend fun softDeleteCustom(key: String, now: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM categories")
    suspend fun nextSortOrder(): Int

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface RuleDao {

    @Query("SELECT * FROM category_rules WHERE enabled = 1 ORDER BY priority, id")
    suspend fun loadEnabled(): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE enabled = 1 ORDER BY priority, id")
    fun observeEnabled(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules ORDER BY priority, id")
    fun observeAll(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules ORDER BY priority, id")
    suspend fun loadAll(): List<CategoryRuleEntity>

    @Query("SELECT COUNT(*) FROM category_rules")
    suspend fun count(): Int

    @Query("SELECT * FROM category_rules WHERE origin = 'LEARNED' AND pattern = :pattern LIMIT 1")
    suspend fun findLearned(pattern: String): CategoryRuleEntity?

    @Query("UPDATE category_rules SET hitCount = hitCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementHit(id: Long, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<CategoryRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: CategoryRuleEntity): Long

    @Update
    suspend fun update(rule: CategoryRuleEntity)

    @Query("UPDATE category_rules SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("UPDATE category_rules SET categoryKey = :categoryKey, updatedAt = :now WHERE id = :id")
    suspend fun setCategory(id: Long, categoryKey: String?, now: Long)

    @Query("DELETE FROM category_rules WHERE id = :id AND origin != 'PRESET'")
    suspend fun deleteCustom(id: Long): Int

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}

@Dao
interface ImportBatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: ImportBatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(batches: List<ImportBatchEntity>)

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC")
    suspend fun loadAll(): List<ImportBatchEntity>

    @Query("SELECT * FROM import_batches ORDER BY importedAt DESC LIMIT 1")
    suspend fun latest(): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE fileSha256 = :sha LIMIT 1")
    suspend fun findBySha(sha: String): ImportBatchEntity?

    @Query("DELETE FROM import_batches")
    suspend fun deleteAll()
}

/**
 * Diagnostics counters.
 *
 * Deliberately an abstract class rather than an interface with hand-written
 * `INSERT ... ON CONFLICT DO UPDATE` statements. Two reasons, both learned the hard way:
 *
 *  - UPSERT syntax needs SQLite 3.24+, which the device has but the test runtime does
 *    not, so those statements could not be covered by a test at all.
 *  - `capturedCount` / `disconnectedCount` are NOT NULL without a SQL default, so a
 *    partial INSERT raised a constraint failure that the caller's catch-all swallowed.
 *    Reading and writing the whole entity makes that class of bug impossible.
 *
 * Each mutation runs in a transaction so a concurrent capture cannot lose an increment.
 */
@Dao
abstract class CaptureStatsDao {

    @Query("SELECT * FROM capture_stats WHERE id = 1")
    abstract fun observe(): Flow<CaptureStatsEntity?>

    @Query("SELECT * FROM capture_stats WHERE id = 1")
    abstract suspend fun get(): CaptureStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(stats: CaptureStatsEntity)

    @Transaction
    open suspend fun recordCapture(now: Long) {
        val current = get() ?: CaptureStatsEntity()
        upsert(current.copy(lastCaptureAt = now, capturedCount = current.capturedCount + 1))
    }

    @Transaction
    open suspend fun recordListenerConnected(now: Long) {
        val current = get() ?: CaptureStatsEntity()
        upsert(current.copy(lastListenerConnectedAt = now))
    }

    @Transaction
    open suspend fun recordListenerDisconnected(now: Long) {
        val current = get() ?: CaptureStatsEntity()
        upsert(
            current.copy(
                disconnectedCount = current.disconnectedCount + 1,
                lastDisconnectedAt = now,
            )
        )
    }

    @Transaction
    open suspend fun recordSkipped(now: Long, reason: String) {
        val current = get() ?: CaptureStatsEntity()
        upsert(current.copy(lastSkippedAt = now, lastSkipReason = reason))
    }
}
