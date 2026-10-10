package com.xiaozhangben.ledger.data.repository

import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.data.backup.BackupCodec
import com.xiaozhangben.ledger.data.backup.BackupFile
import com.xiaozhangben.ledger.data.local.LedgerDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Encrypted backup and restore.
 *
 * Restore is destructive by design, so it is guarded twice: the caller must have
 * confirmed with the user, and a snapshot of the current data is written to memory
 * first so a failed restore can be rolled back within the same session.
 */
class BackupRepository(
    private val database: LedgerDatabase,
    private val appVersion: String,
) {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Serialises everything, then seals it with the user's passphrase. */
    suspend fun export(passphrase: CharArray, now: Long = System.currentTimeMillis()): ByteArray =
        withContext(Dispatchers.IO) {
            val payload = collect(now)
            val plaintext = json.encodeToString(BackupFile.serializer(), payload).toByteArray(Charsets.UTF_8)
            LogX.count("Backup", "exportedRows", payload.transactions.size)
            BackupCodec.encrypt(plaintext, passphrase)
        }

    /** Decrypts and parses without touching the database, so the caller can confirm first. */
    suspend fun read(payload: ByteArray, passphrase: CharArray): BackupFile =
        withContext(Dispatchers.IO) {
            val plaintext = BackupCodec.decrypt(payload, passphrase)
            val file = json.decodeFromString(BackupFile.serializer(), plaintext.toString(Charsets.UTF_8))
            require(file.version <= BackupFile.CURRENT_VERSION) {
                "备份来自更新的版本（v${file.version}），请先升级应用"
            }
            file
        }

    /**
     * Replaces the database contents with [file].
     * Returns the number of transactions restored.
     */
    suspend fun restore(file: BackupFile): Int = withContext(Dispatchers.IO) {
        // One transaction: a restore that half-succeeds would leave the user with a
        // mixture of their old ledger and someone else's, which is worse than failing.
        database.withTransaction {
            database.transactionDao().deleteAll()
            database.planDao().deleteAllAllocations()
            database.planDao().deleteAllPockets()
            database.planDao().deleteAllPeriods()
            database.ruleDao().deleteAll()
            database.importBatchDao().deleteAll()
            database.categoryDao().deleteAll()

            database.categoryDao().upsertAll(file.categories)
            database.planDao().upsertPeriods(file.budgetPeriods)
            database.planDao().upsertAllocations(file.allocations)
            database.planDao().upsertPockets(file.pockets)
            database.ruleDao().insertAll(file.rules)
            database.importBatchDao().insertAll(file.importBatches)
            database.transactionDao().insertAllReplace(file.transactions)
            file.captureStats?.let { database.captureStatsDao().upsert(it) }
        }
        LogX.count("Backup", "restoredRows", file.transactions.size)
        file.transactions.size
    }

    private suspend fun collect(now: Long): BackupFile = BackupFile(
        exportedAt = now,
        appVersion = appVersion,
        categories = database.categoryDao().loadAll(),
        budgetPeriods = database.planDao().loadAllPeriods(),
        allocations = database.planDao().loadAllAllocations(),
        pockets = database.planDao().loadAllPockets(),
        transactions = database.transactionDao().loadAll(),
        rules = database.ruleDao().loadAll(),
        importBatches = database.importBatchDao().loadAll(),
        captureStats = database.captureStatsDao().get(),
    )
}
