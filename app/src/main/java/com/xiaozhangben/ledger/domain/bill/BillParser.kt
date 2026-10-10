package com.xiaozhangben.ledger.domain.bill

import com.xiaozhangben.ledger.core.DateX
import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource

/**
 * Parses an official Alipay or WeChat bill export.
 *
 * Both exports are "CSV with an attitude": a preamble of metadata lines, a header
 * row that must be located by content, data rows, then a footer of summary lines.
 * Column names also drift between export versions, so columns are resolved by
 * fuzzy keyword match and reported back to the user in the import preview.
 *
 * Design rules:
 *  - never guess a column index
 *  - never silently drop a row: unparseable rows are returned in [ParsedBill.rejected]
 *  - never repair a malformed row invisibly: repairs are reported as warnings
 */
object BillParser {

    private val ROLE_KEYWORDS: List<Pair<ColumnRole, List<String>>> = listOf(
        ColumnRole.TIME to listOf("交易时间", "交易创建时间", "创建时间", "时间"),
        ColumnRole.DIRECTION to listOf("收/支", "收入/支出", "收支", "收付"),
        ColumnRole.AMOUNT to listOf("金额(元)", "金额", "交易金额"),
        // Merchant order id must be resolved before the generic order id, otherwise
        // "商家订单号" gets claimed by the "订单号" keyword.
        ColumnRole.MERCHANT_ORDER_ID to listOf("商家订单号", "商户单号"),
        ColumnRole.TXN_ID to listOf("交易订单号", "交易单号", "订单号", "流水号"),
        ColumnRole.TYPE to listOf("交易分类", "交易类型"),
        ColumnRole.STATUS to listOf("当前状态", "交易状态", "状态"),
        ColumnRole.COUNTERPARTY to listOf("交易对方", "交易对象", "商户名称", "对方"),
        ColumnRole.PRODUCT to listOf("商品说明", "商品名称", "商品", "说明"),
        ColumnRole.PAY_METHOD to listOf("收/付款方式", "支付方式", "付款方式", "付款渠道"),
        ColumnRole.REMARK to listOf("备注"),
    ).map { (role, keys) -> role to keys.map { normalizeHeader(it) } }

    private val RANGE_START = Regex("[起开]始[时日]期?间?\\s*[：:]\\s*\\[?([^\\]\\n]{4,32})")
    private val RANGE_END = Regex("终止[时日]期?间?\\s*[：:]\\s*\\[?([^\\]\\n]{4,32})")

    private const val HEADER_SEARCH_LIMIT = 80

    private val PURE_AMOUNT = Regex("^\\d+(\\.\\d{1,2})?$")
    // WeChat writes "¥1,299.00", so the head of a split thousands separator may carry
    // the currency symbol: cells are "¥1" and "299.00".
    private val THOUSANDS_HEAD = Regex("^[¥￥]?\\d{1,3}$")
    private val THOUSANDS_TAIL = Regex("^\\d{3}(\\.\\d{1,2})?$")
    private val DIRECTION_VALUES = setOf("支出", "收入", "不计收支", "/", "")

