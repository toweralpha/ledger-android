package com.xiaozhangben.ledger.data.repository

import com.xiaozhangben.ledger.core.LogX
import com.xiaozhangben.ledger.data.local.ImportBatchEntity
import com.xiaozhangben.ledger.domain.bill.BillParseResult
import com.xiaozhangben.ledger.domain.bill.BillParser
import com.xiaozhangben.ledger.domain.bill.Dedupe
import com.xiaozhangben.ledger.domain.bill.ImportPreview
import com.xiaozhangben.ledger.domain.bill.ImportReport
import com.xiaozhangben.ledger.domain.bill.ParsedBill
import com.xiaozhangben.ledger.domain.bill.PreviewSample
import com.xiaozhangben.ledger.domain.categorize.ClassifyInput
import com.xiaozhangben.ledger.domain.model.TxSource
import java.util.UUID

/**
 * Two-phase bill import: parse into a preview, then write only on confirmation.
 *
 * The same service covers both Alipay and WeChat because the only real differences
 * are the file encoding and the column names, both of which [BillParser] resolves.
 */
class ImportRepository(private val ledger: LedgerRepository) {

    /** Number of rows shown in the preview table. */
    private val sampleLimit = 20

    suspend fun preview(fileName: String, bytes: ByteArray): Result<ImportPreview> {
        val parsed = when (val result = BillParser.parse(bytes)) {
            is BillParseResult.Error -> return Result.failure(IllegalArgumentException(result.message))
            is BillParseResult.Ok -> result.bill
        }

        return runCatching { buildPreview(fileName, bytes, parsed) }
    }

    private suspend fun buildPreview(fileName: String, bytes: ByteArray, bill: ParsedBill): ImportPreview {
        // Compute every row's fingerprint first, then ask the database once which of
        // them already exist. One query for the whole file rather than N.
        val keys = bill.rows.map { row ->
            Dedupe.keyFor(
                source = bill.source,
                sourceTxnId = row.txnId,
                occurredAt = row.occurredAt,
                amountCents = row.amountCents,
                counterpartyRaw = row.counterpartyRaw,
                productDesc = row.productDesc,
            )
        }
        val existing = ledger.existingDedupeKeys(keys)

        val newIndices = ArrayList<Int>(bill.rows.size)
        var duplicates = 0
        var excluded = 0
        var unclassified = 0
        val samples = ArrayList<PreviewSample>(sampleLimit)

        bill.rows.forEachIndexed { index, row ->
            val classification = ledger.classify(
                ClassifyInput(
                    source = bill.source,
                    direction = row.direction,
                    rawType = row.rawType,
                    status = row.status,
                    counterpartyRaw = row.counterpartyRaw,
                    productDesc = row.productDesc,
                    payMethod = row.payMethod,
                )
            )
            val isDuplicate = keys[index] in existing
            if (isDuplicate) duplicates++ else newIndices += index
            if (classification.excludeReason != null) excluded++
            if (classification.needsTriage) unclassified++

            if (samples.size < sampleLimit) {
                samples += PreviewSample(
                    occurredAt = row.occurredAt,
                    amountCents = row.amountCents,
                    direction = row.direction,
                    counterparty = row.counterpartyRaw,
                    product = row.productDesc,
                    categoryKey = classification.categoryKey,
                    excludeReason = classification.excludeReason,
                    matchedRuleLabel = classification.matchedRuleLabel,
                    confidence = classification.confidence,
                    duplicate = isDuplicate,
                )
            }
        }

        return ImportPreview(
            source = bill.source,
            fileName = fileName,
            encoding = bill.encoding,
            rangeHint = bill.rangeHint,
            columnMapping = bill.columnMapping,
            unmappedColumns = bill.unmappedColumns,
            warnings = bill.warnings,
            totalRows = bill.rows.size,
            newRows = newIndices.size,
            duplicateRows = duplicates,
            excludedRows = excluded,
            unclassifiedRows = unclassified,
            rejected = bill.rejected,
            samples = samples,
            newRowIndices = newIndices,
            bill = bill,
        )
    }

    /** Writes the rows the preview marked as new. Idempotent: re-running inserts nothing. */
    suspend fun commit(preview: ImportPreview, now: Long = System.currentTimeMillis()): ImportReport {
        val batchId = UUID.randomUUID().toString()
        val bill = preview.bill

        var inserted = 0
        var duplicates = 0
        var excluded = 0
        var unclassified = 0

        for (index in preview.newRowIndices) {
            val row = bill.rows[index]
            val outcome = ledger.insert(
                NewTransaction(
                    source = bill.source,
                    direction = row.direction,
                    occurredAt = row.occurredAt,
                    amountCents = row.amountCents,
                    rawType = row.rawType,
                    status = row.status,
                    counterpartyRaw = row.counterpartyRaw,
                    productDesc = row.productDesc,
                    payMethod = row.payMethod,
                    sourceTxnId = row.txnId,
                    merchantOrderId = row.merchantOrderId,
                    remark = row.remark,
                    importBatchId = batchId,
                ),
                now = now,
            )
            when (outcome) {
                is InsertOutcome.Inserted -> {
                    inserted++
                    if (outcome.classification.excludeReason != null) excluded++
                    if (outcome.classification.needsTriage) unclassified++
                }
                is InsertOutcome.Duplicate -> duplicates++
                is InsertOutcome.Rejected -> Unit // counted as rejected in the report
            }
        }

        val batch = ImportBatchEntity(
            id = batchId,
            fileName = preview.fileName,
            fileSha256 = Dedupe.sha256Hex(preview.fileName + "|" + bill.rows.size + "|" + preview.rangeHint),
            source = bill.source.name,
            encoding = bill.encoding,
            rangeHint = preview.rangeHint,
            rowCount = preview.totalRows,
            newCount = inserted,
            dupCount = duplicates,
            excludedCount = excluded,
            unclassifiedCount = unclassified,
            rejectedCount = bill.rejected.size,
            importedAt = now,
        )
        ledger.recordImportBatch(batch)

        LogX.count("ImportRepository", "inserted", inserted)
        LogX.count("ImportRepository", "duplicates", duplicates)

        return ImportReport(
            batchId = batchId,
            inserted = inserted,
            duplicates = duplicates,
            rejected = bill.rejected.size,
            excluded = excluded,
            unclassified = unclassified,
            source = bill.source,
        )
    }

    /** Undo an import wholesale; the rows carry the batch id. */
    suspend fun undoImport(batchId: String): Int = ledger.deleteImportBatch(batchId)

    fun isSupportedSource(source: TxSource): Boolean =
        source == TxSource.ALIPAY || source == TxSource.WECHAT
}
