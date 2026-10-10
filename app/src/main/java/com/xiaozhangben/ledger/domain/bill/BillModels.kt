package com.xiaozhangben.ledger.domain.bill

import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource

/**
 * The semantic columns we need out of an export.
 *
 * Column *names* differ between Alipay and WeChat and between export versions, so
 * everything downstream addresses columns by role. Hard-coding column indexes is
 * the single easiest way to build an importer that silently mis-reads real files.
 */
enum class ColumnRole(val label: String) {
    TIME("交易时间"),
    DIRECTION("收/支"),
    AMOUNT("金额"),
    MERCHANT_ORDER_ID("商家订单号"),
    TXN_ID("交易订单号"),
    TYPE("交易类型"),
    STATUS("交易状态"),
    COUNTERPARTY("交易对方"),
    PRODUCT("商品说明"),
    PAY_METHOD("收/付款方式"),
    REMARK("备注"),
}

/** One successfully parsed bill line. */
data class ParsedRow(
    val lineNumber: Int,
    val occurredAt: Long,
    val amountCents: Long,
    val direction: TxDirection,
    val rawType: String?,
    val status: String?,
    val counterpartyRaw: String?,
    val productDesc: String?,
    val payMethod: String?,
    val txnId: String?,
    val merchantOrderId: String?,
    val remark: String?,
)

/** A line we refused to import, kept visible so nothing disappears silently. */
data class RejectedRow(
    val lineNumber: Int,
    val reason: String,
    val excerpt: String,
)

data class ParsedBill(
    val source: TxSource,
    val encoding: String,
    val headerLineNumber: Int,
    val columnMapping: Map<ColumnRole, String>,
    val unmappedColumns: List<String>,
    /** e.g. "2025-06-01 00:00:00 ~ 2025-06-30 23:59:59", read from the preamble. */
    val rangeHint: String?,
    val rows: List<ParsedRow>,
    val rejected: List<RejectedRow>,
    val warnings: List<String>,
) {
    val totalLines: Int get() = rows.size + rejected.size
}

sealed interface BillParseResult {
    data class Ok(val bill: ParsedBill) : BillParseResult

    /** Unrecoverable: we could not identify the columns, so importing would be guesswork. */
    data class Error(val message: String, val detail: String? = null) : BillParseResult
}
