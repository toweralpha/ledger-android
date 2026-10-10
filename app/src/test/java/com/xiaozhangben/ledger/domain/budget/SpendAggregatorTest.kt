package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The aggregator is the shared definition of "how much was spent". These tests lock in
 * the invariants that let the home screen and the statistics page coexist without
 * disagreeing — the failure mode being that the user notices two different totals and
 * stops trusting both.
 */
class SpendAggregatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(day: Int, hour: Int = 12): Long =
        LocalDateTime.of(2025, 6, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val monthStart = at(1, 0)
    private val monthEnd = LocalDateTime.of(2025, 7, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
    private val dayStart = at(15, 0)
    private val dayEnd = at(16, 0)

    private fun txn(
        id: String,
        cents: Long,
        category: String,
        occurredAt: Long,
        direction: TxDirection = TxDirection.EXPENSE,
        excluded: Boolean = false,
        reason: ExcludeReason? = null,
        refundOf: String? = null,
    ) = TxnLite(
        id = id,
        occurredAt = occurredAt,
        amountCents = cents,
        direction = direction,
        categoryKey = category,
        kind = CategoryKind.VARIABLE,
        excluded = excluded,
        excludeReason = reason,
        refundOfTxnId = refundOf,
    )

    private fun aggregate(rows: List<TxnLite>) = SpendAggregator.aggregate(
        transactions = rows,
        monthStart = monthStart,
        monthEndExclusive = monthEnd,
        dayStart = dayStart,
        dayEndExclusive = dayEnd,
    )

    @Test
    fun `the parts add up to the whole`() {
        val result = aggregate(
            listOf(
                txn("a", 1_000L, CategoryKeys.FOOD, at(3)),
                txn("b", 2_500L, CategoryKeys.TRANSPORT, at(4)),
                txn("c", 700L, CategoryKeys.FOOD, at(5)),
            )
        )
        assertEquals(4_200L, result.totalExpenseCents)
        assertEquals(1_700L, result.netFor(CategoryKeys.FOOD))
        assertEquals(2_500L, result.netFor(CategoryKeys.TRANSPORT))
        assertEquals(result.netTotalCents, result.netFor(CategoryKeys.FOOD) + result.netFor(CategoryKeys.TRANSPORT))
        assertEquals(4_200L, result.netTotalCents)
    }

    @Test
    fun `a refund offsets its own category and not the total at large`() {
        val result = aggregate(
            listOf(
                txn("buy-food", 1_000L, CategoryKeys.FOOD, at(3)),
                txn("buy-shop", 5_000L, CategoryKeys.SHOPPING, at(3)),
                txn(
                    "refund", 1_000L, CategoryKeys.REFUND_POOL, at(5),
                    direction = TxDirection.INCOME, excluded = true,
                    reason = ExcludeReason.REFUND, refundOf = "buy-food",
                ),
            )
        )
        // Only 吃饭 was reversed; 购物 must be untouched.
        assertEquals(0L, result.netFor(CategoryKeys.FOOD))
        assertEquals(5_000L, result.netFor(CategoryKeys.SHOPPING))
        assertEquals(5_000L, result.netTotalCents)
    }

    @Test
    fun `a refund whose original is outside the window is ignored`() {
        val may = LocalDateTime.of(2025, 5, 20, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val result = aggregate(
            listOf(
                txn("buy-may", 1_000L, CategoryKeys.FOOD, may),
                txn(
                    "refund", 1_000L, CategoryKeys.REFUND_POOL, at(5),
                    direction = TxDirection.INCOME, excluded = true,
                    reason = ExcludeReason.REFUND, refundOf = "buy-may",
                ),
            )
        )
        assertEquals(0L, result.totalExpenseCents)
        assertEquals(0L, result.netTotalCents)
    }

    @Test
    fun `an unmatched refund does not silently reduce spending`() {
        val result = aggregate(
            listOf(
                txn("buy", 1_000L, CategoryKeys.FOOD, at(3)),
                txn(
                    "refund", 400L, CategoryKeys.REFUND_POOL, at(5),
                    direction = TxDirection.INCOME, excluded = true,
                    reason = ExcludeReason.REFUND, refundOf = null,
                ),
            )
        )
        assertEquals(1_000L, result.netTotalCents)
    }

    @Test
    fun `excluded rows never enter the totals`() {
        val result = aggregate(
            listOf(
                txn("food", 1_000L, CategoryKeys.FOOD, at(3)),
                txn("transfer", 90_000L, CategoryKeys.UNCLASSIFIED, at(3), excluded = true, reason = ExcludeReason.TRANSFER),
                txn("invest", 50_000L, CategoryKeys.UNCLASSIFIED, at(3), excluded = true, reason = ExcludeReason.INVEST),
            )
        )
        assertEquals(1_000L, result.totalExpenseCents)
        assertEquals(1_000L, result.netTotalCents)
    }

    @Test
    fun `income is not spending`() {
        val result = aggregate(
            listOf(
                txn("food", 1_000L, CategoryKeys.FOOD, at(3)),
                txn("salary", 500_000L, CategoryKeys.OTHER, at(3), direction = TxDirection.INCOME),
            )
        )
        assertEquals(1_000L, result.netTotalCents)
    }

    @Test
    fun `rows outside the month are ignored entirely`() {
        val result = aggregate(
            listOf(
                txn("may", 9_999L, CategoryKeys.FOOD, LocalDateTime.of(2025, 5, 31, 23, 0).atZone(zone).toInstant().toEpochMilli()),
                txn("june-first", 100L, CategoryKeys.FOOD, monthStart),
                txn("july-first", 8_888L, CategoryKeys.FOOD, monthEnd),
            )
        )
        assertEquals(100L, result.netTotalCents)
    }

    @Test
    fun `today is a strict window and excludes yesterday`() {
        val result = aggregate(
            listOf(
                txn("today-early", 100L, CategoryKeys.FOOD, at(15, 0)),
                txn("today-late", 200L, CategoryKeys.FOOD, at(15, 23)),
                txn("yesterday", 300L, CategoryKeys.FOOD, at(14, 23)),
                txn("tomorrow", 400L, CategoryKeys.FOOD, at(16, 0)),
            )
        )
        assertEquals(2, result.todayExpenses.size)
        assertEquals(300L, result.spentTodayCents)
        assertEquals(1_000L, result.netTotalCents)
    }

    @Test
    fun `unclassified rows are counted so the triage queue can be surfaced`() {
        val result = aggregate(
            listOf(
                txn("u1", 100L, CategoryKeys.UNCLASSIFIED, at(3)),
                txn("u2", 200L, CategoryKeys.UNCLASSIFIED, at(4), excluded = true),
                txn("k", 300L, CategoryKeys.FOOD, at(5)),
            )
        )
        // The excluded one is not spending, so it is not something to triage either.
        assertEquals(1, result.unclassifiedCount)
    }

    @Test
    fun `an unknown category is never reported`() {
        val result = aggregate(listOf(txn("a", 100L, CategoryKeys.FOOD, at(3))))
        assertEquals(0L, result.netFor(null))
        assertEquals(0L, result.netFor("does-not-exist"))
        assertTrue(result.netByCategory().containsKey(CategoryKeys.FOOD))
    }

    @Test
    fun `the aggregator and the budget calculator agree on the total`() {
        val rows = listOf(
            txn("food", 3_200L, CategoryKeys.FOOD, at(3)),
            txn("shop", 12_000L, CategoryKeys.SHOPPING, at(4)),
            txn(
                "refund", 2_000L, CategoryKeys.REFUND_POOL, at(6),
                direction = TxDirection.INCOME, excluded = true,
                reason = ExcludeReason.REFUND, refundOf = "food",
            ),
            txn("transfer", 50_000L, CategoryKeys.UNCLASSIFIED, at(7), excluded = true, reason = ExcludeReason.TRANSFER),
        )

        val aggregated = aggregate(rows)
        val snapshot = BudgetCalculator.compute(
            BudgetInput(
                period = PeriodLite(java.time.YearMonth.of(2025, 6), 100_000L),
                allocations = emptyList(),
                pockets = emptyList(),
                transactions = rows,
                nowMillis = at(15),
                zone = zone,
            )
        )

        // This is the invariant that keeps the home screen and the statistics page from
        // ever showing two different "spent" numbers.
        assertEquals(aggregated.netTotalCents, snapshot.spentCents)
    }
}
