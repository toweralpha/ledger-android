package com.xiaozhangben.ledger.ui.daily

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.data.local.TransactionEntity
import com.xiaozhangben.ledger.domain.budget.PocketLite
import com.xiaozhangben.ledger.domain.model.TxSource
import com.xiaozhangben.ledger.ui.common.AlertStrip
import com.xiaozhangben.ledger.ui.common.AmountText
import com.xiaozhangben.ledger.ui.common.BudgetProgressBar
import com.xiaozhangben.ledger.ui.common.EmptyState
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.common.SecondaryButton
import com.xiaozhangben.ledger.ui.common.bandColor
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Radius
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * 「每日」— one day at a time, spending broken into the user's own slots (早饭/午饭/晚饭…).
 *
 * The unassigned block is the point of this screen. A payment notification says
 * "已支付¥15.00" and nothing about whether that was lunch or dinner, so the app places
 * what it can by time window and then asks about the rest, instead of quietly filing
 * everything under whichever meal sorts first.
 */
@Composable
fun DailyScreen(
    onAddForSlot: (slotId: String, categoryKey: String?) -> Unit,
    onOpenPlan: () -> Unit,
    viewModel: DailyViewModel = containerViewModel { DailyViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var reassigning by remember { mutableStateOf<TransactionEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        DayHeader(
            label = DateX.formatDayLabel(state.dayStartMillis).let { day ->
                DateX.formatDate(state.dayStartMillis) + " " + DateX.weekdayLabel(state.dayStartMillis) +
                    if (day == "今天" || day == "昨天") " · $day" else ""
            },
            isToday = state.isToday,
            onPrevious = viewModel::showPreviousDay,
            onNext = viewModel::showNextDay,
            onToday = viewModel::showToday,
        )

        DayTotalCard(state = state, onOpenPlan = onOpenPlan)

        if (state.unassigned.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.gapM))
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.gapS))
                        Text(
                            "${state.unassigned.size} 笔还没分到哪一餐",
                            style = LedgerType.body,
                            modifier = Modifier.weight(1f),
                        )
                        AmountText(
                            cents = state.unassignedCents,
                            style = LedgerType.captionAmount,
                            compact = true,
                        )
                    }
                    Spacer(Modifier.height(Spacing.gapXs))
                    Text(
                        "点一下就能归到早饭 / 午饭 / 晚饭。归过之后这一笔就固定下来，不会再被自动规则改动。",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.gapS))
                    state.unassigned.forEach { txn ->
                        UnassignedRow(txn = txn, onClick = { reassigning = txn })
                    }
                }
            }
        }

        if (!state.slotsConfigured && state.loading.not()) {
            Spacer(Modifier.height(Spacing.gapL))
            EmptyState(
                icon = Icons.Outlined.Tune,
                title = "还没有设置每日类别",
                hint = "先建几个类别，例如 早饭 / 午饭 / 晚饭，每项设一个每天金额和时间段。\n" +
                    "付款时间落在时间段里的流水就会自动算进对应的类别。",
                actionLabel = "去设置每日类别",
                onAction = onOpenPlan,
            )
        } else {
            state.sections.forEach { section ->
                Spacer(Modifier.height(Spacing.gapM))
                SlotCard(
                    section = section,
                    onAdd = { onAddForSlot(section.slot.id, section.slot.categoryKey) },
                    onReassign = { reassigning = it },
                )
            }
        }

        if (state.notCountedCount > 0) {
            Spacer(Modifier.height(Spacing.gapM))
            Text(
                "另有 ${state.notCountedCount} 笔不计入支出（转账、退款或收入）",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.gapXs),
            )
        }

        Spacer(Modifier.height(96.dp))
    }

    reassigning?.let { txn ->
        SlotPickerSheet(
            subtitle = txn.counterpartyRaw ?: txn.productDesc ?: Money.format(txn.amountCents),
            slots = state.sections.map { it.slot },
            selectedId = null,
            allowClear = false,
            onPick = { slotId ->
                viewModel.assign(txn.id, slotId)
                reassigning = null
            },
            onDismiss = { reassigning = null },
        )
    }
}

@Composable
private fun DayHeader(
    label: String,
    isToday: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LedgerType.screenTitle, modifier = Modifier.weight(1f))
        if (!isToday) {
            Text(
                "回到今天",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.s))
                    .clickable(onClick = onToday)
                    .padding(horizontal = Spacing.gapS, vertical = 6.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = "前一天",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onPrevious).padding(4.dp).size(24.dp),
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "后一天",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onNext).padding(4.dp).size(24.dp),
        )
    }
}

