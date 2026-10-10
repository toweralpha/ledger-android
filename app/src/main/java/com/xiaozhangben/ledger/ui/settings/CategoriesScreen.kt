package com.xiaozhangben.ledger.ui.settings

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.ui.common.CategoryAvatar
import com.xiaozhangben.ledger.ui.common.CategoryVisuals
import com.xiaozhangben.ledger.ui.common.ConfirmDialog
import com.xiaozhangben.ledger.ui.common.LedgerCard
import com.xiaozhangben.ledger.ui.common.PrimaryButton
import com.xiaozhangben.ledger.ui.common.SectionHeader
import com.xiaozhangben.ledger.ui.common.containerViewModel
import com.xiaozhangben.ledger.ui.theme.DangerRed
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

@Composable
fun CategoriesScreen(
    viewModel: CategoriesViewModel = containerViewModel { CategoriesViewModel(it) },
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.pageHorizontal),
    ) {
        SectionHeader(
            title = "分类管理",
            actionLabel = "新建",
            onAction = { adding = true },
        )

        val userFacing = categories.filterNot { CategoryVisuals.isSystemKey(it.key) }
        val system = categories.filter { CategoryVisuals.isSystemKey(it.key) }

        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                userFacing.forEachIndexed { index, category ->
                    if (index > 0) Spacer(Modifier.height(Spacing.gapM))
                    CategoryRow(
                        category = category,
                        onClick = { editing = category },
                        onDelete = { pendingDelete = category },
                    )
                }
            }
        }

        SectionHeader("系统分类")
        LedgerCard(Modifier.fillMaxWidth()) {
            Column {
                system.forEachIndexed { index, category ->
                    if (index > 0) Spacer(Modifier.height(Spacing.gapM))
                    CategoryRow(
                        category = category,
                        onClick = { editing = category },
                        onDelete = null,
                    )
                }
                Spacer(Modifier.height(Spacing.gapS))
                Text(
                    "「待归类」是没能匹配任何规则的流水暂时落脚的地方，不会被静默并进「其他」。",
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(96.dp))
    }

    if (adding) {
        CategoryEditorSheet(
            existing = null,
            onSave = { name, icon, color ->
                viewModel.add(name, icon, color, "VARIABLE")
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    editing?.let { category ->
        CategoryEditorSheet(
            existing = category,
            onSave = { name, icon, color ->
                viewModel.update(category, name, icon, color)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    pendingDelete?.let { category ->
        ConfirmDialog(
            title = "删除「${category.name}」？",
            message = "已经归到这个分类的流水会变成「待归类」，需要重新归类。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.remove(category)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CategoryRow(
    category: CategoryEntity,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryAvatar(
            icon = CategoryVisuals.icon(category.iconKey),
            color = CategoryVisuals.color(category.colorHex),
            size = 34.dp,
        )
        Spacer(Modifier.size(Spacing.gapM))
        Text(category.name, style = LedgerType.body, modifier = Modifier.weight(1f))
        if (category.builtIn) {
            Text("内置", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDelete != null) {
            Spacer(Modifier.size(Spacing.gapS))
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = DangerRed,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorSheet(
    existing: CategoryEntity?,
    onSave: (name: String, iconKey: String, colorHex: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var iconKey by remember { mutableStateOf(existing?.iconKey ?: CategoryVisuals.ICON_CHOICES.first()) }
    var colorHex by remember { mutableStateOf(existing?.colorHex ?: CategoryVisuals.COLOR_CHOICES.first()) }

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
            Text(if (existing == null) "新建分类" else "编辑分类", style = LedgerType.sectionTitle)
            Spacer(Modifier.height(Spacing.gapL))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(8) },
                label = { Text("名称", style = LedgerType.caption) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.gapL))
            Text("图标", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapS))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gapS)) {
                items(CategoryVisuals.ICON_CHOICES) { key ->
                    CategoryAvatar(
                        icon = CategoryVisuals.icon(key),
                        color = CategoryVisuals.color(colorHex),
                        size = 40.dp,
                        selected = key == iconKey,
                        modifier = Modifier.clickable { iconKey = key },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.gapL))
            Text("颜色", style = LedgerType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.gapS))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(CategoryVisuals.COLOR_CHOICES) { hex ->
                    val selected = hex == colorHex
                    Box(
                        Modifier
                            .size(if (selected) 34.dp else 26.dp)
                            .clip(CircleShape)
                            .background(CategoryVisuals.color(hex))
                            .clickable { colorHex = hex },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.gapL))
            PrimaryButton(
                text = "保存",
                enabled = name.isNotBlank(),
                onClick = { onSave(name, iconKey, colorHex) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.gapXl))
        }
    }
}
