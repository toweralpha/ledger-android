package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection

/**
 * The single definition of "how much was spent, by category, this month".
 *
 * Both the home screen's budget maths and the statistics page go through this, so the
 * two can never disagree about a total — which is exactly the kind of inconsistency
 * that makes a user stop trusting a ledger.
 *
 * Two rules are encoded here and nowhere else:
 *
 *  1. An excluded row is never spending, regardless of its amount. Transfers, red
 *     packets received, Yu'ebao moves and card repayments are already excluded
 *     upstream, but a manual "不计入预算" toggle lands here too.
 *  2. A refund is subtracted from the category it reverses, and only when the
 *     original purchase is inside the same window. A refund of last month's order
 *     must not make this month look richer.
 */
object SpendAggregator {

    data class Aggregated(
        val grossByCategory: Map<String, Long>,
        val refundByCategory: Map<String, Long>,
        val totalExpenseCents: Long,
        val totalRefundOffsetCents: Long,
        val unclassifiedCount: Int,
        val todayExpenses: List<TxnLite>,
    ) {
        /**
         * Net spending for one category, never negative.
         *
         * Clamping is safe because a refund that survived the same-window check can
         * only reverse a purchase of the same or a larger amount in the same category.
         */
        fun netFor(categoryKey: String?): Long {
            if (categoryKey == null) return 0L
            val gross = grossByCategory[categoryKey] ?: 0L
            val refunded = refundByCategory[categoryKey] ?: 0L
            return (gross - refunded).coerceAtLeast(0L)
        }

        /** Sum of the per-category nets, so the parts always add up to the whole. */
        val netTotalCents: Long
            get() = grossByCategory.keys.sumOf { netFor(it) }

        val spentTodayCents: Long get() = todayExpenses.sumOf { it.amountCents }

        fun netByCategory(): Map<String, Long> =
            grossByCategory.keys.associateWith { netFor(it) }.filterValues { it > 0L }
    }

    fun aggregate(
        transactions: List<TxnLite>,
        monthStart: Long,
        monthEndExclusive: Long,
        dayStart: Long,
        dayEndExclusive: Long,
    ): Aggregated {
        val byId = transactions.associateBy { it.id }
        val gross = HashMap<String, Long>()
        val refunds = HashMap<String, Long>()
        val today = ArrayList<TxnLite>()
        var totalExpense = 0L
        var totalRefund = 0L
        var unclassified = 0

        for (txn in transactions) {
            if (txn.occurredAt < monthStart || txn.occurredAt >= monthEndExclusive) continue

            if (txn.direction == TxDirection.EXPENSE && !txn.excluded) {
                totalExpense += txn.amountCents
                gross.merge(txn.categoryKey, txn.amountCents, Long::plus)
                if (txn.categoryKey == CategoryKeys.UNCLASSIFIED) unclassified++
                if (txn.occurredAt in dayStart until dayEndExclusive) today += txn
            }

            if (txn.excludeReason == ExcludeReason.REFUND) {
                val original = txn.refundOfTxnId?.let { byId[it] } ?: continue
                if (original.direction != TxDirection.EXPENSE) continue
                // Same window only: the refund must belong to this month's spending.
                if (original.occurredAt < monthStart || original.occurredAt >= monthEndExclusive) continue
                refunds.merge(original.categoryKey, txn.amountCents, Long::plus)
                totalRefund += txn.amountCents
            }
        }

        return Aggregated(
            grossByCategory = gross,
            refundByCategory = refunds,
            totalExpenseCents = totalExpense,
            totalRefundOffsetCents = totalRefund,
            unclassifiedCount = unclassified,
            todayExpenses = today,
        )
    }
}
