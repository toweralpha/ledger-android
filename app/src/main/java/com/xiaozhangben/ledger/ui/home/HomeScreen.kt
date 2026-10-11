package com.xiaozhangben.ledger.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.capture.CaptureHealth
import com.xiaozhangben.ledger.capture.CaptureHealthEvaluator
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.domain.budget.BudgetBucket
import com.xiaozhangben.ledger.domain.budget.BudgetSnapshot
import com.xiaozhangben.ledger.domain.budget.PocketStatus
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.ui.common.AlertStrip
import com.xiaozhangben.ledger.ui.common.AmountText
import com.xiaozhangben.ledger.ui.common.BudgetProgressBar
import com.xiaozhangben.ledger.ui.common.BudgetRing
import com.xiaozhangben.ledger.ui.common.EmptyState
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.bandColor
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Radius
import com.xiaozhangben.ledger.ui.theme.Spacing
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    onOpenPlan: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenTriage: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onAddTransaction: () -> Unit,
    viewModel: HomeViewModel = containerViewModel { HomeViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Permissions and OEM settings can only change outside the app, so re-read them
    // every time the user comes back rather than caching the answer for the session.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshEnvironment()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        MonthHeader(
            label = DateX.formatMonthLabel(state.month),
            isCurrentMonth = state.month == DateX.yearMonthOf(System.currentTimeMillis()),
            onPrevious = viewModel::showPreviousMonth,
            onCurrent = viewModel::showCurrentMonth,
            onNext = viewModel::showNextMonth,
        )

        val snapshot = state.snapshot
        if (snapshot == null) {
            Spacer(Modifier.height(80.dp))
        } else if (!snapshot.hasBudget) {
            EmptyState(
                icon = Icons.Outlined.Savings,
                title = "还没有设置本月预算",
                hint = "先填入这个月能花的钱，再把它分成几个部分。\n有预算之后，首页才会告诉你今天还能花多少。",
                actionLabel = "设置本月预算",
                onAction = onOpenPlan,
            )
            Spacer(Modifier.height(Spacing.gapM))
            if (state.hasTransactions) {
                Text(
                    "上个月已经有记录了，可以直接复制上月设置。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.gapXs),
                )
            }
            Spacer(Modifier.height(Spacing.gapXl))
        } else {
            Spacer(Modifier.height(Spacing.gapS))
            HeroCard(snapshot = snapshot, onOpenPlan = onOpenPlan)
            Spacer(Modifier.height(Spacing.gapM))
            PocketsCard(snapshot = snapshot, onOpenPlan = onOpenPlan)

            // Alerts sit between "how am I doing" and "where did it go", which is where
            // they are actually read.
            //
            // The first two branches are the important ones: "capture is broken" has to
            // outrank everything else on this screen, because every number below it is
            // wrong while it is true. Before this, the app only checked whether
            // notification access had been *granted* — which stayed true while the system
            // had stopped the listener, so two days of spending went unrecorded in
            // complete silence.
            Spacer(Modifier.height(Spacing.gapM))
            when (state.captureHealth) {
                CaptureHealth.NOT_CONNECTED -> {
                    AlertStrip(
                        text = "实时捕获已停止，付款不会自动记账",
                        tone = AlertLevel.OVER,
                        actionLabel = "修复",
                        onAction = { viewModel.repairCapture() },
                    )
                    Spacer(Modifier.height(Spacing.gapS))
                    AlertStrip(
                        text = "点「修复」没反应的话，去「我的」重新开关一次通知使用权",
                        tone = AlertLevel.NEAR,
                        actionLabel = "去设置",
                        onAction = onOpenDiagnostics,
                    )
                    Spacer(Modifier.height(Spacing.gapS))
                }

                CaptureHealth.NOT_RECEIVING -> {
                    AlertStrip(
                        text = "已 ${silentDays(state)} 天没收到任何支付应用通知，可能漏单",
                        tone = AlertLevel.NEAR,
                        actionLabel = "用账单补齐",
                        onAction = onOpenImport,
                    )
                    Spacer(Modifier.height(Spacing.gapS))
                }

                CaptureHealth.NOT_AUTHORISED -> {
                    AlertStrip(
                        text = "实时捕获未开启，付款不会自动记账",
                        tone = AlertLevel.NEAR,
                        actionLabel = "去开启",
                        onAction = onOpenDiagnostics,
                    )
                    Spacer(Modifier.height(Spacing.gapS))
                }

                CaptureHealth.OFF -> Unit

                CaptureHealth.OK -> {
                    if (state.needsNotificationPermission) {
                        AlertStrip(
                            text = "通知权限未开启，记账提示卡不会出现",
                            tone = AlertLevel.OK,
                            actionLabel = "去开启",
                            onAction = onOpenDiagnostics,
                        )
                        Spacer(Modifier.height(Spacing.gapS))
                    }
                }
            }

            if (state.unclassifiedCount > 0) {
                AlertStrip(
                    text = "${state.unclassifiedCount} 笔还没归类，预算数字还不准",
                    tone = AlertLevel.OVER,
                    actionLabel = "去处理",
                    onAction = onOpenTriage,
                )
                Spacer(Modifier.height(Spacing.gapS))
            }

            if (snapshot.unallocatedCents != 0L) {
                AlertStrip(
                    text = if (snapshot.unallocatedCents > 0L) {
                        "还有 ${Money.formatCompact(snapshot.unallocatedCents)} 没有分配"
                    } else {
                        "分项合计超出了总额 ${Money.formatCompact(-snapshot.unallocatedCents)}"
                    },
                    tone = AlertLevel.NEAR,
                    actionLabel = "去调整",
                    onAction = onOpenPlan,
                )
                Spacer(Modifier.height(Spacing.gapS))
            }

            BucketsSection(snapshot = snapshot, onOpenPlan = onOpenPlan)
        }

        Spacer(Modifier.height(96.dp))
    }
}

