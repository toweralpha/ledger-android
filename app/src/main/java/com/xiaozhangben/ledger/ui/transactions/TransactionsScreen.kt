package com.xiaozhangben.ledger.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.local.TransactionEntity
import com.xiaozhangben.ledger.data.local.toDirection
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import com.xiaozhangben.ledger.ui.common.AmountText
import com.xiaozhangben.ledger.ui.common.CategoryAvatar
import com.xiaozhangben.ledger.ui.common.CategoryPickerSheet
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.ChoiceChip
import com.xiaozhangben.ledger.ui.common.EmptyState
import com.xiaozhangben.ledger.ui.common.IncomeAmountText
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.IncomeGreen
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Radius
import com.xiaozhangben.ledger.ui.theme.Spacing

@Composable
fun TransactionsScreen(
    onAddTransaction: () -> Unit,
    focusTransactionId: String? = null,
    viewModel: TransactionsViewModel = containerViewModel { TransactionsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searchOpen by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var detailTarget by remember { mutableStateOf<TransactionEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.pageHorizontal,
                end = Spacing.pageHorizontal,
                top = Spacing.gapS,
                bottom = 108.dp,
            ),
        ) {
            item(key = "header") {
                MonthSummary(
                    state = state,
                    searchOpen = searchOpen,
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setQuery("")
                    },
                    onPrevious = viewModel::showPreviousMonth,
                    onNext = viewModel::showNextMonth,
                    onQueryChange = viewModel::setQuery,
                )
                FilterRow(state = state, viewModel = viewModel)
                Spacer(Modifier.height(Spacing.gapS))
            }

            if (state.days.isEmpty()) {
                item(key = "empty") {
                    Spacer(Modifier.height(Spacing.gapXl))
                    EmptyState(
                        icon = Icons.Outlined.ReceiptLong,
                        title = if (state.query.isBlank()) "这个月还没有流水" else "没找到匹配的流水",
                        hint = if (state.query.isBlank()) {
                            "付款时会自动记上；也可以手动记一笔，或导入支付宝/微信账单补齐历史。"
                        } else {
                            "换个关键词，或者清空筛选条件试试。"
                        },
                        actionLabel = if (state.query.isBlank()) "记一笔" else null,
                        onAction = if (state.query.isBlank()) onAddTransaction else null,
                    )
                }
            }

            state.days.forEach { day ->
                item(key = "day-${day.dayStartMillis}") {
                    DayHeader(day)
                }
                items(day.items, key = { it.id }) { row ->
                    SwipeableTransactionRow(
                        row = row,
                        category = state.categories[row.categoryKey],
                        onReclassify = { pickerTarget = row },
                        onDelete = { viewModel.delete(row.id) },
                        onClick = { detailTarget = row },
                    )
                }
            }
        }

        state.lastDeletedId?.let {
            UndoBar(
                onUndo = viewModel::undoDelete,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    pickerTarget?.let { target ->
        CategoryPickerSheet(
            title = "改分类",
            subtitle = target.counterpartyRaw ?: target.productDesc,
            categories = state.categories.values.toList(),
            selectedKey = target.categoryKey,
            learnEnabled = true,
            onPick = { key ->
                viewModel.reclassify(target.id, key, com.xiaozhangben.ledger.domain.model.CategoryKind.VARIABLE, true)
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null },
        )
    }

    detailTarget?.let { target ->
        TransactionDetailSheet(
            row = target,
            category = state.categories[target.categoryKey],
            onDismiss = { detailTarget = null },
            onReclassify = {
                pickerTarget = it
                detailTarget = null
            },
            onToggleExcluded = { reason ->
                viewModel.markExcluded(target.id, reason)
                detailTarget = null
            },
            onDelete = {
                viewModel.delete(target.id)
                detailTarget = null
            },
        )
    }
}

@Composable
private fun MonthSummary(
    state: TransactionsUiState,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(DateX.formatMonthLabel(state.month), style = LedgerType.screenTitle, modifier = Modifier.weight(1f))
            Icon(
                Icons.Outlined.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleSearch)
                    .padding(6.dp)
                    .size(22.dp),
            )
        }

        Spacer(Modifier.height(Spacing.gapS))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapL)) {
            Column {
                Text("支出", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountText(cents = state.monthExpenseCents, style = LedgerType.largeAmount, compact = true)
            }
            Column {
                Text("收入", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Money.formatCompact(state.monthIncomeCents),
                    style = LedgerType.largeAmount,
                    color = IncomeGreen,
                    maxLines = 1,
                )
            }
        }

        if (searchOpen) {
            Spacer(Modifier.height(Spacing.gapM))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜商户、商品或备注", style = LedgerType.caption) },
                singleLine = true,
                textStyle = LedgerType.caption,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FilterRow(state: TransactionsUiState, viewModel: TransactionsViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.gapM),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gapS),
    ) {
        ChoiceChip(label = "全部", selected = state.filter.source == null, onClick = { viewModel.setSourceFilter(null) })
        ChoiceChip(label = "支付宝", selected = state.filter.source == TxSource.ALIPAY, onClick = { viewModel.setSourceFilter(TxSource.ALIPAY) })
        ChoiceChip(label = "微信", selected = state.filter.source == TxSource.WECHAT, onClick = { viewModel.setSourceFilter(TxSource.WECHAT) })
        ChoiceChip(label = "手动", selected = state.filter.source == TxSource.MANUAL, onClick = { viewModel.setSourceFilter(TxSource.MANUAL) })
        ChoiceChip(
            label = "待归类",
            selected = state.filter.onlyUnclassified,
            onClick = { viewModel.setOnlyUnclassified(!state.filter.onlyUnclassified) },
        )
    }
}