    fun parse(bytes: ByteArray, sourceHint: TxSource? = null): BillParseResult {
        if (bytes.isEmpty()) return BillParseResult.Error("文件是空的")

        val decoded = CharsetDetector.decode(bytes)
        val text = decoded.text
        val rows = CsvParser.parse(text)
        if (rows.isEmpty()) return BillParseResult.Error("文件里没有任何可解析的行")

        val warnings = ArrayList<String>(4)
        if (decoded.encoding.startsWith("GB18030")) {
            warnings += "文件按 GB18030 解码（支付宝账单的常见编码），中文商户名已正确还原"
        }

        // --- locate the header row ------------------------------------------------
        var headerIndex = -1
        var mapping: Map<ColumnRole, Int> = emptyMap()
        var headerCells: List<String> = emptyList()

        for (i in 0 until minOf(rows.size, HEADER_SEARCH_LIMIT)) {
            val normalized = rows[i].cells.map { normalizeHeader(it) }
            val candidate = mapColumns(normalized)
            val usable = candidate.containsKey(ColumnRole.TIME) && candidate.containsKey(ColumnRole.AMOUNT) &&
                (candidate.containsKey(ColumnRole.DIRECTION) ||
                    candidate.containsKey(ColumnRole.TYPE) ||
                    candidate.containsKey(ColumnRole.COUNTERPARTY))
            if (usable) {
                headerIndex = i
                mapping = candidate
                headerCells = normalized
                break
            }
        }

        if (headerIndex < 0) {
            return BillParseResult.Error(
                message = "没能识别出账单表头",
                detail = "前 $HEADER_SEARCH_LIMIT 行里没有找到包含「交易时间」和「金额」的表头行。" +
                    "请确认导入的是支付宝「交易流水证明」或微信「支付账单明细」导出的 CSV，而不是截图或其他表格。",
            )
        }

        val preamble = rows.take(headerIndex).joinToString("\n") { it.cells.joinToString(" ") }
        val source = sourceHint ?: detectSource(preamble, headerCells)
        val rangeHint = extractRange(preamble)

        val unmapped = headerCells.filterIndexed { idx, name -> name.isNotBlank() && mapping.values.none { it == idx } }

        val timeIdx = mapping.getValue(ColumnRole.TIME)
        val amountIdx = mapping.getValue(ColumnRole.AMOUNT)
        val directionIdx = mapping[ColumnRole.DIRECTION]
        val typeIdx = mapping[ColumnRole.TYPE]
        val statusIdx = mapping[ColumnRole.STATUS]
        val counterpartyIdx = mapping[ColumnRole.COUNTERPARTY]
        val productIdx = mapping[ColumnRole.PRODUCT]
        val payMethodIdx = mapping[ColumnRole.PAY_METHOD]
        val txnIdx = mapping[ColumnRole.TXN_ID]
        val merchantOrderIdx = mapping[ColumnRole.MERCHANT_ORDER_ID]
        val remarkIdx = mapping[ColumnRole.REMARK]

        val headerSize = headerCells.size
        val parsed = ArrayList<ParsedRow>(rows.size)
        val rejected = ArrayList<RejectedRow>(8)
        var repairedRows = 0

        for (i in headerIndex + 1 until rows.size) {
            val raw = rows[i]
            val cells = raw.cells

            // Footer separators ("------") and summary lines ("共 12 笔记录").
            if (cells.isEmpty()) continue
            val joined = cells.joinToString(",")
            if (joined.isBlank()) continue
            if (cells.size == 1 && cells[0].trim().startsWith("---")) break
            if (joined.contains("共") && joined.contains("笔")) break
            if (cells.size == 1 && cells[0].trim().isEmpty()) continue

            val (fitted, repairNote) = fitRow(cells, headerSize, mapping)
            if (repairNote != null) {
                repairedRows++
                if (repairedRows <= 3) warnings += "第 ${raw.lineNumber} 行：$repairNote"
            }

            if (fitted.size < 5) {
                rejected += RejectedRow(raw.lineNumber, "列数过少，疑似表尾或空行", joined.take(80))
                continue
            }

            val timeText = fitted.getOrNull(timeIdx)
            val occurredAt = DateX.parseBillTimestamp(timeText)
            if (occurredAt == null) {
                rejected += RejectedRow(raw.lineNumber, "无法解析交易时间「${timeText.orEmpty().take(24)}」", joined.take(80))
                continue
            }

            val amountText = fitted.getOrNull(amountIdx)
            val amountSigned = Money.parseToCentsOrNull(amountText)
            if (amountSigned == null) {
                rejected += RejectedRow(raw.lineNumber, "无法解析金额「${amountText.orEmpty().take(24)}」", joined.take(80))
                continue
            }
            if (amountSigned == 0L) {
                rejected += RejectedRow(raw.lineNumber, "金额为 0", joined.take(80))
                continue
            }

            val direction = parseDirection(
                directionIdx?.let { fitted.getOrNull(it) },
                amountSigned,
            )

            parsed += ParsedRow(
                lineNumber = raw.lineNumber,
                occurredAt = occurredAt,
                amountCents = if (amountSigned < 0) -amountSigned else amountSigned,
                direction = direction,
                rawType = typeIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                status = statusIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                counterpartyRaw = counterpartyIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                productDesc = productIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                payMethod = payMethodIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                txnId = txnIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                merchantOrderId = merchantOrderIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
                remark = remarkIdx?.let { fitted.getOrNull(it) }?.trim()?.ifBlank { null },
            )
        }

        if (repairedRows > 3) {
            warnings += "共修复 $repairedRows 行含未转义逗号的记录（多余字段已合并回商品说明列）"
        }
        if (parsed.isEmpty()) {
            return BillParseResult.Error(
                message = "表头识别成功，但没有解析出任何一条能用的记录",
                detail = "共跳过 ${rejected.size} 行。第一条跳过原因：${rejected.firstOrNull()?.reason ?: "未知"}",
            )
        }

        return BillParseResult.Ok(
            ParsedBill(
                source = source,
                encoding = decoded.encoding,
                headerLineNumber = rows[headerIndex].lineNumber,
                columnMapping = mapping.entries.associate { (role, idx) -> role to headerCells[idx] },
                unmappedColumns = unmapped,
                rangeHint = rangeHint,
                rows = parsed,
                rejected = rejected,
                warnings = warnings,
            )
        )
    }

