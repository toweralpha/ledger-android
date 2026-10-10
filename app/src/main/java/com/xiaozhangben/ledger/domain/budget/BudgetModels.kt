package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection

/**
 * Domain-side projections of the persisted rows.
 *
 * The calculator deliberately takes these instead of Room entities: the budget
 * maths is the part most likely to be wrong and the part hardest to notice being
 * wrong, so it must be testable as plain Kotlin with no Android, no database and
 * no clock.
 */
data class TxnLite(
    val id: String,
    val occurredAt: Long,
    val amountCents: Long,
    val direction: TxDirection,
    val categoryKey: String,
    val kind: CategoryKind,
    val excluded: Boolean = false,
    val excludeReason: ExcludeReason? = null,
    val refundOfTxnId: String? = null,
    val pocketId: String? = null,
)

data class AllocationLite(
    val id: String,
    val name: String,
    val amountCents: Long,
    val kind: CategoryKind,
    val categoryKey: String? = null,
    val sortOrder: Int = 0,
)

data class PocketLite(
    val id: String,
    val name: String,
    val dailyAmountCents: Long,
    val categoryKey: String? = null,
    /** bit 0 = Monday … bit 6 = Sunday; 127 = every day. */
    val weekdaysMask: Int = 127,
    /** Minutes from local midnight. Both null = this slot has no time rule. */
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val builtIn: Boolean = false,
    val sortOrder: Int = 0,
) {
    fun activeOn(weekdayMon1: Int): Boolean {
        if (weekdaysMask == 0) return false
        val bit = 1 shl (weekdayMon1 - 1)
        return weekdaysMask and bit != 0
    }

    val isEveryDay: Boolean get() = weekdaysMask == 0b1111111

    /** True when this slot has no category and no time rule, i.e. it is a catch-all. */
    val isCatchAll: Boolean get() = categoryKey == null && startMinute == null

    /** True when [minuteOfDay] falls inside this slot's window. */
    fun coversMinute(minuteOfDay: Int): Boolean {
        val start = startMinute ?: return false
        val end = endMinute ?: return false
        return if (start <= end) {
            minuteOfDay in start until end
        } else {
            // A window that wraps past midnight, e.g. 宵夜 22:00–02:00.
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /**
     * Length of the window in minutes, or `null` when there is no window.
     * Used to resolve overlaps towards the more specific slot.
     */
    val windowLengthMinutes: Int?
        get() {
            val start = startMinute ?: return null
            val end = endMinute ?: return null
            return if (start <= end) end - start else (end + 1440) - start
        }
}

data class PeriodLite(
    val yearMonth: java.time.YearMonth,
    val totalAmountCents: Long,
    val carryOverEnabled: Boolean = false,
    val carryInCents: Long = 0L,
)

/** Budget health bands, matching the 70% / 90% / over-budget thresholds. */
enum class AlertLevel { OK, NEAR, ALERT, OVER }

data class BudgetBucket(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val budgetCents: Long,
    /** Net of refunds, never negative. */
    val spentCents: Long,
    val categoryKey: String? = null,
) {
    val remainingCents: Long get() = budgetCents - spentCents
    val overspentCents: Long get() = if (spentCents > budgetCents) spentCents - budgetCents else 0L

    val ratio: Float
        get() = if (budgetCents <= 0L) 0f else spentCents.toFloat() / budgetCents.toFloat()

    val alert: AlertLevel get() = levelFor(ratio)

    companion object {
        fun levelFor(ratio: Float): AlertLevel = when {
            ratio >= 1f -> AlertLevel.OVER
            ratio >= 0.9f -> AlertLevel.ALERT
            ratio >= 0.7f -> AlertLevel.NEAR
            else -> AlertLevel.OK
        }
    }
}

data class PocketStatus(
    val id: String,
    val name: String,
    val dailyAmountCents: Long,
    val spentTodayCents: Long,
    val activeToday: Boolean,
    val categoryKey: String? = null,
) {
    val remainingTodayCents: Long get() = dailyAmountCents - spentTodayCents
    val overspentTodayCents: Long get() = if (spentTodayCents > dailyAmountCents) spentTodayCents - dailyAmountCents else 0L
    val ratio: Float get() = if (dailyAmountCents <= 0L) 0f else spentTodayCents.toFloat() / dailyAmountCents
    val alert: AlertLevel get() = BudgetBucket.levelFor(ratio)
}

/**
 * Everything the home screen needs, computed in one pass.
 *
 * All amounts are cents; the UI never does arithmetic on money.
 */
data class BudgetSnapshot(
    val yearMonth: java.time.YearMonth,
    /** Total money for the month, including any carry-over. */
    val totalCents: Long,
    val carryInCents: Long,
    val spentCents: Long,
    val remainingCents: Long,
    val remainingDays: Int,
    /** Spendable today: (remaining - still-unpaid fixed commitments) / remaining days. */
    val dailyAllowanceCents: Long,
    val spentTodayCents: Long,
    val dailyRemainingCents: Long,
    /** Fixed allocations not yet consumed, held back from the daily allowance. */
    val fixedUnpaidCents: Long,
    val buckets: List<BudgetBucket>,
    val pockets: List<PocketStatus>,
    val pocketsTotalCents: Long,
    val pocketsMismatch: Boolean,
    /** Today's spending the slot rules could not place. Surfaced, never guessed at. */
    val unassignedTodayCount: Int = 0,
    val unassignedTodayCents: Long = 0L,
    val unallocatedCents: Long,
    val unclassifiedCount: Int,
    val alert: AlertLevel,
) {
    val hasBudget: Boolean get() = totalCents > 0L
    val ratio: Float get() = if (totalCents <= 0L) 0f else spentCents.toFloat() / totalCents.toFloat()
}

data class BudgetInput(
    val period: PeriodLite?,
    val allocations: List<AllocationLite>,
    val pockets: List<PocketLite>,
    val transactions: List<TxnLite>,
    /** Local-time instant used for "today" and "days remaining". Not read from a clock inside. */
    val nowMillis: Long,
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
)

/** Everything the user configured for one month. */
data class MonthPlan(
    val yearMonth: java.time.YearMonth,
    val period: PeriodLite?,
    val allocations: List<AllocationLite> = emptyList(),
    val pockets: List<PocketLite> = emptyList(),
) {
    val allocatedTotalCents: Long get() = allocations.sumOf { it.amountCents }
    val pocketsTotalCents: Long get() = pockets.sumOf { it.dailyAmountCents }
    val hasPlan: Boolean get() = period != null
}
