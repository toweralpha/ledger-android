package com.xiaozhangben.ledger.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

@Composable
fun AmountInputDialog(
    title: String,
    initialCents: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    label: String = "金额",
    supporting: String? = null,
) {
    var text by remember {
        mutableStateOf(if (initialCents > 0L) Money.toPlainString(initialCents) else "")
    }
    val parsed = Money.parseUserInputToCents(text)
    val error = when {
        text.isBlank() -> null
        parsed == null -> "请输入正确的数字"
        parsed <= 0L -> "金额要大于 0"
        parsed > Money.MAX_TRANSACTION_CENTS -> "金额过大"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = LedgerType.sectionTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(14) },
                    label = { Text(label, style = LedgerType.caption) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(error, style = LedgerType.caption, color = DangerRed)
                } else if (supporting != null) {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(
                        supporting,
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = error == null && parsed != null && parsed > 0L,
                onClick = { parsed?.let(onConfirm) },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = "名称",
    supporting: String? = null,
    maxLength: Int = 16,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = LedgerType.sectionTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(maxLength) },
                    label = { Text(label, style = LedgerType.caption) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (supporting != null) {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(
                        supporting,
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = LedgerType.sectionTitle) },
        text = { Text(message, style = LedgerType.body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) DangerRed else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Passphrase prompt for encrypted backup.
 *
 * Masked, with a minimum length, because this one key protects the entire ledger and
 * a four-character secret would make the encryption decorative.
 */
@Composable
fun PassphraseDialog(
    title: String,
    message: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    minLength: Int = 6,
    requireConfirmation: Boolean = false,
) {
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val tooShort = passphrase.length in 1 until minLength
    val mismatch = requireConfirmation && repeated.isNotEmpty() && repeated != passphrase
    val valid = passphrase.length >= minLength && (!requireConfirmation || passphrase == repeated)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = LedgerType.sectionTitle) },
        text = {
            Column {
                Text(
                    message,
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.gapM))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(64) },
                    label = { Text("口令（至少 $minLength 位）", style = LedgerType.caption) },
                    singleLine = true,
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirmation) {
                    Spacer(Modifier.height(Spacing.gapS))
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it.take(64) },
                        label = { Text("再输一次", style = LedgerType.caption) },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                        else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(Spacing.gapXs))
                TextButton(onClick = { visible = !visible }) {
                    Text(if (visible) "隐藏口令" else "显示口令", style = LedgerType.caption)
                }
                if (tooShort) {
                    Text("口令太短", style = LedgerType.caption, color = DangerRed)
                }
                if (mismatch) {
                    Text("两次输入不一致", style = LedgerType.caption, color = DangerRed)
                }
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "口令不会被保存在任何地方。忘记口令就无法恢复这份备份。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(passphrase) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
