package com.xiaozhangben.ledger.ui.plan

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.domain.budget.AllocationLite
import com.xiaozhangben.ledger.domain.budget.PocketLite
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.ui.common.AlertStrip
import com.xiaozhangben.ledger.ui.common.AmountText
import com.xiaozhangben.ledger.ui.common.CategoryAvatar
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.ConfirmDialog
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.common.SecondaryButton
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.bandColor
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing
import com.xiaozhangben.ledger.ui.common.AmountInputDialog

/**
 * The plan editor.
 *
 * Two ideas are surfaced deliberately, because both are easy to get wrong and both
 * silently break the numbers: the total must be fully allocated, and a slice with no
 * bound category can never be measured.
 */
@Composable
fun PlanScreen(
    viewModel: PlanViewModel = containerViewModel { PlanViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showTotalDialog by remember { mutableStateOf(false) }
    var editingAllocation by remember { mutableStateOf<AllocationLite?>(null) }
    var addingAllocation by remember { mutableStateOf(false) }
    var editingPocket by remember { mutableStateOf<PocketLite?>(null) }
    var addingPocket by remember { mutableStateOf(false) }
    var pendingDeleteAllocation by remember { mutableStateOf<AllocationLite?>(null) }
    var pendingDeletePocket by remember { mutableStateOf<PocketLite?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.gapS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(DateX.formatMonthLabel(state.month), style = LedgerType.screenTitle, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = "上个月",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable(onClick = viewModel::showPreviousMonth).padding(4.dp).size(24.dp),
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "下个月",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable(onClick = viewModel::showNextMonth).padding(4.dp).size(24.dp),
            )
        }

        // ---- total ---------------------------------------------------------------
        LedgerCard(modifier = Modifier.fillMaxWidth(), onClick = { showTotalDialog = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本月总钱量", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.gapXs))
                    AmountText(
                        cents = state.period?.totalAmountCents ?: 0L,
                        style = LedgerType.heroAmount,
                        compact = true,
                    )
                    Spacer(Modifier.height(Spacing.gapXs))
                    Text(
                        if (state.hasPlan) {
                            "已分配 ${Money.formatCompact(state.allocatedTotalCents)}"
                        } else {
                            "点这里填入这个月能花的钱"
                        },
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state.hasPlan && state.unallocatedCents != 0L) {
            Spacer(Modifier.height(Spacing.gapM))
            AlertStrip(
                text = if (state.unallocatedCents > 0L) {
                    "还有 ${Money.formatCompact(state.unallocatedCents)} 没有分配到任何部分"
                } else {
                    "分项合计超出了总钱量 ${Money.formatCompact(-state.unallocatedCents)}"
                },
                tone = AlertLevel.NEAR,
            )
        }

        if (!state.hasPlan) {
            Spacer(Modifier.height(Spacing.gapM))
            AlertStrip(
                text = "这个月还没有计划。可以直接复制上个月的设置。",
                tone = AlertLevel.OK,
                actionLabel = "复制上月",
                onAction = viewModel::copyFromPreviousMonth,
            )
        }

        // ---- named slices of the total -------------------------------------------
        SectionHeader(
            title = "预算分项（你自己命名）",
            actionLabel = "添加",
            onAction = { addingAllocation = true },
        )
        if (state.allocations.isEmpty()) {
            LedgerCard(Modifier.fillMaxWidth()) {
                Text(
                    "例如「房租 / 吃饭 / 通勤 / 人情」。每一项绑定一个消费分类，" +
                        "自动归类的流水就会算到对应的部分里。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    state.allocations.forEachIndexed { index, allocation ->
                        if (index > 0) Spacer(Modifier.height(Spacing.gapM))
                        AllocationRow(
                            allocation = allocation,
                            spentCents = state.snapshot?.buckets
                                ?.firstOrNull { it.id == allocation.id }?.spentCents,
                            categoryName = state.categories.firstOrNull { it.key == allocation.categoryKey }?.name,
                            categoryIcon = state.categories.firstOrNull { it.key == allocation.categoryKey }?.iconKey,
                            categoryColor = state.categories.firstOrNull { it.key == allocation.categoryKey }?.colorHex,
                            onEdit = { editingAllocation = allocation },
                            onDelete = { pendingDeleteAllocation = allocation },
                        )
                    }
                }
            }
        }

        // ---- named slices of the daily allowance ---------------------------------
        SectionHeader(
            title = "日额度分项（你自己命名）",
            actionLabel = "添加",
            onAction = { addingPocket = true },
        )
        if (state.pockets.isEmpty()) {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "把「今天还能花」再拆成几份，例如 早餐 / 通勤 / 午饭 / 机动。" +
                            "每花一笔，对应的那一份就会减少。",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "合计 ${Money.formatCompact(state.pocketsTotalCents)}",
                            style = LedgerType.body,
                            modifier = Modifier.weight(1f),
                        )
                        val allowance = state.snapshot?.dailyAllowanceCents ?: 0L
                        Text(
                            "日均额度 ${Money.formatCompact(allowance)}",
                            style = LedgerType.caption,
                            color = if (state.pocketsTotalCents > allowance) DangerRed
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.pocketsTotalCents > (state.snapshot?.dailyAllowanceCents ?: 0L)) {
                        Spacer(Modifier.height(Spacing.gapXs))
                        Text(
                            "分项合计超过了实际日均额度，会经常出现「不够花」的情况。",
                            style = LedgerType.caption,
                            color = DangerRed,
                        )
                    }
                    Spacer(Modifier.height(Spacing.gapS))
                    SecondaryButton(
                        text = "按日均额度平均分配",
                        onClick = viewModel::balancePocketsEvenly,
                    )
                    Spacer(Modifier.height(Spacing.gapM))
                    state.pockets.forEachIndexed { index, pocket ->
                        if (index > 0) Spacer(Modifier.height(Spacing.gapM))
                        PocketRow(
                            pocket = pocket,
                            categoryName = state.categories.firstOrNull { it.key == pocket.categoryKey }?.name,
                            onEdit = { editingPocket = pocket },
                            onDelete = { pendingDeletePocket = pocket },
                        )
                    }
                }
            }
        }

        // ---- carry over ----------------------------------------------------------
        SectionHeader(title = "其它")
        LedgerCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("结余结转到下月", style = LedgerType.body)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "开启后，本月没花完的额度会加到下个月的总额里。默认关闭。",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(Spacing.gapS))
                Switch(
                    checked = state.carryOverEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setCarryOver(
                            enabled = enabled,
                            carryInCents = (state.snapshot?.remainingCents ?: 0L).coerceAtLeast(0L),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    if (showTotalDialog) {
        AmountInputDialog(
            title = "本月总钱量",
            initialCents = state.period?.totalAmountCents ?: 0L,
            onConfirm = {
                viewModel.setTotal(it)
                showTotalDialog = false
            },
            onDismiss = { showTotalDialog = false },
            supporting = "这是这个月全部可支配的钱。下面再把它分成几个部分。",
        )
    }

    if (addingAllocation) {
        AllocationEditorSheet(
            existing = null,
            categories = state.categories,
            onSave = { name, amount, kind, categoryKey ->
                viewModel.addAllocation(name, amount, kind, categoryKey)
                addingAllocation = false
            },
            onDismiss = { addingAllocation = false },
        )
    }

    editingAllocation?.let { allocation ->
        AllocationEditorSheet(
            existing = allocation,
            categories = state.categories,
            onSave = { name, amount, kind, categoryKey ->
                viewModel.updateAllocation(allocation.id, name, amount, kind, categoryKey)
                editingAllocation = null
            },
            onDismiss = { editingAllocation = null },
        )
    }

    if (addingPocket) {
        PocketEditorSheet(
            existing = null,
            categories = state.categories,
            dailyAllowanceCents = state.snapshot?.dailyAllowanceCents ?: 0L,
            pocketsTotalExcludingThis = state.pocketsTotalCents,
            onSave = { name, amount, categoryKey, mask, startMinute, endMinute ->
                viewModel.addPocket(name, amount, categoryKey, mask, startMinute, endMinute)
                addingPocket = false
            },
            onDismiss = { addingPocket = false },
        )
    }

    editingPocket?.let { pocket ->
        PocketEditorSheet(
            existing = pocket,
            categories = state.categories,
            dailyAllowanceCents = state.snapshot?.dailyAllowanceCents ?: 0L,
            pocketsTotalExcludingThis = state.pocketsTotalCents - pocket.dailyAmountCents,
            onSave = { name, amount, categoryKey, mask, startMinute, endMinute ->
                viewModel.updatePocket(pocket.id, name, amount, categoryKey, mask, startMinute, endMinute)
                editingPocket = null
            },
            onDismiss = { editingPocket = null },
        )
    }

    pendingDeleteAllocation?.let { allocation ->
        ConfirmDialog(
            title = "删除「${allocation.name}」？",
            message = "删除后这一项的预算会回到「未分配」。已经发生的流水不会受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.removeAllocation(allocation.id)
                pendingDeleteAllocation = null
            },
            onDismiss = { pendingDeleteAllocation = null },
        )
    }

    pendingDeletePocket?.let { pocket ->
        ConfirmDialog(
            title = "删除「${pocket.name}」？",
            message = "删除后这一份不再计入今日额度。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.removePocket(pocket.id)
                pendingDeletePocket = null
            },
            onDismiss = { pendingDeletePocket = null },
        )
    }
}

