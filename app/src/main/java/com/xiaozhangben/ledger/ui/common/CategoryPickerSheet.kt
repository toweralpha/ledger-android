package com.xiaozhangben.ledger.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.ui.theme.LedgerType
import com.xiaozhangben.ledger.ui.theme.Spacing

/**
 * Category chooser used by "改分类" and by the plan editor.
 *
 * Also carries the "remember this merchant" switch, because the moment a user
 * corrects a category is exactly the moment they are willing to decide whether the
 * app should learn from it — asking later is asking never.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    title: String,
    categories: List<CategoryEntity>,
    selectedKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    learnEnabled: Boolean = false,
    learn: Boolean = true,
    onLearnChange: (Boolean) -> Unit = {},
    allowUnclassified: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectable = categories.filter {
        allowUnclassified || !CategoryVisuals.isSystemKey(it.key)
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
            Text(title, style = LedgerType.sectionTitle)
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.gapXs))
                Text(
                    subtitle,
                    style = LedgerType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.gapL))

            // Laid out as fixed rows rather than a lazy grid: the list is short and a
            // nested scrollable inside a sheet scrolls badly.
            selectable.chunked(4).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    rowItems.forEach { category ->
                        CategoryCell(
                            category = category,
                            selected = category.key == selectedKey,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(category.key) },
                        )
                    }
                    repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(Spacing.gapM))
            }

            if (learnEnabled) {
                Spacer(Modifier.height(Spacing.gapXs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLearnChange(!learn) }
                        .padding(vertical = Spacing.gapS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = learn, onCheckedChange = onLearnChange)
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "记住这个商户，以后自动归到这一类",
                        style = LedgerType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.gapXl))
        }
    }
}

@Composable
private fun CategoryCell(
    category: CategoryEntity,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.gapS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryAvatar(
            icon = CategoryVisuals.icon(category.iconKey),
            color = CategoryVisuals.color(category.colorHex),
            size = 44.dp,
            selected = selected,
        )
        Spacer(Modifier.height(Spacing.gapS))
        Text(
            text = category.name,
            style = LedgerType.caption,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
