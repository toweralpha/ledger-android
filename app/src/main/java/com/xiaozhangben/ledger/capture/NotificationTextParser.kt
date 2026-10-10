package com.xiaozhangben.ledger.capture

import com.xiaozhangben.ledger.core.Money
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource

/** A payment recognised inside a notification, before it is written to the ledger. */
data class CapturedPayment(
    val source: TxSource,
    val direction: TxDirection,
    val amountCents: Long,
    val counterparty: String?,
    /**
     * The bill-type hint to hand the classifier, when the notification reveals it.
     *
     * A WeChat "已转账" receipt is a person-to-person transfer, not consumption: carrying
     * the hint through means it is recorded *and* excluded from spending, which is what
     * the budget rules require. Without it a transfer would land in the triage queue and
     * distort the month's totals.
     */
    val kindHint: String?,
    /** Kept for the row's raw fields; never logged. */
    val rawText: String,
)

/** Why a notification was not turned into a ledger row. Content-free, so it is safe to persist. */
sealed interface ParseOutcome {

    data class Captured(val payment: CapturedPayment) : ParseOutcome

    data class Rejected(val reason: Rejection) : ParseOutcome

    enum class Rejection(val label: String) {
        EMPTY("通知内容为空"),
        IGNORED("不像支付通知（营销活动或提醒）"),
        NO_DIRECTION("没有找到「已支付 / 已转账 / 已收款」之类的标记"),
        NO_AMOUNT("没有找到金额"),
        ZERO_AMOUNT("金额是 0"),
    }
}

/**
 * Extracts a payment from a notification's text.
 *
 * Two problems make this harder than a regex:
 *
 *  1. We subscribe to the whole of WeChat, so most notifications are chat messages.
 *     A capture therefore requires *both* an amount and an explicit payment marker;
 *     anything else is discarded. Missing a payment is recoverable through the monthly
 *     bill import, but inventing one is not.
 *  2. Notification fields move around between Android versions and notification styles —
 *     the amount can be in the title on one phone and in the collapsed summary on
 *     another — so the caller passes every candidate field and this parser searches all
 *     of them.
 *
 * Pure Kotlin on purpose: the parsing rules are exactly the part that needs tests.
 */
object NotificationTextParser {

    private val AMOUNT_WITH_YUAN = Regex(
        "(?:¥|￥)?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*[元圆]"
    )

    private val AMOUNT_WITH_SYMBOL = Regex(
        "[¥￥]\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
    )

    /**
     * Money *arriving*, checked before anything else.
     *
     * Order matters: "张三已转账 50 元给你" contains the expense phrase "已转账", so the
     * incoming forms have to win first or every transfer received would be booked as
     * spending. The trailing "给你" / "到你" is the only signal in that sentence, which is
     * why it is listed explicitly rather than relying on "转给你" being contiguous.
     */
    private val INCOME_PRIORITY = listOf(
        "向你转账", "给你转账", "转给你", "转账给你", "向你付款", "给你付款", "向你收款",
        "元给你", "元到你", "钱给你", "钱到你",
        "已收款", "收款成功", "对方已收款", "收款到账", "退款到账", "已退还",
    )

    /**
     * Note the bare "付款" and "已转账": a real WeChat receipt reads
     * "向 星巴克 付款 12.34 元" with no "已支付", and a WeChat transfer reads
     * "已转账¥0.01" — requiring a stronger phrase would silently drop both. The amount
     * requirement plus the ignore list is what keeps this from firing on unrelated text.
     */
    private val EXPENSE_MARKERS = listOf(
        "已支付", "支付成功", "成功付款", "付款成功", "已付款", "已扣款", "扣款成功",
        "消费", "支出", "代扣成功", "已成功付款", "付款",
        "已转账", "转账成功", "转给", "已转出", "转出成功",
    )

    private val INCOME_MARKERS = listOf(
        "已收款", "收款成功", "收款", "收到", "入账", "到账", "退款成功", "已退还",
    )

    /** Notification kinds that are never a payment receipt. */
    private val IGNORE_MARKERS = listOf(
        "优惠券", "红包到账", "活动", "推荐", "账单提醒", "积分", "点击", "快来", "领取",
        "版本更新", "安全提醒", "蚂蚁森林", "花呗额度", "还款提醒", "余额预警", "提额",
        "签到", "抽奖", "邀请", "广告", "限时", "直播", "看点", "基金涨跌", "收益",
        // Requests to pay are not payments, and neither are failures.
        "待付款", "未付款", "待支付", "付款失败", "支付失败", "交易失败", "已取消", "已关闭",
        // A transfer that has not been accepted yet has not moved any money.
        "待确认收款", "待对方确认", "等待对方确认", "已退还对方",
    )

