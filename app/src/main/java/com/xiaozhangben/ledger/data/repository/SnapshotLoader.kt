package com.xiaozhangben.ledger.data.repository

import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.toLite
import com.xiaozhangben.ledger.domain.budget.BudgetCalculator
import com.xiaozhangben.ledger.domain.budget.BudgetInput
import com.xiaozhangben.ledger.domain.budget.BudgetSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.YearMonth

/**
 * Joins the two halves of the picture — what the user planned and what actually
 * happened — into the single [BudgetSnapshot] every screen renders.
 *
 * Both halves are Room `Flow`s, which is what makes the home screen update by itself
 * a moment after a payment is captured, with no refresh and no polling.
 */
class SnapshotLoader(
    private val budgets: BudgetRepository,
    private val ledger: LedgerRepository,
) {

    /** One-shot load, used by the widget and by the periodic worker. */
    suspend fun load(nowMillis: Long = System.currentTimeMillis()): BudgetSnapshot {
        val yearMonth = DateX.yearMonthOf(nowMillis)
        val plan = budgets.loadMonthPlan(yearMonth)
        val (from, to) = ledger.monthRange(yearMonth)
        val transactions = ledger.loadInRange(from, to).map { it.toLite() }
        return BudgetCalculator.compute(
            BudgetInput(
                period = plan.period,
                allocations = plan.allocations,
                pockets = plan.pockets,
                transactions = transactions,
                nowMillis = nowMillis,
            )
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        yearMonth: YearMonth,
        nowProvider: () -> Long = { System.currentTimeMillis() },
    ): Flow<BudgetSnapshot> {
        val (from, to) = ledger.monthRange(yearMonth)
        return budgets.observeMonthPlan(yearMonth).flatMapLatest { plan ->
            ledger.observeInRange(from, to).map { transactions ->
                BudgetCalculator.compute(
                    BudgetInput(
                        period = plan.period,
                        allocations = plan.allocations,
                        pockets = plan.pockets,
                        transactions = transactions.map { it.toLite() },
                        nowMillis = nowProvider(),
                    )
                )
            }
        }
    }
}
