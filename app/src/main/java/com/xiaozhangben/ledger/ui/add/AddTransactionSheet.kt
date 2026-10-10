package com.xiaozhangben.ledger.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.core.AmountInput
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.domain.model.CategoryKeys
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.ui.common.CategoryAvatar
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.ChoiceChip
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Radius
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * Quick entry.
 *
 * The target is "three seconds per entry", so the whole flow lives on one screen with
 * a custom keypad (no system keyboard, no scrolling, no page changes) and the likely
 * categories are one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    /** Pre-selected when the sheet is opened from a meal card on the 每日 page. */
    presetCategoryKey: String? = null,
    presetSlotId: String? = null,
    viewModel: AddTransactionViewModel = containerViewModel { AddTransactionViewModel(it) },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AddTransactionContent(
            viewModel = viewModel,
            presetCategoryKey = presetCategoryKey,
            presetSlotId = presetSlotId,
            onSaved = onSaved,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTransactionContent(
    viewModel: AddTransactionViewModel,
    presetCategoryKey: String?,
    presetSlotId: String?,
    onSaved: (String) -> Unit,
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()

    var amount by remember { mutableStateOf(AmountInput()) }
    var direction by remember { mutableStateOf(TxDirection.EXPENSE) }
    var categoryKey by remember { mutableStateOf(presetCategoryKey ?: CategoryKeys.FOOD) }
    var occurredAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val error = amount.normalized().validationError()
    val selectable = remember(categories) { categories.filterNot { CategoryVisuals.isSystemKey(it.key) } }

    LaunchedEffect(saveState) {
        val state = saveState
        if (state is SaveState.Saved) {
            viewModel.resetSaveState()
            onSaved(state.id)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.pageHorizontal)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记一笔", style = LedgerType.sectionTitle, modifier = Modifier.weight(1f))
            ChoiceChip(
                label = "支出",
                selected = direction == TxDirection.EXPENSE,
                onClick = { direction = TxDirection.EXPENSE },
            )
            Spacer(Modifier.width(Spacing.gapS))
            ChoiceChip(
                label = "收入",
                selected = direction == TxDirection.INCOME,
                onClick = { direction = TxDirection.INCOME },
            )
        }

        Spacer(Modifier.height(Spacing.gapL))

        // The running total is the hero: it is the only thing the user looks at while
        // typing, and "＋" is beside it because adding several items is the common case.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = amount.display,
                style = LedgerType.heroAmount,
                color = if (direction == TxDirection.INCOME) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Row(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { amount = amount.plus() },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "加上一笔", modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = error ?: "可连续相加：12＋8.5",
            style = LedgerType.caption,
            color = if (error != null) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.gapM))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
            items(selectable, key = { it.key }) { category ->
                ChoiceChip(
                    label = category.name,
                    selected = category.key == categoryKey,
                    onClick = { categoryKey = category.key },
                    leading = {
                        CategoryAvatar(
                            icon = CategoryVisuals.icon(category.iconKey),
                            color = CategoryVisuals.color(category.colorHex),
                            size = 20.dp,
                            selected = category.key == categoryKey,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(Spacing.gapM))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.s))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showDatePicker = true }
                    .padding(horizontal = Spacing.gapM, vertical = Spacing.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(Spacing.gapS))
                Text(DateX.formatDateTime(occurredAt), style = LedgerType.caption)
            }
            Spacer(Modifier.width(Spacing.gapS))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(60) },
                placeholder = { Text("备注（可选）", style = LedgerType.caption) },
                singleLine = true,
                textStyle = LedgerType.caption,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Spacing.gapM))
        Keypad(
            onDigit = { amount = amount.digit(it) },
            onDot = { amount = amount.dot() },
            onBackspace = { amount = amount.backspace() },
        )

        Spacer(Modifier.height(Spacing.gapM))
        val failure = saveState
        if (failure is SaveState.Failed) {
            Text(failure.message, style = LedgerType.caption, color = DangerRed)
            Spacer(Modifier.height(Spacing.gapS))
        }
        PrimaryButton(
            text = if (saveState is SaveState.Saving) "保存中…" else "保存",
            onClick = {
                viewModel.save(
                    amountCents = amount.normalized().valueCents,
                    direction = direction,
                    categoryKey = categoryKey,
                    occurredAt = occurredAt,
                    note = note,
                    slotId = presetSlotId,
                )
            },
            enabled = error == null && saveState !is SaveState.Saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.gapXl))
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateX.localDateToPickerMillis(occurredAt),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            // Only the calendar day is being chosen; keep the time of day.
                            val dayStart = DateX.pickedDayToLocalStartOfDay(picked)
                            val timeOfDay = occurredAt - DateX.startOfTodayMillis(occurredAt)
                            occurredAt = dayStart + timeOfDay
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onDot: () -> Unit,
    onBackspace: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                rowDigits.forEach { digit ->
                    KeypadKey(text = digit.toString(), modifier = Modifier.weight(1f)) { onDigit(digit) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
            KeypadKey(text = ".", modifier = Modifier.weight(1f), onClick = onDot)
            KeypadKey(text = "0", modifier = Modifier.weight(1f)) { onDigit('0') }
            KeypadKey(text = BACKSPACE_LABEL, modifier = Modifier.weight(1f), onClick = onBackspace)
        }
    }
}

private const val BACKSPACE_LABEL = "backspace"

@Composable
private fun KeypadKey(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(Radius.m))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (text == BACKSPACE_LABEL) {
            Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = "退格")
        } else {
            Text(text, style = LedgerType.largeAmount.copy(fontWeight = FontWeight.Normal))
        }
    }
}
