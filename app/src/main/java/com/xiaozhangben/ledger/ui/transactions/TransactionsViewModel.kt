package com.xiaozhangben.ledger.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.local.TransactionEntity
import com.xiaozhangben.ledger.data.local.toDirection
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

data class DayGroup(
    val dayStartMillis: Long,
    val items: List<TransactionEntity>,
    val expenseCents: Long,
    val incomeCents: Long,
)

data class TransactionFilter(
    val source: TxSource? = null,
    val onlyUnclassified: Boolean = false,
    val includeExcluded: Boolean = true,
)

data class TransactionsUiState(
    val month: YearMonth,
    val days: List<DayGroup> = emptyList(),
    val categories: Map<String, CategoryEntity> = emptyMap(),
    val query: String = "",
    val filter: TransactionFilter = TransactionFilter(),
    val monthExpenseCents: Long = 0L,
    val monthIncomeCents: Long = 0L,
    val hiddenByFilter: Int = 0,
    val lastDeletedId: String? = null,
    val loading: Boolean = true,
)

/**
 * Transaction list for one month.
 *
 * Filtering happens in memory over the month's rows. A personal ledger is thousands of
 * rows, not millions, and keeping the filter logic in Kotlin means the day grouping,
 * the keyword match and the "excluded" rules all agree with the budget calculator
 * instead of being re-derived in SQL.
 */
class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    private val month = MutableStateFlow(DateX.yearMonthOf(System.currentTimeMillis()))
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(TransactionFilter())
    private val lastDeleted = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TransactionsUiState> = combine(
        month.flatMapLatest { ym ->
            val (from, to) = container.ledger.monthRange(ym)
            container.ledger.observeInRange(from, to)
        },
        container.ledger.observeCategories(),
        combine(query, filter, lastDeleted) { q, f, d -> Triple(q, f, d) },
    ) { rows, categories, (q, f, deleted) ->
        buildState(month.value, rows, categories, q, f, deleted)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState(month = month.value),
    )

    private fun buildState(
        yearMonth: YearMonth,
        rows: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        query: String,
        filter: TransactionFilter,
        lastDeletedId: String?,
    ): TransactionsUiState {
        val needle = query.trim().lowercase()

        val matched = rows.filter { row ->
            if (filter.source != null && row.source != filter.source.name) return@filter false
            if (filter.onlyUnclassified && row.categoryKey != com.xiaozhangben.ledger.domain.model.CategoryKeys.UNCLASSIFIED) {
                return@filter false
            }
            if (!filter.includeExcluded && row.excluded) return@filter false
            if (needle.isEmpty()) return@filter true
            listOfNotNull(
                row.counterpartyRaw,
                row.counterparty,
                row.productDesc,
                row.note,
                row.remark,
                row.rawType,
            ).any { it.lowercase().contains(needle) }
        }

        // Totals always describe the month, not the filtered slice, so the header does
        // not appear to change meaning when a filter is toggled.
        val monthExpense = rows.filter { it.direction.toDirection() == TxDirection.EXPENSE && !it.excluded }
            .sumOf { it.amountCents }
        val monthIncome = rows.filter { it.direction.toDirection() == TxDirection.INCOME && !it.excluded }
            .sumOf { it.amountCents }

        val days = matched
            .groupBy { DateX.startOfTodayMillis(it.occurredAt) }
            .toSortedMap(reverseOrder())
            .map { (dayStart, items) ->
                val sorted = items.sortedByDescending { it.occurredAt }
                DayGroup(
                    dayStartMillis = dayStart,
                    items = sorted,
                    expenseCents = sorted
                        .filter { it.direction.toDirection() == TxDirection.EXPENSE && !it.excluded }
                        .sumOf { it.amountCents },
                    incomeCents = sorted
                        .filter { it.direction.toDirection() == TxDirection.INCOME && !it.excluded }
                        .sumOf { it.amountCents },
                )
            }

        return TransactionsUiState(
            month = yearMonth,
            days = days,
            categories = categories.associateBy { it.key },
            query = query,
            filter = filter,
            monthExpenseCents = monthExpense,
            monthIncomeCents = monthIncome,
            hiddenByFilter = rows.size - matched.size,
            lastDeletedId = lastDeletedId,
            loading = false,
        )
    }

    fun setQuery(value: String) = query.update { value }

    fun setSourceFilter(source: TxSource?) = filter.update { it.copy(source = source) }

    fun setOnlyUnclassified(enabled: Boolean) = filter.update { it.copy(onlyUnclassified = enabled) }

    fun setIncludeExcluded(enabled: Boolean) = filter.update { it.copy(includeExcluded = enabled) }

    fun showPreviousMonth() = month.update { it.minusMonths(1) }

    fun showNextMonth() = month.update { it.plusMonths(1) }

    fun showCurrentMonth() {
        month.value = DateX.yearMonthOf(System.currentTimeMillis())
    }

    /**
     * Applies a corrected category, and by default records a rule for the merchant.
     * That single behaviour is what makes the classification improve with use.
     */
    fun reclassify(id: String, categoryKey: String, kind: CategoryKind, learn: Boolean) {
        viewModelScope.launch {
            container.ledger.reclassify(id = id, categoryKey = categoryKey, kind = kind, learn = learn)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            container.ledger.softDelete(id)
            lastDeleted.value = id
        }
    }

    fun undoDelete() {
        val id = lastDeleted.value ?: return
        viewModelScope.launch {
            container.ledger.restore(id)
            lastDeleted.value = null
        }
    }

    fun clearUndo() {
        lastDeleted.value = null
    }

    fun markExcluded(id: String, reason: ExcludeReason?) {
        viewModelScope.launch {
            val row = container.ledger.findById(id) ?: return@launch
            container.ledger.reclassify(
                id = id,
                categoryKey = row.categoryKey,
                kind = runCatching { CategoryKind.valueOf(row.kind) }.getOrDefault(CategoryKind.VARIABLE),
                excluded = reason != null,
                excludeReason = reason,
                learn = false,
            )
        }
    }
}
