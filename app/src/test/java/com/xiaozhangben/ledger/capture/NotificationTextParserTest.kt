package com.xiaozhangben.ledger.capture

import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Capturing is the one feature where a false positive is worse than a miss: a missed
 * payment is recovered by the monthly bill import, but a payment invented from a
 * marketing notification corrupts the budget silently.
 *
 * These tests therefore push hard on the "must not capture" cases.
 */
class NotificationTextParserTest {

    @Test
    fun `wechat payment receipt is captured with payee and amount`() {
        val payment = NotificationTextParser.parse(
            TxSource.WECHAT,
            "微信支付",
            listOf("向 星巴克 付款 12.34 元"),
        )
        assertNotNull(payment)
        assertEquals(TxDirection.EXPENSE, payment!!.direction)
        assertEquals(1234L, payment.amountCents)
        assertEquals("星巴克", payment.counterparty)
    }

    @Test
    fun `wechat receipt without the 元 suffix still works`() {
        val payment = NotificationTextParser.parse(TxSource.WECHAT, "微信支付凭证", listOf("已支付¥32.00"))
        assertNotNull(payment)
        assertEquals(3200L, payment!!.amountCents)
        assertEquals(TxDirection.EXPENSE, payment.direction)
    }

    @Test
    fun `alipay puts the payee in the title`() {
        val payment = NotificationTextParser.parse(TxSource.ALIPAY, "星巴克", listOf("你已成功付款 32.00 元"))
        assertNotNull(payment)
        assertEquals(3200L, payment!!.amountCents)
        assertEquals("星巴克", payment.counterparty)
        assertEquals(TxDirection.EXPENSE, payment.direction)
    }

    @Test
    fun `money arriving is recorded as income, not spending`() {
        val payment = NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("已收款¥66.00"))
        assertNotNull(payment)
        assertEquals(TxDirection.INCOME, payment!!.direction)
        assertEquals(6600L, payment.amountCents)
    }

    @Test
    fun `a coupon notification is never a payment`() {
        assertNull(NotificationTextParser.parse(TxSource.ALIPAY, "支付宝", listOf("您有一张 5 元优惠券即将过期")))
    }

    @Test
    fun `marketing notifications are discarded`() {
        assertNull(NotificationTextParser.parse(TxSource.ALIPAY, "蚂蚁森林", listOf("你的能量球还有 10 克可以收取")))
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "服务通知", listOf("限时活动，点击领取 20 元红包到账")))
        assertNull(NotificationTextParser.parse(TxSource.ALIPAY, "花呗", listOf("还款提醒：本月账单 1,234.56 元")))
    }

    @Test
    fun `an ordinary wechat chat message is not a payment`() {
        // We subscribe to the whole of WeChat, so this is the common case by volume.
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "小明", listOf("在吗")))
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "家庭群", listOf("晚上一起吃饭")))
    }

    @Test
    fun `a request to pay is not a payment`() {
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "待付款提醒", listOf("你有一笔订单待付款 128.00 元")))
        assertNull(NotificationTextParser.parse(TxSource.ALIPAY, "支付宝", listOf("付款失败，订单已取消 128.00 元")))
    }

    @Test
    fun `text mentioning an amount without a payment marker is ignored`() {
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "小明", listOf("借我 100 元")))
    }

    @Test
    fun `a payment marker without an amount is ignored`() {
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("支付成功")))
    }

    @Test
    fun `empty or blank input is handled`() {
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, null, emptyList()))
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "", listOf("   ")))
        assertNull(NotificationTextParser.parse(TxSource.ALIPAY, null, listOf("")))
    }

    @Test
    fun `a zero amount is not a payment`() {
        assertNull(NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("已支付 ¥0.00")))
    }

    @Test
    fun `amounts with thousands separators are parsed exactly`() {
        val payment = NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("已支付¥1,234.56"))
        assertNotNull(payment)
        assertEquals(123456L, payment!!.amountCents)
    }

    @Test
    fun `the payee is found even when the amount comes first`() {
        val payment = NotificationTextParser.parse(
            TxSource.WECHAT,
            "微信支付",
            listOf("已支付 88.80 元", "向 美团外卖 付款"),
        )
        assertNotNull(payment)
        assertEquals(8880L, payment!!.amountCents)
        assertEquals("美团外卖", payment.counterparty)
    }

    @Test
    fun `raw text is preserved for the classifier but is not the payee`() {
        val payment = NotificationTextParser.parse(TxSource.ALIPAY, "支付宝", listOf("你已成功付款 12.34 元"))
        assertNotNull(payment)
        assertNotNull(payment!!.rawText)
    }

    @Test
    fun `a wechat transfer is captured and carries the transfer hint`() {
        // This is the exact case that silently produced nothing before: the receipt says
        // "已转账", which matched none of the expense markers.
        val payment = NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("已转账¥0.01"))
        assertNotNull(payment)
        assertEquals(1L, payment!!.amountCents)
        assertEquals(TxDirection.EXPENSE, payment.direction)
        // The hint is what makes the classifier exclude it from spending.
        assertEquals("转账", payment.kindHint)
    }

    @Test
    fun `an alipay transfer is captured too`() {
        val payment = NotificationTextParser.parse(TxSource.ALIPAY, "支付宝", listOf("转账成功 500.00 元"))
        assertNotNull(payment)
        assertEquals(50_000L, payment!!.amountCents)
        assertEquals("转账", payment.kindHint)
    }

    @Test
    fun `an incoming transfer is not mistaken for spending`() {
        // "XXX已转账 50 元给你" contains the expense phrase 已转账, so the incoming forms
        // have to be recognised first.
        val payment = NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("张三已转账 50.00 元给你"))
        assertNotNull(payment)
        assertEquals(TxDirection.INCOME, payment!!.direction)
    }

    @Test
    fun `a transfer still waiting for confirmation is not money moved`() {
        assertNull(
            NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("已转账¥0.01，待对方确认收款"))
        )
    }

    @Test
    fun `a red packet carries its own hint`() {
        val payment = NotificationTextParser.parse(TxSource.WECHAT, "微信支付", listOf("已支付¥6.66"))
        assertNotNull(payment)
        assertNull(payment!!.kindHint)
    }

    @Test
    fun `rejections explain themselves`() {
        assertEquals(
            ParseOutcome.Rejection.EMPTY,
            (NotificationTextParser.parseOutcome(TxSource.WECHAT, null, emptyList()) as ParseOutcome.Rejected).reason,
        )
        assertEquals(
            ParseOutcome.Rejection.IGNORED,
            (NotificationTextParser.parseOutcome(TxSource.ALIPAY, "支付宝", listOf("您有一张优惠券")) as ParseOutcome.Rejected).reason,
        )
        assertEquals(
            ParseOutcome.Rejection.NO_DIRECTION,
            (NotificationTextParser.parseOutcome(TxSource.WECHAT, "小明", listOf("在吗")) as ParseOutcome.Rejected).reason,
        )
        assertEquals(
            ParseOutcome.Rejection.NO_AMOUNT,
            (NotificationTextParser.parseOutcome(TxSource.WECHAT, "微信支付", listOf("支付成功")) as ParseOutcome.Rejected).reason,
        )
        assertEquals(
            ParseOutcome.Rejection.ZERO_AMOUNT,
            (NotificationTextParser.parseOutcome(TxSource.WECHAT, "微信支付", listOf("已支付 ¥0.00")) as ParseOutcome.Rejected).reason,
        )
    }
}
