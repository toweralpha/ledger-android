package com.xiaozhangben.ledger.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.KeyValueRow
import com.xiaozhangben.ledger.ui.common.SecondaryButton
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * Transaction detail.
 *
 * Deliberately shows *why* a row was classified the way it was — the matched rule and
 * the raw fields. Without that, a wrong category looks like the app being arbitrary,
 * and the user has no basis for trusting the ones it gets right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    row: TransactionEntity,
    category: CategoryEntity?,
    onDismiss: () -> Unit,
    onReclassify: (TransactionEntity) -> Unit,
    onToggleExcluded: (ExcludeReason?) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val direction = row.direction.toDirection()

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(
                    icon = CategoryVisuals.icon(category?.iconKey),
                    color = CategoryVisuals.color(category?.colorHex),
                    size = 44.dp,
                )
                Spacer(Modifier.width(Spacing.gapM))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.counterpartyRaw ?: row.productDesc ?: "未命名",
                        style = LedgerType.sectionTitle,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        category?.name ?: if (row.categoryKey == CategoryKeys.UNCLASSIFIED) "待归类" else row.categoryKey,
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(
                    cents = row.amountCents,
                    style = LedgerType.largeAmount,
                    color = if (direction == TxDirection.INCOME) {
                        com.xiaozhangben.ledger.ui.theme.IncomeGreen
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            Spacer(Modifier.height(Spacing.gapL))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.gapM))

            KeyValueRow("时间", DateX.formatDateTime(row.occurredAt) + " · " + DateX.weekdayLabel(row.occurredAt))
            KeyValueRow("来源", TxSource.valueOf(row.source).label)
            KeyValueRow("收支", direction.label)
            if (row.excluded) {
                KeyValueRow(
                    label = "计入预算",
                    value = "否（${row.excludeReason?.let { runCatching { ExcludeReason.valueOf(it) }.getOrNull()?.label } ?: "已排除"}）",
                    valueColor = DangerRed,
                )
            } else {
                KeyValueRow("计入预算", "是")
            }
            row.rawType?.let { KeyValueRow("账单类型", it) }
            row.status?.let { KeyValueRow("账单状态", it) }
            row.productDesc?.let { KeyValueRow("商品说明", it) }
            row.payMethod?.let { KeyValueRow("支付方式", it) }
            row.remark?.let { KeyValueRow("账单备注", it) }
            row.note?.let { KeyValueRow("我的备注", it) }

            Spacer(Modifier.height(Spacing.gapM))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.gapM))

            KeyValueRow("归类依据", row.matchedRuleLabel ?: if (row.userCorrected) "手动指定" else "无匹配规则")
            if (row.confidence > 0f) {
                KeyValueRow("置信度", "${(row.confidence * 100).toInt()}%")
            }
            row.counterparty?.let { KeyValueRow("归一化商户", it) }
            row.sourceTxnId?.let { KeyValueRow("交易单号", it) }
            row.merchantOrderId?.let { KeyValueRow("商户单号", it) }
            row.refundOfTxnId?.let { KeyValueRow("冲抵的原交易", it.take(8) + "…") }
            KeyValueRow("疑似重复", if (row.excludeReason == ExcludeReason.DUPLICATE.name) "是" else "否")

            Spacer(Modifier.height(Spacing.gapL))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                SecondaryButton(
                    text = "改分类",
                    icon = Icons.Outlined.Edit,
                    onClick = { onReclassify(row) },
                )
                SecondaryButton(
                    text = if (row.excluded) "计入预算" else "不计入预算",
                    onClick = {
                        onToggleExcluded(if (row.excluded) null else ExcludeReason.TRANSFER)
                    },
                )
            }
            Spacer(Modifier.height(Spacing.gapS))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDelete)
                    .padding(vertical = Spacing.gapM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = DangerRed, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(Spacing.gapS))
                Text("删除这笔", style = LedgerType.body, color = DangerRed)
            }
            Spacer(Modifier.height(Spacing.gapXl))
        }
    }
}
