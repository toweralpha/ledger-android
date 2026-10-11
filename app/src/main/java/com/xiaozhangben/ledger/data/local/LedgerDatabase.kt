package com.xiaozhangben.ledger.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        BudgetPeriodEntity::class,
        BudgetAllocationEntity::class,
        DailyPocketEntity::class,
        TransactionEntity::class,
        CategoryRuleEntity::class,
        ImportBatchEntity::class,
        CaptureStatsEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun planDao(): PlanDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun captureStatsDao(): CaptureStatsDao

    companion object {
        const val NAME = "ledger.db"

        /**
         * v1 -> v2: the diagnostics row gained the reason a notification was rejected.
         *
         * Written by hand rather than leaning on `fallbackToDestructiveMigration`:
         * silently dropping a user's ledger to accommodate a schema change is not an
         * acceptable failure mode for a bookkeeping app.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE capture_stats ADD COLUMN lastSkippedAt INTEGER")
                db.execSQL("ALTER TABLE capture_stats ADD COLUMN lastSkipReason TEXT")
            }
        }

        /**
         * v2 -> v3: the daily slots become global instead of belonging to a month, and
         * gain the optional time window used to place captured payments automatically.
         *
         * The table is rebuilt (SQLite cannot drop a NOT NULL column in place). The rows
         * that survive are the most recent month's — an older month's slot names are
         * stale duplicates by definition, and carrying all of them would produce three
         * "早饭" entries in the list.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_pockets_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `dailyAmountCents` INTEGER NOT NULL,
                        `categoryKey` TEXT,
                        `weekdaysMask` INTEGER NOT NULL,
                        `startMinute` INTEGER,
                        `endMinute` INTEGER,
                        `builtIn` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO `daily_pockets_new`
                        (id, name, dailyAmountCents, categoryKey, weekdaysMask,
                         startMinute, endMinute, builtIn, sortOrder, updatedAt, deletedAt)
                    SELECT id, name, dailyAmountCents, categoryKey, weekdaysMask,
                           NULL, NULL, 0, sortOrder, updatedAt, deletedAt
                    FROM `daily_pockets`
                    WHERE deletedAt IS NULL
                      AND periodId = (SELECT id FROM budget_periods ORDER BY yearMonth DESC LIMIT 1)
                    """
                )
                db.execSQL("DROP TABLE `daily_pockets`")
                db.execSQL("ALTER TABLE `daily_pockets_new` RENAME TO `daily_pockets`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_pockets_sortOrder` ON `daily_pockets` (`sortOrder`)")
            }
        }

        /**
         * v3 -> v4: adds the notification heartbeat to the diagnostics row.
         *
         * A plain ADD COLUMN with a NULL default, so no table rebuild is needed and
         * existing counters are untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `capture_stats` ADD COLUMN `lastNotificationSeenAt` INTEGER")
            }
        }

        /**
         * v4 -> v5: counts the times the app reconnected the listener on its own.
         *
         * Two plain ADD COLUMNs, so existing counters survive untouched.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `capture_stats` ADD COLUMN `repairCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `capture_stats` ADD COLUMN `lastRepairAt` INTEGER")
            }
        }

        fun build(context: Context): LedgerDatabase =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
