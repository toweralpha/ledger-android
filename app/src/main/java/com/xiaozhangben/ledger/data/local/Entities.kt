package com.xiaozhangben.ledger.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Room schema.
 *
 * Conventions used throughout:
 *  - money is `Long` cents, never Double
 *  - enums are stored as their `name` string and mapped explicitly in the
 *    repository layer, so a renamed/serialised enum never silently changes meaning
 *  - ids are UUID strings, not auto-increment ints, to keep future merging possible
 *  - sync-ready columns (`updatedAt`, `deletedAt`) exist from day one even though
 *    this release is local-only
 *
 * Every entity is also `@Serializable`, which is what the encrypted backup writes
 * directly — one shape for the database and the backup, so a field can never be
 * persisted but silently missing from a restore.
 */

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    /** Stable key such as "food". Referenced by preset rules, so it must not be a generated id. */
    @PrimaryKey val key: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val kind: String,
    val sortOrder: Int,
    val archived: Boolean = false,
    val builtIn: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(
    tableName = "budget_periods",
    indices = [Index(value = ["yearMonth"], unique = true)],
)
data class BudgetPeriodEntity(
    @PrimaryKey val id: String,
    /** "2025-06" */
    val yearMonth: String,
    /** The month's total money. */
    val totalAmountCents: Long,
    val carryOverEnabled: Boolean = false,
    val carryInCents: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One user-named slice of the monthly total ("房租", "吃饭", ...). */
@Serializable
@Entity(
    tableName = "budget_allocations",
    indices = [Index("periodId"), Index(value = ["periodId", "sortOrder"])],
)
data class BudgetAllocationEntity(
    @PrimaryKey val id: String,
    val periodId: String,
    val name: String,
    val amountCents: Long,
    val kind: String,
    /** Optional link to a category; a pure envelope may leave this null. */
    val categoryKey: String? = null,
    val sortOrder: Int,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * A user-named slot of the daily allowance, such as 早饭 / 午饭 / 晚饭 / 机动.
 *
 * **Global, not per-month.** This is a correction: the first version hung these off a
 * `BudgetPeriod`, which meant the user had to recreate 早饭/午饭/晚饭 every month. The
 * structure of a day is a personal constant; only the month's *total* changes monthly.
 *
 * The optional `startMinute` / `endMinute` window is what lets a captured payment be
 * placed automatically: a payment app's notification says "已支付¥15.00" and nothing
 * about whether that was lunch, so the time of day is the only honest signal available.
 * Windows are nullable, and a slot with no window is never auto-filled.
 */
@Serializable
@Entity(
    tableName = "daily_pockets",
    indices = [Index("sortOrder")],
)
data class DailyPocketEntity(
    @PrimaryKey val id: String,
    val name: String,
    val dailyAmountCents: Long,
    val categoryKey: String? = null,
    /** bit 0 = Monday ... bit 6 = Sunday. 0b1111111 (127) means every day. */
    val weekdaysMask: Int = 127,
    /** Minutes from local midnight, inclusive start / exclusive end. Both null = no window. */
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    /** Shipped with the app (早饭/午饭/晚饭); can be renamed but not conceptually removed. */
    val builtIn: Boolean = false,
    val sortOrder: Int,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredAt"),
        Index("categoryKey"),
        Index("importBatchId"),
        Index("direction"),
        Index(value = ["dedupeKey"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val source: String,
    val direction: String,
    val occurredAt: Long,
    val amountCents: Long,
    val categoryKey: String,
    val kind: String,

    /** True when the row is kept for the record but must not count as spending. */
    val excluded: Boolean = false,
    val excludeReason: String? = null,

    val rawType: String? = null,
    val status: String? = null,
    val counterpartyRaw: String? = null,
    /** Normalised payee, used by the rule engine and by learned rules. */
    val counterparty: String? = null,
    val productDesc: String? = null,
    val payMethod: String? = null,
    val sourceTxnId: String? = null,
    val merchantOrderId: String? = null,
    val remark: String? = null,
    val note: String? = null,

    /** Which rule decided the category, so the classification stays explainable. */
    val matchedRuleId: Long? = null,
    val matchedRuleLabel: String? = null,
    val confidence: Float = 0f,
    /** Once the user edits the category, automatic re-classification must not undo it. */
    val userCorrected: Boolean = false,

    /** Set on a refund row once it is matched to the payment it reverses. */
    val refundOfTxnId: String? = null,
    /** Groups the two halves of a move between the user's own accounts. */
    val transferGroupId: String? = null,
    val importBatchId: String? = null,
    val notificationKey: String? = null,

    /** Unique fingerprint: source transaction id, or a hash of the row's content. */
    val dedupeKey: String,

    /** Which daily pocket this spending counted against, and who decided. */
    val pocketId: String? = null,
    val pocketAssignedBy: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "category_rules", indices = [Index("priority"), Index("origin")])
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val priority: Int,
    val pattern: String,
    val target: String,
    val matchType: String,
    val categoryKey: String? = null,
    val excludeReason: String? = null,
    val kind: String,
    val origin: String,
    val label: String,
    val hitCount: Int = 0,
    val enabled: Boolean = true,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val fileSha256: String,
    val source: String,
    val encoding: String,
    val rangeHint: String? = null,
    val rowCount: Int,
    val newCount: Int,
    val dupCount: Int,
    val excludedCount: Int,
    val unclassifiedCount: Int,
    val rejectedCount: Int,
    val importedAt: Long,
)

/**
 * Single-row table holding counters that the diagnostics screen reports.
 * Persisted (not in-memory) so "captured nothing for 3 days" survives a restart.
 *
 * `lastSkipReason` exists because the hardest failure to diagnose in the field is a
 * notification that arrived and was *rejected* — the user sees nothing, and without the
 * reason there is no way to tell "WeChat never posted it" from "our parser refused it".
 * It stores a reason code, never any notification content.
 */
@Serializable
@Entity(tableName = "capture_stats")
data class CaptureStatsEntity(
    @PrimaryKey val id: Int = 1,
    val lastCaptureAt: Long? = null,
    val lastListenerConnectedAt: Long? = null,
    val capturedCount: Int = 0,
    val disconnectedCount: Int = 0,
    val lastDisconnectedAt: Long? = null,
    val lastSkippedAt: Long? = null,
    val lastSkipReason: String? = null,
)
