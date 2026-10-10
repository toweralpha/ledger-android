package com.xiaozhangben.ledger.domain.bill

import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource

/**
 * What the user is shown *before* anything is written.
 *
 * Importing a bill is the one operation that can flood the ledger with hundreds of
 * rows at once, so it is explicitly two-phase: preview, then commit. The preview
 * reports the column mapping that was inferred and how many rows will be new,
 * duplicate, excluded or unclassified — the difference between the user noticing a
 * mis-parsed file and silently ruining their budget.
 */
data class ImportPreview(
    val source: TxSource,
    val fileName: String,
    val encoding: String,
    val rangeHint: String?,
    val columnMapping: Map<ColumnRole, String>,
    val unmappedColumns: List<String>,
    val warnings: List<String>,
    val totalRows: Int,
    val newRows: Int,
    val duplicateRows: Int,
    val excludedRows: Int,
    val unclassifiedRows: Int,
    val rejected: List<RejectedRow>,
    val samples: List<PreviewSample>,
    /** Indices into [ParsedBill.rows] that are actually new and will be written. */
    val newRowIndices: List<Int>,
    val bill: ParsedBill,
) {
    val isFirstImport: Boolean get() = duplicateRows == 0
}

data class PreviewSample(
    val occurredAt: Long,
    val amountCents: Long,
    val direction: TxDirection,
    val counterparty: String?,
    val product: String?,
    val categoryKey: String,
    val excludeReason: ExcludeReason?,
    val matchedRuleLabel: String?,
    val confidence: Float,
    val duplicate: Boolean,
)

data class ImportReport(
    val batchId: String,
    val inserted: Int,
    val duplicates: Int,
    val rejected: Int,
    val excluded: Int,
    val unclassified: Int,
    val source: TxSource,
)