@Composable
private fun DayHeader(day: DayGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.gapL, bottom = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            DateX.formatDayLabel(day.dayStartMillis),
            style = LedgerType.caption.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.weight(1f),
        )
        if (day.expenseCents > 0L) {
            Text(
                "支出 ${Money.formatCompact(day.expenseCents)}",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (day.incomeCents > 0L) {
            Spacer(Modifier.width(Spacing.gapS))
            Text("收入 ${Money.formatCompact(day.incomeCents)}", style = LedgerType.caption, color = IncomeGreen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTransactionRow(
    row: TransactionEntity,
    category: CategoryEntity?,
    onReclassify: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onReclassify()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.m))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = Spacing.gapL),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("改分类 →", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("← 删除", style = LedgerType.caption, color = DangerRed)
            }
        },
    ) {
        TransactionRow(row = row, category = category, onClick = onClick)
    }
}

@Composable
private fun TransactionRow(
    row: TransactionEntity,
    category: CategoryEntity?,
    onClick: () -> Unit,
) {
    val direction = row.direction.toDirection()
    LedgerCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryAvatar(
                icon = CategoryVisuals.icon(category?.iconKey),
                color = CategoryVisuals.color(category?.colorHex),
            )
            Spacer(Modifier.width(Spacing.gapM))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.counterpartyRaw ?: row.productDesc ?: category?.name ?: "未命名",
                    style = LedgerType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle(row, category?.name),
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.gapS))
            if (direction == TxDirection.INCOME) {
                IncomeAmountText(row.amountCents)
            } else {
                AmountText(
                    cents = row.amountCents,
                    color = if (row.excluded) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
            }
        }
    }
}

private fun subtitle(row: TransactionEntity, categoryName: String?): String {
    val parts = mutableListOf<String>()
    if (row.excluded) {
        parts += row.excludeReason?.let { runCatching { ExcludeReason.valueOf(it) }.getOrNull()?.label } ?: "不计入"
    } else if (row.categoryKey == CategoryKeys.UNCLASSIFIED) {
        parts += "待归类"
    } else {
        parts += categoryName ?: row.categoryKey
    }
    parts += TxSource.valueOf(row.source).label
    parts += DateX.formatTime(row.occurredAt)
    return parts.joinToString(" · ")
}

@Composable
private fun UndoBar(onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(horizontal = Spacing.pageHorizontal, vertical = Spacing.gapL)
            .clip(RoundedCornerShape(Radius.m))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = Spacing.gapL, vertical = Spacing.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "已删除",
            style = LedgerType.caption,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            "撤销",
            style = LedgerType.caption.copy(color = MaterialTheme.colorScheme.inversePrimary),
            modifier = Modifier
                .clickable(onClick = onUndo)
                .padding(4.dp),
        )
    }
}