    /** WeChat puts the payee here: "向 星巴克 付款 12.34 元". */
    private val PAY_TO = Regex("向\\s*(.{1,24}?)\\s*(?:付款|支付|转账)")

    /** "在 星巴克 消费 12.34 元" / "于XX支付". */
    private val PAY_AT = Regex("(?:在|于)\\s*(.{1,24}?)\\s*(?:消费|支付|付款)")

    /** "星巴克-付款成功" style titles. */
    private val TITLE_MERCHANT = Regex("^(.{1,24}?)\\s*[-－]\\s*(?:付款|支付|消费|收款)")

    private val NON_MERCHANT_TITLES = setOf(
        "微信支付", "微信", "微信支付凭证", "微信转账", "支付宝", "支付宝安全支付",
        "服务通知", "收款小账本", "微信支付助手",
    )

    fun parse(source: TxSource, title: String?, texts: List<String>): CapturedPayment? =
        (parseOutcome(source, title, texts) as? ParseOutcome.Captured)?.payment

    /**
     * Same as [parse] but explains a rejection, so the diagnostics screen can say *why*
     * capture produced nothing instead of leaving the user to guess.
     */
    fun parseOutcome(source: TxSource, title: String?, texts: List<String>): ParseOutcome {
        val allTexts = (listOfNotNull(title) + texts).filter { it.isNotBlank() }
        if (allTexts.isEmpty()) return ParseOutcome.Rejected(ParseOutcome.Rejection.EMPTY)

        val haystack = allTexts.joinToString(" ")
        if (IGNORE_MARKERS.any { haystack.contains(it) }) {
            return ParseOutcome.Rejected(ParseOutcome.Rejection.IGNORED)
        }

        val direction = when {
            INCOME_PRIORITY.any { haystack.contains(it) } -> TxDirection.INCOME
            EXPENSE_MARKERS.any { haystack.contains(it) } -> TxDirection.EXPENSE
            INCOME_MARKERS.any { haystack.contains(it) } -> TxDirection.INCOME
            else -> return ParseOutcome.Rejected(ParseOutcome.Rejection.NO_DIRECTION)
        }

        val amountCents = extractAmount(haystack)
            ?: return ParseOutcome.Rejected(ParseOutcome.Rejection.NO_AMOUNT)
        if (amountCents <= 0L) return ParseOutcome.Rejected(ParseOutcome.Rejection.ZERO_AMOUNT)

        return ParseOutcome.Captured(
            CapturedPayment(
                source = source,
                direction = direction,
                amountCents = amountCents,
                counterparty = extractCounterparty(title, texts, source),
                kindHint = extractKindHint(haystack, source),
                rawText = haystack,
            )
        )
    }

    private fun extractAmount(haystack: String): Long? {
        // Prefer a figure explicitly followed by 元; fall back to a ¥-prefixed figure.
        AMOUNT_WITH_YUAN.find(haystack)?.let { m ->
            Money.parseToCentsOrNull(m.groupValues[1])?.let { return it }
        }
        AMOUNT_WITH_SYMBOL.find(haystack)?.let { m ->
            Money.parseToCentsOrNull(m.groupValues[1])?.let { return it }
        }
        return null
    }

    /**
     * Turns a phrase the notification *does* reveal into the bill-type column the
     * classifier already understands, so the exclusion rules apply unchanged.
     */
    private fun extractKindHint(haystack: String, source: TxSource): String? = when {
        haystack.contains("红包") && source == TxSource.WECHAT -> "微信红包"
        haystack.contains("红包") -> "红包"
        haystack.contains("转账") -> "转账"
        haystack.contains("提现") -> "零钱提现"
        haystack.contains("还款") -> "信用卡还款"
        else -> null
    }

    private fun extractCounterparty(title: String?, texts: List<String>, source: TxSource): String? {
        // WeChat often splits the receipt across fields — the amount in the collapsed
        // line and "向 X 付款" in the expanded one — so every text is scanned, not just
        // the first.
        for (body in texts) {
            if (body.isBlank()) continue
            PAY_TO.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return clean(it) }
            PAY_AT.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return clean(it) }
        }

        val t = title?.trim().orEmpty()
        if (t.isNotEmpty() && t !in NON_MERCHANT_TITLES) {
            TITLE_MERCHANT.find(t)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                ?.let { return clean(it) }
            // WeChat titles are generic, so a non-generic title is the merchant.
            // Alipay titles are usually the merchant directly.
            if (source == TxSource.ALIPAY || !t.contains("微信")) {
                return clean(t)
            }
        }
        return null
    }

    private fun clean(value: String): String =
        value.trim().trim('，', ',', '。', '.', ':', '：', ' ', '"', '"').take(40)
}
