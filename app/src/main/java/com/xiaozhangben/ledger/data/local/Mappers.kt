package com.xiaozhangben.ledger.data.local

import com.xiaozhangben.ledger.domain.budget.AllocationLite
import com.xiaozhangben.ledger.domain.budget.PeriodLite
import com.xiaozhangben.ledger.domain.budget.PocketLite
import com.xiaozhangben.ledger.domain.budget.TxnLite
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import java.time.YearMonth

/**
 * Entity <-> domain mapping.
 *
 * Enums cross this boundary as strings with a safe fallback, so a value written by
 * a newer build (or a corrupted row) degrades to something harmless instead of
 * throwing while the user is looking at their ledger.
 */

fun TransactionEntity.toLite(): TxnLite = TxnLite(
    id = id,
    occurredAt = occurredAt,
    amountCents = amountCents,
    direction = direction.toDirection(),
    categoryKey = categoryKey,
    kind = kind.toCategoryKind(),
    excluded = excluded,
    excludeReason = excludeReason?.toExcludeReason(),
    refundOfTxnId = refundOfTxnId,
    pocketId = pocketId,
)

fun BudgetAllocationEntity.toLite(): AllocationLite = AllocationLite(
    id = id,
    name = name,
    amountCents = amountCents,
    kind = kind.toCategoryKind(),
    categoryKey = categoryKey,
    sortOrder = sortOrder,
)

fun DailyPocketEntity.toLite(): PocketLite = PocketLite(
    id = id,
    name = name,
    dailyAmountCents = dailyAmountCents,
    categoryKey = categoryKey,
    weekdaysMask = weekdaysMask,
    startMinute = startMinute,
    endMinute = endMinute,
    builtIn = builtIn,
    sortOrder = sortOrder,
)

fun BudgetPeriodEntity.toLite(): PeriodLite? {
    val ym = com.xiaozhangben.ledger.core.DateX.parseMonthKey(yearMonth) ?: return null
    return PeriodLite(
        yearMonth = ym,
        totalAmountCents = totalAmountCents,
        carryOverEnabled = carryOverEnabled,
        carryInCents = carryInCents,
    )
}

fun PeriodLite.toEntity(id: String, now: Long, createdAt: Long = now, carryOver: Boolean = carryOverEnabled): BudgetPeriodEntity =
    BudgetPeriodEntity(
        id = id,
        yearMonth = yearMonth.toString(),
        totalAmountCents = totalAmountCents,
        carryOverEnabled = carryOver,
        carryInCents = carryInCents,
        createdAt = createdAt,
        updatedAt = now,
    )

fun YearMonth.toKey(): String = toString()

fun String.toDirection(): TxDirection =
    runCatching { TxDirection.valueOf(this) }.getOrDefault(TxDirection.EXPENSE)

fun String.toCategoryKind(): CategoryKind =
    runCatching { CategoryKind.valueOf(this) }.getOrDefault(CategoryKind.VARIABLE)

fun String.toExcludeReason(): ExcludeReason? =
    runCatching { ExcludeReason.valueOf(this) }.getOrNull()

fun String.toTxSource(): TxSource =
    runCatching { TxSource.valueOf(this) }.getOrDefault(TxSource.UNKNOWN)