    // ---- column resolution -------------------------------------------------------

    private fun mapColumns(headers: List<String>): Map<ColumnRole, Int> {
        val mapping = LinkedHashMap<ColumnRole, Int>()
        val claimed = HashSet<Int>()

        for ((role, keywords) in ROLE_KEYWORDS) {
            var bestIndex = -1
            var bestKeywordLength = -1
            headers.forEachIndexed { idx, header ->
                if (idx in claimed || header.isBlank()) return@forEachIndexed
                // Longest matching keyword wins, so "交易对方" beats a bare "对方"
                // and "商家订单号" is not taken by the generic "订单号" rule.
                val hit = keywords.filter { header.contains(it) }.maxByOrNull { it.length } ?: return@forEachIndexed
                if (hit.length > bestKeywordLength) {
                    bestKeywordLength = hit.length
                    bestIndex = idx
                }
            }
            if (bestIndex >= 0) {
                mapping[role] = bestIndex
                claimed += bestIndex
            }
        }
        return mapping
    }

    private fun normalizeHeader(raw: String): String = raw
        .replace('\uFEFF', ' ')
        .replace('（', '(')
        .replace('）', ')')
        .replace(" ", "")
        .replace("\t", "")
        .trim()
        .lowercase()

    // ---- row handling ------------------------------------------------------------

