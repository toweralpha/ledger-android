package com.xiaozhangben.ledger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.data.local.CategoryRuleEntity
import com.xiaozhangben.ledger.domain.model.RuleOrigin
import com.xiaozhangben.ledger.ui.common.AlertStrip
import com.xiaozhangben.ledger.ui.common.CategoryPickerSheet
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.TextInputDialog
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.domain.budget.AlertLevel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * The rule table.
 *
 * Rules are shown rather than hidden because they are the explanation for every
 * automatic decision the app makes. Being able to see and disable "星巴克 → 餐饮" is
 * what turns a black box into something the user can debug themselves.
 */
@Composable
fun RulesScreen(
    viewModel: RulesViewModel = containerViewModel { RulesViewModel(it) },
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    var addingPattern by remember { mutableStateOf<String?>(null) }
    var pickingTargetFor by remember { mutableStateOf<CategoryRuleEntity?>(null) }

    val learned = rules.filter { it.origin == RuleOrigin.LEARNED.name }
    val user = rules.filter { it.origin == RuleOrigin.USER.name }
    val preset = rules.filter { it.origin == RuleOrigin.PRESET.name }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(Spacing.gapS))
        Text("归类规则", style = LedgerType.screenTitle)
        Spacer(Modifier.height(Spacing.gapS))
        AlertStrip(
            text = "改一次分类就会生成一条「已学习」规则，优先级高于内置规则库。",
            tone = AlertLevel.OK,
        )

        if (user.isNotEmpty()) {
            SectionHeader(
                title = "手动规则（${user.size}）",
                actionLabel = "新建",
                onAction = { addingPattern = "" },
            )
            RuleCard(user, onToggle = viewModel::setEnabled, onDelete = viewModel::delete, onRetarget = { pickingTargetFor = it })
        } else {
            SectionHeader(
                title = "手动规则",
                actionLabel = "新建",
                onAction = { addingPattern = "" },
            )
            LedgerCard(Modifier.fillMaxWidth()) {
                Text(
                    "还没有手动规则。一般不需要自己写 —— 在流水里改一次分类就会自动学会。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (learned.isNotEmpty()) {
            SectionHeader("已学习（${learned.size}）")
            RuleCard(learned, onToggle = viewModel::setEnabled, onDelete = viewModel::delete, onRetarget = { pickingTargetFor = it })
        }

        SectionHeader("内置规则库（${preset.size}）")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "内置了常见商户关键词。可以停用某一条，但不会删除。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.gapM))
                preset.take(40).forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            rule.pattern,
                            style = LedgerType.caption,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            rule.categoryKey ?: (rule.excludeReason ?: "—"),
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(Spacing.gapS))
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { viewModel.setEnabled(rule.id, it) },
                        )
                    }
                }
                if (preset.size > 40) {
                    Spacer(Modifier.height(Spacing.gapS))
                    Text(
                        "只显示前 40 条，共 ${preset.size} 条。",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    addingPattern?.let { initial ->
        TextInputDialog(
            title = "新建手动规则",
            initial = initial,
            label = "商户关键词",
            supporting = "归一化后的商户名包含这个词时，就会按规则归类。",
            onConfirm = { pattern ->
                pickingTargetFor = CategoryRuleEntity(
                    id = -1L,
                    priority = 400,
                    pattern = pattern,
                    target = "COUNTERPARTY",
                    matchType = "CONTAINS",
                    kind = "VARIABLE",
                    origin = RuleOrigin.USER.name,
                    label = pattern,
                    updatedAt = 0L,
                )
                addingPattern = null
            },
            onDismiss = { addingPattern = null },
        )
    }

    pickingTargetFor?.let { rule ->
        CategoryPickerSheet(
            title = "归到哪个分类",
            subtitle = rule.pattern,
            categories = categories,
            selectedKey = rule.categoryKey,
            onPick = { key ->
                if (rule.id >= 0L) {
                    viewModel.setCategory(rule.id, key)
                } else {
                    viewModel.addRule(rule.pattern, key)
                }
                pickingTargetFor = null
            },
            onDismiss = { pickingTargetFor = null },
        )
    }
}

@Composable
private fun RuleCard(
    rules: List<CategoryRuleEntity>,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onRetarget: (CategoryRuleEntity) -> Unit,
) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Column {
            rules.forEachIndexed { index, rule ->
                if (index > 0) Spacer(Modifier.height(Spacing.gapS))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onRetarget(rule) },
                    ) {
                        Text(rule.pattern, style = LedgerType.body)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                append(rule.categoryKey ?: rule.excludeReason ?: "—")
                                if (rule.hitCount > 0) append(" · 命中 ${rule.hitCount} 次")
                            },
                            style = LedgerType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule.id, it) })
                    if (rule.origin != RuleOrigin.PRESET.name) {
                        Spacer(Modifier.size(Spacing.gapS))
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = DangerRed,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onDelete(rule.id) }
                                .padding(4.dp)
                                .size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
