package com.xiaozhangben.ledger.di

import android.content.Context
import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.data.local.LedgerDatabase
import com.xiaozhangben.ledger.data.local.Seed
import com.xiaozhangben.ledger.data.repository.BackupRepository
import com.xiaozhangben.ledger.data.repository.BudgetRepository
import com.xiaozhangben.ledger.data.repository.Classifier
import com.xiaozhangben.ledger.data.repository.ImportRepository
import com.xiaozhangben.ledger.data.repository.LedgerRepository
import com.xiaozhangben.ledger.data.repository.SettingsRepository
import com.xiaozhangben.ledger.data.repository.SnapshotLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-written dependency container.
 *
 * Deliberately not Hilt: this app has one graph, a handful of singletons and no
 * build-variant complexity, so an annotation processor would only add build time and
 * a class of failure that is hard to debug.
 */
class AppContainer(val appContext: Context) {

    /** Application-lifetime scope. Work here must not outlive the process by design. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: LedgerDatabase by lazy { LedgerDatabase.build(appContext) }

    val classifier: Classifier by lazy { Classifier(database.ruleDao()) }

    val ledger: LedgerRepository by lazy {
        LedgerRepository(
            txDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
            importBatchDao = database.importBatchDao(),
            captureStatsDao = database.captureStatsDao(),
            classifier = classifier,
        )
    }

    val budgets: BudgetRepository by lazy { BudgetRepository(database.planDao()) }

    val imports: ImportRepository by lazy { ImportRepository(ledger) }

    /** Joins plan + actuals into the snapshot every screen and the widget render. */
    val snapshot: SnapshotLoader by lazy { SnapshotLoader(budgets, ledger) }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** Encrypted export/restore of the whole ledger. */
    val backup: BackupRepository by lazy {
        BackupRepository(database, com.xiaozhangben.ledger.BuildConfig.VERSION_NAME)
    }

    /**
     * Seeds first-run data and warms the rule cache.
     *
     * Seeding is guarded by a row count rather than a Room callback so the ordering
     * of "database exists -> defaults exist -> classifier can classify" is explicit
     * and does not depend on when Room happens to invoke its callback.
     */
    fun bootstrap() {
        appScope.launch {
            try {
                val now = System.currentTimeMillis()
                if (database.categoryDao().count() == 0) {
                    database.categoryDao().upsertAll(Seed.categories(now))
                    LogX.count("AppContainer", "categoriesSeeded", Seed.CATEGORIES.size)
                }
                if (database.ruleDao().count() == 0) {
                    database.ruleDao().insertAll(Seed.rules(now))
                    LogX.count("AppContainer", "rulesSeeded", Seed.rules(now).size)
                }
                // Daily slots are global and seeded once: 早饭 / 午饭 / 晚饭, separated by
                // time window, so the 每日 page is useful before anything is configured.
                if (budgets.ensureDefaultPockets(now)) {
                    LogX.count("AppContainer", "dailySlotsSeeded", 3)
                }
                classifier.ensureLoaded()
            } catch (t: Throwable) {
                // A seeding failure must not crash the app before the user can see it;
                // the UI surfaces an empty state and the next launch retries.
                LogX.e("AppContainer", t) { "bootstrap failed" }
            }
        }
    }
}