@Composable
private fun DayTotalCard(state: DailyUiState, onOpenPlan: () -> Unit) {
    val budget = state.sections.sumOf { it.slot.dailyAmountCents }
    val spent = state.sections.sumOf { it.spentCents }

    LedgerCard(Modifier.fillMaxWidth()) {
        // LedgerCard is a Box, so several children would overlap: everything must sit
        // inside one Column.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("当天合计", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.gapXs))
                    AmountText(state.dayTotalCents, style = LedgerType.heroAmount, compact = true)
                }
                if (budget > 0L) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "各类别合计",
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        AmountText(budget, style = LedgerType.largeAmount, compact = true)
                    }
                }
            }
            if (state.sections.isNotEmpty() && budget > 0L) {
                Spacer(Modifier.height(Spacing.gapM))
                BudgetProgressBar(
                    ratio = spent.toFloat() / budget.toFloat(),
                    band = com.xiaozhangben.ledger.domain.budget.BudgetBucket.levelFor(
                        spent.toFloat() / budget.toFloat()
                    ),
                )
            }
            Spacer(Modifier.height(Spacing.gapM))
            SecondaryButton(text = "调整每日类别", icon = Icons.Outlined.Tune, onClick = onOpenPlan)
        }
    }
}

@Composable
private fun SlotCard(
    section: DailySlotSection,
    onAdd: () -> Unit,
    onReassign: (TransactionEntity) -> Unit,
) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(section.slot.name, style = LedgerType.sectionTitle, modifier = Modifier.weight(1f))
                // Labelled "已用" so it cannot be misread as the 首页 row, which shows the
                // remaining amount in the same "X / Y" shape.
                Text("已用 ", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AmountText(section.spentCents, style = LedgerType.captionAmount, compact = true)
                Spacer(Modifier.width(Spacing.gapXs))
                Text(
                    "/ ${Money.formatCompact(section.slot.dailyAmountCents)}",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.gapS))
            BudgetProgressBar(ratio = section.ratio, band = section.alert, thickness = 5.dp)
            Spacer(Modifier.height(Spacing.gapXs))
            Text(
                text = if (section.remainingCents >= 0L) {
                    "还剩 ${Money.formatCompact(section.remainingCents)}"
                } else {
                    "超出 ${Money.formatCompact(section.overspentCents)}"
                },
                style = LedgerType.caption,
                color = if (section.remainingCents < 0L) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (section.items.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.gapS))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                section.items.forEach { txn ->
                    SlotItemRow(txn = txn, onClick = { onReassign(txn) })
                }
            }

            Spacer(Modifier.height(Spacing.gapM))
            SecondaryButton(text = "记一笔", icon = Icons.Outlined.Add, onClick = onAdd)
        }
    }
}

@Composable
private fun SlotItemRow(txn: TransactionEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                txn.counterpartyRaw ?: txn.productDesc ?: "未命名",
                style = LedgerType.body,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                DateX.formatTime(txn.occurredAt) + " · " + TxSource.valueOf(txn.source).label,
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AmountText(txn.amountCents, style = LedgerType.captionAmount)
        Spacer(Modifier.width(Spacing.gapS))
        Icon(
            Icons.Outlined.Edit,
            contentDescription = "改到别的类别",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun UnassignedRow(txn: TransactionEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(txn.counterpartyRaw ?: txn.productDesc ?: "未命名", style = LedgerType.body, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                DateX.formatTime(txn.occurredAt) + " · " + TxSource.valueOf(txn.source).label,
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AmountText(txn.amountCents, style = LedgerType.captionAmount)
        Spacer(Modifier.width(Spacing.gapS))
        Text("归到…", style = LedgerType.caption, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotPickerSheet(
    subtitle: String,
    slots: List<PocketLite>,
    selectedId: String?,
    allowClear: Boolean,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.pageHorizontal)) {
            Text("归到哪一餐", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapXs))
            Text(subtitle, style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapL))

            slots.forEach { slot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.s))
                        .clickable { onPick(slot.id) }
                        .padding(vertical = Spacing.gapM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.name, style = LedgerType.body)
                        if (slot.startMinute != null && slot.endMinute != null) {
                            Text(
                                DateX.formatMinuteOfDay(slot.startMinute) + " — " +
                                    DateX.formatMinuteOfDay(slot.endMinute),
                                style = LedgerType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (slot.id == selectedId) {
                        Text("当前", style = LedgerType.caption, color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (allowClear) {
                Spacer(Modifier.height(Spacing.gapM))
                SecondaryButton(text = "不分到任何一餐", onClick = { onPick(null) })
            }

            Spacer(Modifier.height(Spacing.gapXl))
        }
    }
}
