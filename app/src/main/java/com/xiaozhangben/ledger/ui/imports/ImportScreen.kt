package com.xiaozhangben.ledger.ui.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.domain.bill.ColumnRole
import com.xiaozhangben.ledger.ui.common.AlertStrip
import com.xiaozhangben.ledger.ui.common.AmountText
import com.xiaozhangben.ledger.ui.common.KeyValueRow
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.common.SecondaryButton
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.IncomeGreen
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * Bill import: pick a file, inspect what we understood, then commit.
 *
 * The instructions live on the same screen as the picker because the export flows in
 * the two apps are genuinely hard to find, and a user who cannot find the export
 * concludes the feature does not exist.
 */
@Composable
fun ImportScreen(
    viewModel: ImportViewModel = containerViewModel { ImportViewModel(it) },
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        // Bill exports arrive as text/csv, application/vnd.ms-excel, or
        // application/octet-stream depending on the phone, so no filter is applied.
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "bill.csv"
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            viewModel.parse(name, ByteArray(0))
        } else {
            viewModel.parse(name, bytes)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        Text(
            "导入账单",
            style = LedgerType.screenTitle,
            modifier = Modifier.padding(vertical = Spacing.gapS),
        )

        when (val current = step) {
            is ImportStep.Pick -> {
                ExportInstructions()
                Spacer(Modifier.height(Spacing.gapL))
                PrimaryButton(
                    text = "选择账单文件",
                    icon = Icons.Outlined.FileOpen,
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "支持支付宝「交易流水证明」CSV（GBK）与微信「支付账单明细」CSV（UTF-8）。" +
                        "微信的账单要先解压出 CSV 再导入。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ImportStep.Parsing -> {
                Spacer(Modifier.height(Spacing.gapXl))
                Text("正在解析…", style = LedgerType.body)
            }

            is ImportStep.Failed -> {
                Spacer(Modifier.height(Spacing.gapL))
                AlertStrip(
                    text = current.message,
                    tone = AlertLevel.OVER,
                )
                current.detail?.let {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(it, style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Spacing.gapL))
                SecondaryButton(text = "重新选择文件", onClick = viewModel::reset)
            }

            is ImportStep.Preview -> PreviewContent(
                step = current,
                onConfirm = { viewModel.commit(current.preview) },
                onCancel = viewModel::reset,
            )

            is ImportStep.Done -> DoneContent(
                step = current,
                onImportAnother = viewModel::reset,
                onUndo = { viewModel.undoBatch(current.report.batchId) },
            )
        }

        if (history.isNotEmpty() && step is ImportStep.Pick) {
            SectionHeader("导入记录")
            LedgerCard(Modifier.fillMaxWidth()) {
                Column {
                    history.take(6).forEachIndexed { index, batch ->
                        if (index > 0) {
                            Spacer(Modifier.height(Spacing.gapM))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(Spacing.gapM))
                        }
                        Text(batch.fileName, style = LedgerType.body, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${TxSource.valueOf(batch.source).label} · ${batch.encoding} · " +
                                DateX.formatDateTime(batch.importedAt),
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "新增 ${batch.newCount} · 重复 ${batch.dupCount} · 排除 ${batch.excludedCount} · " +
                                "待归类 ${batch.unclassifiedCount}" +
                                if (batch.rejectedCount > 0) " · 跳过 ${batch.rejectedCount}" else "",
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun ExportInstructions() {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            Text("怎么导出账单", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapM))
            Text("支付宝", style = LedgerType.body)
            Spacer(Modifier.height(2.dp))
            Text(
                "我的 → 账单 → 右上角「…」→ 开具交易流水证明 → 用于个人对账 → 选时间段 → 填邮箱。" +
                    "下载的压缩包解开后是 CSV。",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.gapM))
            Text("微信", style = LedgerType.body)
            Spacer(Modifier.height(2.dp))
            Text(
                "我 → 服务 → 钱包 → 账单 → 右上角常见问题 → 下载账单 → 用于个人对账 → 选时间段 → 填邮箱。" +
                    "邮件里的压缩包有密码，先解压出 CSV 再导入。",
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewContent(
    step: ImportStep.Preview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val preview = step.preview

    Spacer(Modifier.height(Spacing.gapM))
    AlertStrip(
        text = "先确认下面的识别结果，再决定是否写入。这一步不会改动任何数据。",
        tone = AlertLevel.OK,
    )

    Spacer(Modifier.height(Spacing.gapM))
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            Text("识别结果", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapS))
            KeyValueRow("文件名", preview.fileName)
            KeyValueRow("来源", TxSource.valueOf(preview.source.name).label)
            KeyValueRow("编码", preview.encoding)
            preview.rangeHint?.let { KeyValueRow("账单范围", it) }
            KeyValueRow("总行数", "${preview.totalRows}")
        }
    }

    Spacer(Modifier.height(Spacing.gapM))
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            Text("将写入", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapS))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapXl)) {
                BigCount("新增", preview.newRows, MaterialTheme.colorScheme.primary)
                BigCount("重复跳过", preview.duplicateRows, MaterialTheme.colorScheme.onSurfaceVariant)
                BigCount("自动排除", preview.excludedRows, IncomeGreen)
                BigCount(
                    "待归类",
                    preview.unclassifiedRows,
                    if (preview.unclassifiedRows > 0) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.unclassifiedRows > 0) {
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "待归类的流水会计入支出但不知道属于哪一类，导入后可以在流水页批量归类。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.rejected.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "另有 ${preview.rejected.size} 行无法解析，将被跳过（下方列出原因）。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(Spacing.gapM))
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            Text("识别到的列", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapS))
            preview.columnMapping.forEach { (role, header) ->
                KeyValueRow(role.label, header)
            }
            if (preview.unmappedColumns.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "未使用：${preview.unmappedColumns.joinToString("、")}",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (preview.warnings.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.gapM))
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                Text("解析提示", style = LedgerType.sectionTitle)
                Spacer(Modifier.height(Spacing.gapS))
                preview.warnings.forEach {
                    Text("· $it", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Spacer(Modifier.height(Spacing.gapM))
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            Text("前 ${preview.samples.size} 条样例", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapS))
            preview.samples.forEach { sample ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            sample.counterparty ?: sample.product ?: "—",
                            style = LedgerType.caption,
                            maxLines = 1,
                        )
                        Text(
                            buildString {
                                append(DateX.formatDate(sample.occurredAt))
                                append(" · ")
                                append(if (sample.duplicate) "重复" else if (sample.excludeReason != null) "排除" else "新增")
                                if (sample.excludeReason != null) {
                                    append("（")
                                    append(reasonLabel(sample.excludeReason))
                                    append("）")
                                }
                            },
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        (if (sample.direction == TxDirection.INCOME) "+" else "") + Money.format(sample.amountCents),
                        style = LedgerType.captionAmount,
                        color = if (sample.direction == TxDirection.INCOME) IncomeGreen else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (preview.rejected.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.gapM))
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                Text("被跳过的行", style = LedgerType.sectionTitle)
                Spacer(Modifier.height(Spacing.gapS))
                preview.rejected.take(10).forEach {
                    KeyValueRow("第 ${it.lineNumber} 行", it.reason)
                }
                if (preview.rejected.size > 10) {
                    Text(
                        "另有 ${preview.rejected.size - 10} 行…",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(Spacing.gapL))
    PrimaryButton(
        text = "确认导入 ${preview.newRows} 笔",
        icon = Icons.Outlined.Upload,
        enabled = preview.newRows > 0,
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.gapS))
    SecondaryButton(text = "取消", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DoneContent(
    step: ImportStep.Done,
    onImportAnother: () -> Unit,
    onUndo: () -> Unit,
) {
    Spacer(Modifier.height(Spacing.gapL))
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            androidx.compose.material3.Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.gapS))
            Text("导入完成", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapM))
            KeyValueRow("来源", TxSource.valueOf(step.report.source.name).label)
            KeyValueRow("新增", "${step.report.inserted} 笔")
            KeyValueRow("重复跳过", "${step.report.duplicates} 笔")
            KeyValueRow("自动排除", "${step.report.excluded} 笔")
            KeyValueRow("待归类", "${step.report.unclassified} 笔")
            KeyValueRow("无法解析", "${step.report.rejected} 行")
        }
    }
    Spacer(Modifier.height(Spacing.gapL))
    PrimaryButton(text = "再导入一份", onClick = onImportAnother, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.gapS))
    SecondaryButton(text = "撤销这次导入", onClick = onUndo, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun BigCount(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text("$value", style = LedgerType.largeAmount, color = color)
    }
}

private fun reasonLabel(reason: ExcludeReason): String = reason.label
