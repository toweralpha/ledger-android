package com.xiaozhangben.ledger.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.toDirection
import com.xiaozhangben.ledger.data.local.toLite
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.budget.SpendAggregator
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.TxDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.YearMonth

data class CategoryShare(
    val categoryKey: String,
    val name: String,
    val iconKey: String?,
    val colorHex: String?,
    val amountCents: Long,
    val ratio: Float,
    val previousCents: Long,
) {
    val deltaCents: Long get() = amountCents - previousCents
}

data class MonthPoint(val month: YearMonth, val expenseCents: Long)

data class StatsUiState(
    val month: YearMonth,
    val totalExpenseCents: Long = 0L,
    val previousTotalCents: Long = 0L,
    val fixedCents: Long = 0L,
    val variableCents: Long = 0L,
    val shares: List<CategoryShare> = emptyList(),
    val trend: List<MonthPoint> = emptyList(),
    val sources: Map<String, Long> = emptyMap(),
    val loading: Boolean = true,
) {
    val hasData: Boolean get() = totalExpenseCents > 0L

    val changeRatio: Float?
        get() = if (previousTotalCents <= 0L) null
        else (totalExpenseCents - previousTotalCents).toFloat() / previousTotalCents.toFloat()

    /** Biggest swings first — "外卖比上月多了 300" is the interesting fact, not the biggest total. */
    val topMovers: List<CategoryShare>
        get() = shares
            .filter { it.deltaCents != 0L }
            .sortedByDescending { kotlin.math.abs(it.deltaCents) }
            .take(4)
}

/**
 * Statistics for a month plus a short history.
 *
 * Every total comes from [SpendAggregator], the same component the budget calculator
 * uses, so this page can never disagree with the home screen about how much was spent.
 * Aggregating in Kotlin rather than SQL is what makes that sharing possible.
 */
class StatsViewModel(private val container: AppContainer) : ViewModel() {

    private val month = MutableStateFlow(DateX.yearMonthOf(System.currentTimeMillis()))
    private val _state = MutableStateFlow(StatsUiState(month = month.value))
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            month.flatMapLatest { ym ->
                val (from, to) = container.ledger.monthRange(ym)
                // Re-emits whenever this month's rows change, so the page stays live.
                container.ledger.observeInRange(from, to).map { ym }
            }.collectLatest { ym ->
                runCatching { compute(ym) }
                    .onSuccess { _state.value = it }
            }
        }
    }

    fun showPreviousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun showNextMonth() {
        month.value = month.value.plusMonths(1)
    }

    private suspend fun compute(yearMonth: YearMonth): StatsUiState {
        val categories = container.ledger.loadCategories().associateBy { it.key }
        val current = aggregateMonth(yearMonth)
        val previous = aggregateMonth(yearMonth.minusMonths(1))

        val currentNet = current.netByCategory
        val previousNet = previous.netByCategory
        val total = current.netTotalCents

        val shares = currentNet
            .map { (key, amount) ->
                CategoryShare(
                    categoryKey = key,
                    name = categories[key]?.name ?: labelFor(key),
                    iconKey = categories[key]?.iconKey,
                    colorHex = categories[key]?.colorHex,
                    amountCents = amount,
                    ratio = if (total <= 0L) 0f else amount.toFloat() / total.toFloat(),
                    previousCents = previousNet[key] ?: 0L,
                )
            }
            .sortedByDescending { it.amountCents }

        // Fixed costs are those whose rows were classified as belonging to a fixed
        // slice; the classification itself comes from the plan, not from this screen.
        val fixedKeys = current.fixedCategoryKeys
        val fixedCents = currentNet.filterKeys { it in fixedKeys }.values.sum()

        val trend = (TREND_MONTHS - 1 downTo 0).map { back ->
            val ym = yearMonth.minusMonths(back.toLong())
            MonthPoint(ym, if (back == 0) total else aggregateMonth(ym).netTotalCents)
        }

        return StatsUiState(
            month = yearMonth,
            totalExpenseCents = total,
            previousTotalCents = previous.netTotalCents,
            fixedCents = fixedCents,
            variableCents = (total - fixedCents).coerceAtLeast(0L),
            shares = shares,
            trend = trend,
            sources = current.sourceTotals,
            loading = false,
        )
    }

    private suspend fun aggregateMonth(yearMonth: YearMonth): MonthStats {
        val (from, to) = container.ledger.monthRange(yearMonth)
        val rows = container.ledger.loadInRange(from, to)
        val aggregated = SpendAggregator.aggregate(
            transactions = rows.map { it.toLite() },
            monthStart = from,
            monthEndExclusive = to,
            dayStart = from,
            dayEndExclusive = from,
        )
        return MonthStats(
            netByCategory = aggregated.netByCategory(),
            netTotalCents = aggregated.netTotalCents,
            fixedCategoryKeys = rows
                .filter { it.kind == CategoryKind.FIXED.name }
                .map { it.categoryKey }
                .toSet(),
            sourceTotals = rows
                .filter { it.direction.toDirection() == TxDirection.EXPENSE && !it.excluded }
                .groupBy { it.source }
                .mapValues { (_, group) -> group.sumOf { it.amountCents } },
        )
    }

    private data class MonthStats(
        val netByCategory: Map<String, Long>,
        val netTotalCents: Long,
        val fixedCategoryKeys: Set<String>,
        val sourceTotals: Map<String, Long>,
    )

    private fun labelFor(key: String): String = when (key) {
        CategoryKeys.UNCLASSIFIED -> "待归类"
        CategoryKeys.REFUND_POOL -> "退款"
        else -> key
    }

    companion object {
        const val TREND_MONTHS = 6
    }
}