private fun silentDays(state: HomeUiState): Int {
    // Measured from the last notification of any kind, not the last capture: a fortnight
    // without spending is normal, a fortnight without a single WeChat notification is not.
    val last = state.lastNotificationSeenAt
        ?: return CaptureHealthEvaluator.NOT_RECEIVING_AFTER_MILLIS.toInt() / (24 * 60 * 60 * 1000)
    val days = ((System.currentTimeMillis() - last) / (24 * 60 * 60 * 1000L)).toInt()
    return days.coerceAtLeast(1)
}

@Composable
private fun MonthHeader(
    label: String,
    isCurrentMonth: Boolean,
    onPrevious: () -> Unit,
    onCurrent: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LedgerType.screenTitle, modifier = Modifier.weight(1f))

        if (!isCurrentMonth) {
            Text(
                "回到本月",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.s))
                    .clickable(onClick = onCurrent)
                    .padding(horizontal = Spacing.gapS, vertical = 6.dp),
            )
            Spacer(Modifier.width(Spacing.gapXs))
        }

        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = "上个月",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onPrevious)
                .padding(4.dp)
                .size(24.dp),
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "下个月",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onNext)
                .padding(4.dp)
                .size(24.dp),
        )
    }
}

@Composable
private fun HeroCard(snapshot: BudgetSnapshot, onOpenPlan: () -> Unit) {
    val ring = bandColor(snapshot.alert)
    LedgerCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenPlan) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "本月可用",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.gapXs))
                    AmountText(
                        cents = snapshot.remainingCents,
                        style = LedgerType.heroAmount,
                        color = if (snapshot.remainingCents < 0L) DangerRed else MaterialTheme.colorScheme.onSurface,
                        compact = true,
                    )
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(
                        "总 ${Money.formatCompact(snapshot.totalCents)} · 已用 ${Money.formatCompact(snapshot.spentCents)}",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BudgetRing(
                    ratio = snapshot.ratio,
                    color = ring,
                    centerText = "${(snapshot.ratio * 100).roundToInt()}%",
                )
            }

            Spacer(Modifier.height(Spacing.gapL))
            BudgetProgressBar(ratio = snapshot.ratio, band = snapshot.alert)

            Spacer(Modifier.height(Spacing.gapM))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapL)) {
                MiniStat("还剩", "${snapshot.remainingDays} 天")
                MiniStat("已为固定支出预留", Money.formatCompact(snapshot.fixedUnpaidCents))
                if (snapshot.carryInCents > 0L) {
                    MiniStat("上月结余", Money.formatCompact(snapshot.carryInCents))
                }
            }

            if (snapshot.remainingCents < 0L) {
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "已超出 ${Money.formatCompact(-snapshot.remainingCents)}",
                    style = LedgerType.caption,
                    color = DangerRed,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = LedgerType.caption.copy(color = MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
private fun PocketsCard(snapshot: BudgetSnapshot, onOpenPlan: () -> Unit) {
    LedgerCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "今日还能花",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    AmountText(
                        cents = snapshot.dailyRemainingCents.coerceAtLeast(0L),
                        style = LedgerType.largeAmount,
                        color = if (snapshot.dailyRemainingCents < 0L) DangerRed else MaterialTheme.colorScheme.primary,
                        compact = true,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "今日已用 ${Money.formatCompact(snapshot.spentTodayCents)}",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (snapshot.dailyAllowanceCents != snapshot.pocketsTotalCents) {
                        Text(
                            "日均额度 ${Money.formatCompact(snapshot.dailyAllowanceCents)}",
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (snapshot.pockets.isEmpty()) {
                Spacer(Modifier.height(Spacing.gapM))
                Text(
                    "把今日额度拆成几份（早餐 / 通勤 / 机动…），花的时候就能看到每份还剩多少。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.gapM))
                PrimaryButton(text = "设置日额度分项", onClick = onOpenPlan, icon = Icons.Outlined.Add)
            } else {
                Spacer(Modifier.height(Spacing.gapM))
                snapshot.pockets.forEach { pocket ->
                    PocketRow(pocket)
                }
                if (snapshot.pocketsMismatch) {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(
                        "分项合计 ${Money.formatCompact(snapshot.pocketsTotalCents)}，" +
                            "与日均额度 ${Money.formatCompact(snapshot.dailyAllowanceCents)} 不一致",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PocketRow(pocket: PocketStatus) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pocket.name, style = LedgerType.body, modifier = Modifier.weight(1f))
            // "还剩" is spelled out because this row is remaining/budget while the 每日
            // page's row is spent/budget. The same "X / Y" shape meaning opposite things
            // on two screens is a trap — it caught the author of both.
            Text(
                "还剩 " + Money.formatCompact(pocket.remainingTodayCents),
                style = LedgerType.captionAmount,
                color = if (pocket.remainingTodayCents < 0L) DangerRed else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(Spacing.gapS))
            Text(
                "/ ${Money.formatCompact(pocket.dailyAmountCents)}",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.gapXs))
        BudgetProgressBar(ratio = pocket.ratio, band = pocket.alert, thickness = 5.dp)
    }
}

@Composable
private fun BucketsSection(snapshot: BudgetSnapshot, onOpenPlan: () -> Unit) {
    if (snapshot.buckets.isEmpty()) {
        Spacer(Modifier.height(Spacing.gapM))
        LedgerCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenPlan) {
            Column {
                Text("把预算分成几个部分", style = LedgerType.sectionTitle)
                Spacer(Modifier.height(Spacing.gapXs))
                Text(
                    "例如「房租 / 吃饭 / 通勤 / 人情」，每个部分绑定一个消费分类，" +
                        "自动归类的流水就会落到对应的部分里。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.gapM))
                PrimaryButton(text = "去分配", onClick = onOpenPlan)
            }
        }
        return
    }

    SectionHeader(title = "预算分项", actionLabel = "调整", onAction = onOpenPlan)
    LedgerCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            snapshot.buckets.forEachIndexed { index, bucket ->
                if (index > 0) Spacer(Modifier.height(Spacing.gapM))
                BucketRow(bucket)
            }
        }
    }
}

@Composable
private fun BucketRow(bucket: BudgetBucket) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bucket.name, style = LedgerType.body, modifier = Modifier.weight(1f))
            if (bucket.kind == CategoryKind.FIXED) {
                Text(
                    "固定",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = Spacing.gapS)
                        .padding(horizontal = 2.dp),
                )
            }
            AmountText(cents = bucket.spentCents, style = LedgerType.captionAmount, compact = true)
            Spacer(Modifier.width(Spacing.gapXs))
            Text(
                "/ ${Money.formatCompact(bucket.budgetCents)}",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.gapXs))
        BudgetProgressBar(ratio = bucket.ratio, band = bucket.alert, thickness = 5.dp)
        if (bucket.overspentCents > 0L) {
            Spacer(Modifier.height(2.dp))
            Text(
                "超出 ${Money.formatCompact(bucket.overspentCents)}",
                style = LedgerType.caption,
                color = DangerRed,
            )
        }
    }
}