@Composable
private fun AllocationRow(
    allocation: AllocationLite,
    spentCents: Long?,
    categoryName: String?,
    categoryIcon: String?,
    categoryColor: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CategoryAvatar(
            icon = CategoryVisuals.icon(categoryIcon),
            color = CategoryVisuals.color(categoryColor),
            size = 34.dp,
        )
        Spacer(Modifier.width(Spacing.gapM))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(allocation.name, style = LedgerType.body)
                if (allocation.kind == CategoryKind.FIXED) {
                    Spacer(Modifier.width(Spacing.gapS))
                    Text("固定", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                categoryName ?: "未绑定分类（统计不到自动流水）",
                style = LedgerType.caption,
                color = if (categoryName == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            AmountText(cents = allocation.amountCents, style = LedgerType.captionAmount, compact = true)
            if (spentCents != null) {
                Text(
                    "已用 ${Money.formatCompact(spentCents)}",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Spacing.gapS))
        Icon(
            Icons.Outlined.Edit,
            contentDescription = "编辑",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onEdit).padding(4.dp).size(18.dp),
        )
        Icon(
            Icons.Outlined.Delete,
            contentDescription = "删除",
            tint = DangerRed,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(4.dp).size(18.dp),
        )
    }
}

private fun weekdaySuffix(mask: Int): String = when (mask) {
    0b1111111 -> ""
    0b0011111 -> " · 仅工作日"
    0b1100000 -> " · 仅周末"
    else -> " · 指定日期"
}

@Composable
private fun PocketRow(
    pocket: PocketLite,
    categoryName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pocket.name, style = LedgerType.body)
            Spacer(Modifier.height(2.dp))
            Text(
                (categoryName ?: "机动") + weekdaySuffix(pocket.weekdaysMask),
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AmountText(cents = pocket.dailyAmountCents, style = LedgerType.captionAmount, compact = true)
        Spacer(Modifier.width(Spacing.gapS))
        Icon(
            Icons.Outlined.Edit,
            contentDescription = "编辑",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onEdit).padding(4.dp).size(18.dp),
        )
        Icon(
            Icons.Outlined.Delete,
            contentDescription = "删除",
            tint = DangerRed,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(4.dp).size(18.dp),
        )
    }
}
