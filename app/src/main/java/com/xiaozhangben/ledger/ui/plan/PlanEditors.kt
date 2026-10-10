package com.xiaozhangben.ledger.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.domain.budget.AllocationLite
import com.xiaozhangben.ledger.domain.budget.PocketLite
import com.xiaozhangben.ledger.domain.model.CategoryKind
import com.xiaozhangben.ledger.ui.common.CategoryAvatar
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.ChoiceChip
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * Editor for one named slice of the monthly total.
 *
 * The name is free text — that is the whole point of the feature — but the category
 * link is what makes the slice actually measurable, so the screen says so explicitly
 * rather than letting the user create an envelope that can never fill up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllocationEditorSheet(
    existing: AllocationLite?,
    categories: List<CategoryEntity>,
    onSave: (name: String, amountCents: Long, kind: CategoryKind, categoryKey: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var amountText by remember {
        mutableStateOf(existing?.let { Money.toPlainString(it.amountCents) } ?: "")
    }
    var kind by remember {
        mutableStateOf(
            existing?.kind ?: CategoryKind.VARIABLE,
        )
    }
    var categoryKey by remember { mutableStateOf(existing?.categoryKey) }

    val parsed = Money.parseUserInputToCents(amountText)
    val error = when {
        name.isBlank() -> "给它起个名字"
        amountText.isBlank() -> "请输入金额"
        parsed == null || parsed <= 0L -> "金额要大于 0"
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal),
        ) {
            Text(
                if (existing == null) "添加预算分项" else "编辑预算分项",
                style = LedgerType.sectionTitle,
            )
            Spacer(Modifier.height(Spacing.gapL))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(12) },
                label = { Text("名称（你自己命名）", style = LedgerType.caption) },
                placeholder = { Text("例如：房租、吃饭、通勤、人情", style = LedgerType.caption) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapM))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(12) },
                label = { Text("每月金额", style = LedgerType.caption) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapM))

            Text("类型", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapS))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                ChoiceChip(
                    label = "弹性支出",
                    selected = kind == CategoryKind.VARIABLE,
                    onClick = { kind = CategoryKind.VARIABLE },
                )
                ChoiceChip(
                    label = "固定支出",
                    selected = kind == CategoryKind.FIXED,
                    onClick = { kind = CategoryKind.FIXED },
                )
            }
            Spacer(Modifier.height(Spacing.gapXs))
            Text(
                "固定支出（房租、话费）不会被算进「今天还能花多少」，会先预留出来。",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.gapL))
            Text("绑定消费分类", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapS))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                item {
                    ChoiceChip(label = "不绑定", selected = categoryKey == null, onClick = { categoryKey = null })
                }
                items(categories.filterNot { CategoryVisuals.isSystemKey(it.key) }, key = { it.key }) { category ->
                    ChoiceChip(
                        label = category.name,
                        selected = category.key == categoryKey,
                        onClick = { categoryKey = if (categoryKey == category.key) null else category.key },
                        leading = {
                            CategoryAvatar(
                                icon = CategoryVisuals.icon(category.iconKey),
                                color = CategoryVisuals.color(category.colorHex),
                                size = 18.dp,
                                selected = category.key == categoryKey,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.gapXs))
            Text(
                "不绑定分类的话，这一项只能当纯数字用，自动归类的流水不会算进来。",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                Spacer(Modifier.height(Spacing.gapM))
                Text(error, style = LedgerType.caption, color = DangerRed)
            }

            Spacer(Modifier.height(Spacing.gapL))
            PrimaryButton(
                text = "保存",
                enabled = error == null,
                onClick = { parsed?.let { onSave(name.trim(), it, kind, categoryKey) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapXl))
        }
    }
}

