package com.xiaozhangben.ledger.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.ui.common.AmountText
import com.xiaozhangben.ledger.ui.common.CategoryAvatar
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.EmptyState
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.IncomeGreen
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Motion
import com.xiaozhangben.ledger.ui.theme.Spacing
import com.xiaozhangben.ledger.ui.theme.rememberAnimationsEnabled
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    viewModel: StatsViewModel = containerViewModel { StatsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

        if (!state.hasData && !state.loading) {
            EmptyState(
                icon = Icons.Outlined.BarChart,
                title = "这个月还没有支出",
                hint = "有流水之后，这里会显示分类占比、近半年的趋势，以及与上个月的对比。",
            )
            return@Column
        }

        LedgerCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本月支出", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.gapXs))
                    AmountText(state.totalExpenseCents, style = LedgerType.heroAmount, compact = true)
                }
                state.changeRatio?.let { ratio ->
                    val up = ratio > 0f
                    Column(horizontalAlignment = Alignment.End) {
                        Text("较上月", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            (if (up) "↑ " else "↓ ") + "${abs((ratio * 100).roundToInt())}%",
                            style = LedgerType.largeAmount,
                            color = if (up) DangerRed else IncomeGreen,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.gapM))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapXl)) {
                MiniStat("固定支出", Money.formatCompact(state.fixedCents))
                MiniStat("弹性支出", Money.formatCompact(state.variableCents))
                MiniStat("上月同期", Money.formatCompact(state.previousTotalCents))
            }
        }

        if (state.shares.isNotEmpty()) {
            SectionHeader("分类占比")
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryDonut(
                            shares = state.shares,
                            totalCents = state.totalExpenseCents,
                        )
                        Spacer(Modifier.width(Spacing.gapL))
                        Column(Modifier.weight(1f)) {
                            state.shares.take(5).forEach { share ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(CategoryVisuals.color(share.colorHex)),
                                    )
                                    Spacer(Modifier.width(Spacing.gapS))
                                    Text(share.name, style = LedgerType.caption, modifier = Modifier.weight(1f))
                                    Text(
                                        "${(share.ratio * 100).roundToInt()}%",
                                        style = LedgerType.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (state.shares.size > 5) {
                                Spacer(Modifier.height(Spacing.gapXs))
                                Text(
                                    "另有 ${state.shares.size - 5} 个分类",
                                    style = LedgerType.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.gapL))
                    state.shares.forEach { share ->
                        ShareRow(share)
                        Spacer(Modifier.height(Spacing.gapM))
                    }
                }
            }
        }

        if (state.topMovers.isNotEmpty()) {
            SectionHeader("与上月相比")
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    state.topMovers.forEachIndexed { index, mover ->
                        if (index > 0) Spacer(Modifier.height(Spacing.gapM))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryAvatar(
                                icon = CategoryVisuals.icon(mover.iconKey),
                                color = CategoryVisuals.color(mover.colorHex),
                                size = 30.dp,
                            )
                            Spacer(Modifier.width(Spacing.gapM))
                            Column(Modifier.weight(1f)) {
                                Text(mover.name, style = LedgerType.body)
                                Text(
                                    "上月 ${Money.formatCompact(mover.previousCents)}",
                                    style = LedgerType.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (mover.deltaCents > 0) "+" else "−") + Money.formatCompact(abs(mover.deltaCents)),
                                style = LedgerType.captionAmount,
                                color = if (mover.deltaCents > 0) DangerRed else IncomeGreen,
                            )
                        }
                    }
                }
            }
        }

        if (state.trend.any { it.expenseCents > 0L }) {
            SectionHeader("近 ${StatsViewModel.TREND_MONTHS} 个月")
            LedgerCard(Modifier.fillMaxWidth()) {
                TrendChart(points = state.trend)
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(label, style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = LedgerType.caption)
    }
}

@Composable
private fun ShareRow(share: CategoryShare) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(share.name, style = LedgerType.body, modifier = Modifier.weight(1f))
            AmountText(share.amountCents, style = LedgerType.captionAmount, compact = true)
            Spacer(Modifier.width(Spacing.gapS))
            Text(
                "${(share.ratio * 100).roundToInt()}%",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.gapXs))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(share.ratio.coerceIn(0f, 1f))
                    .height(5.dp)
                    .background(CategoryVisuals.color(share.colorHex)),
            )
        }
    }
}

/**
 * Category share as a ring.
 *
 * Drawn with a single animated progress value so the whole ring grows together on
 * entry, instead of seven arcs animating independently and looking like a glitch.
 */
@Composable
private fun CategoryDonut(shares: List<CategoryShare>, totalCents: Long) {
    val animationsEnabled = rememberAnimationsEnabled()
    val progress by animateFloatAsState(
        targetValue = if (totalCents > 0L) 1f else 0f,
        animationSpec = if (animationsEnabled) Motion.spring else snap(),
        label = "donut",
    )

    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(112.dp)) {
            val stroke = 18.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            var start = -90f
            shares.forEach { share ->
                val sweep = 360f * share.ratio * progress
                if (sweep > 0.4f) {
                    drawArc(
                        color = CategoryVisuals.color(share.colorHex),
                        startAngle = start,
                        sweepAngle = sweep - 1.5f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${shares.size}", style = LedgerType.largeAmount)
            Text("个分类", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrendChart(points: List<MonthPoint>) {
    val animationsEnabled = rememberAnimationsEnabled()
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = if (animationsEnabled) Motion.spring else snap(),
        label = "trend",
    )
    val max = (points.maxOfOrNull { it.expenseCents } ?: 0L).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gapS),
            verticalAlignment = Alignment.Bottom,
        ) {
            points.forEach { point ->
                val ratio = (point.expenseCents.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (point.expenseCents > 0L) Money.formatCompact(point.expenseCents, withSymbol = false) else "",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(trackColor),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((84 * ratio * progress).dp.coerceAtLeast(if (point.expenseCents > 0L) 3.dp else 0.dp))
                                .background(barColor),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.gapS))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gapS),
        ) {
            points.forEach { point ->
                Text(
                    DateX.formatMonthShort(point.month),
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
