package com.xiaozhangben.ledger.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.BuildConfig
import com.xiaozhangben.ledger.capture.CaptureHealth
import com.xiaozhangben.ledger.capture.CapturePermissions
import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.data.repository.ThemeMode
import com.xiaozhangben.ledger.ui.common.AlertStrip
import com.xiaozhangben.ledger.ui.common.ChoiceChip
import com.xiaozhangben.ledger.ui.common.ConfirmDialog
import com.xiaozhangben.ledger.ui.common.KeyValueRow
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.PassphraseDialog
import com.xiaozhangben.ledger.ui.common.SecondaryButton
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.IncomeGreen
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * "我的" tab: capture health, preferences, data management and backup.
 *
 * The capture diagnostics are placed above the preferences on purpose — if capture is
 * quietly dead, nothing else in the app matters.
 */
@Composable
fun SettingsScreen(
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenImport: () -> Unit,
    viewModel: SettingsViewModel = containerViewModel { SettingsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backupEvent by viewModel.backupEvent.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showExportPassphrase by remember { mutableStateOf(false) }
    var showImportPassphrase by remember { mutableStateOf(false) }
    var pendingImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var confirmRestore by remember { mutableStateOf<com.xiaozhangben.ledger.data.backup.BackupFile?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }

    // Which manufacturer's battery manager this phone uses. Read once: the build brand
    // cannot change while the app is running.
    val deviceProfile = remember { CapturePermissions.deviceProfile() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshEnvironment()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val bytes = exportBytes
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.onSuccess { viewModel.showMessage("备份已导出") }
                .onFailure { viewModel.showMessage("写入失败：${it.message}") }
        }
        exportBytes = null
        viewModel.consumeBackupEvent()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            viewModel.showMessage("无法读取该文件")
        } else {
            pendingImportBytes = bytes
            showImportPassphrase = true
        }
    }

    LaunchedEffect(backupEvent) {
        when (val event = backupEvent) {
            is BackupEvent.Exported -> {
                exportBytes = event.bytes
                exportLauncher.launch(event.suggestedName)
            }
            is BackupEvent.ReadyToRestore -> confirmRestore = event.file
            is BackupEvent.Restored -> viewModel.showMessage("已恢复 ${event.transactions} 笔流水")
            is BackupEvent.Failed -> viewModel.showMessage(event.message)
            BackupEvent.Idle -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        Text(
            "我的",
            style = LedgerType.screenTitle,
            modifier = Modifier.padding(vertical = Spacing.gapS),
        )

        message?.let {
            AlertStrip(text = it, tone = AlertLevel.OK)
            Spacer(Modifier.height(Spacing.gapM))
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(2_500)
                viewModel.consumeMessage()
            }
        }

        // ---- capture -------------------------------------------------------------
        SectionHeader("实时捕获")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("支付通知自动记账", style = LedgerType.body)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "付款后 1 秒内自动记上，并自动归类。",
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.captureEnabled, onCheckedChange = viewModel::setCaptureEnabled)
                }

                Spacer(Modifier.height(Spacing.gapM))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(Spacing.gapM))

                KeyValueRow(
                    "通知使用权",
                    if (state.listenerAuthorised) "已开启" else "未开启",
                    valueColor = if (state.listenerAuthorised) IncomeGreen else DangerRed,
                )
                if (!state.listenerAuthorised) {
                    Spacer(Modifier.height(Spacing.gapS))
                    SecondaryButton(
                        text = "去开启通知使用权",
                        onClick = { CapturePermissions.openListenerSettings(context) },
                    )
                }

                KeyValueRow(
                    "通知权限",
                    if (state.notificationsAllowed) "已允许" else "被拒绝（记账提示卡不会出现）",
                    valueColor = if (state.notificationsAllowed) IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(Spacing.gapM))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示记账提示卡", style = LedgerType.body)
                        Text(
                            "记账后弹一条通知，可以直接点进去改分类。",
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.showCaptureCard, onCheckedChange = viewModel::setShowCaptureCard)
                }
            }
        }

        Spacer(Modifier.height(Spacing.gapM))
        SectionHeader("后台存活自检")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                // First row on purpose. "Is the listener actually running?" is the question
                // that matters most and it is NOT the same as the permission switch above:
                // the grant can be intact while the system has stopped the service, and
                // that exact gap silently cost two days of unrecorded spending.
                KeyValueRow(
                    "监听器状态",
                    when (state.captureHealth) {
                        CaptureHealth.OK -> "运行中"
                        CaptureHealth.OFF -> "已关闭"
                        CaptureHealth.NOT_AUTHORISED -> "未授权"
                        CaptureHealth.NOT_CONNECTED -> "未连接 —— 正在漏单"
                        CaptureHealth.NOT_RECEIVING -> "已连接，但很久没收到通知"
                    },
                    valueColor = if (state.captureHealth == CaptureHealth.OK) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        DangerRed
                    },
                )
                KeyValueRow(
                    "最近一次捕获",
                    state.stats?.lastCaptureAt?.let { DateX.formatDateTime(it) } ?: "还没有捕获过",
                )
                KeyValueRow("累计捕获", "${state.stats?.capturedCount ?: 0} 笔")
                // The heartbeat. Any notification from WeChat or Alipay updates it, so it
                // stays fresh on a day with no spending — which is what makes it a real
                // liveness signal rather than a spending counter.
                KeyValueRow(
                    "最近收到通知",
                    state.stats?.lastNotificationSeenAt?.let { DateX.formatDateTime(it) } ?: "—",
                )
                KeyValueRow(
                    "监听最近连接",
                    state.stats?.lastListenerConnectedAt?.let { DateX.formatDateTime(it) } ?: "—",
                )
                // Reported rather than announced. The watchdog reconnects on its own; this
                // row is how the user can confirm it is working without being interrupted
                // by a notification, and it counts how often this ROM drops the binding.
                KeyValueRow(
                    "自动重连",
                    "${state.stats?.repairCount ?: 0} 次" +
                        (state.stats?.lastRepairAt?.let { "（最近 " + DateX.formatDateTime(it) + "）" } ?: ""),
                )
                KeyValueRow(
                    "被系统回收次数",
                    "${state.stats?.disconnectedCount ?: 0} 次",
                    valueColor = if ((state.stats?.disconnectedCount ?: 0) > 5) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                KeyValueRow("流水总数", "${state.transactionCount} 笔")

                // The single most useful diagnostic when capture produces nothing: it
                // separates "the payment app never posted a notification" from "we saw
                // one and refused it".
                state.stats?.lastSkipReason?.let { reason ->
                    KeyValueRow(
                        "最近忽略的通知",
                        reason + (state.stats?.lastSkippedAt?.let { "（" + DateX.formatDateTime(it) + "）" } ?: ""),
                        valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val silentDays = state.silentDays
                if (state.captureHealth == CaptureHealth.OK && silentDays != null && silentDays >= 3) {
                    Spacer(Modifier.height(Spacing.gapS))
                    AlertStrip(
                        text = "已经 $silentDays 天没收到任何支付应用通知，可能在漏单。可以导入账单补齐。",
                        tone = AlertLevel.NEAR,
                        actionLabel = "去导入",
                        onAction = onOpenImport,
                    )
                }
                if (state.captureHealth == CaptureHealth.NOT_CONNECTED) {
                    Spacer(Modifier.height(Spacing.gapS))
                    AlertStrip(
                        text = "系统没有在运行监听服务（授权还在，但服务没起来）。" +
                            "把上面的「支付通知自动记账」关掉再打开，或重新开关一次通知使用权。",
                        tone = AlertLevel.OVER,
                        actionLabel = "重试绑定",
                        onAction = { viewModel.retryListenerBinding() },
                    )
                }
                if (!state.listenerAuthorised) {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(
                        "通知使用权没开的话，实时捕获完全不会工作。",
                        style = LedgerType.caption,
                        color = DangerRed,
                    )
                }

                Spacer(Modifier.height(Spacing.gapM))
                Text(
                    "${deviceProfile.oem.displayName} 的后台保活设置",
                    style = LedgerType.body,
                )
                Spacer(Modifier.height(Spacing.gapXs))
                Text(
                    "不设置的话，监听会被系统在几小时内清掉：",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.gapXs))
                deviceProfile.setupSteps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                Spacer(Modifier.height(Spacing.gapM))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                    SecondaryButton(
                        text = "打开自启动设置",
                        onClick = { CapturePermissions.openAutoStartSettings(context) },
                    )
                    SecondaryButton(
                        text = "电池设置",
                        onClick = { CapturePermissions.openBatterySettings(context) },
                    )
                }
            }
        }

        // ---- reminders -----------------------------------------------------------
        SectionHeader("提醒")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                ToggleRow(
                    title = "预算预警",
                    subtitle = "用到 70% / 90% / 超支时提醒一次",
                    checked = state.budgetAlerts,
                    onChange = viewModel::setBudgetAlerts,
                )
                Spacer(Modifier.height(Spacing.gapM))
                ToggleRow(
                    title = "每日小结",
                    subtitle = "每天固定时间汇总当日支出（默认关闭）",
                    checked = state.dailySummary,
                    onChange = viewModel::setDailySummary,
                )
            }
        }

        // ---- appearance ----------------------------------------------------------
        SectionHeader("外观")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                Text("主题", style = LedgerType.body)
                Spacer(Modifier.height(Spacing.gapS))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                    ThemeMode.entries.forEach { mode ->
                        ChoiceChip(
                            label = when (mode) {
                                ThemeMode.SYSTEM -> "跟随系统"
                                ThemeMode.LIGHT -> "浅色"
                                ThemeMode.DARK -> "深色"
                            },
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                        )
                    }
                }
            }
        }

        // ---- data ----------------------------------------------------------------
        SectionHeader("数据")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                NavRow("分类管理", "${state.categoryCount} 个自定义分类", onOpenCategories)
                Spacer(Modifier.height(Spacing.gapM))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(Spacing.gapM))
                NavRow("归类规则", "查看、编辑与停用规则", onOpenRules)
                Spacer(Modifier.height(Spacing.gapM))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(Spacing.gapM))
                NavRow("导入账单", "从支付宝 / 微信账单补齐历史", onOpenImport)
            }
        }

        // ---- backup --------------------------------------------------------------
        SectionHeader("备份")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "导出的是一份加密文件，口令只有你自己知道。换手机时用它迁移。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.lastImportAt != null) {
                    Spacer(Modifier.height(Spacing.gapS))
                    KeyValueRow("最近导入", DateX.formatDateTime(state.lastImportAt!!))
                }
                Spacer(Modifier.height(Spacing.gapM))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                    SecondaryButton(
                        text = "导出备份",
                        icon = Icons.Outlined.FileDownload,
                        onClick = { showExportPassphrase = true },
                    )
                    SecondaryButton(
                        text = "恢复备份",
                        icon = Icons.Outlined.FileUpload,
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }
        }

        // ---- danger --------------------------------------------------------------
        SectionHeader("危险操作")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                SecondaryButton(
                    text = "清空全部流水",
                    onClick = { confirmClear = true },
                )
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "分类、预算设置和规则会保留；流水、导入记录会被删除。建议先导出备份。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- about ---------------------------------------------------------------
        SectionHeader("关于")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                KeyValueRow("版本", BuildConfig.VERSION_NAME)
                KeyValueRow("网络权限", "未申请 INTERNET")
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "所有数据只存在这台手机上，不会上传到任何服务器。付款通知只在本机解析，" +
                        "不写日志。应用不访问支付宝/微信的任何非公开接口，只读取系统通知，" +
                        "以及在你自己导出账单后解析文件。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    if (showExportPassphrase) {
        PassphraseDialog(
            title = "设置备份口令",
            message = "这份备份包含你全部的账单，会用这个口令加密。",
            requireConfirmation = true,
            minLength = SettingsViewModel.MIN_PASSPHRASE,
            onConfirm = {
                showExportPassphrase = false
                viewModel.exportBackup(it)
            },
            onDismiss = { showExportPassphrase = false },
        )
    }

    if (showImportPassphrase) {
        PassphraseDialog(
            title = "输入备份口令",
            message = "读取备份需要原口令。口令错误或文件损坏都会提示失败。",
            confirmLabel = "读取",
            minLength = 1,
            onConfirm = { passphrase ->
                showImportPassphrase = false
                pendingImportBytes?.let { viewModel.readBackup(it, passphrase) }
            },
            onDismiss = {
                showImportPassphrase = false
                pendingImportBytes = null
            },
        )
    }

    confirmRestore?.let { file ->
        ConfirmDialog(
            title = "恢复备份？",
            message = "这会**覆盖**当前手机上的全部流水和预算设置，" +
                "备份里有 ${file.transactions.size} 笔流水（导出于 ${DateX.formatDateTime(file.exportedAt)}）。" +
                "建议先导出当前数据。",
            confirmLabel = "覆盖并恢复",
            destructive = true,
            onConfirm = {
                viewModel.confirmRestore(file)
                confirmRestore = null
            },
            onDismiss = { confirmRestore = null },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空全部流水？",
            message = "流水和导入记录会被删除，分类与预算设置保留。此操作不能撤销。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = {
                viewModel.clearAllData()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LedgerType.body)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LedgerType.body)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = LedgerType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.gapS))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
    }
}