/** Editor for one named slice of the daily allowance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketEditorSheet(
    existing: PocketLite?,
    categories: List<CategoryEntity>,
    dailyAllowanceCents: Long,
    pocketsTotalExcludingThis: Long,
    onSave: (
        name: String,
        dailyAmountCents: Long,
        categoryKey: String?,
        weekdaysMask: Int,
        startMinute: Int?,
        endMinute: Int?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var amountText by remember {
        mutableStateOf(existing?.let { Money.toPlainString(it.dailyAmountCents) } ?: "")
    }
    var categoryKey by remember { mutableStateOf(existing?.categoryKey) }
    var mask by remember { mutableStateOf(existing?.weekdaysMask ?: 0b1111111) }
    var startText by remember {
        mutableStateOf(existing?.startMinute?.let { DateX.formatMinuteOfDay(it) } ?: "")
    }
    var endText by remember {
        mutableStateOf(existing?.endMinute?.let { DateX.formatMinuteOfDay(it) } ?: "")
    }

    val parsed = Money.parseUserInputToCents(amountText)
    val startMinute = DateX.parseMinuteOfDay(startText)
    val endMinute = DateX.parseMinuteOfDay(endText)

    // A window needs both ends, and they cannot be identical — that would match nothing.
    val windowIncomplete = (startText.isNotBlank() || endText.isNotBlank()) &&
        (startMinute == null || endMinute == null || startMinute == endMinute)

    val error = when {
        name.isBlank() -> "给它起个名字"
        amountText.isBlank() -> "请输入每天的金额"
        parsed == null || parsed <= 0L -> "金额要大于 0"
        windowIncomplete -> "时间段要填成 HH:mm，且起止不能相同（可留空表示不按时间归类）"
        else -> null
    }

    val remainingForThis = (dailyAllowanceCents - pocketsTotalExcludingThis).coerceAtLeast(0L)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.pageHorizontal),
        ) {
            Text(
                if (existing == null) "添加每日类别" else "编辑每日类别",
                style = LedgerType.sectionTitle,
            )
            Spacer(Modifier.height(Spacing.gapXs))
            Text(
                "把「今天还能花」拆成几份，例如 早饭 / 午饭 / 晚饭 / 机动。",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.gapL))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(8) },
                label = { Text("名称（你自己命名）", style = LedgerType.caption) },
                placeholder = { Text("例如：早饭、午饭、机动", style = LedgerType.caption) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapM))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                label = { Text("每天金额", style = LedgerType.caption) },
                supportingText = {
                    if (dailyAllowanceCents > 0L) {
                        Text(
                            "今日额度 ${Money.formatCompact(dailyAllowanceCents)}，" +
                                "这一项最多还能分到 ${Money.formatCompact(remainingForThis)}",
                            style = LedgerType.caption,
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapM))

            Text("适用于", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapS))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEKDAY_LABELS.forEachIndexed { index, label ->
                    val bit = 1 shl index
                    ChoiceChip(
                        label = label,
                        selected = mask and bit != 0,
                        onClick = { mask = mask xor bit },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.gapL))
            Text("绑定消费分类（可留空作为机动）", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapS))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                item {
                    ChoiceChip(label = "不绑定", selected = categoryKey == null, onClick = { categoryKey = null })
                }
                items(categories.filterNot { CategoryVisuals.isSystemKey(it.key) }, key = { it.key }) { category ->
                    ChoiceChip(
                        label = category.name,
                        selected = category.key == categoryKey,
                        onClick = { categoryKey = if (categoryKey == category.key) null else category.key },
                    )
                }
            }

            // The time window is what lets a captured payment be placed automatically:
            // the notification carries an amount and nothing else, so "15:20" is the only
            // evidence that a ¥15 payment was lunch rather than dinner.
            Spacer(Modifier.height(Spacing.gapL))
            Text("时间段（用于自动归类）", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapXs))
            Text(
                "付款时间落在这个区间、且分类也对得上的流水，会自动算进这一项。留空表示不自动归类。",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.gapS))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                item {
                    ChoiceChip(
                        label = "不设时间",
                        selected = startText.isBlank() && endText.isBlank(),
                        onClick = { startText = ""; endText = "" },
                    )
                }
                items(WINDOW_PRESETS, key = { it.label }) { preset ->
                    ChoiceChip(
                        label = preset.label,
                        selected = startMinute == preset.start && endMinute == preset.end,
                        onClick = {
                            startText = DateX.formatMinuteOfDay(preset.start)
                            endText = DateX.formatMinuteOfDay(preset.end)
                        },
                    )
                }
            }
            TinyGap()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.take(5) },
                    label = { Text("开始", style = LedgerType.caption) },
                    placeholder = { Text("05:00", style = LedgerType.caption) },
                    singleLine = true,
                    isError = startText.isNotBlank() && startMinute == null,
                    modifier = Modifier.weight(1f),
                )
                Text("—", style = LedgerType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it.take(5) },
                    label = { Text("结束", style = LedgerType.caption) },
                    placeholder = { Text("10:00", style = LedgerType.caption) },
                    singleLine = true,
                    isError = endText.isNotBlank() && endMinute == null,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Spacing.gapXs))
            Text(
                "跨午夜可以这样写：22:00 — 02:00",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                Spacer(Modifier.height(Spacing.gapM))
                Text(error, style = LedgerType.caption, color = DangerRed)
            }
            if (mask == 0) {
                Spacer(Modifier.height(Spacing.gapM))
                Text("至少选择一天", style = LedgerType.caption, color = DangerRed)
            }

            Spacer(Modifier.height(Spacing.gapL))
            PrimaryButton(
                text = "保存",
                enabled = error == null && mask != 0,
                onClick = {
                    parsed?.let {
                        onSave(
                            name.trim(),
                            it,
                            categoryKey,
                            mask,
                            if (windowIncomplete) null else startMinute,
                            if (windowIncomplete) null else endMinute,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapXl))
        }
    }
}

/** Small vertical gap; named to avoid clashing with the [Spacing] object. */
@Composable
private fun TinyGap() = Spacer(Modifier.height(Spacing.gapS))

private data class WindowPreset(val label: String, val start: Int, val end: Int)

private val WINDOW_PRESETS = listOf(
    WindowPreset("早饭 05–10", 5 * 60, 10 * 60),
    WindowPreset("午饭 10–15", 10 * 60, 15 * 60),
    WindowPreset("晚饭 15–24", 15 * 60, 24 * 60),
    WindowPreset("宵夜 22–02", 22 * 60, 2 * 60),
)

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