    /**
     * Makes a row match the header width.
     *
     * A row with *extra* cells means an unquoted comma somewhere. There are two
     * common causes and they need opposite repairs:
     *
     *  - a comma inside the product description: `咖啡,拿铁` — the surplus belongs to
     *    the product column
     *  - a thousands separator in the amount: `1,234.56` — the surplus belongs to the
     *    amount column
     *
     * Guessing wrong shifts every following column, so instead of assuming an anchor
     * we try each possible merge point and score the result on whether the amount,
     * timestamp and direction columns then parse. A separate, decisive bonus
     * recognises the `1` + `234.56` split that only a thousands separator produces.
     * The repair is always reported, never silent.
     */
    private fun fitRow(
        cells: List<String>,
        headerSize: Int,
        mapping: Map<ColumnRole, Int>,
    ): Pair<List<String>, String?> {
        if (cells.size == headerSize) return cells to null
        if (cells.size < headerSize) {
            return (cells + List(headerSize - cells.size) { "" }) to null
        }

        val extra = cells.size - headerSize
        var best: List<String>? = null
        var bestScore = Int.MIN_VALUE

        for (anchor in 0 until headerSize) {
            val candidate = ArrayList<String>(headerSize)
            for (i in 0 until anchor) candidate += cells[i]
            candidate += cells.subList(anchor, anchor + extra + 1).joinToString(",")
            for (i in anchor + extra + 1 until cells.size) candidate += cells[i]

            val score = scoreAlignment(candidate, mapping, anchor, cells, extra)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        val repaired = best ?: return cells.take(headerSize) to "列数异常，仅保留前 $headerSize 列"
        return repaired to "检测到 $extra 个未转义的逗号，已按可解析性重新对齐"
    }

    /** Higher is better. Only ever used to choose between candidate alignments. */
    private fun scoreAlignment(
        candidate: List<String>,
        mapping: Map<ColumnRole, Int>,
        anchor: Int,
        originalCells: List<String>,
        extra: Int,
    ): Int {
        var score = 0

        val amountIdx = mapping[ColumnRole.AMOUNT]
        if (amountIdx != null) {
            val cell = candidate.getOrNull(amountIdx)
            if (isWellFormedAmount(cell)) score += 10
            // `1` followed by `234.56` is the signature of a split thousands
            // separator, and nothing else in a bill looks like it.
            if (anchor == amountIdx && extra == 1) {
                val head = originalCells.getOrNull(amountIdx)?.trim().orEmpty()
                val tail = originalCells.getOrNull(amountIdx + 1)?.trim().orEmpty()
                if (head.matches(THOUSANDS_HEAD) && tail.matches(THOUSANDS_TAIL)) score += 6
            }
        }

        mapping[ColumnRole.TIME]?.let { idx ->
            if (DateX.parseBillTimestamp(candidate.getOrNull(idx)) != null) score += 6
        }
        mapping[ColumnRole.DIRECTION]?.let { idx ->
            if (candidate.getOrNull(idx)?.trim() in DIRECTION_VALUES) score += 3
        }
        // A surplus that lands in a free-text column is the other common case.
        if (anchor == mapping[ColumnRole.PRODUCT]) score += 3

        return score
    }

    /**
     * A genuine amount cell contains only digits, one optional decimal point and a
     * currency symbol. `32.00,余额宝` is rejected here, which is precisely what stops
     * the amount column from absorbing its neighbour during realignment.
     */
    private fun isWellFormedAmount(cell: String?): Boolean {
        if (cell == null) return false
        val stripped = cell.trim()
            .removePrefix("¥").removePrefix("￥")
            .replace(",", "")
            .replace(" ", "")
        return stripped.matches(PURE_AMOUNT)
    }

    private fun parseDirection(raw: String?, amountCents: Long): TxDirection {
        val s = raw?.trim().orEmpty()
        if (s.contains("收入")) return TxDirection.INCOME
        if (s.contains("支出")) return TxDirection.EXPENSE
        // Signed exports: a negative amount is money leaving the account.
        if (amountCents < 0) return TxDirection.EXPENSE
        // WeChat writes "/" and Alipay writes "不计收支" for rows that move nothing.
        return TxDirection.NEUTRAL
    }

    // ---- source / metadata -------------------------------------------------------

    private fun detectSource(preamble: String, headers: List<String>): TxSource {
        if (headers.any { it.contains("商户单号") || it.contains("当前状态") }) return TxSource.WECHAT
        if (headers.any { it.contains("商品说明") || it.contains("交易分类") || it.contains("商家订单号") }) return TxSource.ALIPAY
        if (preamble.contains("微信")) return TxSource.WECHAT
        if (preamble.contains("支付宝")) return TxSource.ALIPAY
        return TxSource.UNKNOWN
    }

    private fun extractRange(preamble: String): String? {
        val start = RANGE_START.find(preamble)?.groupValues?.getOrNull(1)?.trim()
        val end = RANGE_END.find(preamble)?.groupValues?.getOrNull(1)?.trim()
        return when {
            start != null && end != null -> "$start ~ $end"
            start != null -> start
            else -> null
        }
    }
}
