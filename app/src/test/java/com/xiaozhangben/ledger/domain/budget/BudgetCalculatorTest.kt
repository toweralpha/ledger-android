package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * The budget maths is the part that is hardest to notice being wrong, so it is
 * pinned down here with the clock passed in rather than read.
 */
class BudgetCalculatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val june = YearMonth.of(2025, 6)

    private fun period(total: Long, carryOver: Boolean = false, carryIn: Long = 0L) =
        PeriodLite(june, total, carryOver, carryIn)

    private fun txn(
        id: String,
        cents: Long,
        category: String,
        at: Long,
        kind: CategoryKind = CategoryKind.VARIABLE,
        direction: TxDirection = TxDirection.EXPENSE,
        excluded: Boolean = false,
        excludeReason: ExcludeReason? = null,
        refundOf: String? = null,
        pocketId: String? = null,
    ) = TxnLite(
        id = id,
        occurredAt = at,
        amountCents = cents,
        direction = direction,
        categoryKey = category,
        kind = kind,
        excluded = excluded,
        excludeReason = excludeReason,
        refundOfTxnId = refundOf,
        pocketId = pocketId,
    )

    @Test
    fun `total, spent and remaining add up`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = listOf(
                    AllocationLite("a1", "吃饭", 40_000L, CategoryKind.VARIABLE, CategoryKeys.FOOD),
                    AllocationLite("a2", "房租", 30_000L, CategoryKind.FIXED, CategoryKeys.HOUSING),
                ),
                pockets = emptyList(),
                transactions = listOf(
                    txn("t1", 5_000L, CategoryKeys.FOOD, at(2025, 6, 10)),
                    txn("t2", 30_000L, CategoryKeys.HOUSING, at(2025, 6, 1), CategoryKind.FIXED),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )

        assertEquals(100_000L, snapshot.totalCents)
        assertEquals(35_000L, snapshot.spentCents)
        assertEquals(65_000L, snapshot.remainingCents)
        // 30 days in June, the 15th -> 16 spendable days including today.
        assertEquals(16, snapshot.remainingDays)
        // Rent is already paid, so nothing is held back.
        assertEquals(0L, snapshot.fixedUnpaidCents)
        assertEquals(65_000L / 16, snapshot.dailyAllowanceCents)
        assertEquals(30_000L, snapshot.unallocatedCents)
        assertEquals(AlertLevel.OK, snapshot.alert)
    }

    @Test
    fun `unpaid fixed commitments are held back from the daily allowance`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = listOf(
                    AllocationLite("a2", "房租", 30_000L, CategoryKind.FIXED, CategoryKeys.HOUSING),
                ),
                pockets = emptyList(),
                transactions = emptyList(),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        // 30,000 of rent is still to come, so it cannot be treated as spendable today.
        assertEquals(30_000L, snapshot.fixedUnpaidCents)
        assertEquals((100_000L - 30_000L) / 16, snapshot.dailyAllowanceCents)
    }

    @Test
    fun `excluded rows never count as spending`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(
                    txn("food", 5_000L, CategoryKeys.FOOD, at(2025, 6, 10)),
                    txn("transfer", 50_000L, CategoryKeys.UNCLASSIFIED, at(2025, 6, 10), excluded = true, excludeReason = ExcludeReason.TRANSFER),
                    txn("invest", 100_000L, CategoryKeys.UNCLASSIFIED, at(2025, 6, 11), excluded = true, excludeReason = ExcludeReason.INVEST),
                    txn("closed", 9_900L, CategoryKeys.SHOPPING, at(2025, 6, 12), excluded = true, excludeReason = ExcludeReason.CLOSED),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(5_000L, snapshot.spentCents)
        assertEquals(95_000L, snapshot.remainingCents)
    }

    @Test
    fun `income is recorded but not treated as reducing spending`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(
                    txn("food", 5_000L, CategoryKeys.FOOD, at(2025, 6, 10)),
                    txn("salary", 800_000L, CategoryKeys.OTHER, at(2025, 6, 10), direction = TxDirection.INCOME),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(5_000L, snapshot.spentCents)
    }

    @Test
    fun `a refund offsets the category it reverses instead of inflating income`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = listOf(
                    AllocationLite("a1", "购物", 40_000L, CategoryKind.VARIABLE, CategoryKeys.SHOPPING),
                ),
                pockets = emptyList(),
                transactions = listOf(
                    txn("buy", 20_000L, CategoryKeys.SHOPPING, at(2025, 6, 5)),
                    txn(
                        "refund", 20_000L, CategoryKeys.REFUND_POOL, at(2025, 6, 8),
                        direction = TxDirection.INCOME, excluded = true,
                        excludeReason = ExcludeReason.REFUND, refundOf = "buy",
                    ),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(0L, snapshot.spentCents)
        assertEquals(100_000L, snapshot.remainingCents)
        assertEquals(0L, snapshot.buckets.first().spentCents)
    }

    @Test
    fun `a refund of a previous month does not make this month look richer`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(
                    // Bought in May, refunded in June. May's spending is not part of
                    // June's budget, so the refund must not be either.
                    txn("buy", 20_000L, CategoryKeys.SHOPPING, at(2025, 5, 20)),
                    txn(
                        "refund", 20_000L, CategoryKeys.REFUND_POOL, at(2025, 6, 5),
                        direction = TxDirection.INCOME, excluded = true,
                        excludeReason = ExcludeReason.REFUND, refundOf = "buy",
                    ),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(0L, snapshot.spentCents)
    }

    @Test
    fun `an unmatched refund is not counted either way`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(
                    txn("food", 5_000L, CategoryKeys.FOOD, at(2025, 6, 10)),
                    txn(
                        "refund", 3_000L, CategoryKeys.REFUND_POOL, at(2025, 6, 12),
                        direction = TxDirection.INCOME, excluded = true,
                        excludeReason = ExcludeReason.REFUND, refundOf = null,
                    ),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        // The purchase still stands; an unattributable refund cannot be subtracted
        // from a category we cannot identify.
        assertEquals(5_000L, snapshot.spentCents)
    }

    @Test
    fun `transactions outside the month are ignored`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(
                    txn("may", 9_999L, CategoryKeys.FOOD, at(2025, 5, 31, 23)),
                    txn("june", 1_000L, CategoryKeys.FOOD, at(2025, 6, 1, 0)),
                    txn("july", 8_888L, CategoryKeys.FOOD, at(2025, 7, 1, 0)),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(1_000L, snapshot.spentCents)
    }

    @Test
    fun `daily pockets are named, summed, and each expense lands in exactly one`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = listOf(
                    PocketLite("p1", "早餐", 2_000L, CategoryKeys.FOOD),
                    PocketLite("p2", "通勤", 1_200L, CategoryKeys.TRANSPORT),
                    PocketLite("p3", "机动", 9_000L, null),
                ),
                transactions = listOf(
                    txn("today-food", 1_500L, CategoryKeys.FOOD, at(2025, 6, 15, 8)),
                    txn("today-other", 3_000L, CategoryKeys.UNCLASSIFIED, at(2025, 6, 15, 10)),
                    txn("yesterday", 7_777L, CategoryKeys.FOOD, at(2025, 6, 14, 8)),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )

        assertEquals(3, snapshot.pockets.size)
        assertEquals(12_200L, snapshot.pocketsTotalCents)
        // The food expense matches 早餐 by category; the unclassified one falls to the
        // pocket with no category, i.e. 机动. Neither is counted twice.
        assertEquals(1_500L, snapshot.pockets.first { it.id == "p1" }.spentTodayCents)
        assertEquals(0L, snapshot.pockets.first { it.id == "p2" }.spentTodayCents)
        assertEquals(3_000L, snapshot.pockets.first { it.id == "p3" }.spentTodayCents)
        assertEquals(4_500L, snapshot.spentTodayCents)
        assertEquals(12_200L - 4_500L, snapshot.dailyRemainingCents)
    }

    @Test
    fun `an explicit pocket assignment wins over the category match`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = listOf(
                    PocketLite("p1", "早餐", 2_000L, CategoryKeys.FOOD),
                    PocketLite("p3", "机动", 9_000L, null),
                ),
                transactions = listOf(
                    txn("t", 1_000L, CategoryKeys.FOOD, at(2025, 6, 15, 8), pocketId = "p3"),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(0L, snapshot.pockets.first { it.id == "p1" }.spentTodayCents)
        assertEquals(1_000L, snapshot.pockets.first { it.id == "p3" }.spentTodayCents)
    }

    @Test
    fun `pockets restricted to certain weekdays are inactive on other days`() {
        val monday = at(2025, 6, 16, 9) // 2025-06-16 is a Monday
        val sunday = at(2025, 6, 15, 9)

        val weekdayOnly = 0b0011111 // Monday..Friday
        val input = { now: Long ->
            BudgetCalculator.compute(
                BudgetInput(
                    period = period(100_000L),
                    allocations = emptyList(),
                    pockets = listOf(PocketLite("p1", "通勤", 1_200L, CategoryKeys.TRANSPORT, weekdayOnly)),
                    transactions = emptyList(),
                    nowMillis = now,
                    zone = zone,
                )
            )
        }
        assertEquals(1, input(monday).pockets.size)
        assertTrue(input(monday).pockets.first().activeToday)
        assertEquals(0, input(sunday).pockets.size)
    }

    @Test
    fun `alert bands follow the seventy and ninety percent thresholds`() {
        fun alertFor(spent: Long): AlertLevel = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(txn("t", spent, CategoryKeys.FOOD, at(2025, 6, 10))),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        ).alert

        assertEquals(AlertLevel.OK, alertFor(69_000L))
        assertEquals(AlertLevel.NEAR, alertFor(70_000L))
        assertEquals(AlertLevel.ALERT, alertFor(90_000L))
        assertEquals(AlertLevel.OVER, alertFor(100_000L))
        assertEquals(AlertLevel.OVER, alertFor(120_000L))
    }

    @Test
    fun `over budget reports the excess rather than pinning the bar at one hundred`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = listOf(AllocationLite("a1", "吃饭", 40_000L, CategoryKind.VARIABLE, CategoryKeys.FOOD)),
                pockets = emptyList(),
                transactions = listOf(txn("t", 55_000L, CategoryKeys.FOOD, at(2025, 6, 10))),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        // The month itself is not overspent - only the 吃饭 slice is, and the two must
        // not be conflated.
        assertEquals(45_000L, snapshot.remainingCents)
        assertEquals(15_000L, snapshot.buckets.first().overspentCents)
        assertEquals(AlertLevel.OVER, snapshot.buckets.first().alert)
        assertEquals(AlertLevel.OK, snapshot.alert)
    }

    @Test
    fun `unclassified rows are counted so an unreliable budget is visible`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(
                    txn("u1", 1_000L, CategoryKeys.UNCLASSIFIED, at(2025, 6, 10)),
                    txn("u2", 2_000L, CategoryKeys.UNCLASSIFIED, at(2025, 6, 11)),
                    txn("k", 3_000L, CategoryKeys.FOOD, at(2025, 6, 12)),
                ),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertEquals(2, snapshot.unclassifiedCount)
    }

    @Test
    fun `an unset budget does not produce a division by zero or a false alert`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = null,
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = listOf(txn("t", 1_000L, CategoryKeys.FOOD, at(2025, 6, 10))),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        )
        assertFalse(snapshot.hasBudget)
        assertEquals(AlertLevel.OK, snapshot.alert)
        assertEquals(0L, snapshot.totalCents)
        assertTrue(snapshot.dailyAllowanceCents >= 0L)
    }

    @Test
    fun `carry over only applies when it is switched on`() {
        fun totalFor(enabled: Boolean) = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L, carryOver = enabled, carryIn = 25_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = emptyList(),
                nowMillis = at(2025, 6, 15),
                zone = zone,
            )
        ).totalCents

        assertEquals(125_000L, totalFor(true))
        assertEquals(100_000L, totalFor(false))
    }

    @Test
    fun `the last day of the month still exposes at least one spendable day`() {
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = period(100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = emptyList(),
                nowMillis = at(2025, 6, 30),
                zone = zone,
            )
        )
        assertEquals(1, snapshot.remainingDays)
        assertEquals(100_000L, snapshot.dailyAllowanceCents)
    }
}
