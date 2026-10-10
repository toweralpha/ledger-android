package com.xiaozhangben.ledger.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.IncomeGreen
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Motion
import com.xiaozhangben.ledger.ui.theme.Radius
import com.xiaozhangben.ledger.ui.theme.Spacing
import com.xiaozhangben.ledger.ui.theme.WarnAmber
import com.xiaozhangben.ledger.ui.theme.rememberAnimationsEnabled

@Composable
fun bandColor(band: AlertLevel): Color = when (band) {
    AlertLevel.OK -> MaterialTheme.colorScheme.primary
    AlertLevel.NEAR -> WarnAmber
    AlertLevel.ALERT -> WarnAmber
    AlertLevel.OVER -> DangerRed
}

/**
 * Rounded surface used for every grouped block, with the lightest possible border.
 *
 * NOTE: the content slot is a **Box**, not a Column — several children placed directly
 * inside will overlap rather than stack. Wrap them in a `Column` (which is what every
 * other call site does). This cost one shipped layout bug on the 每日 page, where a
 * heading, an amount and a button all rendered on top of each other.
 */
@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.l)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Spacing.cardPadding),
    ) {
        content()
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.gapXs, vertical = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = LedgerType.sectionTitle, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
}

/** Animated budget bar. Never relies on colour alone — callers pair it with text. */
@Composable
fun BudgetProgressBar(
    ratio: Float,
    band: AlertLevel,
    modifier: Modifier = Modifier,
    thickness: Dp = 8.dp,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val target = ratio.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = if (animationsEnabled) Motion.spring else snap(),
        label = "budgetProgress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(RoundedCornerShape(thickness / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .background(bandColor(band)),
        )
    }
}

/**
 * Money, always with tabular figures.
 *
 * Proportional digits make a ticking total visibly jitter, and the home screen ticks
 * on every payment — which is precisely the moment the app is being judged.
 */
@Composable
fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = LedgerType.rowAmount,
    color: Color = Color.Unspecified,
    withSymbol: Boolean = true,
    compact: Boolean = false,
    signed: Boolean = false,
) {
    val text = when {
        signed && cents > 0L -> "+" + if (compact) Money.formatCompact(cents, withSymbol) else Money.format(cents, withSymbol)
        else -> if (compact) Money.formatCompact(cents, withSymbol) else Money.format(cents, withSymbol)
    }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        maxLines = 1,
    )
}

@Composable
fun IncomeAmountText(cents: Long, style: TextStyle = LedgerType.rowAmount, modifier: Modifier = Modifier) {
    Text(
        text = "+" + Money.format(cents),
        modifier = modifier,
        style = style,
        color = IncomeGreen,
        maxLines = 1,
    )
}

@Composable
fun CategoryAvatar(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = if (selected) 0.28f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.gapXl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(Spacing.gapM))
        Text(title, style = LedgerType.sectionTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.gapXs))
        Text(
            hint,
            style = LedgerType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.gapL))
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.s))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.gapL, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(Spacing.gapS))
        }
        Text(text, style = LedgerType.body.copy(fontWeight = FontWeight.Medium), color = fg)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.s))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.gapL, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(Spacing.gapS))
        }
        Text(text, style = LedgerType.body.copy(fontWeight = FontWeight.Medium))
    }
}

/** A selectable chip used for categories, filters and quick choices. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Radius.s)
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.gapM, vertical = Spacing.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.gapS))
        }
        Text(
            text = label,
            style = LedgerType.caption.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = LedgerType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = LedgerType.caption,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Inline warning strip: capture not authorised, rows awaiting triage, and so on. */
@Composable
fun AlertStrip(
    text: String,
    modifier: Modifier = Modifier,
    tone: AlertLevel = AlertLevel.NEAR,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val color = bandColor(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.m))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(Radius.m))
            .padding(horizontal = Spacing.gapM, vertical = Spacing.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Spacing.gapM))
        Text(text, style = LedgerType.caption, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(Spacing.gapS))
            Text(
                actionLabel,
                style = LedgerType.caption.copy(fontWeight = FontWeight.Medium),
                color = color,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
}

@Composable
fun VerticalGap(height: Dp = Spacing.gapL) = Spacer(Modifier.height(height))
