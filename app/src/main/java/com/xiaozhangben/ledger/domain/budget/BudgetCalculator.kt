package com.xiaozhangben.ledger.domain.budget

import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import java.time.YearMonth
import java.time.ZoneId

/**
 * All budget arithmetic lives here, in one pure function that takes the clock as an
 * argument rather than reading it.
 *
 * The decisions that make the numbers trustworthy:
 *
 *  1. Only `EXPENSE` rows that are not excluded count as spending. Transfers,
 *     red-packet receipts, Yu'ebao moves and card repayments are already excluded
 *     upstream — counting any of them makes "已用" permanently wrong.
 *  2. A refund is **subtracted from the category it reverses**, never added as
 *     income. And only when the original purchase falls in the same month: a refund
 *     of last month's order must not make this month look richer.
 *  3. Fixed commitments that have not been paid yet are held back from the daily
 *     allowance, so "today you may spend 152" is not a lie before rent is due.
 *  4. Rows the engine could not classify are reported as a count, because a budget
 *     is only as correct as the classification behind it.
 */
object BudgetCalculator {

    fun compute(input: BudgetInput): BudgetSnapshot {
        val zone: ZoneId = input.zone
        val period = input.period
        val yearMonth: YearMonth = period?.yearMonth ?: DateX.yearMonthOf(input.nowMillis, zone)

        val carryIn = if (period?.carryOverEnabled == true) period.carryInCents else 0L
        val total = (period?.totalAmountCents ?: 0L) + carryIn

        val monthStart = DateX.startOfMonthMillis(yearMonth, zone)
        val monthEnd = DateX.endOfMonthExclusiveMillis(yearMonth, zone)
        val dayStart = DateX.startOfTodayMillis(input.nowMillis, zone)
        val dayEnd = DateX.endOfTodayExclusiveMillis(input.nowMillis, zone)

        // Spending and refund offsetting come from the shared aggregator so the home
        // screen and the statistics page can never report different totals.
        val aggregated = SpendAggregator.aggregate(
            transactions = input.transactions,
            monthStart = monthStart,
            monthEndExclusive = monthEnd,
            dayStart = dayStart,
            dayEndExclusive = dayEnd,
        )

        val spent = aggregated.netTotalCents
        val remaining = total - spent
        val unclassifiedCount = aggregated.unclassifiedCount
        val todayExpenses = aggregated.todayExpenses

        fun netForCategory(categoryKey: String?): Long = aggregated.netFor(categoryKey)

        // ---- 3. named slices of the monthly total --------------------------------
        val buckets = input.allocations
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .map { a ->
                BudgetBucket(
                    id = a.id,
                    name = a.name,
                    kind = a.kind,
                    budgetCents = a.amountCents,
                    spentCents = netForCategory(a.categoryKey),
                    categoryKey = a.categoryKey,
                )
            }

        val allocatedTotal = input.allocations.sumOf { it.amountCents }
        val unallocated = total - allocatedTotal

        // Fixed commitments not yet consumed are reserved, not spendable today.
        val fixedUnpaid = input.allocations
            .filter { it.kind == CategoryKind.FIXED }
            .sumOf { a -> (a.amountCents - netForCategory(a.categoryKey)).coerceAtLeast(0L) }

        val remainingDays = DateX.remainingDaysInMonth(input.nowMillis, zone)
        val spendable = (remaining - fixedUnpaid).coerceAtLeast(0L)
        val dailyAllowance = spendable / remainingDays

        // ---- 4. named slices of the daily allowance -----------------------------
        val weekdayMon1 = DateX.toLocalDate(input.nowMillis, zone).dayOfWeek.value
        val activePockets = input.pockets
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .filter { it.activeOn(weekdayMon1) }

        // Each of today's expenses lands in at most one slot. Anything the rules cannot
        // place unambiguously is reported as unassigned and shown to the user — for a
        // personal ledger, "I don't know if this was lunch or dinner" is a better answer
        // than silently filing it under whichever meal happens to sort first.
        val spentByPocket = HashMap<String, Long>()
        val orderedSlots = input.pockets.sortedWith(compareBy({ it.sortOrder }, { it.name }))
        var unassignedTodayCount = 0
        var unassignedTodayCents = 0L

        val spentToday = aggregated.spentTodayCents
        for (t in todayExpenses) {
            val target = DailySlotAssigner.assign(t, orderedSlots, zone)
            if (target != null) {
                spentByPocket.merge(target, t.amountCents, Long::plus)
            } else {
                unassignedTodayCount++
                unassignedTodayCents += t.amountCents
            }
        }

        val pocketStatuses = activePockets.map { p ->
            PocketStatus(
                id = p.id,
                name = p.name,
                dailyAmountCents = p.dailyAmountCents,
                spentTodayCents = spentByPocket[p.id] ?: 0L,
                activeToday = true,
                categoryKey = p.categoryKey,
            )
        }

        val pocketsTotal = activePockets.sumOf { it.dailyAmountCents }
        val dailyQuota = if (pocketStatuses.isNotEmpty()) pocketsTotal else dailyAllowance

        return BudgetSnapshot(
            yearMonth = yearMonth,
            totalCents = total,
            carryInCents = carryIn,
            spentCents = spent,
            remainingCents = remaining,
            remainingDays = remainingDays,
            dailyAllowanceCents = dailyAllowance,
            spentTodayCents = spentToday,
            dailyRemainingCents = dailyQuota - spentToday,
            fixedUnpaidCents = fixedUnpaid,
            buckets = buckets,
            pockets = pocketStatuses,
            pocketsTotalCents = pocketsTotal,
            pocketsMismatch = pocketStatuses.isNotEmpty() && pocketsTotal != dailyAllowance,
            unassignedTodayCount = unassignedTodayCount,
            unassignedTodayCents = unassignedTodayCents,
            unallocatedCents = unallocated,
            unclassifiedCount = unclassifiedCount,
            alert = BudgetBucket.levelFor(
                if (total <= 0L) 0f else spent.toFloat() / total.toFloat()
            ),
        )
    }
}
