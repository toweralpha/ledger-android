package com.xiaozhangben.ledger.domain.bill

import com.xiaozhangben.ledger.domain.categorize.RawTypeMapping
import com.xiaozhangben.ledger.domain.model.ExcludeReason
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Parser tests against realistic (sanitised) bill fixtures.
 *
 * The fixtures are stored as UTF-8 so they stay readable in the repo, and the test
 * re-encodes them the way the real exporters do: Alipay as GB18030, WeChat as UTF-8
 * with a BOM. That way the encoding-detection path is genuinely exercised rather
 * than bypassed.
 */
class BillParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/bills/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun alipayBytes(): ByteArray = fixture("alipay_sample.csv").toByteArray(Charset.forName("GB18030"))

    private fun wechatBytes(): ByteArray = ("\uFEFF" + fixture("wechat_sample.csv")).toByteArray(Charsets.UTF_8)

    private fun parseAlipay(): ParsedBill = when (val r = BillParser.parse(alipayBytes())) {
        is BillParseResult.Ok -> r.bill
        is BillParseResult.Error -> throw AssertionError("alipay fixture failed: ${r.message} ${r.detail}")
    }

    private fun parseWechat(): ParsedBill = when (val r = BillParser.parse(wechatBytes())) {
        is BillParseResult.Ok -> r.bill
        is BillParseResult.Error -> throw AssertionError("wechat fixture failed: ${r.message} ${r.detail}")
    }

    // ---- Alipay ----------------------------------------------------------------

    @Test
    fun `alipay bill is detected as GB18030 and fully parsed`() {
        val bill = parseAlipay()
        assertEquals(TxSource.ALIPAY, bill.source)
        assertTrue("expected GB18030 fallback, got ${bill.encoding}", bill.encoding.startsWith("GB18030"))
        assertEquals(10, bill.rows.size)
        assertTrue("no row should be rejected: ${bill.rejected}", bill.rejected.isEmpty())
    }

    @Test
    fun `alipay preamble and footer are skipped, range is reported`() {
        val bill = parseAlipay()
        assertNotNull(bill.rangeHint)
        assertTrue(bill.rangeHint!!.contains("2025-06-01"))
        // The footer's "共 10 笔记录" must not become a transaction.
        assertTrue(bill.rows.none { it.amountCents == 199906L })
    }

    @Test
    fun `column mapping is inferred from the header, not from indexes`() {
        val bill = parseAlipay()
        assertEquals("交易时间", bill.columnMapping[ColumnRole.TIME])
        assertEquals("金额", bill.columnMapping[ColumnRole.AMOUNT])
        assertEquals("收/支", bill.columnMapping[ColumnRole.DIRECTION])
        assertEquals("交易对方", bill.columnMapping[ColumnRole.COUNTERPARTY])
        assertEquals("商品说明", bill.columnMapping[ColumnRole.PRODUCT])
        // 对方账号 is present but unused; it must be reported as unmapped, not mis-assigned.
        assertTrue(bill.unmappedColumns.contains("对方账号"))
    }

    @Test
    fun `a thousands separator in the amount column is rejoined correctly`() {
        // Raw cells are "1" and "234.56"; naive parsing yields 100 cents.
        val row = parseAlipay().rows.first { it.txnId == "2025060922004" }
        assertEquals(123456L, row.amountCents)
        assertEquals(TxDirection.EXPENSE, row.direction)
        assertEquals("天猫超市", row.counterpartyRaw)
    }

    @Test
    fun `an unescaped comma in the product column is rejoined correctly`() {
        val row = parseAlipay().rows.first { it.txnId == "2025061422009" }
        assertEquals(2800L, row.amountCents)
        assertEquals("沙县小吃", row.counterpartyRaw)
    }

    @Test
    fun `a quoted product containing a comma stays intact`() {
        val row = parseAlipay().rows.first { it.txnId == "2025061122006" }
        assertEquals("咖啡,拿铁", row.productDesc)
        assertEquals(3200L, row.amountCents)
    }

    @Test
    fun `alipay directions map correctly, including 不计收支`() {
        val rows = parseAlipay().rows
        assertEquals(TxDirection.EXPENSE, rows.first { it.txnId == "2025060322001" }.direction)
        assertEquals(TxDirection.INCOME, rows.first { it.txnId == "2025061222007" }.direction)
        assertEquals(TxDirection.NEUTRAL, rows.first { it.txnId == "2025061022005" }.direction)
    }

    // ---- WeChat ----------------------------------------------------------------

    @Test
    fun `wechat bill is detected as UTF-8 with BOM and fully parsed`() {
        val bill = parseWechat()
        assertEquals(TxSource.WECHAT, bill.source)
        assertTrue("expected BOM detection, got ${bill.encoding}", bill.encoding.contains("BOM"))
        assertEquals(10, bill.rows.size)
        assertTrue("no row should be rejected: ${bill.rejected}", bill.rejected.isEmpty())
    }

    @Test
    fun `wechat column names are mapped even though they differ from Alipay`() {
        val bill = parseWechat()
        assertEquals("金额(元)", bill.columnMapping[ColumnRole.AMOUNT])
        assertEquals("商品", bill.columnMapping[ColumnRole.PRODUCT])
        assertEquals("支付方式", bill.columnMapping[ColumnRole.PAY_METHOD])
        assertEquals("当前状态", bill.columnMapping[ColumnRole.STATUS])
        assertEquals("交易单号", bill.columnMapping[ColumnRole.TXN_ID])
        assertEquals("商户单号", bill.columnMapping[ColumnRole.MERCHANT_ORDER_ID])
    }

    @Test
    fun `a currency prefixed amount with a thousands separator is parsed`() {
        val row = parseWechat().rows.first { it.txnId == "4200001234202506200009" }
        assertEquals(129900L, row.amountCents)
        assertEquals("京东", row.counterpartyRaw)
    }

    @Test
    fun `wechat writes a slash for rows that move no money`() {
        val row = parseWechat().rows.first { it.txnId == "4200001234202506130006" }
        assertEquals(TxDirection.NEUTRAL, row.direction)
        assertEquals("零钱通转入", row.rawType)
        assertEquals(100000L, row.amountCents)
    }

    @Test
    fun `wechat statuses are preserved for exclusion decisions`() {
        val rows = parseWechat().rows
        assertEquals("已全额退款", rows.first { it.txnId == "4200001234202506120005" }.status)
        assertEquals("交易关闭", rows.first { it.txnId == "4200001234202506160008" }.status)
    }

    // ---- robustness ------------------------------------------------------------

    @Test
    fun `parsing is deterministic so a re-import produces identical fingerprints`() {
        val first = parseAlipay().rows.map { it.txnId to it.amountCents to it.occurredAt }
        val second = parseAlipay().rows.map { it.txnId to it.amountCents to it.occurredAt }
        assertEquals(first, second)
    }

    @Test
    fun `a file that is not a bill fails loudly instead of importing garbage`() {
        val result = BillParser.parse("hello,world\n1,2\n".toByteArray())
        assertTrue(result is BillParseResult.Error)
    }

    @Test
    fun `an empty file is rejected`() {
        assertTrue(BillParser.parse(ByteArray(0)) is BillParseResult.Error)
    }

    @Test
    fun `dedupe keys are stable and distinguish different rows`() {
        val bill = parseAlipay()
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
        assertEquals(keys.size, keys.toSet().size)
        // Recomputing must give the same answer, which is what makes a second import
        // of the same file insert zero rows.
        val again = bill.rows.map { row ->
            Dedupe.keyFor(bill.source, row.txnId, row.occurredAt, row.amountCents, row.counterpartyRaw, row.productDesc)
        }
        assertEquals(keys, again)
        assertTrue(keys.all { it.startsWith("ALIPAY:id:") })
    }

    @Test
    fun `rows without a transaction id fall back to a content fingerprint`() {
        val key = Dedupe.keyFor(TxSource.MANUAL, null, 1_700_000_000_000L, 3200L, "星巴克", "拿铁")
        assertTrue(key.startsWith("MANUAL:h:"))
        assertEquals(key, Dedupe.keyFor(TxSource.MANUAL, null, 1_700_000_000_000L, 3200L, "星巴克", "拿铁"))
        assertTrue(key != Dedupe.keyFor(TxSource.MANUAL, null, 1_700_000_000_000L, 3201L, "星巴克", "拿铁"))
    }

    @Test
    fun `a notification and a bill entry for one payment are the same payment`() {
        // The common real-world duplicate: captured live, then appearing again in the
        // monthly bill. The notification has no transaction id; the bill row does.
        assertTrue(
            Dedupe.looksLikeSamePayment(
                amountA = 3200L, timeA = 1_700_000_000_000L, txnIdA = null,
                amountB = 3200L, timeB = 1_700_000_060_000L, txnIdB = "4200001234",
            )
        )
    }

    @Test
    fun `two different bill entries are two different payments`() {
        // Both carry their own transaction id, so they are by definition distinct — two
        // genuine ¥32 purchases a minute apart in the same bill.
        assertTrue(
            !Dedupe.looksLikeSamePayment(
                amountA = 3200L, timeA = 1_700_000_000_000L, txnIdA = "4200001111",
                amountB = 3200L, timeB = 1_700_000_060_000L, txnIdB = "4200002222",
            )
        )
    }

    @Test
    fun `two notifications for one payment are the same payment`() {
        // Payment apps do post more than one notification per purchase; neither carries
        // a transaction id, so the pair must be treated as one.
        assertTrue(
            Dedupe.looksLikeSamePayment(
                amountA = 1L, timeA = 1_791_600_479_078L, txnIdA = null,
                amountB = 1L, timeB = 1_791_600_505_000L, txnIdB = null,
            )
        )
    }

    @Test
    fun `the same amount eight minutes apart is not the same payment`() {
        assertTrue(
            !Dedupe.looksLikeSamePayment(
                3200L, 1_700_000_000_000L, null,
                3200L, 1_700_000_480_000L, null,
            )
        )
    }

    @Test
    fun `a different amount is never the same payment`() {
        assertTrue(
            !Dedupe.looksLikeSamePayment(
                3200L, 1_700_000_000_000L, null,
                3300L, 1_700_000_010_000L, null,
            )
        )
    }

    @Test
    fun `parsed rows carry the raw fields the classifier needs`() {
        val row = parseAlipay().rows.first { it.txnId == "2025060722003" }
        assertEquals("转账", row.rawType)
        assertEquals(
            ExcludeReason.TRANSFER,
            RawTypeMapping.fromRawType(TxSource.ALIPAY, row.rawType, row.direction)?.excludeReason,
        )
    }
}
