package com.xiaozhangben.ledger.data.backup

import com.xiaozhangben.ledger.data.local.BudgetAllocationEntity
import com.xiaozhangben.ledger.data.local.BudgetPeriodEntity
import com.xiaozhangben.ledger.data.local.CaptureStatsEntity
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.local.CategoryRuleEntity
import com.xiaozhangben.ledger.data.local.DailyPocketEntity
import com.xiaozhangben.ledger.data.local.ImportBatchEntity
import com.xiaozhangben.ledger.data.local.TransactionEntity
import kotlinx.serialization.Serializable

/**
 * The backup payload.
 *
 * It mirrors the Room entities one-to-one. That is deliberate: a hand-written export
 * schema drifts from the database and you only discover the missing field when
 * restoring five years of records. [version] exists so a future format change can be
 * migrated rather than guessed at.
 */
@Serializable
data class BackupFile(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val categories: List<CategoryEntity> = emptyList(),
    val budgetPeriods: List<BudgetPeriodEntity> = emptyList(),
    val allocations: List<BudgetAllocationEntity> = emptyList(),
    val pockets: List<DailyPocketEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val rules: List<CategoryRuleEntity> = emptyList(),
    val importBatches: List<ImportBatchEntity> = emptyList(),
    val captureStats: CaptureStatsEntity? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
